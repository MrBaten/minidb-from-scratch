package com.minidb.storage;

import java.util.Arrays;
import java.util.Objects;

/**
 * A fixed-size 4096-byte page: the unit of I/O, caching and journaling.
 *
 * <p>All multi-byte accessors are big-endian. A page handed out by the
 * {@code BufferPool} is <em>pinned</em>; the holder must call {@link #close()} exactly
 * once (try-with-resources) to release the pin. Pages that were not handed out by a
 * pool have no {@link Releaser} and closing them is a no-op.</p>
 */
public final class Page implements AutoCloseable {

    /** Size of every page in bytes. */
    public static final int SIZE = 4096;

    /**
     * Callback invoked when a holder closes a page. Implemented by the buffer pool to
     * decrement the pin count of the page's frame.
     */
    @FunctionalInterface
    public interface Releaser {
        /**
         * Releases one pin of the given page.
         *
         * @param page the page being closed
         */
        void release(Page page);
    }

    private final int pageNum;
    private final byte[] data;
    private volatile Releaser releaser;

    /**
     * Creates a zero-filled page.
     *
     * @param pageNum the page number (index in the database file), must be {@code >= 0}
     */
    public Page(int pageNum) {
        this(pageNum, new byte[SIZE]);
    }

    /**
     * Creates a page that wraps the given array (no copy is made).
     *
     * @param pageNum the page number, must be {@code >= 0}
     * @param data    exactly {@link #SIZE} bytes
     * @throws IllegalArgumentException if the page number is negative or the array has the wrong length
     */
    public Page(int pageNum, byte[] data) {
        if (pageNum < 0) {
            throw new IllegalArgumentException("negative page number: " + pageNum);
        }
        Objects.requireNonNull(data, "data");
        if (data.length != SIZE) {
            throw new IllegalArgumentException("page data must be " + SIZE + " bytes, got " + data.length);
        }
        this.pageNum = pageNum;
        this.data = data;
    }

    /**
     * Returns the page number.
     *
     * @return index of this page in the database file
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * Returns the live backing array (modifications are visible to every holder).
     *
     * @return the 4096-byte backing array
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Returns a copy of the page contents.
     *
     * @return a new 4096-byte array
     */
    public byte[] copyData() {
        return Arrays.copyOf(data, SIZE);
    }

    /**
     * Replaces the whole page contents.
     *
     * @param src exactly {@link #SIZE} bytes
     * @throws IllegalArgumentException if {@code src} has the wrong length
     */
    public void copyFrom(byte[] src) {
        if (src.length != SIZE) {
            throw new IllegalArgumentException("page data must be " + SIZE + " bytes, got " + src.length);
        }
        System.arraycopy(src, 0, data, 0, SIZE);
    }

    /**
     * Reads one byte.
     *
     * @param offset byte offset within the page
     * @return the byte value
     */
    public byte getByte(int offset) {
        check(offset, 1);
        return data[offset];
    }

    /**
     * Writes one byte.
     *
     * @param offset byte offset within the page
     * @param value  value to write
     */
    public void putByte(int offset, byte value) {
        check(offset, 1);
        data[offset] = value;
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param offset byte offset within the page
     * @return the value
     */
    public short getShort(int offset) {
        check(offset, 2);
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    /**
     * Writes a big-endian 16-bit value.
     *
     * @param offset byte offset within the page
     * @param value  value to write
     */
    public void putShort(int offset, short value) {
        check(offset, 2);
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    /**
     * Reads a big-endian 32-bit value.
     *
     * @param offset byte offset within the page
     * @return the value
     */
    public int getInt(int offset) {
        check(offset, 4);
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Writes a big-endian 32-bit value.
     *
     * @param offset byte offset within the page
     * @param value  value to write
     */
    public void putInt(int offset, int value) {
        check(offset, 4);
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    /**
     * Reads a big-endian 64-bit value.
     *
     * @param offset byte offset within the page
     * @return the value
     */
    public long getLong(int offset) {
        check(offset, 8);
        return ((long) getInt(offset) << 32) | (getInt(offset + 4) & 0xFFFFFFFFL);
    }

    /**
     * Writes a big-endian 64-bit value.
     *
     * @param offset byte offset within the page
     * @param value  value to write
     */
    public void putLong(int offset, long value) {
        check(offset, 8);
        putInt(offset, (int) (value >>> 32));
        putInt(offset + 4, (int) value);
    }

    /**
     * Copies a byte range out of the page.
     *
     * @param offset byte offset within the page
     * @param length number of bytes
     * @return a new array with the bytes
     */
    public byte[] getBytes(int offset, int length) {
        check(offset, length);
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    /**
     * Copies an entire array into the page.
     *
     * @param offset byte offset within the page
     * @param src    bytes to write
     */
    public void putBytes(int offset, byte[] src) {
        putBytes(offset, src, 0, src.length);
    }

    /**
     * Copies part of an array into the page.
     *
     * @param offset    byte offset within the page
     * @param src       source array
     * @param srcOffset start offset in {@code src}
     * @param length    number of bytes
     */
    public void putBytes(int offset, byte[] src, int srcOffset, int length) {
        check(offset, length);
        System.arraycopy(src, srcOffset, data, offset, length);
    }

    /**
     * Fills a byte range with a value.
     *
     * @param offset byte offset within the page
     * @param length number of bytes
     * @param value  fill value
     */
    public void fill(int offset, int length, byte value) {
        check(offset, length);
        Arrays.fill(data, offset, offset + length, value);
    }

    /**
     * Moves a byte range inside the page (overlapping ranges are handled correctly).
     *
     * @param srcOffset  source offset
     * @param destOffset destination offset
     * @param length     number of bytes
     */
    public void move(int srcOffset, int destOffset, int length) {
        check(srcOffset, length);
        check(destOffset, length);
        System.arraycopy(data, srcOffset, data, destOffset, length);
    }

    /**
     * Installs the callback invoked by {@link #close()}. Used by the buffer pool.
     *
     * @param releaser the releaser, or {@code null} to make {@link #close()} a no-op
     */
    public void setReleaser(Releaser releaser) {
        this.releaser = releaser;
    }

    /**
     * Releases one pin of this page (if it came from a buffer pool). Must be called
     * exactly once per {@code getPage}/{@code getPageForWrite}/{@code createNewPage} call.
     */
    @Override
    public void close() {
        Releaser r = releaser;
        if (r != null) {
            r.release(this);
        }
    }

    private static void check(int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > SIZE) {
            throw new IndexOutOfBoundsException("range [" + offset + ", " + (offset + length)
                    + ") outside page of " + SIZE + " bytes");
        }
    }

    /**
     * Returns a short description for debugging.
     *
     * @return e.g. {@code Page[7]}
     */
    @Override
    public String toString() {
        return "Page[" + pageNum + "]";
    }
}
