package com.minidb.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * A dependency-free micro test framework: register test cases with {@link #test}, then call
 * {@link #finish()} from {@code main}, which exits with status 1 if any test failed.
 */
public final class MiniTest {

    /** A test body that may throw anything. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        /**
         * Runs the test body.
         *
         * @throws Throwable on failure
         */
        void run() throws Throwable;
    }

    private static int passed;
    private static int failed;

    private MiniTest() {
    }

    /**
     * Runs one named test case and records the outcome.
     *
     * @param name test name
     * @param body test body
     */
    public static void test(String name, ThrowingRunnable body) {
        long start = System.nanoTime();
        try {
            body.run();
            passed++;
            System.out.printf("  PASS  %-60s %6.1f ms%n", name, (System.nanoTime() - start) / 1e6);
        } catch (Throwable t) {
            failed++;
            System.out.printf("  FAIL  %s%n        %s%n", name, t);
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("com.minidb")) {
                    System.out.println("          at " + e);
                }
            }
        }
    }

    /**
     * Prints a summary and exits with status 1 if any test failed (0 otherwise).
     */
    public static void finish() {
        System.out.println("  " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * Fails unless the condition holds.
     *
     * @param condition condition
     * @param message   failure message
     */
    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Fails if the condition holds.
     *
     * @param condition condition
     * @param message   failure message
     */
    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    /**
     * Fails unless {@code expected} equals {@code actual}.
     *
     * @param expected expected value
     * @param actual   actual value
     * @param message  failure message
     */
    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /**
     * Fails unless the body throws an exception of the given type.
     *
     * @param type    expected exception type
     * @param body    code that should throw
     * @param message failure message
     * @param <T>     exception type
     * @return the thrown exception
     */
    public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable body, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError(message + ": expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    /**
     * Creates a fresh temporary directory.
     *
     * @param prefix directory name prefix
     * @return the directory
     */
    public static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Recursively deletes a directory, ignoring errors.
     *
     * @param dir directory to delete
     */
    public static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of temporary test data
        }
    }
}
