package rdt; 
public final class RttEstimator { 
    private static final double ALPHA = 1.0 / 8.0; 
    private static final double BETA = 1.0 / 4.0; 
    private static final double G_MS = 1.0; 
    private static final long MAX_RTO_MS = 60_000L; 
    private final long minRtoMs; private double srtt = -1; 
    private double rttvar = -1; private long rto; 
    private int backoffShift = 0; 
    public RttEstimator() { 
        this(200L); 
    } 
    public RttEstimator(long minRtoMs) { 
        this.minRtoMs = minRtoMs; 
        this.rto = Math.max(minRtoMs, 1000L); 
    } 
    public void update(long sampleNanos) { 
        double sampleMs = sampleNanos / 1_000_000.0; 
        if (srtt < 0) { 
            srtt = sampleMs; 
            rttvar = sampleMs / 2.0; 
        } 
        else { 
            rttvar = (1 - BETA) * rttvar + BETA * Math.abs(srtt - sampleMs); 
            srtt = (1 - ALPHA) * srtt + ALPHA * sampleMs; 
        } 
        rto = clamp(Math.round(srtt + Math.max(G_MS, 4 * rttvar))); 
    } 
    public void doubleRto() { 
        backoffShift++; 
    } 
    public void resetBackoff() { 
        backoffShift = 0; 
    } 
    public long rtoMs() { 
        return clamp(rto * (1L << Math.min(backoffShift, 16))); 
    } 
    private long clamp(long val) { 
        return Math.max(minRtoMs, Math.min(MAX_RTO_MS, val)); 
    } 
}