package com.minidb.index;

import com.minidb.DbException;
import com.minidb.cache.BufferPool;
import com.minidb.storage.Page;
import com.minidb.storage.Pager;
import com.minidb.testkit.MiniTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.TreeMap;

/** Unit tests for {@link BPlusTree}; run through {@link #main(String[])}. */
public final class BPlusTreeTest {

    private BPlusTreeTest() {
    }

    /** Test fixture: a pool over a temporary database file. */
    private record Env(Path dir, BufferPool pool) {
        static Env create() {
            Path dir = MiniTest.tempDir("bptree-test");
            Pager pager = new Pager(dir.resolve("t.db"));
            return new Env(dir, new BufferPool(pager, null, 100));
        }

        void cleanup() {
            MiniTest.deleteRecursively(dir);
        }
    }

    /**
     * Runs all tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        MiniTest.test("physical maxima are consistent with the page size", BPlusTreeTest::maxima);
        MiniTest.test("empty tree", BPlusTreeTest::emptyTree);
        MiniTest.test("constructor validation", BPlusTreeTest::constructorValidation);
        MiniTest.test("sequential inserts 1..1000 with default capacity", BPlusTreeTest::sequential);
        MiniTest.test("random 20000 inserts with tiny nodes vs TreeMap", BPlusTreeTest::randomSmallOrder);
        MiniTest.test("descending inserts with tiny nodes", BPlusTreeTest::descendingInserts);
        MiniTest.test("duplicate insert throws and leaves tree unchanged", BPlusTreeTest::duplicateInsert);
        MiniTest.test("delete half randomly, ranges span empty leaves", BPlusTreeTest::deleteHalf);
        MiniTest.test("delete all then reinsert", BPlusTreeTest::deleteAllThenReinsert);
        MiniTest.test("update", BPlusTreeTest::update);
        MiniTest.test("boundary and negative keys", BPlusTreeTest::boundaryKeys);
        MiniTest.test("rangeQuery with low > high is empty", BPlusTreeTest::emptyRange);
        MiniTest.test("reopen on the same root sees the data", BPlusTreeTest::reopen);
        MiniTest.test("large default-capacity random workload", BPlusTreeTest::largeDefault);
        MiniTest.finish();
    }

    private static void assertNoPins(Env env, String when) {
        MiniTest.assertEquals(0, env.pool().getPinnedCount(), "pinned pages after " + when);
    }

    private static void maxima() {
        MiniTest.assertEquals(340, BPlusTree.MAX_LEAF_KEYS, "MAX_LEAF_KEYS");
        MiniTest.assertEquals(509, BPlusTree.MAX_INTERNAL_KEYS, "MAX_INTERNAL_KEYS");
        MiniTest.assertTrue(16 + 12 * BPlusTree.MAX_LEAF_KEYS <= Page.SIZE, "leaf fits");
        MiniTest.assertTrue(16 + 8 * BPlusTree.MAX_INTERNAL_KEYS + 4 <= Page.SIZE, "internal fits");
    }

    private static void emptyTree() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            assertNoPins(env, "create");
            BPlusTree tree = new BPlusTree(env.pool(), root);
            MiniTest.assertEquals(root, tree.getRootPageNum(), "root page number");
            MiniTest.assertEquals(0L, tree.count(), "count");
            MiniTest.assertEquals(1, tree.height(), "height");
            MiniTest.assertFalse(tree.search(5).isPresent(), "search");
            MiniTest.assertFalse(tree.delete(5), "delete");
            MiniTest.assertFalse(tree.update(5, 1L), "update");
            MiniTest.assertTrue(tree.rangeQuery(Integer.MIN_VALUE, Integer.MAX_VALUE).isEmpty(), "range");
            assertNoPins(env, "empty operations");
        } finally {
            env.cleanup();
        }
    }

    private static void constructorValidation() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            MiniTest.assertThrows(IllegalArgumentException.class,
                    () -> new BPlusTree(env.pool(), root, 2, 4), "leaf too small");
            MiniTest.assertThrows(IllegalArgumentException.class,
                    () -> new BPlusTree(env.pool(), root, 4, 2), "internal too small");
            MiniTest.assertThrows(IllegalArgumentException.class,
                    () -> new BPlusTree(env.pool(), root, BPlusTree.MAX_LEAF_KEYS + 1, 4), "leaf too large");
            MiniTest.assertThrows(IllegalArgumentException.class,
                    () -> new BPlusTree(env.pool(), root, 4, BPlusTree.MAX_INTERNAL_KEYS + 1), "internal too large");
            new BPlusTree(env.pool(), root, 3, 3);
            new BPlusTree(env.pool(), root, BPlusTree.MAX_LEAF_KEYS, BPlusTree.MAX_INTERNAL_KEYS);
            // a zero-filled page is not a tree page
            int foreign;
            try (Page page = env.pool().createNewPage()) {
                foreign = page.getPageNum();
            }
            MiniTest.assertThrows(DbException.class, () -> new BPlusTree(env.pool(), foreign), "foreign page");
            assertNoPins(env, "constructor validation");
        } finally {
            env.cleanup();
        }
    }

    private static void sequential() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root);
            for (int i = 1; i <= 1000; i++) {
                tree.insert(i, i * 10L);
                assertNoPins(env, "insert " + i);
            }
            MiniTest.assertEquals(root, tree.getRootPageNum(), "root unchanged");
            MiniTest.assertEquals(1000L, tree.count(), "count");
            MiniTest.assertEquals(2, tree.height(), "height with 1000 keys");
            for (int i = 1; i <= 1000; i++) {
                MiniTest.assertEquals(OptionalLong.of(i * 10L), tree.search(i), "search " + i);
            }
            MiniTest.assertFalse(tree.search(0).isPresent(), "search 0");
            MiniTest.assertFalse(tree.search(1001).isPresent(), "search 1001");
            List<BPlusTree.Entry> range = tree.rangeQuery(101, 199);
            MiniTest.assertEquals(99, range.size(), "range size");
            for (int i = 0; i < range.size(); i++) {
                MiniTest.assertEquals(new BPlusTree.Entry(101 + i, (101 + i) * 10L), range.get(i), "range entry");
            }
            MiniTest.assertEquals(1000, tree.rangeQuery(Integer.MIN_VALUE, Integer.MAX_VALUE).size(), "full range");
            assertNoPins(env, "queries");
        } finally {
            env.cleanup();
        }
    }

    private static void verifyAgainst(BPlusTree tree, TreeMap<Integer, Long> model, Random rnd, int rangeCount,
                                      int keySpan) {
        MiniTest.assertEquals((long) model.size(), tree.count(), "count");
        for (int i = 0; i < rangeCount; i++) {
            int a = rnd.nextInt(keySpan) - keySpan / 10;
            int b = rnd.nextInt(keySpan) - keySpan / 10;
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            List<BPlusTree.Entry> expected = new ArrayList<>();
            for (Map.Entry<Integer, Long> e : model.subMap(lo, true, hi, true).entrySet()) {
                expected.add(new BPlusTree.Entry(e.getKey(), e.getValue()));
            }
            MiniTest.assertEquals(expected, tree.rangeQuery(lo, hi), "range [" + lo + "," + hi + "]");
        }
        MiniTest.assertEquals(allEntries(model), tree.rangeQuery(Integer.MIN_VALUE, Integer.MAX_VALUE), "full scan");
    }

    private static List<BPlusTree.Entry> allEntries(TreeMap<Integer, Long> model) {
        List<BPlusTree.Entry> list = new ArrayList<>();
        model.forEach((k, v) -> list.add(new BPlusTree.Entry(k, v)));
        return list;
    }

    private static void randomSmallOrder() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 4, 4);
            TreeMap<Integer, Long> model = new TreeMap<>();
            Random rnd = new Random(42);
            int lastHeight = 1;
            int n = 20_000;
            List<Integer> keys = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                keys.add(i * 3 - 5000);
            }
            Collections.shuffle(keys, rnd);
            for (int i = 0; i < n; i++) {
                int key = keys.get(i);
                long value = rnd.nextLong();
                tree.insert(key, value);
                model.put(key, value);
                if (i % 1000 == 0) {
                    assertNoPins(env, "insert #" + i);
                    int h = tree.height();
                    MiniTest.assertTrue(h >= lastHeight, "height never shrinks");
                    lastHeight = h;
                }
            }
            assertNoPins(env, "bulk insert");
            MiniTest.assertEquals(root, tree.getRootPageNum(), "root unchanged");
            MiniTest.assertTrue(tree.height() >= 5, "height grew: " + tree.height());
            for (Map.Entry<Integer, Long> e : model.entrySet()) {
                MiniTest.assertEquals(OptionalLong.of(e.getValue()), tree.search(e.getKey()), "search " + e.getKey());
            }
            for (int i = 0; i < 500; i++) {
                int missing = rnd.nextInt(n * 3) - 5000;
                MiniTest.assertEquals(model.containsKey(missing), tree.search(missing).isPresent(), "presence");
            }
            verifyAgainst(tree, model, rnd, 300, n * 3);
            assertNoPins(env, "verification");
        } finally {
            env.cleanup();
        }
    }

    private static void descendingInserts() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 3, 3);
            TreeMap<Integer, Long> model = new TreeMap<>();
            for (int i = 2000; i > 0; i--) {
                tree.insert(i, i);
                model.put(i, (long) i);
            }
            verifyAgainst(tree, model, new Random(1), 100, 2200);
            assertNoPins(env, "descending inserts");
        } finally {
            env.cleanup();
        }
    }

    private static void duplicateInsert() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 4, 4);
            TreeMap<Integer, Long> model = new TreeMap<>();
            for (int i = 0; i < 300; i++) {
                tree.insert(i * 2, i);
                model.put(i * 2, (long) i);
            }
            int pagesBefore = env.pool().getPageCount();
            for (int i = 0; i < 300; i += 7) {
                int key = i * 2;
                DbException e = MiniTest.assertThrows(DbException.class, () -> tree.insert(key, -1L), "duplicate");
                MiniTest.assertEquals("duplicate key: " + key, e.getMessage(), "message");
                assertNoPins(env, "failed insert");
            }
            MiniTest.assertEquals(pagesBefore, env.pool().getPageCount(), "no page allocated by duplicates");
            verifyAgainst(tree, model, new Random(3), 50, 700);
        } finally {
            env.cleanup();
        }
    }

    private static void deleteHalf() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 4, 4);
            TreeMap<Integer, Long> model = new TreeMap<>();
            Random rnd = new Random(7);
            int n = 5000;
            List<Integer> keys = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                keys.add(i);
            }
            Collections.shuffle(keys, rnd);
            for (int key : keys) {
                tree.insert(key, key * 2L);
                model.put(key, key * 2L);
            }
            Collections.shuffle(keys, rnd);
            for (int i = 0; i < n / 2; i++) {
                int key = keys.get(i);
                MiniTest.assertTrue(tree.delete(key), "delete existing " + key);
                MiniTest.assertFalse(tree.delete(key), "delete again " + key);
                model.remove(key);
                MiniTest.assertFalse(tree.search(key).isPresent(), "deleted key gone");
            }
            assertNoPins(env, "deletes");
            for (int i = 0; i < n; i++) {
                MiniTest.assertEquals(model.containsKey(i), tree.search(i).isPresent(), "presence " + i);
            }
            verifyAgainst(tree, model, rnd, 300, n);
            // wipe a contiguous block so that whole leaves are empty and a range spans them
            for (int k = 1000; k < 1600; k++) {
                tree.delete(k);
                model.remove(k);
            }
            MiniTest.assertEquals(model.subMap(900, true, 1700, true).size(), tree.rangeQuery(900, 1700).size(),
                    "range across empty leaves");
            MiniTest.assertTrue(tree.rangeQuery(1000, 1599).isEmpty(), "range entirely inside the hole");
            verifyAgainst(tree, model, rnd, 100, n);
            assertNoPins(env, "verification");
        } finally {
            env.cleanup();
        }
    }

    private static void deleteAllThenReinsert() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 4, 4);
            for (int i = 0; i < 1000; i++) {
                tree.insert(i, i);
            }
            for (int i = 0; i < 1000; i++) {
                MiniTest.assertTrue(tree.delete(i), "delete " + i);
            }
            MiniTest.assertEquals(0L, tree.count(), "count after delete all");
            MiniTest.assertTrue(tree.rangeQuery(Integer.MIN_VALUE, Integer.MAX_VALUE).isEmpty(), "empty scan");
            MiniTest.assertFalse(tree.search(500).isPresent(), "search");
            TreeMap<Integer, Long> model = new TreeMap<>();
            Random rnd = new Random(11);
            for (int i = 0; i < 1500; i++) {
                int key = rnd.nextInt(3000);
                if (model.containsKey(key)) {
                    continue;
                }
                tree.insert(key, key + 1L);
                model.put(key, key + 1L);
            }
            verifyAgainst(tree, model, rnd, 100, 3000);
            MiniTest.assertEquals(root, tree.getRootPageNum(), "root unchanged");
            assertNoPins(env, "reinsert");
        } finally {
            env.cleanup();
        }
    }

    private static void update() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 4, 4);
            for (int i = 0; i < 200; i++) {
                tree.insert(i, i);
            }
            MiniTest.assertTrue(tree.update(77, 12345L), "update existing");
            MiniTest.assertEquals(OptionalLong.of(12345L), tree.search(77), "updated value");
            MiniTest.assertFalse(tree.update(1000, 1L), "update missing");
            MiniTest.assertFalse(tree.search(1000).isPresent(), "update did not insert");
            MiniTest.assertEquals(200L, tree.count(), "count unchanged");
            tree.delete(5);
            MiniTest.assertFalse(tree.update(5, 1L), "update deleted");
            MiniTest.assertEquals(OptionalLong.of(76), tree.search(76), "neighbour untouched");
            assertNoPins(env, "update");
        } finally {
            env.cleanup();
        }
    }

    private static void boundaryKeys() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 3, 3);
            int[] special = {Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1, -100, 100, Integer.MIN_VALUE + 1,
                Integer.MAX_VALUE - 1};
            TreeMap<Integer, Long> model = new TreeMap<>();
            for (int k : special) {
                tree.insert(k, k * 3L);
                model.put(k, k * 3L);
            }
            Random rnd = new Random(5);
            for (int i = 0; i < 300; i++) {
                int k = rnd.nextInt();
                if (model.containsKey(k)) {
                    continue;
                }
                tree.insert(k, k * 3L);
                model.put(k, k * 3L);
            }
            for (int k : special) {
                MiniTest.assertEquals(OptionalLong.of(k * 3L), tree.search(k), "search " + k);
            }
            MiniTest.assertEquals(allEntries(model), tree.rangeQuery(Integer.MIN_VALUE, Integer.MAX_VALUE), "all");
            MiniTest.assertEquals(1, tree.rangeQuery(Integer.MAX_VALUE, Integer.MAX_VALUE).size(), "max only");
            MiniTest.assertEquals(1, tree.rangeQuery(Integer.MIN_VALUE, Integer.MIN_VALUE).size(), "min only");
            MiniTest.assertEquals(model.subMap(-1, true, 1, true).size(), tree.rangeQuery(-1, 1).size(), "around 0");
            verifyAgainst(tree, model, rnd, 100, 1000);
            MiniTest.assertTrue(tree.delete(Integer.MIN_VALUE), "delete min");
            MiniTest.assertTrue(tree.delete(Integer.MAX_VALUE), "delete max");
            MiniTest.assertFalse(tree.search(Integer.MIN_VALUE).isPresent(), "min gone");
            assertNoPins(env, "boundary keys");
        } finally {
            env.cleanup();
        }
    }

    private static void emptyRange() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root, 4, 4);
            for (int i = 0; i < 100; i++) {
                tree.insert(i, i);
            }
            MiniTest.assertTrue(tree.rangeQuery(10, 9).isEmpty(), "lo > hi");
            MiniTest.assertTrue(tree.rangeQuery(Integer.MAX_VALUE, Integer.MIN_VALUE).isEmpty(), "extreme lo > hi");
            MiniTest.assertEquals(1, tree.rangeQuery(10, 10).size(), "single key range");
            MiniTest.assertTrue(tree.rangeQuery(200, 300).isEmpty(), "beyond the end");
            MiniTest.assertTrue(tree.rangeQuery(-300, -200).isEmpty(), "before the start");
            assertNoPins(env, "empty ranges");
        } finally {
            env.cleanup();
        }
    }

    private static void reopen() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree first = new BPlusTree(env.pool(), root, 4, 4);
            for (int i = 0; i < 500; i++) {
                first.insert(i, i * 5L);
            }
            BPlusTree second = new BPlusTree(env.pool(), root, 4, 4);
            MiniTest.assertEquals(500L, second.count(), "count via reopened tree");
            MiniTest.assertEquals(first.height(), second.height(), "height");
            MiniTest.assertEquals(OptionalLong.of(1245L), second.search(249), "search");
            second.insert(1000, 1L);
            MiniTest.assertEquals(OptionalLong.of(1L), first.search(1000), "write visible through the first object");
            BPlusTree physical = new BPlusTree(env.pool(), root);
            MiniTest.assertEquals(501L, physical.count(), "physical capacity view of a small-node tree");
            assertNoPins(env, "reopen");
        } finally {
            env.cleanup();
        }
    }

    private static void largeDefault() {
        Env env = Env.create();
        try {
            int root = BPlusTree.create(env.pool());
            BPlusTree tree = new BPlusTree(env.pool(), root);
            TreeMap<Integer, Long> model = new TreeMap<>();
            Random rnd = new Random(99);
            for (int i = 0; i < 60_000; i++) {
                int key = rnd.nextInt(1_000_000);
                if (model.containsKey(key)) {
                    MiniTest.assertThrows(DbException.class, () -> tree.insert(key, 0L), "duplicate");
                    continue;
                }
                tree.insert(key, key + 7L);
                model.put(key, key + 7L);
            }
            MiniTest.assertTrue(tree.height() >= 2, "multi-level");
            for (int i = 0; i < 20_000; i++) {
                int key = rnd.nextInt(1_000_000);
                MiniTest.assertEquals(model.remove(key) != null, tree.delete(key), "delete " + key);
            }
            verifyAgainst(tree, model, rnd, 100, 1_000_000);
            MiniTest.assertEquals(root, tree.getRootPageNum(), "root unchanged");
            assertNoPins(env, "large workload");
        } finally {
            env.cleanup();
        }
    }
}
