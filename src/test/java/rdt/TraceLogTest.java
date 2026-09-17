package rdt;

import static rdt.Harness.assertEquals;
import static rdt.Harness.assertTrue;

import emulator.TraceLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Tests for the emulator's JSONL decision trace.
 *
 * <p>The trace is ground truth for Experiment 4: it is what tells us whether a
 * retransmitted packet's original copy actually arrived. So the line format has
 * to be exactly right, it has to survive a run being killed, and it must not
 * depend on the machine that produced it.
 */
public final class TraceLogTest {

    public static void main(String[] args) throws Exception {
        Harness h = new Harness("TraceLogTest");

        h.check("a disabled log writes nothing and closes safely twice", () -> {
            TraceLog log = TraceLog.disabled();
            assertTrue("disabled log must report isEnabled() == false", !log.isEnabled());
            log.record("c2s", 7, "pass", 12.5);
            assertEquals("a disabled log counts no lines", 0, log.lines());
            log.close();
            log.close();
        });

        h.check("one record writes one line carrying dir and action", () -> {
            Path p = Files.createTempFile("tracelog", ".jsonl");
            try (TraceLog log = new TraceLog(p)) {
                assertTrue("an open log must report isEnabled() == true", log.isEnabled());
                log.record("c2s", 17, "drop", 0.0);
                assertEquals("line counter", 1, log.lines());
            }
            List<String> lines = Files.readAllLines(p);
            assertEquals("one record, one line", 1, lines.size());
            String line = lines.get(0);
            assertTrue("a line must be a JSON object: " + line,
                    line.startsWith("{") && line.endsWith("}"));
            assertTrue("line must name the direction: " + line, line.contains("\"dir\":\"c2s\""));
            assertTrue("line must name the action: " + line, line.contains("\"action\":\"drop\""));
            assertTrue("line must carry the sequence number: " + line, line.contains("\"seq\":17"));
            assertTrue("line must carry the timestamp: " + line, line.contains("\"us\":"));
            Files.deleteIfExists(p);
        });

        h.check("delay is written with three decimal places", () -> {
            Path p = Files.createTempFile("tracelog", ".jsonl");
            try (TraceLog log = new TraceLog(p)) {
                log.record("s2c", 3, "pass", 21.4);
            }
            String line = Files.readAllLines(p).get(0);
            assertTrue("expected delayMs 21.400 in: " + line, line.contains("\"delayMs\":21.400"));
            Files.deleteIfExists(p);
        });

        h.check("the number format does not depend on the machine locale", () -> {
            // A locale that formats decimals with a comma. Without an explicit
            // Locale.ROOT the delay is written as 21,400 and the line stops being
            // valid JSON — on that machine only, which is the worst kind of bug.
            Locale original = Locale.getDefault();
            Path p = Files.createTempFile("tracelog", ".jsonl");
            try {
                Locale.setDefault(Locale.GERMANY);
                try (TraceLog log = new TraceLog(p)) {
                    log.record("c2s", 5, "pass", 21.4);
                }
                String line = Files.readAllLines(p).get(0);
                assertTrue("delay must use a decimal point under any locale, got: " + line,
                        line.contains("\"delayMs\":21.400"));
                assertTrue("delay must not contain a decimal comma, got: " + line,
                        !line.contains("21,400"));
            } finally {
                Locale.setDefault(original);
                Files.deleteIfExists(p);
            }
        });

        h.check("every record produces exactly one line", () -> {
            Path p = Files.createTempFile("tracelog", ".jsonl");
            int n = 300;                       // more than FLUSH_EVERY, so a flush happens
            try (TraceLog log = new TraceLog(p)) {
                for (int i = 0; i < n; i++) {
                    log.record("c2s", i, "pass", i * 0.5);
                }
                assertEquals("line counter after " + n + " records", n, log.lines());
            }
            List<String> lines = Files.readAllLines(p);
            assertEquals("lines on disk", n, lines.size());
            for (String line : lines) {
                assertTrue("every line is a JSON object: " + line,
                        line.startsWith("{") && line.endsWith("}"));
            }
            Files.deleteIfExists(p);
        });

        h.check("an open log can be closed twice", () -> {
            Path p = Files.createTempFile("tracelog", ".jsonl");
            TraceLog log = new TraceLog(p);
            log.record("c2s", 1, "pass", 1.0);
            log.close();
            log.close();                       // NetEmSmokeTest does exactly this
            Files.deleteIfExists(p);
        });

        h.check("the six-argument form carries the type and the ack", () -> {
            Path p = Files.createTempFile("tracelog", ".jsonl");
            try (TraceLog log = new TraceLog(p)) {
                log.record("s2c", 1, 0, 17, "pass", 19.1);
            }
            String line = Files.readAllLines(p).get(0);
            assertTrue("line must name the packet type: " + line, line.contains("\"type\":1"));
            assertTrue("line must carry the ack number: " + line, line.contains("\"ack\":17"));
            Files.deleteIfExists(p);
        });

        h.check("a DATA line and an ACK line no longer look identical", () -> {
            // Packet.ack() leaves seq at zero, so before type and ack existed, an ACK
            // for packet 17 and DATA packet 0 wrote the same line. Experiment 4 labels
            // a retransmission spurious by asking whether the original arrived, and
            // that question is unanswerable if the two cannot be told apart.
            Path p = Files.createTempFile("tracelog", ".jsonl");
            try (TraceLog log = new TraceLog(p)) {
                log.record("c2s", 0, 0, 0, "pass", 1.0);     // DATA, packet 0
                log.record("c2s", 1, 0, 17, "pass", 1.0);    // ACK for packet 17
            }
            List<String> lines = Files.readAllLines(p);
            String data = lines.get(0).substring(lines.get(0).indexOf("\"dir\""));
            String ack = lines.get(1).substring(lines.get(1).indexOf("\"dir\""));
            assertTrue("DATA 0 and ACK 17 must not write the same line: " + data,
                    !data.equals(ack));
            Files.deleteIfExists(p);
        });

        h.check("the four-argument form marks the type unknown", () -> {
            // Channel still uses this one until the rewrite lands; -1 is not a real
            // packet type, so these lines are visibly "written before we knew".
            Path p = Files.createTempFile("tracelog", ".jsonl");
            try (TraceLog log = new TraceLog(p)) {
                log.record("c2s", 17, "drop", 0.0);
            }
            String line = Files.readAllLines(p).get(0);
            assertTrue("unknown type must be written as -1: " + line, line.contains("\"type\":-1"));
            assertTrue("unknown ack must be written as 0: " + line, line.contains("\"ack\":0"));
            Files.deleteIfExists(p);
        });

        h.done();
    }
}
