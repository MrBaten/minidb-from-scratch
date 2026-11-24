package com.minidb.cache;

import com.minidb.DbException;
import com.minidb.storage.Page;
import com.minidb.storage.Pager;
import com.minidb.transaction.Journal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-capacity page cache with Clock eviction, pin counts, dirty write-back and the
 * journaling hook (see PLAN.md §3.4). Thread-safe.
 *
 * <p>The pool owns {@code capacity} frames. A page handed out by the pool stays pinned
 * until its holder calls {@link Page#close()}; pinned frames are never evicted. Victims
 * are chosen by the Clock (second-chance) algorithm. Before a dirty page reaches the
 * pager while a transaction is active, the journal is synced so that the before-images
 * are durable first (write-ahead rule).</p>
 */
public final class BufferPool implements AutoCloseable {

    /** Default number of frames. */
    public static final int DEFAULT_CAPACITY = 100;

    /**
     * Cache statistics snapshot.
     *
     * @param hits        lookups served from the cache
     * @param misses      lookups that read from the pager
     * @param evictions   frames evicted by the Clock algorithm
     * @param dirtyWrites dirty pages written back to the pager
     */
    public record Stats(long hits, long misses, long evictions, long dirtyWrites) { }

    /** One cache slot. A frame with a {@code null} page is free. */
    private static final class Frame {
        Page page;
        int pinCount;
        boolean ref;
        boolean dirty;

        void clear() {
            page = null;
            pinCount = 0;
            ref = false;
            dirty = false;
        }
    }

    private final Pager pager;
    private final Journal journal;
    private final Frame[] frames;
    private final Map<Integer, Integer> frameByPage = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Page.Releaser releaser = this::unpin;
    private int hand;
    private int cached;
    private long hits;
    private long misses;
    private long evictions;
    private long dirtyWrites;

    /**
     * Creates a pool.
     *
     * @param pager    the pager to read from / write to
     * @param journal  the journal to record before-images in, or {@code null}
     * @param capacity number of frames, {@code >= 1}
     * @throws IllegalArgumentException if the pager is null or the capacity is below 1
     */
    public BufferPool(Pager pager, Journal journal, int capacity) {
        if (pager == null) {
            throw new IllegalArgumentException("pager must not be null");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.pager = pager;
        this.journal = journal;
        this.frames = new Frame[capacity];
        for (int i = 0; i < capacity; i++) {
            frames[i] = new Frame();
        }
    }

    /**
     * Returns the number of frames.
     *
     * @return capacity
     */
    public int getCapacity() {
        return frames.length;
    }

    /**
     * Returns the database page count (delegates to the pager).
     *
     * @return page count
     */
    public int getPageCount() {
        return pager.getPageCount();
    }

    /**
     * Returns a pinned page for reading. The caller must {@link Page#close()} it.
     *
     * @param pageNum page number
     * @return the cached page
     * @throws DbException if out of range or the pool is exhausted
     */
    public Page getPage(int pageNum) {
        lock.lock();
        try {
            return frames[pin(pageNum)].page;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a pinned page for writing: journals its before-image (if a transaction is
     * active) and marks it dirty. The caller must {@link Page#close()} it.
     *
     * @param pageNum page number
     * @return the cached page
     * @throws DbException if out of range or the pool is exhausted
     */
    public Page getPageForWrite(int pageNum) {
        lock.lock();
        try {
            int idx = pin(pageNum);
            Frame frame = frames[idx];
            try {
                if (journal != null && journal.isActive()) {
                    journal.recordOriginal(pageNum, frame.page.getData());
                }
            } catch (RuntimeException e) {
                frame.pinCount--;
                throw e;
            }
            frame.dirty = true;
            return frame.page;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends a new zero-filled page to the database and returns it pinned and dirty.
     * The caller must {@link Page#close()} it.
     *
     * @return the new page
     * @throws DbException if the pool is exhausted or on I/O error
     */
    public Page createNewPage() {
        lock.lock();
        try {
            int idx = obtainFrame();
            Page page = pager.createNewPage();
            install(idx, page);
            Frame frame = frames[idx];
            frame.dirty = true;
            return page;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes one page back if it is cached and dirty.
     *
     * @param pageNum page number
     */
    public void flushPage(int pageNum) {
        lock.lock();
        try {
            Integer idx = frameByPage.get(pageNum);
            if (idx != null && frames[idx].dirty) {
                syncJournal();
                writeBack(frames[idx]);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes all dirty pages back and syncs the pager (journal synced first if active).
     */
    public void flushAll() {
        lock.lock();
        try {
            boolean anyDirty = false;
            for (Frame frame : frames) {
                if (frame.page != null && frame.dirty) {
                    anyDirty = true;
                    break;
                }
            }
            if (anyDirty) {
                syncJournal();
                for (Frame frame : frames) {
                    if (frame.page != null && frame.dirty) {
                        writeBack(frame);
                    }
                }
            }
            pager.sync();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drops every cached page without writing it (used by ROLLBACK).
     *
     * @throws IllegalStateException if any page is pinned
     */
    public void discardAll() {
        lock.lock();
        try {
            if (pinnedCount() > 0) {
                throw new IllegalStateException(
                        "cannot discard buffer pool: " + pinnedCount() + " page(s) still pinned");
            }
            dropAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drops every cached page without writing it, even pinned ones (used by ROLLBACK so
     * that a leaked pin can never block recovery). Releasers of dropped pages are
     * detached, so a later {@code close()} of a stale page is a harmless no-op.
     *
     * @return the number of frames that were still pinned (0 in a healthy system)
     */
    public int discardAllForced() {
        lock.lock();
        try {
            int pinned = pinnedCount();
            dropAll();
            return pinned;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of occupied frames.
     *
     * @return cached page count
     */
    public int getCachedPageCount() {
        lock.lock();
        try {
            return cached;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of frames with a pin count above zero.
     *
     * @return pinned frame count
     */
    public int getPinnedCount() {
        lock.lock();
        try {
            return pinnedCount();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a statistics snapshot.
     *
     * @return stats
     */
    public Stats getStats() {
        lock.lock();
        try {
            return new Stats(hits, misses, evictions, dirtyWrites);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Flushes all dirty pages. Does not close the pager.
     */
    @Override
    public void close() {
        flushAll();
    }

    // ------------------------------------------------------------------ internals

    /** Pins the requested page (loading it on a miss) and returns its frame index. Lock held. */
    private int pin(int pageNum) {
        int count = pager.getPageCount();
        if (pageNum < 0 || pageNum >= count) {
            throw new DbException("page " + pageNum + " out of range (page count " + count + ")");
        }
        Integer hit = frameByPage.get(pageNum);
        if (hit != null) {
            Frame frame = frames[hit];
            frame.pinCount++;
            frame.ref = true;
            hits++;
            return hit;
        }
        int idx = obtainFrame();
        Page page = pager.getPage(pageNum);
        install(idx, page);
        misses++;
        return idx;
    }

    /** Places a page into a free frame, pinned once, with the ref bit set. Lock held. */
    private void install(int idx, Page page) {
        Frame frame = frames[idx];
        frame.page = page;
        frame.pinCount = 1;
        frame.ref = true;
        frame.dirty = false;
        page.setReleaser(releaser);
        frameByPage.put(page.getPageNum(), idx);
        cached++;
    }

    /** Returns the index of a free frame, evicting a Clock victim if none is free. Lock held. */
    private int obtainFrame() {
        if (cached < frames.length) {
            for (int i = 0; i < frames.length; i++) {
                if (frames[i].page == null) {
                    return i;
                }
            }
        }
        int limit = 2 * frames.length + 1;
        for (int step = 0; step < limit; step++) {
            int idx = hand;
            Frame frame = frames[idx];
            hand = (hand + 1) % frames.length;
            if (frame.pinCount > 0) {
                continue;
            }
            if (frame.ref) {
                frame.ref = false;
                continue;
            }
            evict(frame);
            return idx;
        }
        throw new DbException("buffer pool exhausted: all " + frames.length + " frames are pinned");
    }

    /** Writes the victim back if dirty (journal first) and frees its frame. Lock held. */
    private void evict(Frame frame) {
        if (frame.dirty) {
            syncJournal();
            writeBack(frame);
        }
        frame.page.setReleaser(null);
        frameByPage.remove(frame.page.getPageNum());
        frame.clear();
        cached--;
        evictions++;
    }

    /** Writes a dirty frame to the pager and clears its dirty bit. Lock held. */
    private void writeBack(Frame frame) {
        pager.flushPage(frame.page);
        frame.dirty = false;
        dirtyWrites++;
    }

    /** Makes the before-images durable if a transaction is active. Lock held. */
    private void syncJournal() {
        if (journal != null && journal.isActive()) {
            journal.sync();
        }
    }

    /** Detaches and clears every frame without writing anything. Lock held. */
    private void dropAll() {
        for (Frame frame : frames) {
            if (frame.page != null) {
                frame.page.setReleaser(null);
                frame.clear();
            }
        }
        frameByPage.clear();
        cached = 0;
        hand = 0;
    }

    private int pinnedCount() {
        int n = 0;
        for (Frame frame : frames) {
            if (frame.page != null && frame.pinCount > 0) {
                n++;
            }
        }
        return n;
    }

    /** Releaser callback: drops one pin of the given page. */
    private void unpin(Page page) {
        lock.lock();
        try {
            Integer idx = frameByPage.get(page.getPageNum());
            if (idx == null || frames[idx].page != page) {
                throw new IllegalStateException("page " + page.getPageNum() + " is not cached (stale reference)");
            }
            Frame frame = frames[idx];
            if (frame.pinCount <= 0) {
                throw new IllegalStateException("pin count underflow on page " + page.getPageNum());
            }
            frame.pinCount--;
        } finally {
            lock.unlock();
        }
    }
}
