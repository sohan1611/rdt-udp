package emulator;

import java.util.ArrayList;
import java.util.Arrays;
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

    private final String direction;
    private final ChannelConfig cfg;
    private final Random rnd;
    private final TraceLog trace;

    public long offered;
    public long dropped;
    public long duplicated;
    public long corrupted;
    public long reordered;
    public long delivered;

    public static final class Delivery {

        public final byte[] data;
        public final long releaseNanos;
        public final boolean duplicate;
        public final boolean corrupted;
        public final boolean reordered;
        public final double delayMs;

        public Delivery(byte[] data,
                        long releaseNanos,
                        boolean duplicate,
                        boolean corrupted,
                        boolean reordered,
                        double delayMs) {

            this.data = data;
            this.releaseNanos = releaseNanos;
            this.duplicate = duplicate;
            this.corrupted = corrupted;
            this.reordered = reordered;
            this.delayMs = delayMs;
        }
    }

    public Channel(String direction,
                   ChannelConfig cfg,
                   long seed,
                   TraceLog trace) {

        this.direction = direction;
        this.cfg = cfg;
        this.rnd = new Random(seed);
        this.trace = trace;
    }

    /*
     * Always take all ten random draws, even if some are not used.
     * Otherwise an early drop would skip later draws such as jitter, shifting
     * the random stream so sweep cells with the same seed differ in more than
     * just the variable being tested.
     */
    private double[] drawTen() {

        double[] draws = new double[DRAWS_PER_PACKET];

        for (int i = 0; i < DRAWS_PER_PACKET; i++) {
            draws[i] = rnd.nextDouble();
        }

        return draws;
    }

    public List<Delivery> offer(byte[] data, int len, long nowNanos) {

        offered++;

        double[] draws = drawTen();

        int type = peekType(data, len);
        long seq = peekSeq(data, len);
        long ack = peekAck(data, len);

        List<Delivery> out = new ArrayList<>(2);

        if (draws[R_LOSS] < cfg.lossProb) {

            dropped++;

            trace.record(
                    direction,
                    type,
                    seq,
                    ack,
                    "drop",
                    0.0
            );

            return out;
        }

        Delivery original = buildDelivery(
                data,
                len,
                nowNanos,
                type,
                seq,
                ack,
                false,
                draws[R_CORRUPT],
                draws[R_CORRUPT_POS],
                draws[R_JITTER],
                draws[R_REORDER]
        );

        out.add(original);

        if (draws[R_DUP] < cfg.dupProb) {

            duplicated++;

            Delivery duplicate = buildDelivery(
                    data,
                    len,
                    nowNanos,
                    type,
                    seq,
                    ack,
                    true,
                    draws[R_DUP_CORRUPT],
                    draws[R_DUP_CORRUPT_POS],
                    draws[R_DUP_JITTER],
                    draws[R_DUP_REORDER]
            );

            out.add(duplicate);
        }

        return out;
    }

    private Delivery buildDelivery(byte[] data,
                                   int len,
                                   long nowNanos,
                                   int type,
                                   long seq,
                                   long ack,
                                   boolean isDuplicate,
                                   double corruptDraw,
                                   double corruptPositionDraw,
                                   double jitterDraw,
                                   double reorderDraw) {

        byte[] copy = Arrays.copyOf(data, len);

        boolean didCorrupt =
                corruptDraw < cfg.corruptProb;

        if (didCorrupt) {
            flipOneBit(copy, corruptPositionDraw);
            corrupted++;
        }

        double jitter =
                (jitterDraw * 2.0 - 1.0) * cfg.jitterMs;

        double delayMs =
                cfg.delayMs + jitter;

        if (delayMs < 0.0) {
            delayMs = 0.0;
        }

        boolean didReorder =
                reorderDraw < cfg.reorderProb;

        if (didReorder) {
            delayMs += cfg.reorderExtraMs;
            reordered++;
        }

        /*
         * Trace action vocabulary:
         * pass, dup, corrupt, reorder, combinations joined with '+', and drop.
         */
        String action = "";

        if (isDuplicate) {
            action = "dup";
        }

        if (didCorrupt) {
            if (!action.isEmpty()) {
                action += "+";
            }
            action += "corrupt";
        }

        if (didReorder) {
            if (!action.isEmpty()) {
                action += "+";
            }
            action += "reorder";
        }

        if (action.isEmpty()) {
            action = "pass";
        }

        trace.record(
                direction,
                type,
                seq,
                ack,
                action,
                delayMs
        );

        // Counts datagrams released, so duplicates can make delivered exceed offered - dropped.
        delivered++;

        long releaseNanos =
                nowNanos + (long) (delayMs * 1_000_000.0);

        return new Delivery(
                copy,
                releaseNanos,
                isDuplicate,
                didCorrupt,
                didReorder,
                delayMs
        );
    }

    private static void flipOneBit(byte[] data, double positionDraw) {

        if (data.length == 0) {
            return;
        }

        int totalBits = data.length * 8;

        int bitIndex =
                (int) (positionDraw * totalBits);

        if (bitIndex >= totalBits) {
            bitIndex = totalBits - 1;
        }

        int byteIndex =
                bitIndex >>> 3;

        int bitInsideByte =
                bitIndex & 7;

        data[byteIndex] ^=
                (byte) (1 << bitInsideByte);
    }

    private static int peekType(byte[] data, int len) {

        if (len < 2) {
            return -1;
        }

        return data[1] & 0xFF;
    }

    private static long peekSeq(byte[] data, int len) {

        if (len < 8) {
            return -1;
        }

        return ((long) (data[4] & 0xFF) << 24)
                | ((long) (data[5] & 0xFF) << 16)
                | ((long) (data[6] & 0xFF) << 8)
                | (long) (data[7] & 0xFF);
    }

    private static long peekAck(byte[] data, int len) {

        if (len < 12) {
            return -1;
        }

        return ((long) (data[8] & 0xFF) << 24)
                | ((long) (data[9] & 0xFF) << 16)
                | ((long) (data[10] & 0xFF) << 8)
                | (long) (data[11] & 0xFF);
    }

    public String summary() {

        double lossPercent =
                offered == 0
                        ? 0.0
                        : 100.0 * dropped / offered;

        return String.format(
                "%s: offered=%d delivered=%d dropped=%d (%.2f%%) dup=%d corrupt=%d reorder=%d",
                direction,
                offered,
                delivered,
                dropped,
                lossPercent,
                duplicated,
                corrupted,
                reordered
        );
    }
}