package rdt;

import static rdt.Harness.assertEquals;
import static rdt.Harness.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests for RunStats and the RESULT line.
 *
 * <p>run_matrix.py reads nothing from a run except the RESULT line, so every
 * figure in the report passes through this class. The line must be exactly one
 * JSON object with the sixteen keys CONVENTIONS.md section 10 lists, in that
 * order, and it must read the same on any machine.
 */
public final class RunStatsTest {

    private static final String[] KEYS = {
        "protocol", "window", "seqbits", "rto_mode", "file_bytes", "elapsed_ms",
        "goodput_bps", "wire_bytes", "throughput_bps", "data_sent", "retransmissions",
        "timeouts", "dup_acks", "acks_received", "corrupt_dropped", "sha256_match"
    };

    public static void main(String[] args) throws Exception {
        Harness h = new Harness("RunStatsTest");

        h.check("the RESULT line is one line with the prefix and one JSON object", () -> {
            String line = finished().resultLine();
            assertTrue("must start with 'RESULT {': " + line, line.startsWith("RESULT {"));
            assertTrue("must end with '}': " + line, line.endsWith("}"));
            assertTrue("must be a single line: " + line, !line.contains("\n"));
        });

        h.check("the sixteen keys appear exactly once each, in CONVENTIONS order", () -> {
            List<String> found = new ArrayList<>();
            Matcher m = Pattern.compile("\"([a-z0-9_]+)\":").matcher(finished().resultLine());
            while (m.find()) {
                found.add(m.group(1));
            }
            assertEquals("key list", String.join(",", KEYS), String.join(",", found));
        });

        h.check("text values are quoted and the match flag is a bare boolean", () -> {
            String line = finished().resultLine();
            assertTrue("protocol quoted: " + line, line.contains("\"protocol\":\"gbn\""));
            assertTrue("rto_mode quoted: " + line, line.contains("\"rto_mode\":\"fixed:1.5\""));
            assertTrue("sha256_match bare: " + line, line.contains("\"sha256_match\":true}"));
        });

        h.check("goodput is file bits over the reported elapsed seconds", () -> {
            String line = finished().resultLine();
            double elapsedMs = number(line, "elapsed_ms");
            double expected = 8_388_608L * 8.0 / (elapsedMs / 1000.0);
            double got = number(line, "goodput_bps");
            assertTrue("goodput " + got + " vs " + expected, Math.abs(got - expected) / expected < 1e-3);
        });

        h.check("throughput uses wire bytes, not file bytes", () -> {
            String line = finished().resultLine();
            double elapsedMs = number(line, "elapsed_ms");
            double wire = number(line, "wire_bytes");
            double expected = wire * 8.0 / (elapsedMs / 1000.0);
            double got = number(line, "throughput_bps");
            assertTrue("throughput " + got + " vs " + expected, Math.abs(got - expected) / expected < 1e-3);
        });

        h.check("wire bytes only count between start and stop", () -> {
            RunStats s = new RunStats("stopwait", 1, 32, "adaptive", 1000);
            s.onDataSent(500, false);          // before start(): the handshake
            s.start();
            s.onDataSent(1420, false);
            s.onDataSent(1420, true);
            s.stop();
            s.onDataSent(500, false);          // after stop(): the FIN
            assertEquals("wire bytes inside the timed window", 2840, s.wireBytes);
        });

        h.check("data_sent and retransmissions cover the same window as wire_bytes", () -> {
            // The handshake before start() and the FIN after stop() are excluded
            // from the elapsed time, so they must be excluded from the packet
            // counts too, or data_sent and wire_bytes describe different packets.
            RunStats s = new RunStats("stopwait", 1, 32, "adaptive", 1000);
            s.onDataSent(500, false);          // handshake
            s.start();
            s.onDataSent(1420, false);
            s.onDataSent(1420, true);
            s.stop();
            s.onDataSent(500, true);           // FIN, even if retransmitted
            assertEquals("data_sent counts only the timed window", 2, s.dataSent);
            assertEquals("retransmissions counts only the timed window", 1, s.retransmissions);
        });

        h.check("retransmissions and duplicate ACKs are subsets of their totals", () -> {
            RunStats s = new RunStats("gbn", 8, 32, "adaptive", 1000);
            s.start();
            for (int i = 0; i < 5; i++) {
                s.onDataSent(100, i >= 3);     // two of the five are retransmissions
            }
            for (int i = 0; i < 4; i++) {
                s.onAck(i == 0);               // one of the four is a duplicate
            }
            s.stop();
            assertEquals("retransmissions", 2, s.retransmissions);
            assertEquals("acks received", 4, s.acksReceived);
            assertEquals("duplicate acks", 1, s.dupAcks);
        });

        h.check("a second start() does not reset the clock", () -> {
            // Stop-and-Wait once called start() on every packet with seq 1, so each
            // call restarted the clock and a 23 s transfer reported 93 ms. The first
            // call must win.
            RunStats s = new RunStats("stopwait", 1, 32, "adaptive", 1000);
            s.start();
            Thread.sleep(40);
            s.start();                         // must be ignored
            s.stop();
            assertTrue("elapsed must span both calls, got " + s.elapsedMs() + " ms",
                    s.elapsedMs() >= 35.0);
        });

        h.check("a run that never started reports zero elapsed and zero goodput", () -> {
            String line = new RunStats("sr", 16, 32, "adaptive", 1000).resultLine();
            assertTrue("elapsed zero: " + line, number(line, "elapsed_ms") == 0.0);
            assertTrue("goodput zero: " + line, number(line, "goodput_bps") == 0.0);
        });

        h.check("a transfer that never finished does not report negative time", () -> {
            // A transfer that aborts still prints its RESULT line, with sha256_match
            // false. If stop() was never called, elapsed_ms must not come out as
            // (0 - startNanos), which is a huge negative number that run_matrix.py
            // would happily average into a figure.
            RunStats s = new RunStats("gbn", 32, 32, "adaptive", 1000);
            s.start();
            s.onDataSent(1420, false);
            String line = s.resultLine();
            assertTrue("elapsed_ms must not be negative: " + line, number(line, "elapsed_ms") >= 0.0);
        });

        h.check("the number format does not depend on the machine locale", () -> {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                String line = finished().resultLine();
                Matcher m = Pattern.compile("\"elapsed_ms\":([^,]+),").matcher(line);
                assertTrue("elapsed_ms present: " + line, m.find());
                assertTrue("decimal point, not comma: " + line, m.group(1).contains("."));
                number(line, "goodput_bps");   // throws if the value is not a plain number
            } finally {
                Locale.setDefault(original);
            }
        });

        h.done();
    }

    /** A finished GBN run: 8 MiB, a few sends and ACKs, stopped after a short wait. */
    private static RunStats finished() throws InterruptedException {
        RunStats s = new RunStats("gbn", 32, 32, "fixed:1.5", 8_388_608L);
        s.start();
        for (int i = 0; i < 10; i++) {
            s.onDataSent(1420, i == 9);
            s.onAck(false);
        }
        Thread.sleep(15);                      // a measurable elapsed time
        s.stop();
        s.setShaMatch(true);
        return s;
    }

    /** Pulls one numeric value out of the RESULT line. */
    private static double number(String line, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9]+(?:[.][0-9]+)?)[,}]").matcher(line);
        if (!m.find()) {
            throw new AssertionError(key + " is not a plain number in: " + line);
        }
        return Double.parseDouble(m.group(1));
    }
}
