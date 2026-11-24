package com.minidb.storage;

import com.minidb.DbException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Treats a file as an array of {@link Page#SIZE}-byte pages (see PLAN.md §3.2).
 *
 * <p>Thread-safe. Page reads and writes use positional {@link FileChannel} I/O, which is safe
 * to run concurrently; changes of the page count ({@link #createNewPage()} and
 * {@link #truncate(int)}) are serialised by a lock. All {@link IOException}s are wrapped in
 * {@link DbException}.</p>
 */
public final class Pager implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private final ReentrantLock sizeLock = new ReentrantLock();
    private volatile int pageCount;
    private volatile boolean closed;

    /**
     * Opens (or creates) the database file. A partial trailing page is truncated away.
     *
     * @param file path of the database file
     * @throws DbException if the file cannot be opened
     */
    public Pager(Path file) {
        this.path = file;
        FileChannel opened = null;
        try {
            opened = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);
            long size = opened.size();
            long whole = size / Page.SIZE;
            if (size != whole * Page.SIZE) {
                opened.truncate(whole * Page.SIZE);
            }
            this.pageCount = Math.toIntExact(whole);
            this.channel = opened;
        } catch (IOException | ArithmeticException e) {
            closeQuietly(opened);
            throw new DbException("cannot open database file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the path of the database file.
     *
     * @return the file path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Returns the number of pages currently in the file.
     *
     * @return page count
     * @throws DbException if the pager is closed
     */
    public int getPageCount() {
        ensureOpen();
        return pageCount;
    }

    /**
     * Reads a page from disk into a new {@link Page} object.
     *
     * @param pageNum page number, {@code 0 <= pageNum < getPageCount()}
     * @return a fresh page with the on-disk contents
     * @throws DbException if out of range, the file ends prematurely, or on I/O error
     */
    public Page getPage(int pageNum) {
        ensureOpen();
        checkRange(pageNum);
        byte[] data = new byte[Page.SIZE];
        ByteBuffer buf = ByteBuffer.wrap(data);
        long position = offsetOf(pageNum);
        try {
            while (buf.hasRemaining()) {
                int n = channel.read(buf, position + buf.position());
                if (n < 0) {
                    throw new DbException("corrupt database file " + path + ": unexpected end of file "
                            + "while reading page " + pageNum);
                }
            }
        } catch (IOException e) {
            throw new DbException("cannot read page " + pageNum + " of " + path + ": " + e.getMessage(), e);
        }
        return new Page(pageNum, data);
    }

    /**
     * Writes a page's contents to its slot in the file (not fsynced).
     *
     * @param page the page; its number must be {@code < getPageCount()}
     * @throws DbException if out of range or on I/O error
     */
    public void flushPage(Page page) {
        ensureOpen();
        int pageNum = page.getPageNum();
        checkRange(pageNum);
        writeFully(ByteBuffer.wrap(page.getData()), offsetOf(pageNum), pageNum);
    }

    /**
     * Appends a zero-filled page to the file and returns it.
     *
     * @return the new page; its number equals the previous page count
     * @throws DbException on I/O error
     */
    public Page createNewPage() {
        ensureOpen();
        sizeLock.lock();
        try {
            int pageNum = pageCount;
            Page page = new Page(pageNum);
            writeFully(ByteBuffer.wrap(page.getData()), offsetOf(pageNum), pageNum);
            pageCount = pageNum + 1;
            return page;
        } finally {
            sizeLock.unlock();
        }
    }

    /**
     * Shrinks the file to the given number of pages.
     *
     * @param pageCount new page count, {@code 0 <= pageCount <= getPageCount()}
     * @throws DbException if out of range or on I/O error
     */
    public void truncate(int pageCount) {
        ensureOpen();
        sizeLock.lock();
        try {
            if (pageCount < 0 || pageCount > this.pageCount) {
                throw new DbException("cannot truncate to " + pageCount + " pages (file has "
                        + this.pageCount + ")");
            }
            channel.truncate(offsetOf(pageCount));
            this.pageCount = pageCount;
        } catch (IOException e) {
            throw new DbException("cannot truncate " + path + ": " + e.getMessage(), e);
        } finally {
            sizeLock.unlock();
        }
    }

    /**
     * Forces all written data and metadata to stable storage.
     *
     * @throws DbException on I/O error or if the pager is closed
     */
    public void sync() {
        ensureOpen();
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new DbException("cannot sync " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Closes the file. Further calls fail with {@link DbException}. Closing twice is harmless.
     */
    @Override
    public void close() {
        sizeLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            channel.close();
        } catch (IOException e) {
            throw new DbException("cannot close " + path + ": " + e.getMessage(), e);
        } finally {
            sizeLock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new DbException("pager is closed");
        }
    }

    private void checkRange(int pageNum) {
        int count = pageCount;
        if (pageNum < 0 || pageNum >= count) {
            throw new DbException("page " + pageNum + " out of range (page count " + count + ")");
        }
    }

    private static long offsetOf(int pageNum) {
        return (long) pageNum * Page.SIZE;
    }

    private void writeFully(ByteBuffer buf, long position, int pageNum) {
        try {
            while (buf.hasRemaining()) {
                channel.write(buf, position + buf.position());
            }
        } catch (IOException e) {
            throw new DbException("cannot write page " + pageNum + " of " + path + ": " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // already failing; the original error is reported
            }
        }
    }
}
