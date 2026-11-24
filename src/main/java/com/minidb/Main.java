package com.minidb;

import com.minidb.cache.BufferPool;
import com.minidb.query.QueryExecutor;
import com.minidb.server.DbServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Command-line entry point.
 *
 * <pre>
 * java -cp target/classes com.minidb.Main [server|repl] [--data-dir DIR] [--db NAME]
 *                                          [--port N] [--cache-pages N]
 * </pre>
 *
 * <p>Every option can also come from an environment variable ({@code MINIDB_DATA_DIR},
 * {@code MINIDB_DB}, {@code MINIDB_PORT}, {@code MINIDB_CACHE_PAGES}); command-line flags
 * win. Defaults: data dir {@code ./data}, database {@code minidb}, port 6969, 100 pages.</p>
 */
public final class Main {

    private Main() {
    }

    /**
     * Parsed runtime configuration.
     *
     * @param mode       {@code server} or {@code repl}
     * @param dataDir    data directory
     * @param dbName     database name
     * @param port       TCP port
     * @param cachePages buffer pool capacity
     */
    record Config(String mode, Path dataDir, String dbName, int port, int cachePages) { }

    /**
     * Starts the TCP server (default) or an embedded REPL.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        Config config;
        try {
            config = parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage(System.err);
            System.exit(2);
            return;
        }
        if (config == null) {
            usage(System.out);
            return;
        }
        try {
            if ("repl".equals(config.mode())) {
                runRepl(config);
            } else {
                runServer(config);
            }
        } catch (DbException e) {
            System.err.println("fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Parses arguments and environment into a configuration.
     *
     * @param args command-line arguments
     * @param env  environment variables
     * @return the configuration, or {@code null} if help was requested
     * @throws IllegalArgumentException on invalid arguments
     */
    static Config parse(String[] args, Map<String, String> env) {
        String mode = "server";
        String dataDir = env.getOrDefault("MINIDB_DATA_DIR", "data");
        String db = env.getOrDefault("MINIDB_DB", "minidb");
        String port = env.getOrDefault("MINIDB_PORT", String.valueOf(DbServer.DEFAULT_PORT));
        String cache = env.getOrDefault("MINIDB_CACHE_PAGES", String.valueOf(BufferPool.DEFAULT_CAPACITY));
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "server", "repl" -> mode = a;
                case "--data-dir" -> dataDir = value(args, ++i, a);
                case "--db" -> db = value(args, ++i, a);
                case "--port" -> port = value(args, ++i, a);
                case "--cache-pages" -> cache = value(args, ++i, a);
                case "-h", "--help" -> {
                    return null;
                }
                default -> throw new IllegalArgumentException("unknown argument: " + a);
            }
        }
        int portNum = toInt(port, "port");
        if (portNum < 0 || portNum > 65535) {
            throw new IllegalArgumentException("port out of range: " + portNum);
        }
        int cachePages = toInt(cache, "cache-pages");
        if (cachePages < 8) {
            throw new IllegalArgumentException("cache-pages must be >= 8");
        }
        return new Config(mode, Path.of(dataDir), db, portNum, cachePages);
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }

    private static int toInt(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be an integer: " + s);
        }
    }

    private static void runServer(Config config) {
        Database db = Database.open(config.dataDir(), config.dbName(), config.cachePages());
        DbServer server = new DbServer(db, config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            db.close();
            System.out.println("minidb stopped");
        }, "minidb-shutdown"));
        server.start();
        System.out.println("minidb listening on port " + server.getPort()
                + " (data: " + db.getDbFile().toAbsolutePath() + (db.wasRecovered() ? ", recovered from journal" : "") + ")");
        try {
            server.awaitStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runRepl(Config config) {
        try (Database db = Database.open(config.dataDir(), config.dbName(), config.cachePages());
             QueryExecutor session = new QueryExecutor(db);
             BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("minidb embedded REPL (" + db.getDbFile().toAbsolutePath() + "). Type QUIT to exit.");
            while (true) {
                System.out.print("minidb> ");
                System.out.flush();
                String line = in.readLine();
                if (line == null) {
                    break;
                }
                String sql = line.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                if (sql.equalsIgnoreCase("quit") || sql.equalsIgnoreCase("exit")) {
                    break;
                }
                System.out.println(session.execute(sql).render());
            }
        } catch (IOException e) {
            throw new DbException("cannot read standard input: " + e.getMessage(), e);
        }
    }

    private static void usage(PrintStream out) {
        out.println("usage: Main [server|repl] [--data-dir DIR] [--db NAME] [--port N] [--cache-pages N]");
        out.println("  env: MINIDB_DATA_DIR, MINIDB_DB, MINIDB_PORT, MINIDB_CACHE_PAGES");
    }
}
