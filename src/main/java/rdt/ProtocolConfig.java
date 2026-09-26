package rdt;
public class ProtocolConfig
{
    private final int window;
    private final String rto;
    private final int seqBits;
    private final int maxPayload;
    private final long baseRttMs;
    public ProtocolConfig(int window, String rto, int seqBits, int maxPayload, long baseRttMs)
    {
        this.window = window;
        this.rto = rto;
        this.seqBits = seqBits;
        this.maxPayload = maxPayload;
        this.baseRttMs = baseRttMs;
    }
    public long getBaseRttMs()
    {
        return baseRttMs;
    }

    public int getWindowSize()
    {
        return window;
    }
    public String getRtoMode()
    {
        return rto;
    }
    public int getSequenceBits()
    {
        return seqBits;
    }
    public int getPayloadSize()
    {
        return maxPayload;
    }
}