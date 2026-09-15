package emulator;

public final class ChannelConfig {

    public double lossProb = 0.0;
    public double dupProb = 0.0;
    public double corruptProb = 0.0;
    public double reorderProb = 0.0;
    public double delayMs = 0.0;
    public double jitterMs = 0.0;
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

    public static ChannelConfig perfect() {
        return new ChannelConfig();
    }

    public void validate() {
        prob("lossProb", lossProb);
        prob("dupProb", dupProb);
        prob("corruptProb", corruptProb);
        prob("reorderProb", reorderProb);

        if (delayMs < 0) {
            throw new IllegalArgumentException(
                    "delayMs must not be negative: " + delayMs);
        }

        if (jitterMs < 0) {
            throw new IllegalArgumentException(
                    "jitterMs must not be negative: " + jitterMs);
        }

        if (reorderExtraMs < 0) {
            throw new IllegalArgumentException(
                    "reorderExtraMs must not be negative: " + reorderExtraMs);
        }

        if (jitterMs > delayMs && delayMs > 0) {
            System.err.printf(
                    "warning: jitterMs (%.1f) exceeds delayMs (%.1f); "
                            + "delays will be clamped at zero%n",
                    jitterMs, delayMs);
        }
    }

    private static void prob(String name, double v) {
        if (!(v >= 0.0 && v <= 1.0)) {
            throw new IllegalArgumentException(
                    name + " must be in [0,1] but was " + v);
        }
    }

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
                throw new IllegalArgumentException(
                        "expected key=value but got: " + p);
            }

            String key = p.substring(0, eq).trim();
            double val = Double.parseDouble(
                    p.substring(eq + 1).trim());

            switch (key) {
                case "loss":
                    c.lossProb = val;
                    break;

                case "dup":
                    c.dupProb = val;
                    break;

                case "corrupt":
                    c.corruptProb = val;
                    break;

                case "reorder":
                    c.reorderProb = val;
                    break;

                case "delay":
                    c.delayMs = val;
                    break;

                case "jitter":
                    c.jitterMs = val;
                    break;

                case "reorderExtra":
                    c.reorderExtraMs = val;
                    break;

                default:
                    throw new IllegalArgumentException(
                            "unknown channel key: " + key);
            }
        }

        c.validate();
        return c;
    }

    @Override
    public String toString() {
        return String.format(
                "loss=%.4f,dup=%.4f,corrupt=%.4f,reorder=%.4f,"
                        + "delay=%.1f,jitter=%.1f,reorderExtra=%.1f",
                lossProb,
                dupProb,
                corruptProb,
                reorderProb,
                delayMs,
                jitterMs,
                reorderExtraMs
        );
    }
}