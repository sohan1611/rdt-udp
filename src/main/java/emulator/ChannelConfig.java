package emulator;

/**
 * Impairment settings for one direction of the emulated channel.
 *
 * <p>Probabilities are per packet, in the range 0.0 to 1.0, and are evaluated
 * in a fixed order so that a given seed always produces the same decisions:
 * drop, then duplicate, then corrupt, then delay. Reordering is not a separate
 * draw in that sequence — it emerges from jitter, and from the explicit
 * {@link #reorderProb} which gives a packet an extra delay large enough to fall
 * behind the one after it.
 */
public final class ChannelConfig {

    /** Probability a packet is dropped outright. */
    public double lossProb = 0.0;

    /** Probability a packet is delivered twice. */
    public double dupProb = 0.0;

    /** Probability a single random bit is flipped in the payload. */
    public double corruptProb = 0.0;

    /** Probability a packet is held back far enough to swap with its successor. */
    public double reorderProb = 0.0;

    /** Base one-way delay in milliseconds. */
    public double delayMs = 0.0;

    /** Uniform jitter, +/- this many milliseconds around {@link #delayMs}. */
    public double jitterMs = 0.0;

    /**
     * Extra delay added to a packet chosen for reordering, in milliseconds.
     *
     * <p>Reordering is produced by holding a packet back until the one behind
     * it overtakes, so this has to exceed the typical gap between consecutive
     * packets or nothing actually swaps. The default of 20 ms is comfortably
     * larger than the inter-packet gap at the rates we test.
     */
    public double reorderExtraMs = 20.0;

    public ChannelConfig() {
    }

    public ChannelConfig(double lossProb, double dupProb, double corruptProb,
                         double reorderProb, double delayMs, double jitterMs) {
        this.lossProb = lossProb;
        this.dupProb = dupProb;
        this.corruptProb = corruptProb;
        this.reorderProb = reorderProb;
        this.delayMs = delayMs;
        this.jitterMs = jitterMs;
        validate();
    }

    /** A perfect channel: no impairment, no delay. */
    public static ChannelConfig perfect() {
        return new ChannelConfig();
    }

    public void validate() {
        prob("lossProb", lossProb);
        prob("dupProb", dupProb);
        prob("corruptProb", corruptProb);
        prob("reorderProb", reorderProb);
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must not be negative: " + delayMs);
        }
        if (jitterMs < 0) {
            throw new IllegalArgumentException("jitterMs must not be negative: " + jitterMs);
        }
        if (reorderExtraMs < 0) {
            throw new IllegalArgumentException(
                    "reorderExtraMs must not be negative: " + reorderExtraMs);
        }
        if (jitterMs > delayMs && delayMs > 0) {
            // Jitter wider than the base delay would mean negative latency for
            // some packets. We clamp at zero, but warn because it usually means
            // the config is wrong.
            System.err.printf("warning: jitterMs (%.1f) exceeds delayMs (%.1f); "
                    + "delays will be clamped at zero%n", jitterMs, delayMs);
        }
    }

    private static void prob(String name, double v) {
        if (!(v >= 0.0 && v <= 1.0)) {
            throw new IllegalArgumentException(name + " must be in [0,1] but was " + v);
        }
    }

    /**
     * Parses a compact spec such as
     * {@code loss=0.05,dup=0.01,corrupt=0.001,reorder=0.02,delay=20,jitter=5}.
     * Omitted keys keep their defaults.
     */
    public static ChannelConfig parse(String spec) {
        ChannelConfig c = new ChannelConfig();
        if (spec == null || spec.isEmpty()) {
            return c;
        }
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("expected key=value but got: " + p);
            }
            String key = p.substring(0, eq).trim();
            double val = Double.parseDouble(p.substring(eq + 1).trim());
            switch (key) {
                case "loss":    c.lossProb = val; break;
                case "dup":     c.dupProb = val; break;
                case "corrupt": c.corruptProb = val; break;
                case "reorder": c.reorderProb = val; break;
                case "delay":   c.delayMs = val; break;
                case "jitter":  c.jitterMs = val; break;
                case "reorderExtra": c.reorderExtraMs = val; break;
                default: throw new IllegalArgumentException("unknown channel key: " + key);
            }
        }
        c.validate();
        return c;
    }

    @Override
    public String toString() {
        return String.format(
                "loss=%.4f,dup=%.4f,corrupt=%.4f,reorder=%.4f,delay=%.1f,jitter=%.1f,reorderExtra=%.1f",
                lossProb, dupProb, corruptProb, reorderProb, delayMs, jitterMs, reorderExtraMs);
    }
}
