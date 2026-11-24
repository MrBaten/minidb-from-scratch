package com.minidb.query;

import com.minidb.Database;
import com.minidb.DbException;
import com.minidb.table.Catalog;
import com.minidb.table.Row;
import com.minidb.table.Table;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One client session: parses and executes statements, handling autocommit, explicit
 * transactions and the global database lock (see PLAN.md §3.11).
 *
 * <p>Locking model: an explicit transaction ({@code BEGIN}) acquires the global lock and
 * keeps it until {@code COMMIT}, {@code ROLLBACK} or {@link #close()}. Outside a
 * transaction every statement takes the lock for its own duration. Because the lock is
 * owned by a thread, an instance must always be used from the same thread.</p>
 *
 * <p>Error policy: every data statement has a validation phase (table, columns, types,
 * lengths, duplicates; nothing is modified) and a mutation phase. A {@link DbException}
 * from validation returns an error result and leaves an explicit transaction open. Any
 * failure of the mutation phase, or any unexpected exception, rolls the whole explicit
 * transaction back, releases the lock and reports "(transaction rolled back)", so a
 * half-applied statement can never be committed. {@link Error}s trigger the same cleanup
 * and are then rethrown. In autocommit any failure rolls the statement's transaction back.</p>
 *
 * <p>Not thread-safe: one instance per connection.</p>
 */
public final class QueryExecutor implements AutoCloseable {

    /** Default time to wait for the database lock, in milliseconds. */
    public static final long DEFAULT_LOCK_TIMEOUT_MS = 30_000;

    private static final String LOCKED_MESSAGE = "database is locked";

    private final Database db;
    private final Duration lockTimeout;
    /** True while this session holds the lock for an explicit BEGIN..COMMIT block. */
    private boolean explicit;

    /**
     * Creates a session with the default lock timeout.
     *
     * @param db the database
     */
    public QueryExecutor(Database db) {
        this(db, DEFAULT_LOCK_TIMEOUT_MS);
    }

    /**
     * Creates a session.
     *
     * @param db              the database
     * @param lockTimeoutMs   how long to wait for the database lock
     */
    public QueryExecutor(Database db, long lockTimeoutMs) {
        this.db = Objects.requireNonNull(db, "db");
        this.lockTimeout = Duration.ofMillis(Math.max(0, lockTimeoutMs));
    }

    /**
     * Parses and executes one statement. Never throws for user errors.
     *
     * @param sql statement text
     * @return the result (possibly an error result)
     */
    public QueryResult execute(String sql) {
        Statement statement;
        try {
            statement = SqlParser.parse(sql);
        } catch (DbException e) {
            return QueryResult.error(e.getMessage());
        }
        return execute(statement);
    }

    /**
     * Executes one parsed statement. Never throws for user errors.
     *
     * @param statement the statement
     * @return the result (possibly an error result)
     */
    public QueryResult execute(Statement statement) {
        Objects.requireNonNull(statement, "statement");
        return switch (statement) {
            case Statement.Begin b -> begin();
            case Statement.Commit c -> endTransaction(true);
            case Statement.Rollback r -> endTransaction(false);
            case Statement.Select s -> run(statement, false);
            case Statement.ShowTables s -> run(statement, false);
            case Statement.CreateTable c -> run(statement, true);
            case Statement.Insert i -> run(statement, true);
            case Statement.Update u -> run(statement, true);
            case Statement.Delete d -> run(statement, true);
        };
    }

    /**
     * Tells whether this session has an explicit transaction open.
     *
     * @return {@code true} between a successful BEGIN and its COMMIT/ROLLBACK
     */
    public boolean inTransaction() {
        return explicit;
    }

    /**
     * Ends the session: an open explicit transaction is rolled back and the database lock
     * released. Must be called on the thread that issued BEGIN. Idempotent.
     */
    @Override
    public void close() {
        if (!explicit) {
            return;
        }
        try {
            rollbackQuietly();
        } finally {
            releaseExplicit();
        }
    }

    // ------------------------------------------------------------------ transaction control

    private QueryResult begin() {
        if (explicit) {
            return QueryResult.error("transaction already active");
        }
        try {
            if (!db.tryLock(lockTimeout)) {
                return QueryResult.error(LOCKED_MESSAGE);
            }
        } catch (DbException e) {
            return QueryResult.error(e.getMessage());
        }
        try {
            db.beginTransaction();
        } catch (RuntimeException e) {
            db.unlock();
            return QueryResult.error(e.getMessage());
        }
        explicit = true;
        return QueryResult.ok("transaction started");
    }

    private QueryResult endTransaction(boolean commit) {
        if (!explicit) {
            return QueryResult.error("no active transaction");
        }
        try {
            if (commit) {
                db.commitTransaction();
            } else {
                db.rollbackTransaction();
            }
            return QueryResult.ok(commit ? "transaction committed" : "transaction rolled back");
        } catch (RuntimeException e) {
            // A failed commit is rolled back by the database itself; make sure of it.
            rollbackQuietly();
            return QueryResult.error(e.getMessage());
        } finally {
            releaseExplicit();
        }
    }

    /** Leaves explicit mode and releases the lock held for it. */
    private void releaseExplicit() {
        explicit = false;
        db.unlock();
    }

    /** Rolls back the database transaction if one is active, ignoring any failure. */
    private void rollbackQuietly() {
        try {
            if (db.isTransactionActive()) {
                db.rollbackTransaction();
            }
        } catch (RuntimeException ignored) {
            // best effort: the caller is already reporting the primary failure
        }
    }

    // ------------------------------------------------------------------ statement dispatch

    /** The mutation phase of a statement: everything that touches pages or builds the result. */
    @FunctionalInterface
    private interface Mutation {
        QueryResult apply();
    }

    /** Runs a read or write statement under the proper transaction/locking regime. */
    private QueryResult run(Statement statement, boolean write) {
        return explicit ? runExplicit(statement) : runAutocommit(statement, write);
    }

    /**
     * Runs a statement inside this session's explicit transaction. A {@link DbException}
     * from validation leaves the transaction open; any failure of the mutation phase (or any
     * unexpected exception) rolls the whole transaction back, releases the lock and ends the
     * explicit block. {@link Error}s are rethrown after the same cleanup.
     */
    private QueryResult runExplicit(Statement statement) {
        Mutation mutation;
        try {
            mutation = prepare(statement);
        } catch (DbException e) {
            return QueryResult.error(e.getMessage());
        } catch (RuntimeException | Error e) {
            return abortExplicit(e);
        }
        try {
            return mutation.apply();
        } catch (RuntimeException | Error e) {
            return abortExplicit(e);
        }
    }

    /** Rolls back and ends the explicit transaction after a failure; rethrows Errors. */
    private QueryResult abortExplicit(Throwable failure) {
        try {
            rollbackQuietly();
        } finally {
            releaseExplicit();
        }
        if (failure instanceof Error error) {
            throw error;
        }
        String message = failure instanceof DbException
                ? failure.getMessage()
                : "internal error: " + describe(failure);
        return QueryResult.error(message + " (transaction rolled back)");
    }

    /** Runs a statement with the lock held for its duration only (autocommit). */
    private QueryResult runAutocommit(Statement statement, boolean write) {
        try {
            if (!db.tryLock(lockTimeout)) {
                return QueryResult.error(LOCKED_MESSAGE);
            }
        } catch (DbException e) {
            return QueryResult.error(e.getMessage());
        }
        try {
            Mutation mutation = prepare(statement);
            if (!write) {
                return mutation.apply();
            }
            boolean committed = false;
            db.beginTransaction();
            try {
                QueryResult result = mutation.apply();
                db.commitTransaction();
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    rollbackQuietly(); // also runs for Errors, which then propagate
                }
            }
        } catch (DbException e) {
            return QueryResult.error(e.getMessage());
        } catch (RuntimeException e) {
            return QueryResult.error("internal error: " + describe(e));
        } finally {
            db.unlock();
        }
    }

    /**
     * Validation phase: checks everything that can be checked without modifying data and
     * returns the mutation phase. Throws {@link DbException} for user errors.
     */
    private Mutation prepare(Statement statement) {
        return switch (statement) {
            case Statement.CreateTable c -> createTable(c);
            case Statement.Insert i -> insert(i);
            case Statement.Select s -> select(s);
            case Statement.Delete d -> delete(d);
            case Statement.Update u -> update(u);
            case Statement.ShowTables s -> showTables();
            case Statement.Begin b -> throw new IllegalStateException("not a data statement");
            case Statement.Commit c -> throw new IllegalStateException("not a data statement");
            case Statement.Rollback r -> throw new IllegalStateException("not a data statement");
        };
    }

    // ------------------------------------------------------------------ statements

    private Mutation createTable(Statement.CreateTable stmt) {
        if (db.catalog().hasTable(stmt.table())) {
            throw new DbException("table already exists: " + stmt.table());
        }
        for (String name : List.of(stmt.table(), stmt.keyColumn(), stmt.valueColumn())) {
            if (name.getBytes(StandardCharsets.UTF_8).length > Catalog.MAX_NAME_BYTES) {
                throw new DbException("name too long: " + name);
            }
        }
        if (stmt.keyColumn().equalsIgnoreCase(stmt.valueColumn())) {
            throw new DbException("duplicate column name: " + stmt.valueColumn());
        }
        return () -> {
            db.catalog().createTable(stmt.table(), stmt.keyColumn(), stmt.valueColumn());
            return QueryResult.ok("table " + stmt.table() + " created");
        };
    }

    private Mutation insert(Statement.Insert stmt) {
        Table table = db.catalog().getTable(stmt.table());
        // Validate every tuple before the first one is written.
        List<Row> rows = new ArrayList<>(stmt.tuples().size());
        Set<Integer> seen = new HashSet<>();
        for (Statement.Tuple tuple : stmt.tuples()) {
            long key = tuple.key();
            if (key < Integer.MIN_VALUE || key > Integer.MAX_VALUE) {
                throw new DbException("value out of range for INT: " + key);
            }
            Row row = new Row((int) key, tuple.value());
            if (!seen.add(row.id()) || table.find(row.id()).isPresent()) {
                throw new DbException("duplicate key: " + key);
            }
            rows.add(row);
        }
        return () -> {
            for (Row row : rows) {
                table.insert(row);
            }
            return QueryResult.ok(countMessage(rows.size(), "inserted"));
        };
    }

    private Mutation select(Statement.Select stmt) {
        Table table = db.catalog().getTable(stmt.table());
        List<String> headers = new ArrayList<>();
        List<Boolean> keyColumn = new ArrayList<>();
        if (stmt.columns().isEmpty()) {
            headers.add(table.getKeyColumn());
            keyColumn.add(true);
            headers.add(table.getValueColumn());
            keyColumn.add(false);
        } else {
            for (String name : stmt.columns()) {
                if (name.equalsIgnoreCase(table.getKeyColumn())) {
                    headers.add(table.getKeyColumn());
                    keyColumn.add(true);
                } else if (name.equalsIgnoreCase(table.getValueColumn())) {
                    headers.add(table.getValueColumn());
                    keyColumn.add(false);
                } else {
                    throw new DbException("no such column: " + name);
                }
            }
        }
        KeyRange range = analyse(table, stmt.where());
        return () -> {
            List<List<String>> out = new ArrayList<>();
            for (Row row : matchingRows(table, range)) {
                List<String> cells = new ArrayList<>(keyColumn.size());
                for (boolean isKey : keyColumn) {
                    cells.add(isKey ? String.valueOf(row.id()) : row.name());
                }
                out.add(cells);
            }
            return QueryResult.rows(headers, out);
        };
    }

    private Mutation delete(Statement.Delete stmt) {
        Table table = db.catalog().getTable(stmt.table());
        KeyRange range = analyse(table, stmt.where());
        return () -> {
            List<Row> matches = matchingRows(table, range);
            for (Row row : matches) {
                table.delete(row.id());
            }
            return QueryResult.ok(countMessage(matches.size(), "deleted"));
        };
    }

    private Mutation update(Statement.Update stmt) {
        Table table = db.catalog().getTable(stmt.table());
        if (stmt.column().equalsIgnoreCase(table.getKeyColumn())) {
            throw new DbException("cannot update key column " + stmt.column());
        }
        if (!stmt.column().equalsIgnoreCase(table.getValueColumn())) {
            throw new DbException("no such column: " + stmt.column());
        }
        new Row(0, stmt.value()); // validates the length before anything is touched
        KeyRange range = analyse(table, stmt.where());
        return () -> {
            List<Row> matches = matchingRows(table, range);
            for (Row row : matches) {
                table.update(row.id(), stmt.value());
            }
            return QueryResult.ok(countMessage(matches.size(), "updated"));
        };
    }

    private Mutation showTables() {
        return () -> {
            List<List<String>> rows = new ArrayList<>();
            for (String name : db.catalog().listTables()) {
                rows.add(List.of(name));
            }
            return QueryResult.rows(List.of("table"), rows);
        };
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String countMessage(int n, String verb) {
        return n + (n == 1 ? " row " : " rows ") + verb;
    }

    // ------------------------------------------------------------------ WHERE evaluation

    /**
     * The WHERE clause of a statement split into an index-friendly key range and a residual
     * filter on the value column.
     *
     * @param empty  {@code true} if the key conditions are contradictory (no row can match)
     * @param low    inclusive lower key bound, already clamped to the INT range
     * @param high   inclusive upper key bound, already clamped to the INT range
     * @param filter predicate for the value-column conditions (accepts everything if none)
     */
    private record KeyRange(boolean empty, int low, int high, Predicate<Row> filter) { }

    /**
     * Folds the AND-ed comparisons of a WHERE clause. Key-column comparisons narrow one
     * inclusive {@code long} interval (EQ sets both bounds, GT/GE raise the lower bound,
     * LT/LE lower the upper bound); the interval is then clamped to the INT range. Value
     * column comparisons become a predicate using {@link String#compareTo} semantics.
     */
    private static KeyRange analyse(Table table, List<Statement.Comparison> where) {
        long lo = Long.MIN_VALUE;
        long hi = Long.MAX_VALUE;
        boolean empty = false;
        Predicate<Row> filter = row -> true;
        for (Statement.Comparison cmp : where) {
            boolean onKey = cmp.column().equalsIgnoreCase(table.getKeyColumn());
            boolean onValue = cmp.column().equalsIgnoreCase(table.getValueColumn());
            if (!onKey && !onValue) {
                throw new DbException("no such column: " + cmp.column());
            }
            if (onKey) {
                if (!(cmp.value() instanceof Long v)) {
                    throw new DbException("type mismatch for column " + cmp.column());
                }
                switch (cmp.op()) {
                    case EQ -> {
                        lo = Math.max(lo, v);
                        hi = Math.min(hi, v);
                    }
                    case GE -> lo = Math.max(lo, v);
                    case LE -> hi = Math.min(hi, v);
                    case GT -> {
                        if (v == Long.MAX_VALUE) {
                            empty = true; // nothing is greater than Long.MAX_VALUE
                        } else {
                            lo = Math.max(lo, v + 1);
                        }
                    }
                    case LT -> {
                        if (v == Long.MIN_VALUE) {
                            empty = true; // nothing is smaller than Long.MIN_VALUE
                        } else {
                            hi = Math.min(hi, v - 1);
                        }
                    }
                }
            } else {
                if (!(cmp.value() instanceof String s)) {
                    throw new DbException("type mismatch for column " + cmp.column());
                }
                Predicate<Row> next = switch (cmp.op()) {
                    case EQ -> row -> row.name().compareTo(s) == 0;
                    case LT -> row -> row.name().compareTo(s) < 0;
                    case LE -> row -> row.name().compareTo(s) <= 0;
                    case GT -> row -> row.name().compareTo(s) > 0;
                    case GE -> row -> row.name().compareTo(s) >= 0;
                };
                filter = filter.and(next);
            }
        }
        if (empty || lo > hi || lo > Integer.MAX_VALUE || hi < Integer.MIN_VALUE) {
            return new KeyRange(true, 0, -1, filter);
        }
        return new KeyRange(false, (int) Math.max(lo, Integer.MIN_VALUE),
                (int) Math.min(hi, Integer.MAX_VALUE), filter);
    }

    /** Returns the rows (ascending by key) that satisfy the WHERE clause. */
    private static List<Row> matchingRows(Table table, KeyRange range) {
        if (range.empty()) {
            return List.of();
        }
        List<Row> candidates;
        if (range.low() == range.high()) {
            Optional<Row> hit = table.find(range.low());
            candidates = hit.map(List::of).orElse(List.of());
        } else {
            candidates = table.scan(range.low(), range.high());
        }
        List<Row> result = new ArrayList<>(candidates.size());
        for (Row row : candidates) {
            if (range.filter().test(row)) {
                result.add(row);
            }
        }
        return result;
    }
}
