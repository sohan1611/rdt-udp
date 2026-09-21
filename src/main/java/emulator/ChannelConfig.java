package emulator;
public final class ChannelConfig {
    public double lossProb = 0.0;
    public double dupProb = 0.0;
    public double corruptProb = 0.0;
    public double reorderProb = 0.0;
    public double delayMs = 0.0;
    public double jitterMs = 0.0;
    public double reorderExtraMs = 20.0;
    public ChannelConfig() {}
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
    public static ChannelConfig parse(String spec) {
        ChannelConfig c = perfect();
        if (spec == null || spec.isEmpty())
            return c;
        String[] parts = spec.split(",");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty())
                continue;
            int eq = p.indexOf('=');
            if (eq == -1)
                throw new IllegalArgumentException("expected key=value but got: " + p);
            String key = p.substring(0, eq).trim();
            double val = Double.parseDouble(p.substring(eq + 1).trim());
            set(c, key, val);
        }
        c.validate();
        return c;
    }

    private static void set(ChannelConfig c, String key, double val) {
        switch (key) {
            case "loss":
                c.lossProb = val;
                return;
            case "dup":
                c.dupProb = val;
                return;
            case "corrupt":
                c.corruptProb = val;
                return;
            case "reorder":
                c.reorderProb = val;
                return;
            case "delay":
                c.delayMs = val;
                return;
            case "jitter":
                c.jitterMs = val;
                return;
            case "reorderExtra":
                c.reorderExtraMs = val;
                return;
            default:
                throw new IllegalArgumentException("unknown channel key: " + key);
        }
    }

    public void validate() {
        prob("lossProb", lossProb);
        prob("dupProb", dupProb);
        prob("corruptProb", corruptProb);
        prob("reorderProb", reorderProb);
        nonNegative("delayMs", delayMs);
        nonNegative("jitterMs", jitterMs);
        nonNegative("reorderExtraMs", reorderExtraMs);
        if (delayMs > 0 && jitterMs > delayMs)
            System.err.printf(
                    "warning: jitterMs (%.1f) exceeds delayMs (%.1f); delays will be clamped at zero%n",
                    jitterMs, delayMs);
    }

    private static void prob(String name, double v) {
        if (!(v >= 0.0 && v <= 1.0))
            throw new IllegalArgumentException(name + " must be in [0,1] but was " + v);
    }

    private static void nonNegative(String name, double v) {
        if (v < 0)
            throw new IllegalArgumentException(name + " must not be negative: " + v);
    }

    public static ChannelConfig perfect() {
        return new ChannelConfig();
    }

    @Override
    public String toString() {
        return String.format(
                "loss=%.4f,dup=%.4f,corrupt=%.4f,reorder=%.4f,delay=%.1f,jitter=%.1f,reorderExtra=%.1f",
                lossProb, dupProb, corruptProb, reorderProb,
                delayMs, jitterMs, reorderExtraMs);
    }
}