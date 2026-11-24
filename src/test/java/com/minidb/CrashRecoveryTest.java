package com.minidb;

import com.minidb.query.QueryExecutor;
import com.minidb.query.QueryResult;
import com.minidb.server.DbClient;
import com.minidb.testkit.MiniTest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertFalse;
import static com.minidb.testkit.MiniTest.assertTrue;

/**
 * Real crash tests: a server runs in a child JVM, is driven over TCP, and is killed with
 * SIGKILL ({@link Process#destroyForcibly()}) — no shutdown hook, no flush. The database is
 * then reopened in this JVM to verify hot-journal recovery and commit durability.
 */
public final class CrashRecoveryTest {

    private static final Pattern LISTENING = Pattern.compile("listening on port (\\d+)");

    private CrashRecoveryTest() {
    }

    /**
     * Runs the crash tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        System.out.println("CrashRecoveryTest");
        MiniTest.test("kill -9 during an open transaction: hot journal rolls back on restart", CrashRecoveryTest::crashMidTransaction);
        MiniTest.test("kill -9 right after COMMIT: committed rows are durable", CrashRecoveryTest::crashAfterCommit);
        MiniTest.finish();
    }

    /** A child-JVM server and the port it bound. */
    private record Child(Process process, int port) {
        void kill() throws InterruptedException {
            process.destroyForcibly();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child server must die");
        }
    }

    private static Child startServer(Path dataDir) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "com.minidb.Main", "server", "--data-dir", dataDir.toString(), "--db", "crash", "--port", "0");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        CompletableFuture<Integer> port = CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = out.readLine()) != null) {
                    Matcher m = LISTENING.matcher(line);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            } catch (IOException ignored) {
                // child died; reported below as a missing port
            }
            return -1;
        });
        int bound = port.get(30, TimeUnit.SECONDS);
        if (bound <= 0) {
            p.destroyForcibly();
            throw new AssertionError("child server did not start");
        }
        Thread drain = new Thread(() -> {
            try {
                while (out.readLine() != null) {
                    // keep the pipe empty so the child never blocks on stdout
                }
            } catch (IOException ignored) {
                // child exited
            }
        });
        drain.setDaemon(true);
        drain.start();
        return new Child(p, bound);
    }

    private static void ok(DbClient c, String sql) {
        String r = c.execute(sql);
        assertTrue(!r.startsWith("ERROR"), "unexpected error for [" + sql + "]: " + r);
    }

    private static void crashMidTransaction() throws Exception {
        Path dir = MiniTest.tempDir("crash-mid");
        try {
            Child child = startServer(dir);
            long sizeBefore;
            try (DbClient c = new DbClient("127.0.0.1", child.port())) {
                ok(c, "CREATE TABLE users (id INT, name VARCHAR)");
                for (int i = 1; i <= 200; i++) {
                    ok(c, "INSERT INTO users VALUES (" + i + ", 'committed_" + i + "')");
                }
                sizeBefore = Files.size(dir.resolve("crash.db"));
                ok(c, "BEGIN");
                for (int i = 10_000; i < 13_000; i++) {
                    ok(c, "INSERT INTO users VALUES (" + i + ", 'doomed_" + i + "')");
                }
                ok(c, "UPDATE users SET name = 'overwritten' WHERE id <= 200");
                ok(c, "DELETE FROM users WHERE id > 50 AND id <= 150");
                assertTrue(Files.exists(dir.resolve("crash.journal")), "journal must exist mid-transaction");
                assertTrue(Files.size(dir.resolve("crash.db")) > sizeBefore, "dirty pages were evicted to the db file");
                child.kill();
            }
            try (Database db = Database.open(dir, "crash"); QueryExecutor s = new QueryExecutor(db)) {
                assertTrue(db.wasRecovered(), "hot journal must be replayed");
                assertFalse(Files.exists(dir.resolve("crash.journal")), "journal removed after recovery");
                assertEquals(sizeBefore, Files.size(dir.resolve("crash.db")), "db truncated to committed size");
                QueryResult all = s.execute("SELECT * FROM users");
                assertEquals(200, all.getRowCount(), "exactly the committed rows");
                for (int i = 0; i < 200; i++) {
                    assertEquals(List.of(String.valueOf(i + 1), "committed_" + (i + 1)), all.getRows().get(i), "row " + (i + 1));
                }
                assertFalse(s.execute("INSERT INTO users VALUES (10000, 'again')").isError(), "writable after recovery");
            }
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }

    private static void crashAfterCommit() throws Exception {
        Path dir = MiniTest.tempDir("crash-commit");
        try {
            Child child = startServer(dir);
            try (DbClient c = new DbClient("127.0.0.1", child.port())) {
                ok(c, "CREATE TABLE t (id INT, name VARCHAR)");
                ok(c, "BEGIN");
                for (int i = 0; i < 1500; i++) {
                    ok(c, "INSERT INTO t VALUES (" + i + ", 'durable')");
                }
                assertEquals("OK: transaction committed", c.execute("COMMIT"), "commit acknowledged");
                ok(c, "INSERT INTO t VALUES (-1, 'autocommit')");
                child.kill();
            }
            try (Database db = Database.open(dir, "crash"); QueryExecutor s = new QueryExecutor(db)) {
                assertFalse(db.wasRecovered(), "nothing to recover after commits");
                assertEquals(1501, s.execute("SELECT * FROM t").getRowCount(), "all acknowledged rows survive");
            }
        } finally {
            MiniTest.deleteRecursively(dir);
        }
    }
}
