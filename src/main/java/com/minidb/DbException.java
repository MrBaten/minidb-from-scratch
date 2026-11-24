package com.minidb;

/**
 * The single unchecked exception type used by every layer of the engine to report
 * database errors (I/O failures, constraint violations, syntax errors, missing tables, ...).
 *
 * <p>Its message is meant to be shown to the end user verbatim, e.g. as
 * {@code ERROR: <message>} on the wire.</p>
 */
public class DbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a user-facing message.
     *
     * @param message description of the problem
     */
    public DbException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a user-facing message and an underlying cause.
     *
     * @param message description of the problem
     * @param cause   the underlying exception (typically an {@link java.io.IOException})
     */
    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
