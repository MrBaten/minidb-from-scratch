package com.minidb.transaction;

import com.minidb.DbException;
import com.minidb.storage.Page;
import com.minidb.storage.Pager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Rollback journal holding before-images of pages modified by the active transaction
 * (SQLite style, see PLAN.md §1.4 and §3.3).
 *
 * <p>File format (all integers big-endian):</p>
 * <pre>
 * header : magic "MDBJRNL1" (8) | originalPageCount int | pageSize int | crc32 of previous 16 bytes
 * record : pageNum int | 4096-byte before-image | crc32 over (pageNum bytes + image)
 * </pre>
 *
 * <p>Not thread-safe; callers serialise access with the global database lock.</p>
 */
public final class Journal implements AutoCloseable {

    /** Magic bytes at the start of a journal file. */
    public static final String MAGIC = "MDBJRNL1";

    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 20;
    private static final int RECORD_SIZE = 4 + Page.SIZE + 4;

    private final Path path;
    private boolean active;
    private int originalPageCount = -1;
    private FileChannel channel;
    private boolean fileCreated;
    private int recordCount;
    private final Set<Integer> journaled = new HashSet<>();

    /**
     * Creates a journal bound to a file path. No file is created until the first
     * before-image is recorded.
     *
     * @param journalFile path of the journal file
     */
    public Journal(Path journalFile) {
        this.path = journalFile;
    }

    /**
     * Returns the journal file path.
     *
     * @return the path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Tells whether a transaction is active.
     *
     * @return {@code true} between {@link #begin(int)} and commit/rollback
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Starts a transaction.
     *
     * @param originalPageCount database page count at transaction start
     * @throws com.minidb.DbException if a transaction is already active
     */
    public void begin(int originalPageCount) {
        if (active) {
            throw new DbException("transaction already active");
        }
        this.active = true;
        this.originalPageCount = originalPageCount;
        this.journaled.clear();
        this.recordCount = 0;
        this.fileCreated = false;
    }

    /**
     * Returns the page count recorded at {@link #begin(int)}.
     *
     * @return original page count, or {@code -1} if inactive
     */
    public int getOriginalPageCount() {
        return active ? originalPageCount : -1;
    }

    /**
     * Tells whether a before-image of the page is already in the journal.
     *
     * @param pageNum page number
     * @return {@code true} if journaled in the active transaction
     */
    public boolean isJournaled(int pageNum) {
        return active && journaled.contains(pageNum);
    }

    /**
     * Records the before-image of a page. No-op if inactive, if the page did not exist at
     * transaction start, or if it is already journaled. The bytes are copied.
     *
     * @param pageNum  page number
     * @param original current page contents (4096 bytes)
     * @throws com.minidb.DbException on I/O error
     * @throws IllegalArgumentException if {@code original} is not 4096 bytes long
     */
    public void recordOriginal(int pageNum, byte[] original) {
        if (original == null || original.length != Page.SIZE) {
            throw new IllegalArgumentException("before-image must be exactly " + Page.SIZE + " bytes");
        }
        if (!active || pageNum >= originalPageCount || journaled.contains(pageNum)) {
            return;
        }
        byte[] copy = original.clone();
        try {
            ensureOpen();
            ByteBuffer record = ByteBuffer.allocate(RECORD_SIZE);
            record.putInt(pageNum).put(copy).putInt(recordCrc(pageNum, copy));
            record.flip();
            writeFully(record, recordOffset(recordCount));
        } catch (IOException e) {
            throw new DbException("cannot write journal " + path + ": " + e.getMessage(), e);
        }
        recordCount++;
        journaled.add(pageNum);
    }

