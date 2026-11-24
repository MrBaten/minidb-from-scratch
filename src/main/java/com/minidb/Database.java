package com.minidb;

import com.minidb.cache.BufferPool;
import com.minidb.storage.Pager;
import com.minidb.table.Catalog;
import com.minidb.transaction.Journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Engine facade that wires the storage stack together and owns transaction control and
 * the global statement lock.
 *
 * <p>Startup order: create the data directory, open the {@link Pager}, run hot-journal
 * recovery, open the {@link Journal} and the {@link BufferPool}, then initialise or
 * validate the {@link Catalog}.</p>
 *
 * <p>Transaction methods must be called while holding the lock obtained through
 * {@link #tryLock(Duration)}.</p>
 */
public final class Database implements AutoCloseable {

    /** File extension of the database file. */
    public static final String DB_EXTENSION = ".db";

    /** File extension of the rollback journal. */
    public static final String JOURNAL_EXTENSION = ".journal";

    /** How long {@link #close()} waits for an in-flight statement before closing anyway. */
    public static final Duration CLOSE_LOCK_TIMEOUT = Duration.ofSeconds(10);

    private final Path dbFile;
    private final Pager pager;
    private final Journal journal;
    private final BufferPool pool;
    private final Catalog catalog;
    private final boolean recovered;
    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile boolean closed;

    private Database(Path dbFile, Pager pager, Journal journal, BufferPool pool, Catalog catalog, boolean recovered) {
        this.dbFile = dbFile;
        this.pager = pager;
        this.journal = journal;
        this.pool = pool;
        this.catalog = catalog;
        this.recovered = recovered;
    }

    /**
     * Opens (or creates) a database with the default cache size.
     *
     * @param dataDir directory holding the database files (created if missing)
     * @param name    database name; files are {@code name.db} and {@code name.journal}
     * @return the open database
     * @throws DbException if the files cannot be opened or are corrupt
     */
    public static Database open(Path dataDir, String name) {
        return open(dataDir, name, BufferPool.DEFAULT_CAPACITY);
    }

    /**
     * Opens (or creates) a database.
     *
     * @param dataDir       directory holding the database files (created if missing)
     * @param name          database name; files are {@code name.db} and {@code name.journal}
     * @param cacheCapacity number of buffer pool frames
     * @return the open database
     * @throws DbException if the files cannot be opened or are corrupt
     */
    public static Database open(Path dataDir, String name, int cacheCapacity) {
        Objects.requireNonNull(dataDir, "dataDir");
        if (name == null || !name.matches("[A-Za-z0-9_\\-]+")) {
            throw new DbException("invalid database name: " + name);
        }
        if (cacheCapacity < 1) {
            throw new DbException("cache capacity must be >= 1");
        }
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new DbException("cannot create data directory " + dataDir + ": " + e.getMessage(), e);
        }
        Path dbFile = dataDir.resolve(name + DB_EXTENSION);
        Path journalFile = dataDir.resolve(name + JOURNAL_EXTENSION);
        Pager pager = new Pager(dbFile);
        try {
            boolean recovered = Journal.recover(journalFile, pager);
            Journal journal = new Journal(journalFile);
            BufferPool pool = new BufferPool(pager, journal, cacheCapacity);
            Catalog.initialize(pool);
            pool.flushAll();
            return new Database(dbFile, pager, journal, pool, new Catalog(pool), recovered);
        } catch (RuntimeException e) {
            pager.close();
            throw e;
        }
    }

    /**
     * Returns the database file path.
     *
     * @return path of {@code name.db}
     */
    public Path getDbFile() {
        return dbFile;
    }

    /**
     * Tells whether a hot journal was rolled back while opening.
     *
     * @return {@code true} if crash recovery restored pages
     */
    public boolean wasRecovered() {
        return recovered;
    }

    /**
     * Returns the catalog.
     *
     * @return the catalog
     */
    public Catalog catalog() {
        return catalog;
    }

    /**
     * Returns the buffer pool.
     *
     * @return the buffer pool
     */
    public BufferPool bufferPool() {
        return pool;
    }

    /**
     * Acquires the global statement lock (reentrant, fair).
     *
     * @param timeout maximum time to wait
     * @return {@code true} if acquired, {@code false} on timeout
     * @throws DbException if the database is closed or the thread is interrupted
     */
    public boolean tryLock(Duration timeout) {
        ensureOpen();
        try {
            return lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DbException("interrupted while waiting for the database lock", e);
        }
    }

    /**
     * Releases one hold of the global statement lock.
     *
     * @throws IllegalMonitorStateException if the current thread does not hold it
     */
    public void unlock() {
        lock.unlock();
    }

    /**
     * Tells whether the current thread holds the global lock.
     *
     * @return {@code true} if held by the caller
     */
    public boolean isLockedByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    /**
     * Tells whether a write transaction is active.
     *
     * @return {@code true} if the journal is active
     */
    public boolean isTransactionActive() {
        return journal.isActive();
    }

    /**
     * Starts a write transaction. Caller must hold the lock.
     *
     * @throws DbException if a transaction is already active
     */
    public void beginTransaction() {
        requireLock();
        journal.begin(pool.getPageCount());
    }

    /**
     * Commits the active transaction: sync journal, write and fsync all dirty pages, delete
     * the journal (the commit point). Caller must hold the lock.
     *
     * @throws DbException on I/O error (the transaction is then rolled back)
     */
    public void commitTransaction() {
        requireLock();
        if (!journal.isActive()) {
            throw new DbException("no active transaction");
        }
        try {
            journal.sync();
            pool.flushAll();
            journal.commit();
        } catch (RuntimeException e) {
            if (journal.isActive()) {
                rollbackTransaction();
            }
            throw e;
        }
    }

    /**
     * Rolls back the active transaction: discard cached pages, restore before-images,
     * truncate new pages. Caller must hold the lock.
     *
     * <p>The cache is discarded with {@link BufferPool#discardAllForced()}, so even a page
     * pin leaked by a bug can never prevent the journal from being rolled back.</p>
     *
     * @throws DbException if no transaction is active or on I/O error
     */
    public void rollbackTransaction() {
        requireLock();
        if (!journal.isActive()) {
            throw new DbException("no active transaction");
        }
        int leaked = pool.discardAllForced();
        journal.rollback(pager);
        if (leaked > 0) {
            System.err.println("minidb: warning: " + leaked + " page pin(s) leaked before rollback");
        }
    }

    /**
     * Flushes everything and closes the files. An active transaction (abandoned by a
     * crashed session) is rolled back first.
     *
     * <p>Waits up to {@link #CLOSE_LOCK_TIMEOUT} for an in-flight statement to finish. If
     * the lock is still held after that, the files are closed without touching the cache:
     * on-disk state is then exactly as after a crash, and the next {@link #open} rolls back
     * any hot journal.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        boolean locked;
        try {
            locked = lock.tryLock(CLOSE_LOCK_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        try {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (locked) {
                    if (journal.isActive()) {
                        pool.discardAllForced();
                        journal.rollback(pager);
                    } else {
                        pool.flushAll();
                    }
                }
            } finally {
                journal.close();
                pager.close();
            }
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void requireLock() {
        ensureOpen();
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("caller must hold the database lock");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new DbException("database is closed");
        }
    }
}
