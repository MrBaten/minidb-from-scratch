package com.minidb.query;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertFalse;
import static com.minidb.testkit.MiniTest.assertTrue;

import com.minidb.Database;
import com.minidb.query.Statement.Begin;
import com.minidb.query.Statement.Comparison;
import com.minidb.query.Statement.Commit;
import com.minidb.query.Statement.CreateTable;
import com.minidb.query.Statement.Delete;
import com.minidb.query.Statement.Insert;
import com.minidb.query.Statement.Op;
import com.minidb.query.Statement.Rollback;
import com.minidb.query.Statement.Select;
import com.minidb.query.Statement.ShowTables;
import com.minidb.query.Statement.Tuple;
import com.minidb.query.Statement.Update;
import com.minidb.testkit.MiniTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Tests of {@link QueryExecutor}, driven through {@link Statement} records. */
public final class QueryExecutorTest {

    /** Buffer pool size small enough to exhaust during a B+ tree split. */
    private static final int MUTATION_CACHE = 2;

    private QueryExecutorTest() {
    }

    /** A test body that receives a fresh database and a session on it. */
    private interface Body {
        void run(Database db, QueryExecutor ex) throws Throwable;
    }

    private static void withDb(String name, Body body) {
        MiniTest.test(name, () -> {
            Path dir = MiniTest.tempDir("exec");
            Database db = Database.open(dir, "test");
            QueryExecutor ex = new QueryExecutor(db, 500);
            try {
                body.run(db, ex);
            } finally {
                ex.close();
                db.close();
                MiniTest.deleteRecursively(dir);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static Comparison cmp(String col, Op op, Object v) {
        return new Comparison(col, op, v);
    }

    private static Tuple t(long k, String v) {
        return new Tuple(k, v);
    }

    private static void ok(QueryResult r, String message) {
        assertFalse(r.isError(), "unexpected error: " + r.getMessage());
        assertEquals(message, r.getMessage(), "message");
    }

    private static void err(QueryResult r, String message) {
        assertTrue(r.isError(), "expected error '" + message + "' but got " + r.render());
        assertEquals(message, r.getMessage(), "error message");
    }

    private static QueryResult sel(QueryExecutor ex, List<Comparison> where) {
        return ex.execute(new Select("users", List.of(), where));
    }

    private static int count(QueryExecutor ex) {
        QueryResult r = sel(ex, List.of());
        assertTrue(r.hasRows(), "select should return rows: " + r.render());
        return r.getRowCount();
    }

    private static void setup(QueryExecutor ex) {
        ok(ex.execute(new CreateTable("users", "id", "name")), "table users created");
    }

    private static void fill(QueryExecutor ex, int from, int to) {
        List<Tuple> tuples = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            tuples.add(t(i, "n" + i));
        }
        QueryResult r = ex.execute(new Insert("users", tuples));
        assertFalse(r.isError(), "fill failed: " + r.getMessage());
    }

    private static List<String> column(QueryResult r, int index) {
        List<String> out = new ArrayList<>();
        for (List<String> row : r.getRows()) {
            out.add(row.get(index));
        }
        return out;
    }

    // ---------------------------------------------------------------- tests

    /**
     * Runs the test suite.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        System.out.println("QueryExecutorTest");

        withDb("create table, duplicate create is an error", (db, ex) -> {
            setup(ex);
            assertTrue(ex.execute(new CreateTable("USERS", "id", "name")).isError(), "case-insensitive duplicate rejected");
            assertTrue(db.catalog().hasTable("users"), "table exists");
        });

        withDb("insert single and multi row, singular/plural wording", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Insert("users", List.of(t(1, "Jewel")))), "1 row inserted");
            ok(ex.execute(new Insert("users", List.of(t(2, "a"), t(3, "b"), t(4, "c")))), "3 rows inserted");
            assertEquals(4, count(ex), "row count");
            err(ex.execute(new Insert("nope", List.of(t(1, "x")))), "no such table: nope");
        });

        withDb("insert duplicate (existing / inside statement) leaves table unchanged", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 3);
            err(ex.execute(new Insert("users", List.of(t(10, "x"), t(2, "dup")))), "duplicate key: 2");
            err(ex.execute(new Insert("users", List.of(t(20, "x"), t(21, "y"), t(20, "z")))), "duplicate key: 20");
            assertEquals(3, count(ex), "unchanged");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.EQ, 10L))).getRowCount(), "10 absent");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.EQ, 20L))).getRowCount(), "20 absent");
        });

        withDb("insert out-of-range INT and too-long value", (db, ex) -> {
            setup(ex);
            err(ex.execute(new Insert("users", List.of(t(1, "ok"), t(3_000_000_000L, "x")))),
                    "value out of range for INT: 3000000000");
            err(ex.execute(new Insert("users", List.of(t(-2_147_483_649L, "x")))),
                    "value out of range for INT: -2147483649");
            QueryResult tooLong = ex.execute(new Insert("users", List.of(t(1, "ok"), t(2, "x".repeat(256)))));
            assertTrue(tooLong.isError(), "256 bytes rejected");
            ok(ex.execute(new Insert("users", List.of(t(5, "x".repeat(255))))), "1 row inserted");
            // 128 two-byte characters = 256 bytes
            assertTrue(ex.execute(new Insert("users", List.of(t(6, "é".repeat(128))))).isError(),
                    "multi-byte overflow rejected");
            assertEquals(1, count(ex), "only the valid row is present");
        });

        withDb("select *, column list, unknown column, stored header names", (db, ex) -> {
            ok(ex.execute(new CreateTable("users", "ID", "Name")), "table users created");
            fill(ex, 1, 2);
            QueryResult all = sel(ex, List.of());
            assertEquals(List.of("ID", "Name"), all.getColumns(), "headers for *");
            assertEquals(List.of("1", "n1"), all.getRows().get(0), "first row");
            QueryResult cols = ex.execute(new Select("users", List.of("name", "id"), List.of()));
            assertEquals(List.of("Name", "ID"), cols.getColumns(), "headers keep stored case and order");
            assertEquals(List.of("n2", "2"), cols.getRows().get(1), "second row");
            err(ex.execute(new Select("users", List.of("id", "email"), List.of())), "no such column: email");
            err(ex.execute(new Select("users", List.of(), List.of(cmp("age", Op.EQ, 1L)))), "no such column: age");
        });

        withDb("point and range queries", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 1000);
            QueryResult point = sel(ex, List.of(cmp("ID", Op.EQ, 500L)));
            assertEquals(1, point.getRowCount(), "point row");
            assertEquals(List.of("500", "n500"), point.getRows().get(0), "point cells");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.EQ, 5000L))).getRowCount(), "miss");
            QueryResult range = sel(ex, List.of(cmp("id", Op.GT, 100L), cmp("id", Op.LT, 200L)));
            assertEquals(99, range.getRowCount(), "id > 100 AND id < 200");
            assertEquals("101", range.getRows().get(0).get(0), "first");
            assertEquals("199", range.getRows().get(98).get(0), "last");
            QueryResult between = sel(ex, List.of(cmp("id", Op.GE, 10L), cmp("id", Op.LE, 20L)));
            assertEquals(11, between.getRowCount(), "BETWEEN 10 AND 20");
            assertEquals(1000, count(ex), "full scan");
            assertEquals(3, sel(ex, List.of(cmp("id", Op.GE, 998L))).getRowCount(), "open upper end");
            assertEquals(2, sel(ex, List.of(cmp("id", Op.LE, 2L))).getRowCount(), "open lower end");
        });

        withDb("empty, contradictory and equal-bound ranges", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 50);
            assertEquals(0, sel(ex, List.of(cmp("id", Op.GT, 10L), cmp("id", Op.LT, 11L))).getRowCount(), "(10,11)");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.GT, 30L), cmp("id", Op.LT, 20L))).getRowCount(), "30<id<20");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.EQ, 5L), cmp("id", Op.EQ, 6L))).getRowCount(), "id=5 AND id=6");
            assertEquals(1, sel(ex, List.of(cmp("id", Op.EQ, 5L), cmp("id", Op.GE, 5L))).getRowCount(), "id=5 AND id>=5");
            assertEquals(1, sel(ex, List.of(cmp("id", Op.GE, 7L), cmp("id", Op.LE, 7L))).getRowCount(), "7..7");
            assertEquals(1, sel(ex, List.of(cmp("id", Op.GT, 6L), cmp("id", Op.LT, 8L))).getRowCount(), "(6,8)");
        });

        withDb("Integer.MAX_VALUE / MIN_VALUE and Long extremes", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Insert("users", List.of(t(Integer.MAX_VALUE, "max"), t(Integer.MIN_VALUE, "min"), t(0, "zero")))),
                    "3 rows inserted");
            assertEquals(List.of("max"), column(sel(ex, List.of(cmp("id", Op.EQ, (long) Integer.MAX_VALUE))), 1), "eq max");
            assertEquals(List.of("min"), column(sel(ex, List.of(cmp("id", Op.EQ, (long) Integer.MIN_VALUE))), 1), "eq min");
            assertEquals(1, sel(ex, List.of(cmp("id", Op.GT, 0L))).getRowCount(), "> 0");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.GT, (long) Integer.MAX_VALUE))).getRowCount(), "> int max");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.LT, (long) Integer.MIN_VALUE))).getRowCount(), "< int min");
            assertEquals(3, sel(ex, List.of(cmp("id", Op.GE, Long.MIN_VALUE))).getRowCount(), ">= Long.MIN");
            assertEquals(3, sel(ex, List.of(cmp("id", Op.LE, Long.MAX_VALUE))).getRowCount(), "<= Long.MAX");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.GT, Long.MAX_VALUE))).getRowCount(), "> Long.MAX");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.LT, Long.MIN_VALUE))).getRowCount(), "< Long.MIN");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.EQ, 5_000_000_000L))).getRowCount(), "= out of int range");
            assertEquals(3, sel(ex, List.of(cmp("id", Op.LT, 5_000_000_000L))).getRowCount(), "< huge");
            assertEquals(3, sel(ex, List.of(cmp("id", Op.GT, -5_000_000_000L))).getRowCount(), "> -huge");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.GT, 5_000_000_000L))).getRowCount(), "> huge");
        });

        withDb("value-column filters and combined predicates", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Insert("users", List.of(t(1, "alice"), t(2, "bob"), t(3, "carol"), t(4, "bob")))),
                    "4 rows inserted");
            assertEquals(List.of("2", "4"), column(sel(ex, List.of(cmp("name", Op.EQ, "bob"))), 0), "name = bob");
            assertEquals(List.of("3"), column(sel(ex, List.of(cmp("name", Op.GT, "bob"))), 0), "name > bob");
            assertEquals(List.of("2", "3", "4"), column(sel(ex, List.of(cmp("name", Op.GE, "bob"))), 0), "name >= bob");
            assertEquals(List.of("1"), column(sel(ex, List.of(cmp("name", Op.LT, "bob"))), 0), "name < bob");
            assertEquals(List.of("1", "2", "4"), column(sel(ex, List.of(cmp("NAME", Op.LE, "bob"))), 0), "name <= bob");
            assertEquals(List.of("4"), column(sel(ex, List.of(cmp("name", Op.EQ, "bob"), cmp("id", Op.GT, 2L))), 0),
                    "name = bob AND id > 2");
        });

        withDb("type mismatch errors", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 3);
            err(sel(ex, List.of(cmp("id", Op.EQ, "one"))), "type mismatch for column id");
            err(sel(ex, List.of(cmp("name", Op.EQ, 1L))), "type mismatch for column name");
            err(ex.execute(new Delete("users", List.of(cmp("id", Op.EQ, "x")))), "type mismatch for column id");
            assertEquals(3, count(ex), "failed delete changed nothing");
            // type is checked even when the key range is already empty
            err(sel(ex, List.of(cmp("id", Op.GT, 10L), cmp("id", Op.LT, 5L), cmp("name", Op.EQ, 7L))),
                    "type mismatch for column name");
        });

        withDb("delete point / range / all with counts", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 100);
            ok(ex.execute(new Delete("users", List.of(cmp("id", Op.EQ, 10L)))), "1 row deleted");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.EQ, 10L))).getRowCount(), "10 gone");
            ok(ex.execute(new Delete("users", List.of(cmp("id", Op.EQ, 10L)))), "0 rows deleted");
            ok(ex.execute(new Delete("users", List.of(cmp("id", Op.GE, 91L)))), "10 rows deleted");
            ok(ex.execute(new Delete("users", List.of(cmp("id", Op.GT, 20L), cmp("id", Op.LE, 25L)))), "5 rows deleted");
            ok(ex.execute(new Delete("users", List.of(cmp("name", Op.EQ, "n1")))), "1 row deleted");
            assertEquals(100 - 1 - 10 - 5 - 1, count(ex), "remaining");
            ok(ex.execute(new Delete("users", List.of())), "83 rows deleted");
            assertEquals(0, count(ex), "empty");
            ok(ex.execute(new Delete("users", List.of())), "0 rows deleted");
        });

        withDb("update value column, key column error, unknown column", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 10);
            ok(ex.execute(new Update("users", "name", "new", List.of(cmp("id", Op.EQ, 3L)))), "1 row updated");
            assertEquals("new", sel(ex, List.of(cmp("id", Op.EQ, 3L))).getRows().get(0).get(1), "updated value");
            ok(ex.execute(new Update("users", "NAME", "x", List.of(cmp("id", Op.GT, 7L)))), "3 rows updated");
            ok(ex.execute(new Update("users", "name", "x", List.of(cmp("id", Op.EQ, 99L)))), "0 rows updated");
            ok(ex.execute(new Update("users", "name", "all", List.of())), "10 rows updated");
            err(ex.execute(new Update("users", "id", "x", List.of())), "cannot update key column id");
            err(ex.execute(new Update("users", "age", "x", List.of())), "no such column: age");
            assertTrue(ex.execute(new Update("users", "name", "x".repeat(300), List.of())).isError(), "too long");
            assertEquals(List.of("all"), column(sel(ex, List.of(cmp("id", Op.EQ, 1L))), 1), "unchanged by failures");
        });

        withDb("show tables", (db, ex) -> {
            QueryResult empty = ex.execute(new ShowTables());
            assertEquals(List.of("table"), empty.getColumns(), "header");
            assertEquals(0, empty.getRowCount(), "none yet");
            setup(ex);
            ok(ex.execute(new CreateTable("orders", "id", "item")), "table orders created");
            QueryResult r = ex.execute(new ShowTables());
            assertEquals(List.of("users", "orders"), column(r, 0), "creation order");
        });

        withDb("BEGIN / INSERT / ROLLBACK leaves the row absent", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 2);
            ok(ex.execute(new Begin()), "transaction started");
            assertTrue(ex.inTransaction(), "in txn");
            ok(ex.execute(new Insert("users", List.of(t(100, "tmp")))), "1 row inserted");
            assertEquals(3, count(ex), "visible inside txn");
            ok(ex.execute(new Rollback()), "transaction rolled back");
            assertFalse(ex.inTransaction(), "txn over");
            assertFalse(db.isLockedByCurrentThread(), "lock released");
            assertEquals(0, sel(ex, List.of(cmp("id", Op.EQ, 100L))).getRowCount(), "absent");
            assertEquals(2, count(ex), "two rows");
        });

        withDb("BEGIN / INSERT / COMMIT keeps the row", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Begin()), "transaction started");
            ok(ex.execute(new Insert("users", List.of(t(100, "kept")))), "1 row inserted");
            ok(ex.execute(new Commit()), "transaction committed");
            assertFalse(db.isLockedByCurrentThread(), "lock released");
            assertEquals(1, sel(ex, List.of(cmp("id", Op.EQ, 100L))).getRowCount(), "present");
            assertFalse(db.isTransactionActive(), "no db txn left");
        });

        withDb("BEGIN twice and COMMIT/ROLLBACK without BEGIN", (db, ex) -> {
            err(ex.execute(new Commit()), "no active transaction");
            err(ex.execute(new Rollback()), "no active transaction");
            ok(ex.execute(new Begin()), "transaction started");
            err(ex.execute(new Begin()), "transaction already active");
            assertTrue(ex.inTransaction(), "first txn still open");
            ok(ex.execute(new Rollback()), "transaction rolled back");
            err(ex.execute(new Rollback()), "no active transaction");
            assertFalse(db.isLockedByCurrentThread(), "lock released");
        });

        withDb("CREATE TABLE inside a rolled back transaction disappears", (db, ex) -> {
            ok(ex.execute(new Begin()), "transaction started");
            setup(ex);
            assertTrue(db.catalog().hasTable("users"), "visible inside txn");
            ok(ex.execute(new Rollback()), "transaction rolled back");
            assertFalse(db.catalog().hasTable("users"), "table gone");
            assertEquals(0, ex.execute(new ShowTables()).getRowCount(), "show tables empty");
        });

        withDb("error inside explicit transaction keeps it open", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Begin()), "transaction started");
            ok(ex.execute(new Insert("users", List.of(t(1, "a")))), "1 row inserted");
            err(ex.execute(new Insert("users", List.of(t(1, "dup")))), "duplicate key: 1");
            err(ex.execute(new Insert("missing", List.of(t(1, "x")))), "no such table: missing");
            assertTrue(ex.inTransaction(), "still open");
            ok(ex.execute(new Insert("users", List.of(t(2, "b")))), "1 row inserted");
            ok(ex.execute(new Commit()), "transaction committed");
            assertEquals(2, count(ex), "both statements committed");
        });

        withDb("autocommit failure leaves no trace and releases the lock", (db, ex) -> {
            setup(ex);
            fill(ex, 1, 2);
            err(ex.execute(new Insert("users", List.of(t(1, "dup")))), "duplicate key: 1");
            assertFalse(db.isLockedByCurrentThread(), "lock released");
            assertFalse(db.isTransactionActive(), "no txn left");
            assertEquals(2, count(ex), "rows unchanged");
        });

        withDb("close() with open transaction rolls back and releases the lock", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Begin()), "transaction started");
            ok(ex.execute(new Insert("users", List.of(t(1, "gone")))), "1 row inserted");
            ex.close();
            ex.close(); // idempotent
            assertFalse(ex.inTransaction(), "closed");
            assertFalse(db.isTransactionActive(), "db txn rolled back");
            AtomicReference<String> second = new AtomicReference<>();
            Thread th = new Thread(() -> {
                try (QueryExecutor other = new QueryExecutor(db, 500)) {
                    second.set(other.execute(new Begin()).render() + "|" + count(other));
                }
            });
            th.start();
            th.join();
            assertEquals("OK: transaction started|0", second.get(), "second session could BEGIN and sees no row");
        });

        withDb("lock contention: second session gets 'database is locked'", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Begin()), "transaction started"); // main thread = session A
            AtomicReference<QueryResult> begin = new AtomicReference<>();
            AtomicReference<QueryResult> read = new AtomicReference<>();
            AtomicReference<QueryResult> write = new AtomicReference<>();
            Thread th = new Thread(() -> {
                try (QueryExecutor b = new QueryExecutor(db, 200)) {
                    begin.set(b.execute(new Begin()));
                    read.set(b.execute(new Select("users", List.of(), List.of())));
                    write.set(b.execute(new Insert("users", List.of(t(1, "x")))));
                }
            });
            th.start();
            th.join();
            err(begin.get(), "database is locked");
            err(read.get(), "database is locked");
            err(write.get(), "database is locked");
            ok(ex.execute(new Commit()), "transaction committed");
            assertEquals(0, count(ex), "blocked insert did not happen");
        });

        withDb("a failed BEGIN does not open a transaction", (db, ex) -> {
            AtomicReference<Boolean> flag = new AtomicReference<>();
            ok(ex.execute(new Begin()), "transaction started");
            Thread th = new Thread(() -> {
                try (QueryExecutor b = new QueryExecutor(db, 50)) {
                    b.execute(new Begin());
                    flag.set(b.inTransaction());
                    err(b.execute(new Commit()), "no active transaction");
                }
            });
            th.start();
            th.join();
            assertEquals(Boolean.FALSE, flag.get(), "B not in txn");
        });

        withDb("row-count wording is singular for 1 and plural otherwise", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Insert("users", List.of(t(1, "a")))), "1 row inserted");
            ok(ex.execute(new Insert("users", List.of(t(2, "a"), t(3, "a")))), "2 rows inserted");
            ok(ex.execute(new Update("users", "name", "b", List.of(cmp("id", Op.EQ, 1L)))), "1 row updated");
            ok(ex.execute(new Update("users", "name", "b", List.of(cmp("id", Op.GE, 1L)))), "3 rows updated");
            ok(ex.execute(new Delete("users", List.of(cmp("id", Op.EQ, 1L)))), "1 row deleted");
            ok(ex.execute(new Delete("users", List.of())), "2 rows deleted");
            ok(ex.execute(new Delete("users", List.of())), "0 rows deleted");
        });

        withDb("CREATE TABLE validation errors keep the explicit transaction open", (db, ex) -> {
            setup(ex);
            ok(ex.execute(new Begin()), "transaction started");
            err(ex.execute(new CreateTable("USERS", "id", "name")), "table already exists: USERS");
            err(ex.execute(new CreateTable("t2", "x".repeat(33), "name")), "name too long: " + "x".repeat(33));
            err(ex.execute(new CreateTable("t3", "id", "ID")), "duplicate column name: ID");
            assertTrue(ex.inTransaction(), "still open after validation errors");
            ok(ex.execute(new CreateTable("t4", "id", "name")), "table t4 created");
            ok(ex.execute(new Commit()), "transaction committed");
            assertEquals(List.of("users", "t4"), column(ex.execute(new ShowTables()), 0), "tables");
        });

        MiniTest.test("failed mutation inside BEGIN rolls back the whole transaction and frees the lock", () -> {
            Path dir = MiniTest.tempDir("exec");
            Database db = Database.open(dir, "test", MUTATION_CACHE);
            QueryExecutor ex = new QueryExecutor(db, 500);
            try {
                setup(ex);
                fill(ex, 1, 3);
                ok(ex.execute(new Begin()), "transaction started");
                ok(ex.execute(new Insert("users", List.of(t(1000, "kept?")))), "1 row inserted");
                QueryResult failure = null;
                for (int i = 0; i < 5000 && failure == null; i++) {
                    QueryResult r = ex.execute(new Insert("users", List.of(t(2000 + i, "x".repeat(200)))));
                    if (r.isError()) {
                        failure = r;
                    }
                }
                assertTrue(failure != null, "a tiny buffer pool must eventually fail a mutation");
                assertTrue(failure.getMessage().endsWith("(transaction rolled back)"),
                        "message: " + failure.getMessage());
                assertFalse(ex.inTransaction(), "session left the transaction");
                assertFalse(db.isLockedByCurrentThread(), "lock released");
                assertFalse(db.isTransactionActive(), "database transaction rolled back");
                assertEquals(3, count(ex), "state equals the pre-BEGIN state");
                AtomicReference<String> second = new AtomicReference<>();
                Thread th = new Thread(() -> {
                    try (QueryExecutor other = new QueryExecutor(db, 200)) {
                        second.set(other.execute(new Begin()).render());
                    }
                });
                th.start();
                th.join();
                assertEquals("OK: transaction started", second.get(), "another session can BEGIN at once");
            } finally {
                ex.close();
                db.close();
                MiniTest.deleteRecursively(dir);
            }
        });

        MiniTest.finish();
    }
}
