package emulator;

import java.util.Locale;

public final class ChannelConfig {
    public double lossProb;
    public double dupProb;
    public double corruptProb;
    public double reorderProb;
    public double delayMs;
    public double jitterMs;
    public double reorderExtraMs=20.0;
    public static ChannelConfig perfect() {
        return new ChannelConfig();
    }

    public static ChannelConfig parse(String spec) {
        ChannelConfig cfg=perfect();
        if(spec==null||spec.trim().isEmpty())return cfg;
        for(String raw:spec.split(",")) {
            String part=raw.trim();
            if(part.isEmpty())continue;
            int eq=part.indexOf('=');
            if(eq<0)throw new IllegalArgumentException("Expected key=value: "+part);
            String key=part.substring(0,eq).trim();
            String text=part.substring(eq+1).trim();
            switch(key) {
                case "loss":
                    cfg.lossProb=probability(key,number(key,text));
                    break;
                case "dup":
                    cfg.dupProb=probability(key,number(key,text));
                    break;
                case "corrupt":
                    cfg.corruptProb=probability(key,number(key,text));
                    break;
                case "reorder":
                    cfg.reorderProb=probability(key,number(key,text));
                    break;
                case "delay":
                    cfg.delayMs=nonNegative(key,number(key,text));
                    break;
                case "jitter":
                    cfg.jitterMs=nonNegative(key,number(key,text));
                    break;
                case "reorderExtra":
                    cfg.reorderExtraMs=nonNegative(key,number(key,text));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown channel option: "+key);
            }
        }

        if(cfg.jitterMs>cfg.delayMs) {
            System.err.println("Warning: jitter exceeds delay; negative delays will be clamped to 0.");
        }
        return cfg;
    }

    private static double number(String key,String text) {
        try {
            return Double.parseDouble(text);
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for "+key+": "+text,e);
        }
    }

    private static double probability(String key,double v) {
        // NaN makes both ordinary comparisons false, so it must be checked explicitly.
        if(Double.isNaN(v)||v<0.0||v>1.0) {
            throw new IllegalArgumentException(key+" must be between 0 and 1");
        }
        return v;
    }

    private static double nonNegative(String key,double v) {
        if(!Double.isFinite(v)||v<0.0) {
            throw new IllegalArgumentException(key+" must be non-negative");
        }
        return v;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
            "loss=%.4f,dup=%.4f,corrupt=%.4f,reorder=%.4f,delay=%.1f,jitter=%.1f,reorderExtra=%.1f",
            lossProb,dupProb,corruptProb,reorderProb,delayMs,jitterMs,reorderExtraMs);
    }
}