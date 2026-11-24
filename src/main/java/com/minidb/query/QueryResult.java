package com.minidb.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of executing one statement: a status message, an error, or a result set.
 * {@link #render()} produces the text sent to clients.
 */
public final class QueryResult {

    private final boolean error;
    private final String message;
    private final List<String> columns;
    private final List<List<String>> rows;

    private QueryResult(boolean error, String message, List<String> columns, List<List<String>> rows) {
        this.error = error;
        this.message = message;
        this.columns = columns;
        this.rows = rows;
    }

    /**
     * Creates a success result without rows.
     *
     * @param message status text, rendered as {@code OK: message}
     * @return the result
     */
    public static QueryResult ok(String message) {
        return new QueryResult(false, Objects.requireNonNull(message), List.of(), List.of());
    }

    /**
     * Creates an error result.
     *
     * @param message error text, rendered as {@code ERROR: message}
     * @return the result
     */
    public static QueryResult error(String message) {
        return new QueryResult(true, message == null ? "unknown error" : message, List.of(), List.of());
    }

    /**
     * Creates a result set.
     *
     * @param columns column headers
     * @param rows    rows; each row must have one cell per column
     * @return the result
     * @throws IllegalArgumentException if a row has the wrong number of cells
     */
    public static QueryResult rows(List<String> columns, List<List<String>> rows) {
        List<List<String>> copy = new ArrayList<>(rows.size());
        for (List<String> r : rows) {
            if (r.size() != columns.size()) {
                throw new IllegalArgumentException("row has " + r.size() + " cells, expected " + columns.size());
            }
            copy.add(List.copyOf(r));
        }
        return new QueryResult(false, null, List.copyOf(columns), List.copyOf(copy));
    }

    /**
     * Tells whether this is an error result.
     *
     * @return {@code true} for errors
     */
    public boolean isError() {
        return error;
    }

    /**
     * Tells whether this result carries a result set.
     *
     * @return {@code true} for SELECT-style results
     */
    public boolean hasRows() {
        return message == null;
    }

    /**
     * Returns the status or error message.
     *
     * @return the message, or {@code null} for result sets
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the column headers.
     *
     * @return immutable list (empty for messages)
     */
    public List<String> getColumns() {
        return columns;
    }

    /**
     * Returns the rows.
     *
     * @return immutable list of immutable rows (empty for messages)
     */
    public List<List<String>> getRows() {
        return rows;
    }

    /**
     * Returns the number of rows in the result set.
     *
     * @return row count (0 for messages)
     */
    public int getRowCount() {
        return rows.size();
    }

    /**
     * Renders the result as text without a trailing newline: {@code OK: ...},
     * {@code ERROR: ...}, or an ASCII table followed by {@code (N rows)}.
     *
     * @return the rendered text
     */
    public String render() {
        if (error) {
            return "ERROR: " + message;
        }
        if (message != null) {
            return "OK: " + message;
        }
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
        }
        for (List<String> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                widths[i] = Math.max(widths[i], r.get(i).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        String border = border(widths);
        sb.append(border).append('\n');
        appendLine(sb, columns, widths);
        sb.append(border).append('\n');
        for (List<String> r : rows) {
            appendLine(sb, r, widths);
        }
        sb.append(border).append('\n');
        sb.append('(').append(rows.size()).append(rows.size() == 1 ? " row)" : " rows)");
        return sb.toString();
    }

    private static String border(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append('+');
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, List<String> cells, int[] widths) {
        sb.append('|');
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i);
            sb.append(' ').append(c).append(" ".repeat(widths[i] - c.length())).append(" |");
        }
        sb.append('\n');
    }

    /**
     * Same as {@link #render()}.
     *
     * @return the rendered text
     */
    @Override
    public String toString() {
        return render();
    }
}
