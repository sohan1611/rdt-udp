package emulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class TraceLog implements AutoCloseable {

    private static final int FLUSH_EVERY = 256;

    private final Writer out;
    private final long startNanos;
    private final StringBuilder sb = new StringBuilder(128);

    private long lines;
    private int sinceFlush;

    public TraceLog(Path path) throws IOException {
        if (path == null) {
            out = null;
        } else {
            out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }

        startNanos = System.nanoTime();
    }

    public static TraceLog disabled() {
        try {
            return new TraceLog(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isEnabled() {
        return out != null;
    }

    public void record(String direction, long seq, String action, double delayMs) {
        if (out == null) {
            return;
        }

        long us = (System.nanoTime() - startNanos) / 1000L;

        sb.setLength(0);

        sb.append("{\"us\":")
          .append(us)
          .append(",\"dir\":\"")
          .append(direction)
          .append("\",\"seq\":")
          .append(seq)
          .append(",\"action\":\"")
          .append(action)
          .append("\",\"delayMs\":")
          .append(String.format(Locale.ROOT,"%.3f", delayMs))
          .append("}\n");

        try {
            out.write(sb.toString());
            lines++;
            sinceFlush++;

            if (sinceFlush >= FLUSH_EVERY) {
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