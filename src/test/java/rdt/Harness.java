package rdt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A minimal test harness, so the project has zero third-party dependencies.
 *
 * <p>Deliberately tiny: about eighty lines you can read in full and explain in
 * a viva. If the group later adds Gradle, these tests port to JUnit 5 by
 * replacing {@code check(name, body)} with {@code @Test} and the assertion
 * helpers with the JUnit ones, and nothing else changes.
 */
public final class Harness {

    private final String suite;
    private final List<String> failures = new ArrayList<>();
    private int run;

    public Harness(String suite) {
        this.suite = suite;
    }

    /** Runs one named test, catching any failure so the rest still run. */
    public void check(String name, ThrowingRunnable body) {
        run++;
        try {
            body.run();
            System.out.printf("  ok   %s%n", name);
        } catch (Throwable t) {
            failures.add(name + ": " + t);
            System.out.printf("  FAIL %s%n         %s%n", name, t);
        }
    }

    /** Prints the summary and exits non-zero if anything failed. */
    public void done() {
        System.out.printf("%n%s: %d run, %d failed%n", suite, run, failures.size());
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---- assertions ----

    public static void assertTrue(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError(what);
        }
    }

    public static void assertEquals(String what, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + " but got " + actual);
        }
    }

    public static void assertEquals(String what, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ":\n  expected " + abbreviate(expected)
                    + "\n  but got  " + abbreviate(actual));
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }

    public static void assertEquals(String what, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(what + ": arrays differ"
                    + " (expected " + expected.length + " bytes, got " + actual.length + ")");
        }
    }

    /** Asserts that {@code body} throws {@code expected}, and returns the exception. */
    public static <T extends Throwable> T assertThrows(
            String what, Class<T> expected, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                return expected.cast(t);
            }
            throw new AssertionError(what + ": expected " + expected.getSimpleName()
                    + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + expected.getSimpleName()
                + " but nothing was thrown");
    }
}
