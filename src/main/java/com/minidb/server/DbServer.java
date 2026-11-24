package com.minidb.server;

import com.minidb.Database;
import com.minidb.DbException;
import com.minidb.query.QueryExecutor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP server: one virtual thread and one {@link com.minidb.query.QueryExecutor} session per
 * client (wire protocol in PLAN.md §3.13).
 *
 * <p>A request is one UTF-8 line holding one statement; the response is the rendered
 * {@link com.minidb.query.QueryResult} followed by a line containing {@link #END_MARKER}.
 * Each session is created, used and closed on the client's own virtual thread, so the
 * database lock (owned per thread) is always released by the thread that took it.</p>
 */
public final class DbServer implements AutoCloseable {

    /** Default TCP port. */
    public static final int DEFAULT_PORT = 6969;

    /** Line that terminates every response. */
    public static final String END_MARKER = "END";

    /** Maximum accepted request line length in characters. */
    public static final int MAX_LINE_LENGTH = 64 * 1024;

    private static final long SHUTDOWN_WAIT_MS = 5_000;
    private static final int MAX_BYTES_PER_CHAR = 4;

    private final Database db;
    private final int requestedPort;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Set<Thread> clientThreads = ConcurrentHashMap.newKeySet();
    private final Thread.Builder clientThreadBuilder = Thread.ofVirtual().name("minidb-client-", 1);

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    /**
     * Creates a server (not yet listening).
     *
     * @param db   the database
     * @param port TCP port, or {@code 0} for an ephemeral port
     */
    public DbServer(Database db, int port) {
        this.db = Objects.requireNonNull(db, "db");
        this.requestedPort = port;
    }

    /**
     * Binds the port and starts accepting clients in the background.
     *
     * @throws com.minidb.DbException if the port cannot be bound or the server was already started
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new DbException("server already started");
        }
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(requestedPort));
        } catch (IOException e) {
            closeQuietly(ss);
            started.set(false);
            throw new DbException("cannot listen on port " + requestedPort + ": " + e.getMessage(), e);
        }
        serverSocket = ss;
        Thread t = new Thread(() -> acceptLoop(serverSocket), "minidb-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
    }

    /**
     * Returns the bound port (valid after {@link #start()}).
     *
     * @return the port
     */
    public int getPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? requestedPort : ss.getLocalPort();
    }

    /**
     * Returns the number of connected clients.
     *
     * @return active client count
     */
    public int getActiveClientCount() {
        return clients.size();
    }

    /**
     * Blocks until the server has been stopped.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitStop() throws InterruptedException {
        stopLatch.await();
    }

    /**
     * Stops accepting, disconnects all clients (their sessions roll back) and waits briefly
     * for client threads to finish. Idempotent.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            awaitQuietly();
            return;
        }
        try {
            closeQuietly(serverSocket);
            Thread acceptor = acceptThread;
            if (acceptor != null) {
                joinQuietly(acceptor, SHUTDOWN_WAIT_MS);
            }
            for (Socket s : clients) {
                closeQuietly(s);
            }
            long deadline = System.nanoTime() + SHUTDOWN_WAIT_MS * 1_000_000L;
            for (Thread t : clientThreads) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    break;
                }
                joinQuietly(t, remainingMs);
            }
        } finally {
            stopLatch.countDown();
        }
    }

    /**
     * Same as {@link #stop()}.
     */
    @Override
    public void close() {
        stop();
    }

    private void awaitQuietly() {
        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!stopped.get()) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (stopped.get() || ss.isClosed()) {
                    return;
                }
                continue;
            }
            try {
                socket.setTcpNoDelay(true);
            } catch (IOException ignored) {
                // a socket that rejects the option still works
            }
            clients.add(socket);
            Thread t = clientThreadBuilder.unstarted(() -> serveClient(socket));
            clientThreads.add(t);
            if (stopped.get()) {
                closeQuietly(socket);
            }
            t.start();
        }
    }

    private void serveClient(Socket socket) {
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = socket.getOutputStream();
            // Session is created, used and closed on this thread: the DB lock is thread-owned.
            QueryExecutor session = new QueryExecutor(db);
            try {
                converse(in, out, session);
            } finally {
                try {
                    session.close();
                } catch (RuntimeException e) {
                    // nothing more can be done for a failed session close
                }
            }
        } catch (IOException e) {
            // client went away or the server is stopping
        } catch (RuntimeException e) {
            // session creation failed; the connection is dropped
        } finally {
            clients.remove(socket);
            clientThreads.remove(Thread.currentThread());
        }
    }

    private void converse(InputStream in, OutputStream out, QueryExecutor session) throws IOException {
        while (true) {
            String line;
            try {
                line = readLine(in);
            } catch (LineTooLongException e) {
                respond(out, "ERROR: statement too long");
                return;
            }
            if (line == null) {
                return;
            }
            String sql = line.trim();
            if (sql.isEmpty()) {
                respond(out, "ERROR: empty statement");
                continue;
            }
            if (isQuit(sql)) {
                respond(out, "OK: bye");
                return;
            }
            respond(out, run(session, sql));
        }
    }

    private static String run(QueryExecutor session, String sql) {
        try {
            return session.execute(sql).render();
        } catch (RuntimeException | StackOverflowError e) {
            String msg = String.valueOf(e.getMessage()).replace('\r', ' ').replace('\n', ' ');
            return "ERROR: internal error: " + msg;
        }
    }

    private static boolean isQuit(String sql) {
        String s = sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
        return s.equalsIgnoreCase("quit") || s.equalsIgnoreCase("exit");
    }

    private static void respond(OutputStream out, String text) throws IOException {
        out.write((text + "\n" + END_MARKER + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Reads one line without ever buffering more than a bounded number of bytes.
     *
     * @return the line without terminator, or {@code null} at end of stream
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int maxBytes = MAX_LINE_LENGTH * MAX_BYTES_PER_CHAR;
        boolean any = false;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (!any) {
                    return null;
                }
                break;
            }
            any = true;
            if (b == '\n') {
                break;
            }
            if (buf.size() >= maxBytes) {
                throw new LineTooLongException();
            }
            buf.write(b);
        }
        String line = buf.toString(StandardCharsets.UTF_8);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.length() > MAX_LINE_LENGTH) {
            throw new LineTooLongException();
        }
        return line;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Signals a request line that exceeds {@link #MAX_LINE_LENGTH}. */
    private static final class LineTooLongException extends IOException {
        private static final long serialVersionUID = 1L;

        LineTooLongException() {
            super("statement too long");
        }
    }
}