    /**
     * Forces the journal file to stable storage. If a transaction is active but no file
     * exists yet, a header-only file is created first, so that pages appended during the
     * transaction can be truncated away by recovery. No-op when inactive without a file.
     *
     * @throws com.minidb.DbException on I/O error
     */
    public void sync() {
        try {
            if (channel == null && active) {
                ensureOpen();
            }
            if (channel == null) {
                return;
            }
            channel.force(true);
        } catch (IOException e) {
            throw new DbException("cannot sync journal " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Commits: deletes the journal file and ends the transaction. The caller must have
     * flushed and synced the database file first.
     *
     * @throws com.minidb.DbException if inactive or on I/O error
     */
    public void commit() {
        requireActive("commit");
        finish();
    }

    /**
     * Rolls back: restores every before-image into the pager, truncates the pager to the
     * original page count, syncs, deletes the journal and ends the transaction.
     *
     * @param pager the database pager
     * @throws com.minidb.DbException if inactive or on I/O error
     */
    public void rollback(Pager pager) {
        requireActive("roll back");
        int pageCount = pager.getPageCount();
        if (recordCount > 0) {
            restoreFromFile(pager, pageCount);
        }
        if (pager.getPageCount() > originalPageCount) {
            pager.truncate(originalPageCount);
        }
        pager.sync();
        finish();
    }

    /**
     * Hot-journal crash recovery, called at startup before anything reads the database.
     * Valid records are replayed until the first truncated or corrupted one; an unusable
     * header means the journal was never synced, so it is simply deleted.
     *
     * @param journalFile journal path
     * @param pager       the database pager
     * @return {@code true} if before-images were restored or the database was truncated
     * @throws com.minidb.DbException on I/O error
     */
    public static boolean recover(Path journalFile, Pager pager) {
        if (!Files.exists(journalFile)) {
            return false;
        }
        boolean changed;
        try (FileChannel in = FileChannel.open(journalFile, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            int originalCount = readFully(in, header, 0) ? parseHeader(header) : -1;
            changed = originalCount >= 0 && replay(in, originalCount, pager);
        } catch (IOException e) {
            throw new DbException("cannot read journal " + journalFile + ": " + e.getMessage(), e);
        }
        deleteFile(journalFile);
        return changed;
    }

    /** Streams valid records into the pager, truncates it and syncs; returns whether anything changed. */
    private static boolean replay(FileChannel in, int originalCount, Pager pager) throws IOException {
        boolean changed = false;
        int pageCount = pager.getPageCount();
        ByteBuffer record = ByteBuffer.allocate(RECORD_SIZE);
        for (long pos = HEADER_SIZE; ; pos += RECORD_SIZE) {
            record.clear();
            if (!readFully(in, record, pos)) {
                break;
            }
            int pageNum = record.getInt(0);
            byte[] image = new byte[Page.SIZE];
            record.get(4, image);
            if (record.getInt(4 + Page.SIZE) != recordCrc(pageNum, image)) {
                break;
            }
            if (pageNum >= 0 && pageNum < pageCount && pageNum < originalCount) {
                pager.flushPage(new Page(pageNum, image));
                changed = true;
            }
        }
        if (pager.getPageCount() > originalCount) {
            pager.truncate(originalCount);
            changed = true;
        }
        pager.sync();
        return changed;
    }

    /**
     * Closes the file handle. A journal of an active transaction is left on disk so that
     * {@link #recover(Path, Pager)} can roll it back on the next start. Idempotent.
     */
    @Override
    public void close() {
        closeChannel();
    }

    // ------------------------------------------------------------------ internals

    private void requireActive(String action) {
        if (!active) {
            throw new DbException("cannot " + action + ": no active transaction");
        }
    }

    /** Closes and deletes the journal file and resets all transaction state. */
    private void finish() {
        try {
            closeChannel();
            deleteFile(path);
        } finally {
            journaled.clear();
            recordCount = 0;
            fileCreated = false;
            active = false;
            originalPageCount = -1;
        }
    }

    /** Opens the journal channel, creating the file and header on first use. */
    private void ensureOpen() throws IOException {
        if (channel != null) {
            return;
        }
        if (fileCreated) {
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            return;
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        fileCreated = true;
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.put(MAGIC_BYTES).putInt(originalPageCount).putInt(Page.SIZE);
        CRC32 crc = new CRC32();
        crc.update(header.array(), 0, HEADER_SIZE - 4);
        header.putInt((int) crc.getValue());
        header.flip();
        writeFully(header, 0);
        syncDirectoryOf(path);
    }

    private static long recordOffset(int index) {
        return HEADER_SIZE + (long) index * RECORD_SIZE;
    }

    /** Writes every journaled before-image back to the pager, reading them from the journal file. */
    private void restoreFromFile(Pager pager, int pageCount) {
        try (FileChannel in = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer record = ByteBuffer.allocate(RECORD_SIZE);
            for (int i = 0; i < recordCount; i++) {
                record.clear();
                if (!readFully(in, record, recordOffset(i))) {
                    throw new DbException("journal " + path + " is truncated");
                }
                int pageNum = record.getInt(0);
                byte[] image = new byte[Page.SIZE];
                record.get(4, image);
                if (record.getInt(4 + Page.SIZE) != recordCrc(pageNum, image)) {
                    throw new DbException("journal " + path + " is corrupted at record " + i);
                }
                if (pageNum < pageCount) {
                    pager.flushPage(new Page(pageNum, image));
                }
            }
        } catch (IOException e) {
            throw new DbException("cannot read journal " + path + ": " + e.getMessage(), e);
        }
    }

    /** Fills the buffer from the position; returns false if the file ends first. */
    private static boolean readFully(FileChannel in, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int n = in.read(buffer, pos);
            if (n < 0) {
                return false;
            }
            pos += n;
        }
        buffer.flip();
        return true;
    }

    /** Best-effort fsync of the directory holding the file; unsupported on some platforms. */
    private static void syncDirectoryOf(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            return;
        }
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // directory fsync is not supported everywhere; the file itself is still synced
        }
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            pos += channel.write(buffer, pos);
        }
    }

    private void closeChannel() {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            throw new DbException("cannot close journal " + path + ": " + e.getMessage(), e);
        } finally {
            channel = null;
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
            syncDirectoryOf(file);
        } catch (IOException e) {
            throw new DbException("cannot delete journal " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Validates the header at the start of the buffer and consumes it.
     *
     * @return the original page count, or {@code -1} if the header is missing or invalid
     */
    private static int parseHeader(ByteBuffer data) {
        byte[] header = new byte[HEADER_SIZE - 4];
        data.get(header);
        int storedCrc = data.getInt();
        CRC32 crc = new CRC32();
        crc.update(header);
        ByteBuffer fields = ByteBuffer.wrap(header);
        byte[] magic = new byte[MAGIC_BYTES.length];
        fields.get(magic);
        int count = fields.getInt();
        int pageSize = fields.getInt();
        boolean valid = Arrays.equals(magic, MAGIC_BYTES)
                && storedCrc == (int) crc.getValue()
                && pageSize == Page.SIZE
                && count >= 0;
        return valid ? count : -1;
    }

    private static int recordCrc(int pageNum, byte[] image) {
        CRC32 crc = new CRC32();
        crc.update(ByteBuffer.allocate(4).putInt(pageNum).array());
        crc.update(image);
        return (int) crc.getValue();
    }
}
