package com.minidb;

import com.minidb.query.QueryExecutor;
import com.minidb.query.QueryResult;
import com.minidb.testkit.MiniTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertFalse;
import static com.minidb.testkit.MiniTest.assertTrue;

/**
 * End-to-end tests of the real storage stack driven through SQL text: parser → executor →
 * catalog/table → B+ tree → buffer pool → journal → pager. A deliberately tiny buffer pool
 * forces Clock evictions of dirty pages in the middle of transactions.
 */
public final class EngineTest {

    private static final int TINY_CACHE = 8;

    private EngineTest() {
    }

    /**
     * Runs all engine tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        System.out.println("EngineTest");
        MiniTest.test("5000 rows with an 8-page cache: point, range, full scan, reopen", EngineTest::bulkWithTinyCache);
        MiniTest.test("ROLLBACK after dirty evictions restores data and file size", EngineTest::rollbackAfterEvictions);
        MiniTest.test("COMMIT of a large transaction survives reopen", EngineTest::commitLargeTransaction);
        MiniTest.test("randomized workload matches a TreeMap model across reopens", EngineTest::randomizedModel);
        MiniTest.test("CREATE TABLE inside a rolled back transaction disappears", EngineTest::rollbackCreateTable);
        MiniTest.test("UPDATE and DELETE through SQL", EngineTest::updateAndDelete);
        MiniTest.test("errors are reported, never thrown", EngineTest::errorsAreResults);
        MiniTest.test("no journal file remains after autocommit statements", EngineTest::noJournalLeftBehind);
        MiniTest.finish();
    }

    private static String run(QueryExecutor s, String sql) {
        QueryResult r = s.execute(sql);
        if (r.isError()) {
            throw new AssertionError("unexpected error for [" + sql + "]: " + r.getMessage());
        }
        return r.render();
    }

    private static QueryResult query(QueryExecutor s, String sql) {
        QueryResult r = s.execute(sql);
        if (r.isError()) {
            throw new AssertionError("unexpected error for [" + sql + "]: " + r.getMessage());
        }
        return r;
    }

    private static void bulkWithTinyCache() {
        Path dir = MiniTest.tempDir("engine-bulk");
        try {
            try (Database db = Database.open(dir, "t", TINY_CACHE); QueryExecutor s = new QueryExecutor(db)) {
                run(s, "CREATE TABLE users (id INT, name VARCHAR(255))");
                for (int i = 1; i <= 5000; i++) {
                    run(s, "INSERT INTO users VALUES (" + i + ", 'user_" + i + "')");
                }
                assertTrue(db.bufferPool().getStats().evictions() > 0, "tiny cache must evict");
                assertEquals(0, db.bufferPool().getPinnedCount(), "no pins leak");
                QueryResult one = query(s, "SELECT * FROM users WHERE id = 4321");
                assertEquals(List.of(List.of("4321", "user_4321")), one.getRows(), "point lookup");
                assertEquals(99, query(s, "SELECT * FROM users WHERE id > 100 AND id < 200").getRowCount(), "range");
                assertEquals(5000, query(s, "SELECT * FROM users").getRowCount(), "full scan");
            }
            try (Database db = Database.open(dir, "t", TINY_CACHE); QueryExecutor s = new QueryExecutor(db)) {
                QueryResult all = query(s, "SELECT id FROM users");
                assertEquals(5000, all.getRowCount(), "rows after reopen");
                for (int i = 0; i < 5000; i++) {
                    assertEquals(String.valueOf(i + 1), all.getRows().get(i).get(0), "ascending order");
                }
                assertEquals(List.of(List.of("2500", "user_2500")),
                        query(s, "SELECT * FROM users WHERE id = 2500").getRows(), "point after reopen");
            }
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void rollbackAfterEvictions() throws Exception {
        Path dir = MiniTest.tempDir("engine-rollback");
        try (Database db = Database.open(dir, "t", TINY_CACHE); QueryExecutor s = new QueryExecutor(db)) {
            run(s, "CREATE TABLE users (id INT, name VARCHAR)");
            for (int i = 1; i <= 300; i++) {
                run(s, "INSERT INTO users VALUES (" + i + ", 'orig_" + i + "')");
            }
            long sizeBefore = Files.size(db.getDbFile());
            long evictionsBefore = db.bufferPool().getStats().evictions();
            run(s, "BEGIN");
            for (int i = 1000; i < 3000; i++) {
                run(s, "INSERT INTO users VALUES (" + i + ", 'new_" + i + "')");
            }
            run(s, "UPDATE users SET name = 'changed' WHERE id <= 300");
            run(s, "DELETE FROM users WHERE id > 100 AND id <= 200");
            assertTrue(db.bufferPool().getStats().evictions() > evictionsBefore, "transaction must evict pages");
            assertTrue(Files.exists(dir.resolve("t.journal")), "journal exists during transaction");
            String r = run(s, "ROLLBACK");
            assertEquals("OK: transaction rolled back", r, "rollback message");
            assertFalse(Files.exists(dir.resolve("t.journal")), "journal deleted after rollback");
            assertEquals(sizeBefore, Files.size(db.getDbFile()), "file truncated to pre-transaction size");
            QueryResult all = query(s, "SELECT * FROM users");
            assertEquals(300, all.getRowCount(), "only original rows");
            for (int i = 0; i < 300; i++) {
                assertEquals(List.of(String.valueOf(i + 1), "orig_" + (i + 1)), all.getRows().get(i), "original row " + (i + 1));
            }
            run(s, "INSERT INTO users VALUES (1000, 'after')");
            assertEquals(1, query(s, "SELECT * FROM users WHERE id = 1000").getRowCount(), "usable after rollback");
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void commitLargeTransaction() {
        Path dir = MiniTest.tempDir("engine-commit");
        try {
            try (Database db = Database.open(dir, "t", TINY_CACHE); QueryExecutor s = new QueryExecutor(db)) {
                run(s, "BEGIN");
                run(s, "CREATE TABLE items (id INT, label TEXT)");
                StringBuilder multi = new StringBuilder("INSERT INTO items VALUES ");
                for (int i = 0; i < 2000; i++) {
                    multi.append(i == 0 ? "" : ", ").append('(').append(i).append(", 'item ").append(i).append("')");
                }
                assertEquals("OK: 2000 rows inserted", run(s, multi.toString()), "multi-row insert");
                assertEquals("OK: transaction committed", run(s, "COMMIT"), "commit");
            }
            try (Database db = Database.open(dir, "t", TINY_CACHE); QueryExecutor s = new QueryExecutor(db)) {
                assertFalse(db.wasRecovered(), "clean shutdown needs no recovery");
                assertEquals(2000, query(s, "SELECT * FROM items").getRowCount(), "committed rows persisted");
                assertEquals(List.of("id", "label"), query(s, "SELECT * FROM items WHERE id = 7").getColumns(), "column names persisted");
            }
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void randomizedModel() {
        Path dir = MiniTest.tempDir("engine-model");
        Random rnd = new Random(42);
        TreeMap<Integer, String> model = new TreeMap<>();
        try {
            for (int round = 0; round < 3; round++) {
                try (Database db = Database.open(dir, "t", 16); QueryExecutor s = new QueryExecutor(db)) {
                    if (round == 0) {
                        run(s, "CREATE TABLE kv (k INT, v VARCHAR)");
                    }
                    for (int op = 0; op < 1500; op++) {
                        int key = rnd.nextInt(2000) - 1000;
                        int kind = rnd.nextInt(10);
                        if (kind < 5) {
                            QueryResult r = s.execute("INSERT INTO kv VALUES (" + key + ", 'v" + op + "')");
                            assertEquals(model.containsKey(key), r.isError(), "insert outcome for " + key);
                            model.putIfAbsent(key, "v" + op);
                        } else if (kind < 7) {
                            run(s, "DELETE FROM kv WHERE k = " + key);
                            model.remove(key);
                        } else if (kind < 8) {
                            String updated = "u" + op;
                            run(s, "UPDATE kv SET v = '" + updated + "' WHERE k = " + key);
                            model.computeIfPresent(key, (k, v) -> updated);
                        } else {
                            int lo = key;
                            int hi = key + rnd.nextInt(300);
                            QueryResult r = query(s, "SELECT * FROM kv WHERE k >= " + lo + " AND k <= " + hi);
                            List<List<String>> expected = new ArrayList<>();
                            model.subMap(lo, true, hi, true).forEach((k, v) -> expected.add(List.of(String.valueOf(k), v)));
                            assertEquals(expected, r.getRows(), "range [" + lo + "," + hi + "]");
                        }
                    }
                    List<List<String>> expected = new ArrayList<>();
                    model.forEach((k, v) -> expected.add(List.of(String.valueOf(k), v)));
                    assertEquals(expected, query(s, "SELECT * FROM kv").getRows(), "full table after round " + round);
                }
            }
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void rollbackCreateTable() {
        Path dir = MiniTest.tempDir("engine-ddl");
        try (Database db = Database.open(dir, "t"); QueryExecutor s = new QueryExecutor(db)) {
            run(s, "CREATE TABLE keep (id INT, name VARCHAR)");
            run(s, "BEGIN");
            run(s, "CREATE TABLE temp (id INT, name VARCHAR)");
            run(s, "INSERT INTO temp VALUES (1, 'x')");
            run(s, "ROLLBACK");
            QueryResult tables = query(s, "SHOW TABLES");
            assertEquals(List.of(List.of("keep")), tables.getRows(), "only committed table remains");
            assertTrue(s.execute("SELECT * FROM temp").isError(), "rolled back table is gone");
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void updateAndDelete() {
        Path dir = MiniTest.tempDir("engine-upd");
        try (Database db = Database.open(dir, "t"); QueryExecutor s = new QueryExecutor(db)) {
            run(s, "CREATE TABLE users (id INT, name VARCHAR)");
            run(s, "INSERT INTO users VALUES (1, 'Jewel'), (2, 'Baten'), (3, 'O''Brien')");
            assertEquals("OK: 1 row updated", run(s, "UPDATE users SET name = 'Rahim' WHERE id = 2"), "update");
            assertEquals(List.of(List.of("2", "Rahim")), query(s, "SELECT * FROM users WHERE id = 2").getRows(), "updated");
            assertEquals(List.of(List.of("3", "O'Brien")), query(s, "SELECT * FROM users WHERE name = 'O''Brien'").getRows(), "quote escape + value filter");
            assertEquals("OK: 2 rows deleted", run(s, "DELETE FROM users WHERE id BETWEEN 2 AND 3"), "delete between");
            assertEquals("OK: 1 row deleted", run(s, "DELETE FROM users"), "delete all");
            assertEquals(0, query(s, "SELECT * FROM users").getRowCount(), "empty");
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void errorsAreResults() {
        Path dir = MiniTest.tempDir("engine-err");
        try (Database db = Database.open(dir, "t"); QueryExecutor s = new QueryExecutor(db)) {
            run(s, "CREATE TABLE users (id INT, name VARCHAR)");
            run(s, "INSERT INTO users VALUES (1, 'a')");
            String[] bad = {
                "SELEC * FROM users", "SELECT * FROM nope", "INSERT INTO users VALUES (1, 'dup')",
                "INSERT INTO users VALUES (99999999999, 'big')", "COMMIT", "ROLLBACK",
                "SELECT nope FROM users", "UPDATE users SET id = '5'", "SELECT * FROM users WHERE id = 'x'",
                "CREATE TABLE users (id INT, name VARCHAR)", "INSERT INTO users VALUES (2, '" + "x".repeat(256) + "')", "",
                "INSERT INTO users VALUES (3, '\uD800')"
            };
            for (String sql : bad) {
                QueryResult r = s.execute(sql);
                assertTrue(r.isError(), "expected error for: " + sql);
                assertTrue(r.render().startsWith("ERROR: "), "rendered as ERROR for: " + sql);
            }
            assertEquals(1, query(s, "SELECT * FROM users").getRowCount(), "failed statements changed nothing");
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void noJournalLeftBehind() {
        Path dir = MiniTest.tempDir("engine-journal");
        try (Database db = Database.open(dir, "t"); QueryExecutor s = new QueryExecutor(db)) {
            run(s, "CREATE TABLE users (id INT, name VARCHAR)");
            run(s, "INSERT INTO users VALUES (1, 'a')");
            s.execute("INSERT INTO users VALUES (1, 'dup')");
            assertFalse(Files.exists(dir.resolve("t.journal")), "no journal after autocommit");
            assertFalse(db.isTransactionActive(), "no transaction left active");
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }
}
