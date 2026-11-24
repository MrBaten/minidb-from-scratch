package com.minidb.cache;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertFalse;
import static com.minidb.testkit.MiniTest.assertThrows;
import static com.minidb.testkit.MiniTest.assertTrue;

import com.minidb.DbException;
import com.minidb.storage.Page;
import com.minidb.storage.Pager;
import com.minidb.testkit.MiniTest;
import com.minidb.transaction.Journal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Tests for {@link BufferPool}; runs against the real or a faithful fake Pager/Journal. */
public final class BufferPoolTest {

    private BufferPoolTest() {
    }

    /** Test fixture: a temp dir with a pager pre-filled with pages whose first int is {@code 1000 + n}. */
    private static final class Env implements AutoCloseable {
        final Path dir = MiniTest.tempDir("bufferpool");
        final Path dbFile = dir.resolve("t.db");
        Pager pager = new Pager(dbFile);
        final Journal journal = new Journal(dir.resolve("t.journal"));

        Env(int pages) {
            for (int i = 0; i < pages; i++) {
                Page p = pager.createNewPage();
                p.putInt(0, 1000 + i);
                pager.flushPage(p);
            }
            pager.sync();
        }

        @Override
        public void close() {
            pager.close();
            journal.close();
            MiniTest.deleteRecursively(dir);
        }
    }

    private static int firstInt(Pager pager, int n) {
        return pager.getPage(n).getInt(0);
    }

    private static void touch(BufferPool pool, int n) {
        try (Page p = pool.getPage(n)) {
            assertEquals(n, p.getPageNum(), "page number");
        }
    }

    private static void write(BufferPool pool, int n, int value) {
        try (Page p = pool.getPageForWrite(n)) {
            p.putInt(0, value);
        }
    }

