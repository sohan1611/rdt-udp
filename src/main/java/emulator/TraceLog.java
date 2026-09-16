package emulator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TraceLog implements AutoCloseable {

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