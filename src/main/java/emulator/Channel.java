package emulator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class Channel {

    public static final int DRAWS_PER_PACKET = 10;

    private static final int R_LOSS = 0;
    private static final int R_DUP = 1;
    private static final int R_CORRUPT = 2;
    private static final int R_CORRUPT_POS = 3;
    private static final int R_JITTER = 4;
    private static final int R_REORDER = 5;
    private static final int R_DUP_CORRUPT = 6;
    private static final int R_DUP_CORRUPT_POS = 7;
    private static final int R_DUP_JITTER = 8;
    private static final int R_DUP_REORDER = 9;

    public static final class Delivery {
        public final byte[] data;
        public final long releaseNanos;
        public final boolean duplicate;
        public final boolean corrupted;
        public final boolean reordered;
        public final double delayMs;

        Delivery(byte[] data, long releaseNanos, boolean duplicate,
                 boolean corrupted, boolean reordered, double delayMs) {
            this.data = data;
            this.releaseNanos = releaseNanos;
            this.duplicate = duplicate;
            this.corrupted = corrupted;
            this.reordered = reordered;
            this.delayMs = delayMs;
        }
    }

    private final String direction;
    private final ChannelConfig cfg;
    private final Random rng;
    private final TraceLog trace;
    private final double[] draws = new double[DRAWS_PER_PACKET];

    // Counters, reported at shutdown and used by the statistical tests.
    public long offered;
    public long dropped;
    public long duplicated;
    public long corrupted;
    public long reordered;
    public long delivered;

    public Channel(String direction, ChannelConfig cfg, long seed, TraceLog trace) {
        this.direction = direction;
        this.cfg = cfg;
        this.rng = new Random(seed);
        this.trace = trace;
    }

    public List<Delivery> offer(byte[] data, int len, long nowNanos) {
        offered++;
        for (int i = 0; i < DRAWS_PER_PACKET; i++) {
            draws[i] = rng.nextDouble();
        }

        long seq = peekSeq(data, len);
        List<Delivery> out = new ArrayList<>(2);

        if (draws[R_LOSS] < cfg.lossProb) {
            dropped++;
            trace.record(direction, seq, "drop", 0.0);
            return out;
        }

        out.add(build(data, len, nowNanos, seq, false,
                draws[R_CORRUPT], draws[R_CORRUPT_POS], draws[R_JITTER], draws[R_REORDER]));

        if (draws[R_DUP] < cfg.dupProb) {
            duplicated++;
            out.add(build(data, len, nowNanos, seq, true,
                    draws[R_DUP_CORRUPT], draws[R_DUP_CORRUPT_POS],
                    draws[R_DUP_JITTER], draws[R_DUP_REORDER]));
        }
        return out;
    }

    private Delivery build(byte[] data, int len, long nowNanos, long seq, boolean isDup,
                           double rCorrupt, double rCorruptPos, double rJitter, double rReorder) {
        byte[] copy = new byte[len];
        System.arraycopy(data, 0, copy, 0, len);

        boolean didCorrupt = rCorrupt < cfg.corruptProb;
        if (didCorrupt) {
            flipOneBit(copy, rCorruptPos);
            corrupted++;
        }

        double delay = cfg.delayMs + (rJitter * 2.0 - 1.0) * cfg.jitterMs;
        if (delay < 0) {
            delay = 0;
        }

        boolean didReorder = rReorder < cfg.reorderProb;
        if (didReorder) {
            // Push this packet back far enough that the next one overtakes it.
            delay += cfg.reorderExtraMs;
            reordered++;
        }

        String action = isDup ? "dup" : (didCorrupt ? "corrupt" : (didReorder ? "reorder" : "pass"));
        if (isDup && didCorrupt) {
            action = "dup+corrupt";
        }
        trace.record(direction, seq, action, delay);
        delivered++;

        long release = nowNanos + (long) (delay * 1_000_000.0);
        return new Delivery(copy, release, isDup, didCorrupt, didReorder, delay);
    }

    private static void flipOneBit(byte[] b, double r) {
        if (b.length == 0) {
            return;
        }
        int totalBits = b.length * 8;
        int bit = (int) (r * totalBits);
        if (bit >= totalBits) {
            bit = totalBits - 1;      // guard against r == 1.0
        }
        b[bit >>> 3] ^= (byte) (1 << (bit & 7));
    }

    private static long peekSeq(byte[] b, int len) {
        if (len < 8) {
            return -1;
        }
        return ((long) (b[4] & 0xFF) << 24)
                | ((long) (b[5] & 0xFF) << 16)
                | ((long) (b[6] & 0xFF) << 8)
                | (b[7] & 0xFF);
    }

    public String summary() {
        return String.format(
                "%s: offered=%d delivered=%d dropped=%d (%.2f%%) dup=%d corrupt=%d reorder=%d",
                direction, offered, delivered, dropped,
                offered == 0 ? 0.0 : 100.0 * dropped / offered,
                duplicated, corrupted, reordered);
    }
}