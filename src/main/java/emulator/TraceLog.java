package emulator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Append-only JSONL log of every impairment decision the emulator makes.
 *
 * <p>One line per decision, for example:
 *
 * <pre>
 * {"us":1043,"dir":"c2s","seq":17,"action":"drop","delayMs":0.0}
 * {"us":1102,"dir":"c2s","seq":18,"action":"pass","delayMs":21.4}
 * </pre>
 *
 * <p>This file is what makes Experiment 4 possible: because it records whether
 * each packet was really lost, a retransmission can be labelled
 * <em>spurious</em> (the original did arrive) or <em>necessary</em> after the
 * fact. Without ground truth from the channel itself, the best anyone can say
 * is that adaptive RTO "seems better".
 *
 * <p>It also means any strange run can be replayed packet by packet, which is
 * worth having in front of you at the viva.
 *
 * <p>Timestamps are microseconds since the log was opened, so a trace is
 * comparable across machines. Writes are buffered; nothing is flushed to disk
 * until {@link #close}, so tracing costs little in the hot path. Pass a null
 * path to disable tracing entirely.
 */
public final class TraceLog implements AutoCloseable {

    /**
     * Flush after this many records. Buffering keeps tracing cheap in the hot
     * path, but an emulator killed by the experiment harness between runs would
     * otherwise lose whatever was still in the buffer — and Experiment 4 needs
     * a complete trace to label retransmissions. Flushing periodically bounds
     * the loss to at most this many lines.
     */
    private static final int FLUSH_EVERY = 256;

    private final Writer out;
    private final long startNanos;
    private final StringBuilder sb = new StringBuilder(128);
    private long lines;
    private int sinceFlush;

    /** Opens a trace at {@code path}, or a no-op log if {@code path} is null. */
    public TraceLog(Path path) throws IOException {
        this.out = (path == null) ? null
                : Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        this.startNanos = System.nanoTime();
    }

    /** A trace that discards everything, for runs where tracing is off. */
    public static TraceLog disabled() {
        try {
            return new TraceLog(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // unreachable: null path never opens a file
        }
    }

    public boolean isEnabled() {
        return out != null;
    }

    /**
     * Records one decision.
     *
     * @param direction "c2s" or "s2c"
     * @param seq       sequence number peeked from the header, or -1 if unknown
     * @param action    drop, pass, dup, corrupt, reorder, or dup+corrupt
     * @param delayMs   the delay applied, zero for a drop
     */
    public void record(String direction, long seq, String action, double delayMs) {
        if (out == null) {
            return;
        }
        long us = (System.nanoTime() - startNanos) / 1000L;
        sb.setLength(0);
        sb.append("{\"us\":").append(us)
          .append(",\"dir\":\"").append(direction)
          .append("\",\"seq\":").append(seq)
          .append(",\"action\":\"").append(action)
          .append("\",\"delayMs\":").append(String.format("%.3f", delayMs))
          .append("}\n");
        try {
            out.write(sb.toString());
            lines++;
            if (++sinceFlush >= FLUSH_EVERY) {
                out.flush();
                sinceFlush = 0;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing trace", e);
        }
    }

    public long lines() {
        return lines;
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
        }
    }
}
