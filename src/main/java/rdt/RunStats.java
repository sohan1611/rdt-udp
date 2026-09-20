package rdt;
import java.util.Locale;
/**
 * data_sent counts every DATA packet sent in the timed window, including retransmissions.
 * retransmissions is the subset of those packets that were retransmitted.
 */
public final class RunStats {
    public final String protocol;
    public final int window;
    public final int seqbits;
    public final String rtoMode;
    public final long fileBytes;
    public long dataSent;
    public long retransmissions;
    public long timeouts;
    public long dupAcks;
    public long acksReceived;
    public long corruptDropped;
    public long wireBytes;
    public boolean shaMatch;
    private long startNanos;
    private long stopNanos;
    private boolean started;
    private boolean stopped;

    public RunStats(String protocol, int window, int seqbits, String rtoMode, long fileBytes) {
        this.protocol = protocol;
        this.window = window;
        this.seqbits = seqbits;
        this.rtoMode = rtoMode;
        this.fileBytes = fileBytes;
    }

    public void start() {
        startNanos = System.nanoTime();
        started = true;
    }

    public void stop() {
        stopNanos = System.nanoTime();
        stopped = true;
    }

    public void onDataSent(int bytes, boolean retransmission) {
        if (started && !stopped) {
            dataSent++;
            wireBytes += bytes;
            if (retransmission) {
                retransmissions++;
            }
        }
    }

    public void onAck(boolean duplicate) {
        acksReceived++;
        if (duplicate) {
            dupAcks++;
        }
    }

    public void onTimeout() {
        timeouts++;
    }

    public void onCorruptDropped() {
        corruptDropped++;
    }

    public void setShaMatch(boolean match) {
        shaMatch = match;
    }

    public double elapsedMs() {
        if (!started || !stopped) {
            return 0.0;
        }
        return (stopNanos - startNanos) / 1_000_000.0;
    }

    public double goodputBps() {
        double seconds = elapsedMs() / 1000.0;
        if (seconds <= 0) {
            return 0.0;
        }
        return fileBytes * 8.0 / seconds;
    }

    public double throughputBps() {
        double seconds = elapsedMs() / 1000.0;
        if (seconds <= 0) {
            return 0.0;
        }
        return wireBytes * 8.0 / seconds;
    }

    public String resultLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("RESULT {");
        sb.append("\"protocol\":\"").append(protocol).append("\",");
        sb.append("\"window\":").append(window).append(",");
        sb.append("\"seqbits\":").append(seqbits).append(",");
        sb.append("\"rto_mode\":\"").append(rtoMode).append("\",");
        sb.append("\"file_bytes\":").append(fileBytes).append(",");
        sb.append("\"elapsed_ms\":")
                .append(String.format(Locale.ROOT, "%.3f", elapsedMs())).append(",");
        sb.append("\"goodput_bps\":")
                .append(String.format(Locale.ROOT, "%.3f", goodputBps())).append(",");
        sb.append("\"wire_bytes\":").append(wireBytes).append(",");
        sb.append("\"throughput_bps\":")
                .append(String.format(Locale.ROOT, "%.3f", throughputBps())).append(",");
        sb.append("\"data_sent\":").append(dataSent).append(",");
        sb.append("\"retransmissions\":").append(retransmissions).append(",");
        sb.append("\"timeouts\":").append(timeouts).append(",");
        sb.append("\"dup_acks\":").append(dupAcks).append(",");
        sb.append("\"acks_received\":").append(acksReceived).append(",");
        sb.append("\"corrupt_dropped\":").append(corruptDropped).append(",");
        sb.append("\"sha256_match\":").append(shaMatch);
        sb.append("}");
        return sb.toString();
    }
}