package com.minidb.server;

import com.minidb.Database;
import com.minidb.DbException;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertThrows;
import static com.minidb.testkit.MiniTest.assertTrue;
import static com.minidb.testkit.MiniTest.deleteRecursively;
import static com.minidb.testkit.MiniTest.finish;
import static com.minidb.testkit.MiniTest.test;
import static com.minidb.testkit.MiniTest.tempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Tests for {@link DbServer} and {@link DbClient} over real TCP connections. */
public final class DbServerTest {

    private static final String HOST = InetAddress.getLoopbackAddress().getHostAddress();

    private DbServerTest() {
    }

    /** A test body that receives a running server. */
    private interface ServerBody {
        void run(DbServer server) throws Exception;
    }

    /** Runs the body against a fresh database and ephemeral-port server, then cleans up. */
    private static void withServer(ServerBody body) throws Exception {
        Path dir = tempDir("srv");
        Database db = Database.open(dir, "test");
        DbServer server = new DbServer(db, 0);
        try {
            server.start();
            body.run(server);
        } finally {
            server.stop();
            db.close();
            deleteRecursively(dir);
        }
    }

    private static DbClient connect(DbServer server) {
        return new DbClient(HOST, server.getPort());
    }

    private static void createUsers(DbServer server) {
        try (DbClient c = connect(server)) {
            assertEquals("OK: table users created", c.execute("CREATE TABLE users (id INT, name VARCHAR)"), "create");
        }
    }