    /**
     * Runs all tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        MiniTest.test("constructor validates arguments", () -> {
            try (Env e = new Env(1)) {
                assertThrows(IllegalArgumentException.class, () -> new BufferPool(e.pager, null, 0), "capacity 0");
                assertThrows(IllegalArgumentException.class, () -> new BufferPool(null, null, 3), "null pager");
                assertEquals(100, BufferPool.DEFAULT_CAPACITY, "default capacity");
                assertEquals(3, new BufferPool(e.pager, null, 3).getCapacity(), "capacity");
            }
        });

        MiniTest.test("hit/miss counting and same Page instance", () -> {
            try (Env e = new Env(4)) {
                BufferPool pool = new BufferPool(e.pager, null, 4);
                Page a = pool.getPage(2);
                Page b = pool.getPage(2);
                assertTrue(a == b, "same instance while cached");
                assertEquals(1002, a.getInt(0), "content loaded");
                assertEquals(1, pool.getPinnedCount(), "one frame pinned (twice)");
                a.close();
                assertEquals(1, pool.getPinnedCount(), "still pinned once");
                b.close();
                assertEquals(0, pool.getPinnedCount(), "all released");
                touch(pool, 3);
                BufferPool.Stats s = pool.getStats();
                assertEquals(2L, s.misses(), "misses");
                assertEquals(1L, s.hits(), "hits");
                assertEquals(2, pool.getCachedPageCount(), "cached");
            }
        });

        MiniTest.test("out-of-range page numbers throw DbException", () -> {
            try (Env e = new Env(2)) {
                BufferPool pool = new BufferPool(e.pager, null, 2);
                assertThrows(DbException.class, () -> pool.getPage(2), "past end");
                assertThrows(DbException.class, () -> pool.getPage(-1), "negative");
                assertThrows(DbException.class, () -> pool.getPageForWrite(5), "write past end");
                assertEquals(0, pool.getPinnedCount(), "no leaked pins");
            }
        });

        MiniTest.test("capacity is respected over 3x capacity distinct pages", () -> {
            try (Env e = new Env(30)) {
                BufferPool pool = new BufferPool(e.pager, null, 10);
                for (int i = 0; i < 30; i++) {
                    touch(pool, i);
                    assertTrue(pool.getCachedPageCount() <= 10, "cached <= capacity");
                }
                assertEquals(10, pool.getCachedPageCount(), "full");
                assertEquals(20L, pool.getStats().evictions(), "evictions");
                assertEquals(0L, pool.getStats().dirtyWrites(), "clean evictions do not write");
            }
        });

        MiniTest.test("Clock gives referenced pages a second chance", () -> {
            try (Env e = new Env(6)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                touch(pool, 0);
                touch(pool, 1);
                touch(pool, 2);
                touch(pool, 3); // all ref bits cleared by the sweep, page 0 is evicted
                touch(pool, 1); // hit: page 1 gets its ref bit back
                touch(pool, 4); // hand skips (clears) page 1, evicts page 2
                BufferPool.Stats before = pool.getStats();
                touch(pool, 3);
                touch(pool, 1);
                touch(pool, 4);
                BufferPool.Stats afterHits = pool.getStats();
                assertEquals(before.misses(), afterHits.misses(), "3, 1 and 4 are cached");
                assertEquals(before.hits() + 3, afterHits.hits(), "three hits");
                touch(pool, 2);
                assertEquals(afterHits.misses() + 1, pool.getStats().misses(), "page 2 was evicted");
            }
        });

        MiniTest.test("pinned pages are never evicted", () -> {
            try (Env e = new Env(10)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                Page pinned = pool.getPage(0);
                pinned.putInt(8, 77);
                for (int i = 1; i < 10; i++) {
                    touch(pool, i);
                }
                assertEquals(1, pool.getPinnedCount(), "one pinned");
                long hitsBefore = pool.getStats().hits();
                Page again = pool.getPage(0);
                assertTrue(again == pinned, "still the same cached instance");
                assertEquals(77, again.getInt(8), "in-memory change retained");
                assertEquals(hitsBefore + 1, pool.getStats().hits(), "page 0 was a hit");
                again.close();
                pinned.close();
            }
        });

        MiniTest.test("exhausted pool throws DbException; recovers after release", () -> {
            try (Env e = new Env(5)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                Page p0 = pool.getPage(0);
                Page p1 = pool.getPage(1);
                Page p2 = pool.getPage(2);
                DbException ex = assertThrows(DbException.class, () -> pool.getPage(3), "all pinned");
                assertTrue(ex.getMessage().contains("buffer pool exhausted"), "message: " + ex.getMessage());
                assertThrows(DbException.class, pool::createNewPage, "createNewPage when exhausted");
                assertEquals(5, pool.getPageCount(), "failed createNewPage must not grow the file");
                p1.close();
                touch(pool, 3);
                p0.close();
                p2.close();
                assertEquals(0, pool.getPinnedCount(), "released");
            }
        });

        MiniTest.test("dirty page is written back on eviction; clean eviction does not write", () -> {
            try (Env e = new Env(6)) {
                BufferPool pool = new BufferPool(e.pager, null, 2);
                write(pool, 0, 4242);
                assertEquals(1000, firstInt(e.pager, 0), "not on disk yet");
                touch(pool, 1);
                touch(pool, 2);
                touch(pool, 3);
                assertEquals(4242, firstInt(e.pager, 0), "written back after eviction");
                assertEquals(1L, pool.getStats().dirtyWrites(), "exactly one dirty write");
                assertTrue(pool.getStats().evictions() >= 2, "evictions happened");
                try (Page p = pool.getPage(0)) {
                    assertEquals(4242, p.getInt(0), "re-read sees the new content");
                }
            }
        });

        MiniTest.test("flushPage writes one dirty page only", () -> {
            try (Env e = new Env(3)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                write(pool, 0, 1);
                write(pool, 1, 2);
                pool.flushPage(0);
                assertEquals(1, firstInt(e.pager, 0), "page 0 flushed");
                assertEquals(1001, firstInt(e.pager, 1), "page 1 untouched");
                pool.flushPage(0);
                pool.flushPage(2);
                assertEquals(1L, pool.getStats().dirtyWrites(), "clean/uncached flush is a no-op");
            }
        });

        MiniTest.test("flushAll persists across reopen; close() flushes", () -> {
            try (Env e = new Env(3)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                write(pool, 0, 11);
                write(pool, 2, 22);
                try (Page created = pool.createNewPage()) {
                    created.putInt(0, 33);
                }
                pool.close();
                e.pager.close();
                e.pager = new Pager(e.dbFile);
                assertEquals(4, e.pager.getPageCount(), "page count");
                assertEquals(11, firstInt(e.pager, 0), "page 0");
                assertEquals(1001, firstInt(e.pager, 1), "page 1");
                assertEquals(22, firstInt(e.pager, 2), "page 2");
                assertEquals(33, firstInt(e.pager, 3), "page 3");
            }
        });

        MiniTest.test("createNewPage numbering, pinned + dirty", () -> {
            try (Env e = new Env(2)) {
                BufferPool pool = new BufferPool(e.pager, null, 2);
                Page a = pool.createNewPage();
                Page b = pool.createNewPage();
                assertEquals(2, a.getPageNum(), "first new page");
                assertEquals(3, b.getPageNum(), "second new page");
                assertEquals(4, pool.getPageCount(), "page count");
                assertEquals(2, pool.getPinnedCount(), "both pinned");
                a.putInt(0, 5);
                a.close();
                b.close();
                touch(pool, 0);
                touch(pool, 1);
                assertEquals(5, firstInt(e.pager, 2), "new page was dirty and flushed on eviction");
            }
        });

        MiniTest.test("discardAll drops unflushed changes", () -> {
            try (Env e = new Env(3)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                write(pool, 1, 999);
                pool.discardAll();
                assertEquals(0, pool.getCachedPageCount(), "empty");
                try (Page p = pool.getPage(1)) {
                    assertEquals(1001, p.getInt(0), "change was dropped");
                }
            }
        });

        MiniTest.test("discardAll throws while a page is pinned; stale close is harmless", () -> {
            try (Env e = new Env(3)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                Page p = pool.getPage(0);
                assertThrows(IllegalStateException.class, pool::discardAll, "pinned");
                assertEquals(1, pool.getCachedPageCount(), "nothing dropped");
                p.close();
                pool.discardAll();
                p.close(); // stale handle: releaser was cleared by discardAll
                assertEquals(0, pool.getPinnedCount(), "no pins");
            }
        });

        MiniTest.test("discardAllForced drops pinned pages and unflushed changes", () -> {
            try (Env e = new Env(3)) {
                BufferPool pool = new BufferPool(e.pager, null, 3);
                Page leaked = pool.getPageForWrite(1);
                leaked.putInt(0, 999);
                write(pool, 2, 888);
                assertEquals(1, pool.discardAllForced(), "one pinned frame reported");
                assertEquals(0, pool.getCachedPageCount(), "empty");
                assertEquals(0, pool.getPinnedCount(), "no pins");
                leaked.close(); // stale handle: must not throw
                assertEquals(0, pool.getPinnedCount(), "still no pins");
                try (Page p = pool.getPage(1)) {
                    assertEquals(1001, p.getInt(0), "disk content, change dropped");
                    assertTrue(p != leaked, "fresh instance");
                }
                try (Page p = pool.getPage(2)) {
                    assertEquals(1002, p.getInt(0), "unpinned dirty change dropped too");
                }
                assertEquals(0, pool.getPinnedCount(), "pool usable afterwards");
                assertEquals(0, pool.discardAllForced(), "healthy pool reports 0");
            }
        });

        MiniTest.test("unpin underflow throws IllegalStateException", () -> {
            try (Env e = new Env(2)) {
                BufferPool pool = new BufferPool(e.pager, null, 2);
                Page p = pool.getPage(0);
                p.close();
                assertThrows(IllegalStateException.class, p::close, "double close");
                assertEquals(0, pool.getPinnedCount(), "still consistent");
            }
        });

        MiniTest.test("journal: before-image recorded, rollback restores old bytes", () -> {
            try (Env e = new Env(3)) {
                BufferPool pool = new BufferPool(e.pager, e.journal, 3);
                e.journal.begin(pool.getPageCount());
                write(pool, 1, 31337);
                assertTrue(e.journal.isJournaled(1), "before-image recorded");
                assertEquals(1001, firstInt(e.pager, 1), "db file still has the old bytes");
                pool.flushAll();
                assertEquals(31337, firstInt(e.pager, 1), "new bytes in the db file");
                pool.discardAll();
                e.journal.rollback(e.pager);
                assertEquals(1001, firstInt(e.pager, 1), "old bytes restored");
                assertFalse(e.journal.isActive(), "journal finished");
            }
        });

        MiniTest.test("write-ahead: dirty eviction with active journal keeps a recoverable journal", () -> {
            try (Env e = new Env(5)) {
                BufferPool pool = new BufferPool(e.pager, e.journal, 2);
                e.journal.begin(pool.getPageCount());
                write(pool, 0, 500);
                write(pool, 1, 501);
                touch(pool, 2);
                touch(pool, 3);
                assertEquals(500, firstInt(e.pager, 0), "page 0 reached the db file");
                assertEquals(501, firstInt(e.pager, 1), "page 1 reached the db file");
                assertTrue(Files.exists(e.journal.getPath()), "journal file exists before db writes");
                pool.discardAll();
                e.journal.rollback(e.pager);
                assertEquals(1000, firstInt(e.pager, 0), "page 0 rolled back");
                assertEquals(1001, firstInt(e.pager, 1), "page 1 rolled back");
            }
        });

        MiniTest.test("journal: pages created in the transaction are truncated on rollback", () -> {
            try (Env e = new Env(2)) {
                BufferPool pool = new BufferPool(e.pager, e.journal, 2);
                e.journal.begin(pool.getPageCount());
                for (int i = 0; i < 4; i++) {
                    try (Page p = pool.createNewPage()) {
                        p.putInt(0, 7);
                    }
                }
                assertFalse(e.journal.isJournaled(2), "new pages are not journaled");
                pool.flushAll();
                pool.discardAll();
                e.journal.rollback(e.pager);
                assertEquals(2, e.pager.getPageCount(), "truncated to original size");
            }
        });

        MiniTest.test("no journal recording when the journal is inactive", () -> {
            try (Env e = new Env(2)) {
                BufferPool pool = new BufferPool(e.pager, e.journal, 2);
                write(pool, 0, 9);
                assertFalse(Files.exists(e.journal.getPath()), "no journal file outside a transaction");
                pool.flushAll();
                assertEquals(9, firstInt(e.pager, 0), "plain write-through works");
            }
        });

        MiniTest.test("concurrent getPage/getPageForWrite from 16 threads leaves no pins", () -> {
            try (Env e = new Env(40)) {
                BufferPool pool = new BufferPool(e.pager, null, 20);
                int threads = 16;
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                List<Thread> workers = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int seed = t;
                    workers.add(Thread.ofVirtual().unstarted(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < 500; i++) {
                                int n = (seed * 7 + i * 13) % 40;
                                if (i % 5 == 0) {
                                    try (Page p = pool.getPageForWrite(n)) {
                                        p.putInt(4, seed);
                                    }
                                } else {
                                    try (Page p = pool.getPage(n)) {
                                        assertEquals(n, p.getPageNum(), "page number");
                                        assertEquals(1000 + n, p.getInt(0), "content");
                                    }
                                }
                            }
                        } catch (Throwable ex) {
                            failure.compareAndSet(null, ex);
                        }
                    }));
                }
                workers.forEach(Thread::start);
                start.countDown();
                for (Thread w : workers) {
                    w.join();
                }
                assertEquals(null, failure.get(), "no worker failed");
                assertEquals(0, pool.getPinnedCount(), "all pins released");
                assertTrue(pool.getCachedPageCount() <= 20, "capacity respected");
                pool.close();
            }
        });

        MiniTest.finish();
    }
}
