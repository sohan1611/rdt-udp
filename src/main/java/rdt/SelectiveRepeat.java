package rdt;
import java.util.ArrayList;
import java.util.List;
import timer.TimerWheel;

public final class SelectiveRepeat {

    private final int windowSize;
    private final long sequenceSpace;
    private final long timeoutMs;

    private long sendBase;
    private long nextSeq;
    private int sendHead;

    private final Packet[] sent;
    private final boolean[] acked;
    private final TimerWheel.TimerHandle[] timers;

    private final TimerWheel timerWheel;
    private final ReceiveBuffer receiveBuffer;

    public SelectiveRepeat(int windowSize, long sequenceSpace) {
        this(windowSize, sequenceSpace, 500);
    }

    public SelectiveRepeat(
            int windowSize, long sequenceSpace, long timeoutMs) {

        if (windowSize <= 0) {
            throw new IllegalArgumentException(
                    "windowSize must be positive");
        }
        if (sequenceSpace <= 0) {
            throw new IllegalArgumentException(
                    "sequenceSpace must be positive");
        }
        if (windowSize > sequenceSpace / 2) {
            throw new IllegalArgumentException(
                    "Selective Repeat requires windowSize <= sequenceSpace/2");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "timeoutMs must be positive");
        }

        this.windowSize = windowSize;
        this.sequenceSpace = sequenceSpace;
        this.timeoutMs = timeoutMs;

        this.sendBase = 0;
        this.nextSeq = 0;
        this.sendHead = 0;

        this.sent = new Packet[windowSize];
        this.acked = new boolean[windowSize];
        this.timers = new TimerWheel.TimerHandle[windowSize];

        this.timerWheel = new TimerWheel();
        this.receiveBuffer = new ReceiveBuffer(
                windowSize, 0, sequenceSpace);
    }

    public long sendBase() {
        return sendBase;
    }

    public long nextSeq() {
        return nextSeq;
    }

    public int windowSize() {
        return windowSize;
    }

    public long sequenceSpace() {
        return sequenceSpace;
    }

    public boolean windowHasSpace() {
        long outstanding =
                SeqSpace.offset(nextSeq, sendBase, sequenceSpace);
        return outstanding < windowSize;
    }

    public Packet createDataPacket(byte[] payload) {
        if (!windowHasSpace()) {
            return null;
        }

        long seq = nextSeq;
        Packet packet = Packet.data(seq, payload);

        int slot = slotForOffset(
                SeqSpace.offset(seq, sendBase, sequenceSpace));

        sent[slot] = packet;
        acked[slot] = false;
        timers[slot] = timerWheel.schedule(
                timeoutMs, "retransmit:seq=" + seq);

        nextSeq = Math.floorMod(
                nextSeq + 1, sequenceSpace);

        return packet;
    }

    public boolean receiveAck(long ack) {
        ack = Math.floorMod(ack, sequenceSpace);

        if (!SeqSpace.inCurrentWindow(
                ack, sendBase, windowSize, sequenceSpace)) {
            return false;
        }

        long offset =
                SeqSpace.offset(ack, sendBase, sequenceSpace);

        int slot = slotForOffset(offset);

        if (sent[slot] == null || acked[slot]) {
            return false;
        }

        acked[slot] = true;
        timerWheel.cancel(timers[slot]);
        timers[slot] = null;

        slideWindow();
        return true;
    }

    private void slideWindow() {
        while (sendBase != nextSeq) {
            int slot = sendHead;

            if (sent[slot] == null || !acked[slot]) {
                break;
            }

            sent[slot] = null;
            acked[slot] = false;
            timerWheel.cancel(timers[slot]);
            timers[slot] = null;

            sendHead = (sendHead + 1) % windowSize;
            sendBase = Math.floorMod(
                    sendBase + 1, sequenceSpace);
        }
    }

    public Packet outstandingPacket(long seq) {
        seq = Math.floorMod(seq, sequenceSpace);

        if (!SeqSpace.inCurrentWindow(
                seq, sendBase, windowSize, sequenceSpace)) {
            return null;
        }

        long offset =
                SeqSpace.offset(seq, sendBase, sequenceSpace);
        int slot = slotForOffset(offset);

        if (sent[slot] == null || acked[slot]) {
            return null;
        }

        return sent[slot];
    }

    public List<Packet> outstandingPackets() {
        List<Packet> packets = new ArrayList<>();

        long seq = sendBase;
        while (seq != nextSeq) {
            long offset =
                    SeqSpace.offset(seq, sendBase, sequenceSpace);
            int slot = slotForOffset(offset);

            if (sent[slot] != null && !acked[slot]) {
                packets.add(sent[slot]);
            }

            seq = Math.floorMod(
                    seq + 1, sequenceSpace);
        }

        return packets;
    }
    public Packet pollExpiredRetransmission() {
        TimerWheel.TimerHandle fired = timerWheel.poll();

        if (fired == null || fired.tag() == null) {
            return null;
        }

        String prefix = "retransmit:seq=";
        String tag = fired.tag();

        if (!tag.startsWith(prefix)) {
            return null;
        }

        long seq;
        try {
            seq = Long.parseLong(tag.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }

        Packet packet = outstandingPacket(seq);
        if (packet == null) {
            return null;
        }

        long offset =
                SeqSpace.offset(seq, sendBase, sequenceSpace);
        int slot = slotForOffset(offset);

        timers[slot] = timerWheel.schedule(
                timeoutMs, "retransmit:seq=" + seq);

        return packet;
    }

    public long timeUntilNextTimerMs() {
        return timerWheel.timeUntilNextMs();
    }

    public TimerWheel timerWheel() {
        return timerWheel;
    }

    public static final class ReceiveResult {
        private final ReceiveBuffer.Status status;
        private final List<Packet> delivered;
        private final Packet ack;

        private ReceiveResult(
                ReceiveBuffer.Status status,
                List<Packet> delivered,
                Packet ack) {
            this.status = status;
            this.delivered = delivered;
            this.ack = ack;
        }

        public ReceiveBuffer.Status status() {
            return status;
        }

        public List<Packet> delivered() {
            return delivered;
        }

        public Packet ack() {
            return ack;
        }
    }

    public ReceiveResult receiveData(Packet packet) {
        if (packet == null) {
            throw new IllegalArgumentException(
                    "packet cannot be null");
        }

        if (packet.type != Packet.TYPE_DATA) {
            throw new IllegalArgumentException(
                    "packet must be a DATA packet");
        }

        ReceiveBuffer.Result result =
                receiveBuffer.accept(packet);

        Packet ack = null;

        if (result.status() == ReceiveBuffer.Status.ACCEPTED
                || result.status() == ReceiveBuffer.Status.DUPLICATE
                || result.status()
                        == ReceiveBuffer.Status.PREVIOUS_WINDOW) {
            ack = Packet.ack(packet.seq, windowSize);
        }

        return new ReceiveResult(
                result.status(),
                result.delivered(),
                ack);
    }

    public long receiveBase() {
        return receiveBuffer.base();
    }

    private int slotForOffset(long offset) {
        return (int) ((sendHead + offset) % windowSize);
    }
}