    private static void awaitClients(DbServer server, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.getActiveClientCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, server.getActiveClientCount(), "active client count");
    }

    /** Raw protocol access for byte-level tests. */
    private static final class Raw implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final OutputStream out;

        Raw(DbServer server) throws IOException {
            socket = new Socket(HOST, server.getPort());
            socket.setSoTimeout(10_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void send(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Reads one response up to END, lines joined by newline. */
        String response() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (String l = in.readLine(); l != null; l = in.readLine()) {
                if (l.equals("END")) {
                    return sb.toString();
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(l);
            }
            throw new IOException("stream ended before END");
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Runs the server test suite.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        test("create, insert and select over TCP", () -> withServer(server -> {
            try (DbClient c = connect(server)) {
                assertEquals("OK: table users created", c.execute("CREATE TABLE users (id INT, name VARCHAR)"), "create");
                assertEquals("OK: 1 row inserted", c.execute("INSERT INTO users VALUES (1, 'Jewel')"), "insert");
                String r = c.execute("SELECT * FROM users WHERE id = 1");
                assertTrue(r.contains("| 1 "), "row id cell: " + r);
                assertTrue(r.contains("Jewel"), "row name: " + r);
                assertTrue(r.contains("(1 row)"), "row count: " + r);
            }
        }));

        test("blank line gives ERROR: empty statement", () -> withServer(server -> {
            try (DbClient c = connect(server)) {
                assertEquals("ERROR: empty statement", c.execute(""), "empty");
                assertEquals("ERROR: empty statement", c.execute("   "), "spaces");
            }
        }));

        test("QUIT answers OK: bye and closes the connection", () -> withServer(server -> {
            try (DbClient c = connect(server)) {
                assertEquals("OK: bye", c.execute("quit"), "bye");
                DbException e = assertThrows(DbException.class, () -> c.execute("SELECT * FROM users"), "closed");
                assertTrue(e.getMessage().contains("closed"), "message: " + e.getMessage());
            }
            try (DbClient c = connect(server)) {
                assertEquals("OK: bye", c.execute("EXIT;"), "exit with semicolon");
            }
            awaitClients(server, 0);
        }));

        test("malformed SQL yields an ERROR response and keeps the session usable", () -> withServer(server -> {
            createUsers(server);
            try (DbClient c = connect(server)) {
                assertTrue(c.execute("THIS IS NOT SQL").startsWith("ERROR"), "garbage");
                assertTrue(c.execute("SELECT FROM").startsWith("ERROR"), "bad select");
                assertEquals("OK: 1 row inserted", c.execute("INSERT INTO users VALUES (2, 'Ok')"), "still works");
            }
        }));

        test("CRLF line endings are tolerated", () -> withServer(server -> {
            createUsers(server);
            try (Raw raw = new Raw(server)) {
                raw.send("INSERT INTO users VALUES (3, 'Crlf')\r\n");
                assertEquals("OK: 1 row inserted", raw.response(), "crlf insert");
                raw.send("QUIT\r\n");
                assertEquals("OK: bye", raw.response(), "crlf quit");
            }
        }));

        test("DbClient.execute flattens embedded newlines", () -> withServer(server -> {
            createUsers(server);
            try (DbClient c = connect(server)) {
                c.execute("INSERT INTO users VALUES (4, 'Multi')");
                String r = c.execute("SELECT *\r\nFROM users\nWHERE id = 4");
                assertTrue(r.contains("Multi") && r.contains("(1 row)"), "multi-line sql: " + r);
            }
        }));

        test("10 concurrent clients x 50 statements all succeed", () -> withServer(server -> {
            createUsers(server);
            int clients = 10;
            int perClient = 50;
            List<Future<Integer>> futures = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int c = 0; c < clients; c++) {
                    final int index = c;
                    futures.add(pool.submit(() -> {
                        int ok = 0;
                        try (DbClient client = connect(server)) {
                            for (int i = 0; i < perClient; i++) {
                                int id = 1000 + index * perClient + i;
                                String r = client.execute("INSERT INTO users VALUES (" + id + ", 'u" + id + "')");
                                if (r.equals("OK: 1 row inserted")) {
                                    ok++;
                                }
                            }
                        }
                        return ok;
                    }));
                }
                for (Future<Integer> f : futures) {
                    assertEquals(perClient, f.get(60, TimeUnit.SECONDS), "successful inserts per client");
                }
            }
            try (DbClient c = connect(server)) {
                String r = c.execute("SELECT * FROM users WHERE id = 1499");
                assertTrue(r.contains("u1499") && r.contains("(1 row)"), "last row present: " + r);
            }
            awaitClients(server, 0);
        }));

        test("client disconnect inside BEGIN does not block others and rolls back", () -> withServer(server -> {
            createUsers(server);
            DbClient a = connect(server);
            assertEquals("OK: transaction started", a.execute("BEGIN"), "begin");
            assertEquals("OK: 1 row inserted", a.execute("INSERT INTO users VALUES (77, 'ghost')"), "insert");
            a.close();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> f = pool.submit(() -> {
                    try (DbClient b = connect(server)) {
                        return b.execute("SELECT * FROM users WHERE id = 77");
                    }
                });
                String r = f.get(5, TimeUnit.SECONDS);
                assertTrue(r.contains("(0 rows)"), "uncommitted row absent: " + r);
            }
            awaitClients(server, 0);
        }));

        test("active client count tracks connections", () -> withServer(server -> {
            assertEquals(0, server.getActiveClientCount(), "initially none");
            DbClient a = connect(server);
            DbClient b = connect(server);
            a.execute("");
            b.execute("");
            assertEquals(2, server.getActiveClientCount(), "two clients");
            a.close();
            b.close();
            awaitClients(server, 0);
        }));

        test("over-long line gets ERROR: statement too long and the connection closes", () -> withServer(server -> {
            try (Raw raw = new Raw(server)) {
                raw.send("a".repeat(DbServer.MAX_LINE_LENGTH + 10) + "\n");
                assertEquals("ERROR: statement too long", raw.response(), "too long");
                assertEquals(null, raw.in.readLine(), "connection closed");
            }
            try (DbClient c = connect(server)) {
                assertEquals("ERROR: empty statement", c.execute(""), "server still serves others");
            }
        }));

        test("stop releases awaitStop, disconnects clients and refuses new connections", () -> {
            Path dir = tempDir("srv");
            Database db = Database.open(dir, "test");
            DbServer server = new DbServer(db, 0);
            try {
                server.start();
                int port = server.getPort();
                assertTrue(port > 0, "ephemeral port assigned");
                DbClient c = connect(server);
                c.execute("");
                CountDownLatch released = new CountDownLatch(1);
                Thread waiter = Thread.ofVirtual().start(() -> {
                    try {
                        server.awaitStop();
                        released.countDown();
                    } catch (InterruptedException ignored) {
                        // test failure surfaces through the latch timeout
                    }
                });
                assertEquals(1L, released.getCount(), "awaitStop blocks while running");
                server.stop();
                assertTrue(released.await(5, TimeUnit.SECONDS), "awaitStop released");
                waiter.join();
                server.stop();
                server.close();
                assertEquals(0, server.getActiveClientCount(), "no clients after stop");
                assertThrows(DbException.class, () -> c.execute("SELECT * FROM users"), "client disconnected");
                assertThrows(DbException.class, () -> new DbClient(HOST, port), "new connections refused");
                c.close();
            } finally {
                server.stop();
                db.close();
                deleteRecursively(dir);
            }
        });

        test("start twice fails and a busy port is reported", () -> withServer(server -> {
            assertThrows(DbException.class, server::start, "second start");
            Path dir = tempDir("srv2");
            Database db2 = Database.open(dir, "test");
            try {
                DbServer other = new DbServer(db2, server.getPort());
                assertThrows(DbException.class, other::start, "port in use");
                other.stop();
            } finally {
                db2.close();
                deleteRecursively(dir);
            }
        }));

        test("client connect failure raises DbException", () -> withServer(server -> {
            int port = server.getPort();
            server.stop();
            assertThrows(DbException.class, () -> new DbClient(HOST, port), "connect after stop");
        }));

        finish();
    }
}
