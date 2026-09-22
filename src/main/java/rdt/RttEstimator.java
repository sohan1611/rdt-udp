package rdt; 
public final class RttEstimator { 
    private static final double ALPHA = 1.0 / 8.0; 
    private static final double BETA = 1.0 / 4.0; 
    private static final long MIN_RTO_MS = 200L; 
    private static final long MAX_RTO_MS = 60_000L; 
    private double srtt = -1; 
    private double rttvar = -1; 
    private long rto = 1000L; 
    public void update(long sampleMs) { 
        if (srtt < 0) { 
            srtt = sampleMs; 
            rttvar = sampleMs / 2.0; 
        } 
        else { 
            rttvar = (1 - BETA) * rttvar + BETA * Math.abs(srtt - sampleMs); 
            srtt = (1 - ALPHA) * srtt + ALPHA * sampleMs; 
        } 
        rto = clamp(Math.round(srtt + 4 * rttvar)); 
    } 
    public void doubleRto() { 
        rto = clamp(rto * 2); 
    } 
    public long rtoMs() { 
        return rto; 
    } 
    private static long clamp(long val) { 
        return Math.max(MIN_RTO_MS, Math.min(MAX_RTO_MS, val)); 
    } 
}
