package com.minidb.table;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertFalse;
import static com.minidb.testkit.MiniTest.assertThrows;
import static com.minidb.testkit.MiniTest.assertTrue;

import com.minidb.DbException;
import com.minidb.cache.BufferPool;
import com.minidb.storage.Page;
import com.minidb.storage.Pager;
import com.minidb.testkit.MiniTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Unit tests for {@link Catalog} and {@link Table}, using only contract APIs of the lower layers. */
public final class TableTest {

    private TableTest() {
    }

    /** A pager + pool + catalog stack over one database file. */
    private static final class Stack {
        final Path file;
        final Pager pager;
        final BufferPool pool;
        final Catalog catalog;

        Stack(Path file) {
            this.file = file;
            this.pager = new Pager(file);
            this.pool = new BufferPool(pager, null, 100);
            Catalog.initialize(pool);
            this.catalog = new Catalog(pool);
        }

        void noPins(String when) {
            assertEquals(0, pool.getPinnedCount(), "pinned pages after " + when);
        }
    }

    private static Stack fresh(Path dir) {
        return new Stack(dir.resolve("t.db"));
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    /**
     * Runs all tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        Path dir = MiniTest.tempDir("tabletest");
        try {
            run(dir);
        } finally {
            MiniTest.deleteRecursively(dir);
        }
        MiniTest.finish();
    }

    private static void run(Path root) {
        MiniTest.test("initialize empty catalog and re-initialize existing", () -> {
            Path dir = Files.createDirectory(root.resolve("init"));
            Stack s = fresh(dir);
            assertEquals(1, s.pool.getPageCount(), "page count after init");
            assertTrue(s.catalog.listTables().isEmpty(), "no tables");
            Catalog.initialize(s.pool);
            assertEquals(1, s.pool.getPageCount(), "re-init adds no pages");
            s.noPins("init");
        });

        MiniTest.test("corrupt magic is rejected", () -> {
            Path dir = Files.createDirectory(root.resolve("corrupt"));
            Stack s = fresh(dir);
            try (Page p = s.pool.getPageForWrite(0)) {
                p.putBytes(0, "GARBAGE!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            DbException e = assertThrows(DbException.class, () -> Catalog.initialize(s.pool), "bad magic");
            assertTrue(e.getMessage().contains("not a minidb database file"), "message: " + e.getMessage());
            s.noPins("corrupt");
        });

        MiniTest.test("create / get / has / list tables, case-insensitivity", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("cat")));
            Table users = s.catalog.createTable("Users", "id", "name");
            s.catalog.createTable("orders", "oid", "note");
            assertEquals("Users", users.getName(), "name");
            assertEquals("id", users.getKeyColumn(), "key col");
            assertEquals("name", users.getValueColumn(), "value col");
            assertTrue(s.catalog.hasTable("USERS"), "has USERS");
            assertTrue(s.catalog.hasTable("users"), "has users");
            assertFalse(s.catalog.hasTable("nope"), "no nope");
            assertEquals("Users", s.catalog.getTable("uSeRs").getName(), "case-insensitive get");
            assertEquals(List.of("Users", "orders"), s.catalog.listTables(), "creation order");
            DbException e = assertThrows(DbException.class, () -> s.catalog.getTable("nope"), "missing");
            assertEquals("no such table: nope", e.getMessage(), "message");
            s.noPins("catalog ops");
        });

        MiniTest.test("invalid table definitions are rejected", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("invalid")));
            s.catalog.createTable("t", "id", "name");
            DbException dup = assertThrows(DbException.class, () -> s.catalog.createTable("T", "a", "b"), "dup");
            assertEquals("table already exists: T", dup.getMessage(), "dup message");
            assertThrows(DbException.class, () -> s.catalog.createTable(repeat("x", 33), "a", "b"), "long name");
            assertThrows(DbException.class, () -> s.catalog.createTable("ok", repeat("x", 33), "b"), "long key");
            assertThrows(DbException.class, () -> s.catalog.createTable("ok", "a", repeat("y", 33)), "long value");
            assertThrows(DbException.class, () -> s.catalog.createTable("", "a", "b"), "empty name");
            assertThrows(DbException.class, () -> s.catalog.createTable("ok", "Id", "iD"), "key == value");
            s.catalog.createTable(repeat("x", 32), "a", "b");
            assertEquals(2, s.catalog.listTables().size(), "failed creations left no trace");
            s.noPins("invalid");
        });

        MiniTest.test("catalog full", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("full")));
            for (int i = 0; i < Catalog.MAX_TABLES; i++) {
                s.catalog.createTable("t" + i, "id", "v");
            }
            assertThrows(DbException.class, () -> s.catalog.createTable("extra", "id", "v"), "full");
            assertEquals(Catalog.MAX_TABLES, s.catalog.listTables().size(), "table count");
            s.noPins("full");
        });

        MiniTest.test("insert 1000 rows, find all, count", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("ins")));
            Table t = s.catalog.createTable("t", "id", "name");
            for (int i = 1; i <= 1000; i++) {
                t.insert(new Row(i, "name" + i));
            }
            s.noPins("1000 inserts");
            assertEquals(1000L, t.count(), "count");
            for (int i = 1; i <= 1000; i++) {
                Optional<Row> r = t.find(i);
                assertTrue(r.isPresent() && r.get().name().equals("name" + i), "row " + i);
            }
            assertTrue(t.find(1001).isEmpty(), "absent");
            s.noPins("finds");
        });

        MiniTest.test("duplicate key throws and leaves count unchanged", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("dup")));
            Table t = s.catalog.createTable("t", "id", "name");
            t.insert(new Row(7, "seven"));
            int pagesBefore = s.pool.getPageCount();
            DbException e = assertThrows(DbException.class, () -> t.insert(new Row(7, "again")), "duplicate");
            assertEquals("duplicate key: 7", e.getMessage(), "message");
            assertEquals(1L, t.count(), "count unchanged");
            assertEquals("seven", t.find(7).orElseThrow().name(), "old value kept");
            assertEquals(pagesBefore, s.pool.getPageCount(), "no page allocated");
            s.noPins("duplicate");
        });

        MiniTest.test("scan ranges, full range and empty ranges", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("scan")));
            Table t = s.catalog.createTable("t", "id", "name");
            for (int i = 100; i >= 1; i--) {
                t.insert(new Row(i * 2, "n" + i));
            }
            List<Row> all = t.scan(Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertEquals(100, all.size(), "full scan size");
            for (int i = 0; i < 100; i++) {
                assertEquals((i + 1) * 2, all.get(i).id(), "ascending at " + i);
            }
            List<Row> mid = t.scan(11, 21);
            assertEquals(5, mid.size(), "11..21 holds 12,14,16,18,20");
            assertEquals(12, mid.get(0).id(), "first");
            assertEquals(20, mid.get(4).id(), "last");
            assertEquals(1, t.scan(10, 10).size(), "single point");
            assertTrue(t.scan(11, 11).isEmpty(), "missing point");
            assertTrue(t.scan(500, 600).isEmpty(), "above range");
            assertTrue(t.scan(-50, -1).isEmpty(), "below range");
            assertTrue(t.scan(30, 10).isEmpty(), "inverted range");
            s.noPins("scans");
        });

        MiniTest.test("delete, find empty, count, delete missing", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("del")));
            Table t = s.catalog.createTable("t", "id", "name");
            for (int i = 1; i <= 50; i++) {
                t.insert(new Row(i, "r" + i));
            }
            assertTrue(t.delete(10), "deleted");
            assertFalse(t.delete(10), "already gone");
            assertFalse(t.delete(999), "never existed");
            assertTrue(t.find(10).isEmpty(), "find empty");
            assertEquals(49L, t.count(), "count");
            assertEquals(49, t.scan(Integer.MIN_VALUE, Integer.MAX_VALUE).size(), "scan skips deleted");
            s.noPins("delete");
        });

        MiniTest.test("freed slot in the last heap page is reused", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("reuse")));
            Table t = s.catalog.createTable("t", "id", "name");
            for (int i = 1; i <= 20; i++) {
                t.insert(new Row(i, "r" + i));
            }
            int pages = s.pool.getPageCount();
            assertTrue(t.delete(20), "delete from last page");
            t.insert(new Row(21, "reused"));
            assertEquals(pages, s.pool.getPageCount(), "no new page needed");
            assertEquals("reused", t.find(21).orElseThrow().name(), "reused row readable");
            assertEquals("r19", t.find(19).orElseThrow().name(), "neighbour intact");
            assertEquals(20L, t.count(), "count");
            s.noPins("reuse");
        });

        MiniTest.test("update in place; too-long update throws and keeps old value", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("upd")));
            Table t = s.catalog.createTable("t", "id", "name");
            t.insert(new Row(1, "old"));
            assertTrue(t.update(1, "new"), "updated");
            assertEquals("new", t.find(1).orElseThrow().name(), "new value");
            assertFalse(t.update(2, "x"), "missing row");
            assertThrows(DbException.class, () -> t.update(1, repeat("z", 256)), "too long");
            assertEquals("new", t.find(1).orElseThrow().name(), "old value kept");
            assertEquals(1L, t.count(), "count");
            s.noPins("update");
        });

        MiniTest.test("two tables are independent", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("two")));
            Table a = s.catalog.createTable("a", "id", "v");
            Table b = s.catalog.createTable("b", "id", "v");
            for (int i = 1; i <= 100; i++) {
                a.insert(new Row(i, "a" + i));
                if (i <= 40) {
                    b.insert(new Row(i, "b" + i));
                }
            }
            assertEquals(100L, a.count(), "a count");
            assertEquals(40L, b.count(), "b count");
            assertEquals("b5", b.find(5).orElseThrow().name(), "b row");
            assertTrue(b.find(77).isEmpty(), "b lacks 77");
            b.delete(5);
            assertEquals("a5", a.find(5).orElseThrow().name(), "a unaffected");
            assertEquals(100L, s.catalog.getTable("A").count(), "fresh handle sees metadata");
            s.noPins("two tables");
        });

        MiniTest.test("255-byte UTF-8 names round-trip", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("utf")));
            Table t = s.catalog.createTable("t", "id", "v");
            String ascii = repeat("a", 255);
            String multi = repeat("é", 127) + "x"; // 127 * 2 + 1 = 255 bytes
            String cjk = repeat("漢", 85); // 85 * 3 = 255 bytes
            t.insert(new Row(1, ascii));
            t.insert(new Row(2, multi));
            t.insert(new Row(3, cjk));
            t.insert(new Row(4, ""));
            assertEquals(ascii, t.find(1).orElseThrow().name(), "ascii");
            assertEquals(multi, t.find(2).orElseThrow().name(), "two-byte chars");
            assertEquals(cjk, t.find(3).orElseThrow().name(), "three-byte chars");
            assertEquals("", t.find(4).orElseThrow().name(), "empty");
            assertThrows(DbException.class, () -> t.insert(new Row(5, repeat("漢", 86))), "256 bytes rejected");
            s.noPins("utf8");
        });

        MiniTest.test("churn keeps the file bounded (free list)", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("churn")));
            Table t = s.catalog.createTable("t", "id", "name");
            int next = 1;
            for (int round = 0; round < 20; round++) {
                int first = next;
                for (int i = 0; i < 15; i++) {
                    t.insert(new Row(next++, "r" + next));
                }
                for (int i = 0; i < 14; i++) {
                    t.delete(first + i);
                }
            }
            assertEquals(20L, t.count(), "live rows");
            assertTrue(s.pool.getPageCount() <= 1 + 1 + 3 + 1, "pages bounded: " + s.pool.getPageCount());
            s.noPins("churn");
        });

        MiniTest.test("delete from a middle page then insert reuses that page", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("mid")));
            Table t = s.catalog.createTable("t", "id", "name");
            for (int i = 1; i <= 45; i++) {
                t.insert(new Row(i, "r" + i));
            }
            int pages = s.pool.getPageCount();
            assertTrue(t.delete(20), "delete from middle page");
            assertTrue(t.delete(3), "delete from first page");
            t.insert(new Row(100, "a"));
            t.insert(new Row(101, "b"));
            assertEquals(pages, s.pool.getPageCount(), "no new page");
            assertEquals(45L, t.count(), "count");
            for (int i = 1; i <= 45; i++) {
                if (i != 20 && i != 3) {
                    assertEquals("r" + i, t.find(i).orElseThrow().name(), "row " + i);
                }
            }
            s.noPins("middle reuse");
        });

        MiniTest.test("free list survives reopen", () -> {
            Stack s = fresh(Files.createDirectory(root.resolve("freeopen")));
            Table t = s.catalog.createTable("t", "id", "name");
            for (int i = 1; i <= 45; i++) {
                t.insert(new Row(i, "r" + i));
            }
            t.delete(5);
            s.pool.flushAll();
            s.pager.close();
            Stack r = new Stack(s.file);
            Table t2 = r.catalog.getTable("t");
            int pages = r.pool.getPageCount();
            t2.insert(new Row(500, "back"));
            assertEquals(pages, r.pool.getPageCount(), "freed slot reused after reopen");
            assertEquals("back", t2.find(500).orElseThrow().name(), "row");
            r.noPins("free reopen");
        });

        MiniTest.test("persistence across close and reopen", () -> {
            Path dir = Files.createDirectory(root.resolve("persist"));
            Stack s = fresh(dir);
            Table a = s.catalog.createTable("alpha", "id", "txt");
            Table b = s.catalog.createTable("beta", "k", "v");
            for (int i = 1; i <= 300; i++) {
                a.insert(new Row(i, "alpha" + i));
            }
            b.insert(new Row(-5, "neg"));
            a.delete(150);
            a.update(1, "changed");
            s.pool.flushAll();
            s.pager.close();

            Stack r = new Stack(s.file);
            assertEquals(List.of("alpha", "beta"), r.catalog.listTables(), "tables");
            Table a2 = r.catalog.getTable("ALPHA");
            assertEquals("txt", a2.getValueColumn(), "value column");
            assertEquals(299L, a2.count(), "count");
            assertEquals("changed", a2.find(1).orElseThrow().name(), "update persisted");
            assertTrue(a2.find(150).isEmpty(), "delete persisted");
            assertEquals("alpha300", a2.find(300).orElseThrow().name(), "last row");
            assertEquals("neg", r.catalog.getTable("beta").find(-5).orElseThrow().name(), "beta row");
            a2.insert(new Row(1000, "after reopen"));
            assertEquals(300L, a2.count(), "insert after reopen");
            assertEquals(299, a2.scan(1, 300).size(), "scan after reopen");
            r.noPins("reopen");
        });
    }
}
