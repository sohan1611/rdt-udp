package emulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The impairment model for one direction of the emulated link.
 *
 * <p>Given a datagram, {@link #offer} returns zero, one, or two scheduled
 * deliveries, each with the time it should be released. The caller is
 * responsible for actually holding and releasing them; this class makes only
 * the decisions, which keeps it directly unit-testable without any sockets.
 *
 * <h2>Determinism</h2>
 *
 * <p>The brief requires runs to be reproducible from a seed, so two things are
 * fixed here.
 *
 * <p>First, the generator is {@link java.util.Random}, whose algorithm is
 * specified exactly by the Java platform documentation. The same seed therefore
 * produces the same sequence on any JVM, any version, any operating system.
 * {@code ThreadLocalRandom} and {@code SecureRandom} give no such guarantee and
 * must not be used.
 *
 * <p>Second, every packet consumes exactly {@link #DRAWS_PER_PACKET} values
 * from the generator, whether or not each one is needed. That costs a few
 * nanoseconds and buys a real experimental property: changing the loss
 * probability does not shift the delay sequence, so two cells of a sweep that
 * share a seed see the same jitter pattern and differ only in the variable
 * under test. This is the common-random-numbers technique, and it lowers the
 * variance between neighbouring points on a curve.
 */
public final class Channel {

    /** Fixed number of random draws consumed per packet. See the class notes. */
    public static final int DRAWS_PER_PACKET = 10;

    // Indices into the per-packet draw block. Named so the ordering is
    // obvious and stays stable if someone adds a new impairment.
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

    /** One scheduled delivery: bytes to send, and when to send them. */
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

    /**
     * Decides what happens to one datagram.
     *
     * @param data      the datagram; the array is copied, never retained
     * @param len       valid bytes in {@code data}
     * @param nowNanos  current time, from {@link System#nanoTime()}
     * @return the deliveries to schedule, possibly empty
     */
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

        // Uniform jitter across [-jitterMs, +jitterMs], clamped so latency
        // never goes negative.
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

    /**
     * Flips a single bit at a position derived from {@code r}. A one-bit error
     * is always caught by the Internet checksum, so a corrupted packet should
     * never reach the application; if one does, the receiver has a bug.
     */
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

    /**
     * Reads the sequence number straight out of the header for logging.
     *
     * <p>The emulator deliberately does <em>not</em> validate the packet: a
     * middlebox has no business rejecting traffic, and validating here would
     * hide receiver bugs. It only peeks far enough to write a useful trace.
     * Returns -1 for anything too short to have a header.
     */
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
