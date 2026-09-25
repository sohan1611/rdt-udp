package rdt; 
import java.io.ByteArrayOutputStream; 
import java.io.IOException; 
import java.net.DatagramPacket; 
import java.net.DatagramSocket; 
import java.net.InetSocketAddress; 
import java.net.SocketTimeoutException; 
import java.util.Arrays; 
public final class GoBackN { 
    private static final int SEQ_BITS = 16; 
    private static final long M = 1L << SEQ_BITS; 
    private static final int MAX_PAYLOAD = 1400; 
    private static final int MAX_RETRIES = 20; 
    private static final int LINGER_MS = 2000; 
    private final DatagramSocket socket; 
    private final InetSocketAddress remote; 
    private final int windowSize; 
    private final RttEstimator rtt; 
    private final boolean fixedRto; 
    private final long fixedRtoMs; 
    private final Packet[] window; 
    private final long[] sentNanos; 
    private final boolean[] retransmitted; 
    private long sendBase = 0; 
    private long nextSeqNum = 0; 
    private int head = 0; 
    private int retryCount = 0; 
    private final TimerWheel timerWheel = new TimerWheel(); 
    private TimerWheel.TimerHandle timerHandle = null; 
    public GoBackN(DatagramSocket socket, InetSocketAddress remote, int windowSize) { 
        this(socket, remote, windowSize, false, 0L); 
    } 
    public GoBackN(DatagramSocket socket, InetSocketAddress remote, int windowSize, long fixedRtoMs) { 
        this(socket, remote, windowSize, true, fixedRtoMs); 
    } 
    private GoBackN(DatagramSocket socket, InetSocketAddress remote, int windowSize, boolean fixedRto, long fixedRtoMs) { 
        if (windowSize < 1 || windowSize > M - 1) 
            throw new IllegalArgumentException( "windowSize must be 1 .. " + (M - 1) + " (W <= 2^k-1)"); 
        this.socket = socket; 
        this.remote = remote; 
        this.windowSize = windowSize; 
        this.fixedRto = fixedRto; 
        this.fixedRtoMs = fixedRtoMs; 
        this.rtt = new RttEstimator(); 
        this.window = new Packet[windowSize]; 
        this.sentNanos = new long[windowSize]; 
        this.retransmitted = new boolean[windowSize]; 
    } 
    public void send(byte[] data) throws IOException { 
        int dataOffset = 0; 
        boolean allQueued = false; 
        while (!allQueued || sendBase != nextSeqNum) { 
            while (!allQueued && SeqSpace.inCurrentWindow(nextSeqNum, sendBase, windowSize, M)) { 
                int len = Math.min(MAX_PAYLOAD, data.length - dataOffset); 
                boolean last = (dataOffset + len >= data.length); 
                byte[] payload = Arrays.copyOfRange(data, dataOffset, dataOffset + len); 
                Packet pkt = last ? new Packet(Packet.TYPE_FIN, nextSeqNum, 0L, 0, 0, payload) : Packet.data(nextSeqNum, payload); 
                int slot = slot(nextSeqNum); 
                window[slot] = pkt; 
                sentNanos[slot] = System.nanoTime(); 
                retransmitted[slot] = false; 
                doSend(pkt); 
                if (timerHandle == null || timerHandle.isCancelled()) 
                    timerHandle = timerWheel.schedule(rtoMs(), "gbn-base"); 
                dataOffset += len; 
                nextSeqNum = inc(nextSeqNum); 
                if (last) 
                    allQueued = true; 
            } 
            long waitMs = timerWheel.timeUntilNextMs(); 
            socket.setSoTimeout((int) waitMs); 
            byte[] buf = new byte[Packet.HEADER_LEN + MAX_PAYLOAD]; 
            DatagramPacket dp = new DatagramPacket(buf, buf.length); 
            try { 
                socket.receive(dp); 
                Packet ack = Packet.decode(dp.getData(), dp.getLength()); 
                if (ack.type == Packet.TYPE_ACK) 
                    onAck(ack.ack); 
            } 
            catch (SocketTimeoutException e) { 
                onTimeout(); 
            } 
            catch (CorruptPacketException e) { } 
            TimerWheel.TimerHandle fired; 
            while ((fired = timerWheel.poll()) != null) { 
                timerHandle = null; 
                onTimeout();
                break; 
            } 
        } 
    } 
    public byte[] receive() throws IOException { 
        ByteArrayOutputStream out = new ByteArrayOutputStream(); 
        long expectedSeq = 0; 
        long lastAck = 0; 
        while (true) { 
            byte[] buf = new byte[Packet.HEADER_LEN + MAX_PAYLOAD]; 
            DatagramPacket dp = new DatagramPacket(buf, buf.length); 
            socket.receive(dp); 
            Packet pkt; 
            try { 
                pkt = Packet.decode(dp.getData(), dp.getLength()); 
            } 
            catch (CorruptPacketException e) { 
                continue; 
            } 
            if (pkt.type != Packet.TYPE_DATA && pkt.type != Packet.TYPE_FIN) 
                continue; 
            if (pkt.seq == expectedSeq) { 
                out.write(pkt.payload, 0, pkt.payload.length); 
                expectedSeq = inc(expectedSeq); 
                lastAck = expectedSeq; 
                sendAck(expectedSeq); 
                if (pkt.type == Packet.TYPE_FIN) 
                    break; 
            } 
            else { 
                sendAck(lastAck); 
            } 
        } 
        long finSeq = (expectedSeq - 1 + M) % M; 
        socket.setSoTimeout(LINGER_MS); 
        long deadline = System.currentTimeMillis() + LINGER_MS; 
        while (System.currentTimeMillis() < deadline) { 
            byte[] buf = new byte[Packet.HEADER_LEN + MAX_PAYLOAD]; 
            DatagramPacket dp = new DatagramPacket(buf, buf.length); 
            try { 
                socket.receive(dp); 
                Packet pkt = Packet.decode(dp.getData(), dp.getLength()); 
                if ((pkt.type == Packet.TYPE_DATA || pkt.type == Packet.TYPE_FIN) && pkt.seq == finSeq) 
                    sendAck(expectedSeq); 
            } 
            catch (SocketTimeoutException e) { 
                break; 
            } 
            catch (CorruptPacketException e) { } 
        } 
        return out.toByteArray(); 
    } 
    private void onAck(long ackNum) { 
        if (!SeqSpace.inCurrentWindow(ackNum, sendBase, windowSize + 1, M)) 
            return; 
        if (ackNum == sendBase) 
            return; 
        while (sendBase != ackNum) { 
            int slot = slot(sendBase); 
            if (!retransmitted[slot]) { 
                long sampleNanos = System.nanoTime() - sentNanos[slot]; 
                if (sampleNanos > 0) 
                    rtt.update(sampleNanos); 
            } 
            head = (head + 1) % windowSize; 
            sendBase = inc(sendBase); 
        } 
        retryCount = 0; 
        rtt.resetBackoff(); 
        if (timerHandle != null) 
            timerWheel.cancel(timerHandle); 
        timerHandle = (sendBase == nextSeqNum) ? null : timerWheel.schedule(rtoMs(), "gbn-base"); 
    } 
    private void onTimeout() throws IOException { 
        if (++retryCount > MAX_RETRIES) 
            throw new IOException("GoBackN: max retries (" + MAX_RETRIES + ") exceeded"); 
        if (!fixedRto) 
            rtt.doubleRto(); 
        if (timerHandle != null) { 
            timerWheel.cancel(timerHandle); 
            timerHandle = null; 
        } 
        timerHandle = timerWheel.schedule(rtoMs(), "gbn-base"); 
        long seq = sendBase; 
        while (seq != nextSeqNum) { 
            int slot = slot(seq); 
            retransmitted[slot] = true; 
            doSend(window[slot]); 
            seq = inc(seq); 
        } 
    } 
    private void doSend(Packet pkt) throws IOException { 
        byte[] raw = pkt.encode(); 
        socket.send(new DatagramPacket(raw, raw.length, remote)); 
    } 
    private void sendAck(long ackNum) throws IOException { 
        byte[] raw = Packet.ack(ackNum, windowSize).encode(); 
        socket.send(new DatagramPacket(raw, raw.length, remote)); 
    } 
    private long rtoMs() { 
        return fixedRto ? fixedRtoMs : rtt.rtoMs(); 
    } 
    private int slot(long s) { 
        return (int)((head + SeqSpace.offset(s, sendBase, M)) % windowSize); 
    } 
    private long inc(long s) { 
        return (s + 1) % M; 
    } 
}