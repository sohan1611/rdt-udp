package rdt;

import java.util.ArrayList;
import java.util.List;

public final class SelectiveRepeat {

    private final int windowSize;
    private final long sequenceSpace;

    private long sendBase;
    private long nextSeq;

    private final Packet[] sent;
    private final boolean[] acked;

    private final ReceiveBuffer receiveBuffer;

    public SelectiveRepeat(int windowSize, long sequenceSpace) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive");
        }
        if (sequenceSpace <= 0) {
            throw new IllegalArgumentException("sequenceSpace must be positive");
        }
        if (windowSize >= sequenceSpace) {
            throw new IllegalArgumentException(
                    "windowSize must be smaller than sequenceSpace");
        }

        this.windowSize = windowSize;
        this.sequenceSpace = sequenceSpace;

        this.sendBase = 0;
        this.nextSeq = 0;

        this.sent = new Packet[windowSize];
        this.acked = new boolean[windowSize];

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

        int slot = slotFor(seq);
        sent[slot] = packet;
        acked[slot] = false;

        nextSeq = Math.floorMod(nextSeq + 1, sequenceSpace);

        return packet;
    }

    public boolean receiveAck(long ack) {
        ack = Math.floorMod(ack, sequenceSpace);

        if (!SeqSpace.inCurrentWindow(
                ack, sendBase, windowSize, sequenceSpace)) {
            return false;
        }

        int slot = slotFor(ack);

        if (sent[slot] == null) {
            return false;
        }

        acked[slot] = true;
        slideWindow();

        return true;
    }

    private void slideWindow() {
        while (sendBase != nextSeq) {
            int slot = slotFor(sendBase);

            if (sent[slot] == null || !acked[slot]) {
                break;
            }

            sent[slot] = null;
            acked[slot] = false;

            sendBase = Math.floorMod(sendBase + 1, sequenceSpace);
        }
    }

    public Packet outstandingPacket(long seq) {
        seq = Math.floorMod(seq, sequenceSpace);

        if (!SeqSpace.inCurrentWindow(
                seq, sendBase, windowSize, sequenceSpace)) {
            return null;
        }

        int slot = slotFor(seq);

        if (sent[slot] == null || acked[slot]) {
            return null;
        }

        return sent[slot];
    }

    public List<Packet> outstandingPackets() {
        List<Packet> packets = new ArrayList<>();

        long seq = sendBase;

        while (seq != nextSeq) {
            int slot = slotFor(seq);

            if (sent[slot] != null && !acked[slot]) {
                packets.add(sent[slot]);
            }

            seq = Math.floorMod(seq + 1, sequenceSpace);
        }

        return packets;
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
            throw new IllegalArgumentException("packet cannot be null");
        }

        if (packet.type != Packet.TYPE_DATA) {
            throw new IllegalArgumentException(
                    "packet must be a DATA packet");
        }

        ReceiveBuffer.Result result = receiveBuffer.accept(packet);

        Packet ack = null;

        if (result.status() == ReceiveBuffer.Status.ACCEPTED
                || result.status() == ReceiveBuffer.Status.DUPLICATE
                || result.status() == ReceiveBuffer.Status.PREVIOUS_WINDOW) {

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

    private int slotFor(long seq) {
        return (int) Math.floorMod(seq, windowSize);
    }
}