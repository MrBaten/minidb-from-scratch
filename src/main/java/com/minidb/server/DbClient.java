package com.minidb.server;

import com.minidb.DbException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Line-protocol client and command-line tool: single statements, script files, timing,
 * a parallel load tester and an interactive REPL (see PLAN.md §3.13).
 *
 * <p>With {@code -e ... --time} the reported {@code time_ms} is the round-trip of the
 * statement after one untimed warm-up round-trip on the same connection.</p>
 */
public final class DbClient implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final long RETRY_INTERVAL_MS = 100;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final String USAGE = """
            usage: DbClient [--host H] [--port N] [--wait SECONDS] [MODE]
              MODE is one of:
                -e <sql> [--time]          execute one statement; --time prints the round-trip
                                           after one untimed warm-up round-trip
                --file <path|-> [--quiet]  execute one statement per line (blank and -- lines skipped)
                --bench <clients> <queries> [--bench-table T] [--bench-base N]
                                           concurrent insert/select load test
                (none)                     interactive prompt""";

    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;

    /**
     * Connects to a server.
     *
     * @param host server host
     * @param port server port
     * @throws com.minidb.DbException if the connection fails
     */
    public DbClient(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            this.in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            this.out = s.getOutputStream();
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw new DbException("cannot connect to " + host + ":" + port + ": " + e.getMessage(), e);
        }
        this.socket = s;
    }

    /**
     * Sends one statement and reads the response up to (excluding) the {@code END} line.
     *
     * @param sql one statement on a single line
     * @return the response text without trailing newline
     * @throws com.minidb.DbException on I/O error or if the server closed the connection
     */
    public String execute(String sql) {
        String line = sql.replace('\r', ' ').replace('\n', ' ');
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            StringBuilder response = new StringBuilder();
            boolean first = true;
            while (true) {
                String l = in.readLine();
                if (l == null) {
                    throw new DbException("connection closed by server");
                }
                if (l.equals(DbServer.END_MARKER)) {
                    return response.toString();
                }
                if (!first) {
                    response.append('\n');
                }
                response.append(l);
                first = false;
            }
        } catch (IOException e) {
            throw new DbException("connection error: " + e.getMessage(), e);
        }
    }

    /**
     * Closes the connection.
     */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to recover
        }
    }

    /**
     * Command-line entry point; see PLAN.md §3.13 for options.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int code;
        try {
            code = new Cli(args).run();
        } catch (UsageException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(USAGE);
            code = 2;
        } catch (DbException e) {
            System.err.println("error: " + e.getMessage());
            code = 1;
        }
        System.out.flush();
        System.exit(code);
    }

    /** Signals invalid command-line usage. */
    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** Parsed command line and the logic of each mode. */
    private static final class Cli {
        private String host = DEFAULT_HOST;
        private int port = DbServer.DEFAULT_PORT;
        private long waitSeconds;
        private String sql;
        private boolean time;
        private String file;
        private boolean quiet;
        private boolean bench;
        private int benchClients;
        private int benchQueries;
        private String benchTable = "users";
        private int benchBase = 1_000_000;

        Cli(String[] args) {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--host" -> host = value(args, ++i, a);
                    case "--port" -> port = intValue(value(args, ++i, a), a);
                    case "--wait" -> waitSeconds = intValue(value(args, ++i, a), a);
                    case "-e" -> sql = value(args, ++i, a);
                    case "--time" -> time = true;
                    case "--file" -> file = value(args, ++i, a);
                    case "--quiet" -> quiet = true;
                    case "--bench" -> {
                        bench = true;
                        benchClients = intValue(value(args, ++i, a), a);
                        benchQueries = intValue(value(args, ++i, a), a);
                    }
                    case "--bench-table" -> benchTable = value(args, ++i, a);
                    case "--bench-base" -> benchBase = intValue(value(args, ++i, a), a);
                    default -> throw new UsageException("unknown argument: " + a);
                }
            }
            int modes = (sql != null ? 1 : 0) + (file != null ? 1 : 0) + (bench ? 1 : 0);
            if (modes > 1) {
                throw new UsageException("-e, --file and --bench are mutually exclusive");
            }
            if (time && sql == null) {
                throw new UsageException("--time requires -e");
            }
            if (bench && (benchClients < 1 || benchQueries < 1)) {
                throw new UsageException("--bench needs positive <clients> and <queries>");
            }
            if (port < 1 || port > 65535 || waitSeconds < 0) {
                throw new UsageException("invalid --port or --wait");
            }
            if (!benchTable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new UsageException("invalid --bench-table: " + benchTable);
            }
        }

        private static String value(String[] args, int i, String flag) {
            if (i >= args.length) {
                throw new UsageException(flag + " needs a value");
            }
            return args[i];
        }

        private static int intValue(String s, String flag) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new UsageException(flag + " needs an integer, got: " + s);
            }
        }

        int run() {
            if (sql != null) {
                return runSingle();
            }
            if (file != null) {
                return runFile();
            }
            if (bench) {
                return runBench();
            }
            return runInteractive();
        }

        /** Connects, retrying every 100 ms until the {@code --wait} deadline passes. */
        private DbClient connect() {
            long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
            while (true) {
                try {
                    return new DbClient(host, port);
                } catch (DbException e) {
                    if (System.nanoTime() >= deadline) {
                        throw e;
                    }
                    sleep(RETRY_INTERVAL_MS);
                }
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DbException("interrupted");
            }
        }

        private int runSingle() {
            try (DbClient client = connect()) {
                if (time) {
                    client.execute("SHOW TABLES"); // untimed warm-up round-trip
                }
                long start = System.nanoTime();
                String response = client.execute(sql);
                double ms = (System.nanoTime() - start) / 1e6;
                System.out.println(response);
                if (time) {
                    System.out.printf(java.util.Locale.ROOT, "time_ms=%.3f%n", ms);
                }
                return response.startsWith("ERROR") ? 1 : 0;
            }
        }

        private int runFile() {
            List<String> lines = readScript();
            int executed = 0;
            int ok = 0;
            int errors = 0;
            try (DbClient client = connect()) {
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("--")) {
                        continue;
                    }
                    String response = client.execute(line);
                    executed++;
                    if (!quiet) {
                        System.out.println(response);
                    }
                    if (response.startsWith("ERROR")) {
                        errors++;
                    } else {
                        ok++;
                    }
                }
            } finally {
                System.out.println("executed=" + executed + " ok=" + ok + " errors=" + errors);
            }
            return errors > 0 ? 1 : 0;
        }

        private List<String> readScript() {
            try {
                if (file.equals("-")) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    List<String> all = new ArrayList<>();
                    for (String l = r.readLine(); l != null; l = r.readLine()) {
                        all.add(l);
                    }
                    return all;
                }
                return Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DbException("cannot read " + file + ": " + e.getMessage(), e);
            }
        }

        private int runBench() {
            AtomicInteger errors = new AtomicInteger();
            long start = System.nanoTime();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (int c = 0; c < benchClients; c++) {
                    final int clientIndex = c;
                    futures.add(pool.submit(() -> benchClient(clientIndex, errors)));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            }
            long elapsedMs = Math.max(1, (System.nanoTime() - start) / 1_000_000L);
            long total = (long) benchClients * benchQueries;
            long qps = total * 1000L / elapsedMs;
            System.out.println("clients=" + benchClients + " queries=" + total + " errors=" + errors.get()
                    + " elapsed_ms=" + elapsedMs + " qps=" + qps);
            return errors.get() > 0 ? 1 : 0;
        }

        /** Runs one bench client; on any failure the unexecuted statements count as errors. */
        private void benchClient(int clientIndex, AtomicInteger errors) {
            int done = 0;
            try (DbClient client = connect()) {
                for (int i = 0; i < benchQueries; i++) {
                    boolean insertTurn = i % 2 == 0;
                    // a SELECT verifies the row inserted by the statement just before it
                    long id = (long) benchBase + (long) clientIndex * benchQueries + (insertTurn ? i : i - 1);
                    String statement = insertTurn
                            ? "INSERT INTO " + benchTable + " VALUES (" + id + ", 'bench-" + id + "')"
                            : "SELECT * FROM " + benchTable + " WHERE id = " + id;
                    String response = client.execute(statement);
                    boolean good = insertTurn
                            ? !response.startsWith("ERROR")
                            : response.contains("(1 row)") && response.contains("bench-" + id);
                    if (!good) {
                        errors.incrementAndGet();
                    }
                    done++;
                }
            } catch (RuntimeException e) {
                errors.addAndGet(Math.max(1, benchQueries - done));
            }
        }

        private int runInteractive() {
            try (DbClient client = connect();
                 BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (true) {
                    System.out.print("minidb> ");
                    System.out.flush();
                    String line = stdin.readLine();
                    if (line == null) {
                        break;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    System.out.println(client.execute(line));
                    String t = line.trim();
                    if (t.endsWith(";")) {
                        t = t.substring(0, t.length() - 1).trim();
                    }
                    if (t.equalsIgnoreCase("quit") || t.equalsIgnoreCase("exit")) {
                        break;
                    }
                }
                return 0;
            } catch (IOException e) {
                throw new DbException("cannot read standard input: " + e.getMessage(), e);
            }
        }
    }
}
