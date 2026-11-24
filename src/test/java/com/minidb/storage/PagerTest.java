package com.minidb.storage;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertThrows;
import static com.minidb.testkit.MiniTest.assertTrue;
import static com.minidb.testkit.MiniTest.deleteRecursively;
import static com.minidb.testkit.MiniTest.tempDir;
import static com.minidb.testkit.MiniTest.test;

import com.minidb.DbException;
import com.minidb.testkit.MiniTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Tests for {@link Pager}. */
public final class PagerTest {

    private PagerTest() {
    }

    /** A test body that receives a fresh database file path inside a temp directory. */
    private interface WithFile {
        void run(Path file) throws Throwable;
    }

    private static void withTempFile(WithFile body) throws Throwable {
        Path dir = tempDir("pagertest");
        try {
            body.run(dir.resolve("test.db"));
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void fillPattern(Page page, int seed) {
        for (int off = 0; off < Page.SIZE; off += 4) {
            page.putInt(off, seed * 1_000_003 + off);
        }
    }

    private static void checkPattern(Page page, int seed) {
        for (int off = 0; off < Page.SIZE; off += 4) {
            assertEquals(seed * 1_000_003 + off, page.getInt(off), "page " + page.getPageNum() + " offset " + off);
        }
    }

    /**
     * Runs the pager tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        test("new file has zero pages", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                assertEquals(0, p.getPageCount(), "page count");
                assertEquals(file, p.getPath(), "path");
            }
            assertTrue(Files.exists(file), "file created");
        }));

        test("createNewPage numbers pages and returns zeros", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                for (int i = 0; i < 5; i++) {
                    Page page = p.createNewPage();
                    assertEquals(i, page.getPageNum(), "page number");
                    assertEquals(i + 1, p.getPageCount(), "count");
                    assertTrue(java.util.Arrays.equals(new byte[Page.SIZE], page.getData()), "zero content");
                }
                assertEquals(5L * Page.SIZE, Files.size(file), "file size");
                assertTrue(java.util.Arrays.equals(new byte[Page.SIZE], p.getPage(3).getData()), "read zeros");
            }
        }));

        test("write, flush, reopen persists data", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                for (int i = 0; i < 6; i++) {
                    Page page = p.createNewPage();
                    fillPattern(page, i + 1);
                    p.flushPage(page);
                }
                p.sync();
            }
            try (Pager p = new Pager(file)) {
                assertEquals(6, p.getPageCount(), "count after reopen");
                for (int i = 0; i < 6; i++) {
                    checkPattern(p.getPage(i), i + 1);
                }
                Page fresh = p.getPage(2);
                assertTrue(fresh != p.getPage(2), "getPage returns a fresh object");
            }
        }));

        test("getPage out of range throws", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                assertThrows(DbException.class, () -> p.getPage(0), "empty file");
                p.createNewPage();
                assertThrows(DbException.class, () -> p.getPage(1), "n == count");
                assertThrows(DbException.class, () -> p.getPage(-1), "negative");
                p.getPage(0);
            }
        }));

        test("flushPage beyond page count throws", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                p.createNewPage();
                assertThrows(DbException.class, () -> p.flushPage(new Page(1)), "beyond count");
                assertEquals(1, p.getPageCount(), "count unchanged");
                assertEquals((long) Page.SIZE, Files.size(file), "file unchanged");
            }
        }));

        test("truncate shrinks and persists", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                for (int i = 0; i < 4; i++) {
                    p.createNewPage();
                }
                assertThrows(DbException.class, () -> p.truncate(5), "grow not allowed");
                assertThrows(DbException.class, () -> p.truncate(-1), "negative");
                p.truncate(2);
                assertEquals(2, p.getPageCount(), "count");
                assertEquals(2L * Page.SIZE, Files.size(file), "size");
                assertThrows(DbException.class, () -> p.getPage(2), "truncated page unreadable");
                p.truncate(2);
                assertEquals(2, p.createNewPage().getPageNum(), "reuse number after truncate");
                p.truncate(0);
                assertEquals(0, p.getPageCount(), "empty");
                p.createNewPage();
                p.createNewPage();
                p.truncate(1);
            }
            try (Pager p = new Pager(file)) {
                assertEquals(1, p.getPageCount(), "count after reopen");
            }
        }));

        test("partial trailing page is truncated on open", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                p.createNewPage();
                Page second = p.createNewPage();
                fillPattern(second, 9);
                p.flushPage(second);
            }
            Files.write(file, new byte[100], StandardOpenOption.APPEND);
            assertEquals(2L * Page.SIZE + 100, Files.size(file), "garbage appended");
            try (Pager p = new Pager(file)) {
                assertEquals(2, p.getPageCount(), "count unchanged");
                assertEquals(0L, Files.size(file) % Page.SIZE, "size is page multiple");
                checkPattern(p.getPage(1), 9);
            }
            assertEquals(2L * Page.SIZE, Files.size(file), "size after open");
        }));

        test("operations after close throw; close is idempotent", () -> withTempFile(file -> {
            Pager p = new Pager(file);
            Page page = p.createNewPage();
            p.close();
            p.close();
            DbException e = assertThrows(DbException.class, () -> p.getPage(0), "getPage");
            assertEquals("pager is closed", e.getMessage(), "message");
            assertThrows(DbException.class, p::getPageCount, "getPageCount");
            assertThrows(DbException.class, () -> p.flushPage(page), "flushPage");
            assertThrows(DbException.class, p::createNewPage, "createNewPage");
            assertThrows(DbException.class, () -> p.truncate(0), "truncate");
            assertThrows(DbException.class, p::sync, "sync");
        }));

        test("sync does not throw", () -> withTempFile(file -> {
            try (Pager p = new Pager(file)) {
                p.sync();
                p.createNewPage();
                p.sync();
            }
        }));

        test("concurrent reads and writes from 8 threads", () -> withTempFile(file -> {
            int threads = 8;
            int pagesPerThread = 25;
            try (Pager p = new Pager(file)) {
                for (int i = 0; i < threads * pagesPerThread; i++) {
                    p.createNewPage();
                }
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<Void>> futures = new ArrayList<>();
                    for (int t = 0; t < threads; t++) {
                        final int thread = t;
                        Callable<Void> job = () -> {
                            for (int round = 0; round < 5; round++) {
                                for (int i = 0; i < pagesPerThread; i++) {
                                    int n = thread * pagesPerThread + i;
                                    Page page = p.getPage(n);
                                    fillPattern(page, n + round);
                                    p.flushPage(page);
                                    checkPattern(p.getPage(n), n + round);
                                }
                            }
                            return null;
                        };
                        futures.add(pool.submit(job));
                    }
                    for (Future<Void> f : futures) {
                        f.get();
                    }
                } finally {
                    pool.shutdownNow();
                }
                for (int n = 0; n < threads * pagesPerThread; n++) {
                    checkPattern(p.getPage(n), n + 4);
                }
            }
        }));

        test("concurrent createNewPage yields unique numbers", () -> withTempFile(file -> {
            int threads = 8;
            int each = 20;
            try (Pager p = new Pager(file)) {
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<List<Integer>>> futures = new ArrayList<>();
                    for (int t = 0; t < threads; t++) {
                        futures.add(pool.submit(() -> {
                            List<Integer> nums = new ArrayList<>();
                            for (int i = 0; i < each; i++) {
                                nums.add(p.createNewPage().getPageNum());
                            }
                            return nums;
                        }));
                    }
                    java.util.Set<Integer> seen = new java.util.HashSet<>();
                    for (Future<List<Integer>> f : futures) {
                        seen.addAll(f.get());
                    }
                    assertEquals(threads * each, seen.size(), "unique page numbers");
                } finally {
                    pool.shutdownNow();
                }
                assertEquals(threads * each, p.getPageCount(), "count");
                assertEquals((long) threads * each * Page.SIZE, Files.size(file), "size");
            }
        }));

        MiniTest.finish();
    }
}
