
package rdt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SelectiveRepeat implements ArqProtocol {
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
        this(windowSize, sequenceSpace, 500, 0);
    }

    public SelectiveRepeat(
            int windowSize,
            long sequenceSpace,
            long timeoutMs) {

        this(windowSize, sequenceSpace, timeoutMs, 0);
    }

    private SelectiveRepeat(
            int windowSize,
            long sequenceSpace,
            long timeoutMs,
            long initialSequence) {

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

        this.sendBase =
                Math.floorMod(initialSequence, sequenceSpace);

        this.nextSeq =
                Math.floorMod(initialSequence, sequenceSpace);

        this.sendHead = 0;

        this.sent = new Packet[windowSize];
        this.acked = new boolean[windowSize];
        this.timers =
                new TimerWheel.TimerHandle[windowSize];

        this.timerWheel = new TimerWheel();

        this.receiveBuffer =
                new ReceiveBuffer(
                        windowSize,
                        this.sendBase,
                        sequenceSpace);
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
                SeqSpace.offset(
                        nextSeq,
                        sendBase,
                        sequenceSpace);

        return outstanding < windowSize;
    }

    public Packet createDataPacket(byte[] payload) {
        if (!windowHasSpace()) {
            return null;
        }

        long seq = nextSeq;

        Packet packet =
                Packet.data(seq, payload);

        int slot =
                slotForOffset(
                        SeqSpace.offset(
                                seq,
                                sendBase,
                                sequenceSpace));

        sent[slot] = packet;
        acked[slot] = false;

        timers[slot] =
                timerWheel.schedule(
                        timeoutMs,
                        "retransmit:seq=" + seq);

        nextSeq =
                Math.floorMod(
                        nextSeq + 1,
                        sequenceSpace);

        return packet;
    }

    public boolean receiveAck(long ack) {
        ack =
                Math.floorMod(
                        ack,
                        sequenceSpace);

        if (!SeqSpace.inCurrentWindow(
                ack,
                sendBase,
                windowSize,
                sequenceSpace)) {
            return false;
        }

        long offset =
                SeqSpace.offset(
                        ack,
                        sendBase,
                        sequenceSpace);

        int slot =
                slotForOffset(offset);

        if (sent[slot] == null ||
            acked[slot]) {
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

            if (sent[slot] == null ||
                !acked[slot]) {
                break;
            }

            sent[slot] = null;
            acked[slot] = false;

            timerWheel.cancel(timers[slot]);
            timers[slot] = null;

            sendHead =
                    (sendHead + 1) % windowSize;

            sendBase =
                    Math.floorMod(
                            sendBase + 1,
                            sequenceSpace);
        }
    }

    public Packet outstandingPacket(long seq) {
        seq =
                Math.floorMod(
                        seq,
                        sequenceSpace);

        if (!SeqSpace.inCurrentWindow(
                seq,
                sendBase,
                windowSize,
                sequenceSpace)) {
            return null;
        }

        long offset =
                SeqSpace.offset(
                        seq,
                        sendBase,
                        sequenceSpace);

        int slot =
                slotForOffset(offset);

        if (sent[slot] == null ||
            acked[slot]) {
            return null;
        }

        return sent[slot];
    }

    public List<Packet> outstandingPackets() {
        List<Packet> packets =
                new ArrayList<>();

        long seq = sendBase;

        while (seq != nextSeq) {
            long offset =
                    SeqSpace.offset(
                            seq,
                            sendBase,
                            sequenceSpace);

            int slot =
                    slotForOffset(offset);

            if (sent[slot] != null &&
                !acked[slot]) {
                packets.add(sent[slot]);
            }

            seq =
                    Math.floorMod(
                            seq + 1,
                            sequenceSpace);
        }

        return packets;
    }

    public Packet pollExpiredRetransmission() {
        while (true) {
            TimerWheel.TimerHandle fired =
                    timerWheel.poll();

            if (fired == null) {
                return null;
            }

            String tag = fired.tag();

            if (tag == null ||
                !tag.startsWith("retransmit:seq=")) {
                continue;
            }

            long seq;

            try {
                seq =
                        Long.parseLong(
                                tag.substring(
                                        "retransmit:seq=".length()));
            } catch (NumberFormatException e) {
                continue;
            }

            Packet packet =
                    outstandingPacket(seq);

            if (packet == null) {
                continue;
            }

            long offset =
                    SeqSpace.offset(
                            seq,
                            sendBase,
                            sequenceSpace);

            int slot =
                    slotForOffset(offset);

            timers[slot] =
                    timerWheel.schedule(
                            timeoutMs,
                            "retransmit:seq=" + seq);

            return packet;
        }
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

        if (result.status() ==
                    ReceiveBuffer.Status.ACCEPTED ||
            result.status() ==
                    ReceiveBuffer.Status.DUPLICATE ||
            result.status() ==
                    ReceiveBuffer.Status.PREVIOUS_WINDOW) {

            ack =
                    Packet.ack(
                            packet.seq,
                            windowSize);
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
        return (int)
                ((sendHead + offset) %
                        windowSize);
    }

    @Override
    public RunStats send(
            Path file,
            InetSocketAddress peer,
            ProtocolConfig config)
            throws IOException {

        SelectiveRepeat sender =
                new SelectiveRepeat(
                        windowSize,
                        sequenceSpace,
                        timeoutMs,
                        1);

        long rto = 500;

        RunStats stats =
                new RunStats(
                        "sr",
                        windowSize,
                        config.getSequenceBits(),
                        config.getRtoMode(),
                        Files.size(file));

        try (DatagramSocket socket =
                     new DatagramSocket()) {

            socket.setSoTimeout((int) rto);

            Packet meta =
                    Session.createMetaPacket(file);

            byte[] metaData =
                    meta.encode();

            DatagramPacket metaPacket =
                    new DatagramPacket(
                            metaData,
                            metaData.length,
                            peer);

            boolean metaAck = false;

            while (!metaAck) {
                socket.send(metaPacket);

                try {
                    byte[] buffer =
                            new byte[2048];

                    DatagramPacket received =
                            new DatagramPacket(
                                    buffer,
                                    buffer.length);

                    socket.receive(received);

                    Packet ack;

                    try {
                        ack =
                                Packet.decode(
                                        received.getData(),
                                        received.getLength());
                    } catch (CorruptPacketException e) {
                        continue;
                    }

                    if (ack.type ==
                                Packet.TYPE_ACK &&
                        ack.ack == 0) {

                        metaAck = true;
                    }

                } catch (SocketTimeoutException e) {
                }
            }

            stats.start();

            try (InputStream input =
                         Files.newInputStream(file)) {

                boolean finished = false;

                while (!finished ||
                       sender.sendBase() !=
                               sender.nextSeq()) {

                    while (!finished &&
                           sender.windowHasSpace()) {

                        byte[] data =
                                input.readNBytes(
                                        config.getPayloadSize());

                        if (data.length == 0) {
                            finished = true;
                            break;
                        }

                        Packet packet =
                                sender.createDataPacket(data);

                        byte[] bytes =
                                packet.encode();

                        DatagramPacket datagram =
                                new DatagramPacket(
                                        bytes,
                                        bytes.length,
                                        peer);

                        socket.send(datagram);

                        stats.onDataSent(
                                bytes.length,
                                false);
                    }

                    if (finished &&
                        sender.sendBase() ==
                                sender.nextSeq()) {
                        break;
                    }

                    long wait =
                            sender.timeUntilNextTimerMs();

                    if (wait <= 0) {
                        wait = 1;
                    }

                    socket.setSoTimeout(
                            (int) Math.min(wait, 500));

                    try {
                        byte[] buffer =
                                new byte[2048];

                        DatagramPacket received =
                                new DatagramPacket(
                                        buffer,
                                        buffer.length);

                        socket.receive(received);

                        Packet ack;

                        try {
                            ack =
                                    Packet.decode(
                                            received.getData(),
                                            received.getLength());
                        } catch (CorruptPacketException e) {
                            stats.onCorruptDropped();
                            continue;
                        }

                        if (ack.type ==
                                Packet.TYPE_ACK) {

                            if (sender.receiveAck(
                                    ack.ack)) {

                                stats.onAck(false);
                            }
                        }

                    } catch (SocketTimeoutException e) {

                        Packet packet;

                        while ((packet =
                                sender.pollExpiredRetransmission())
                                != null) {

                            byte[] bytes =
                                    packet.encode();

                            DatagramPacket datagram =
                                    new DatagramPacket(
                                            bytes,
                                            bytes.length,
                                            peer);

                            socket.send(datagram);

                            stats.onDataSent(
                                    bytes.length,
                                    true);

                            stats.onTimeout();
                        }
                    }
                }
            }

            stats.stop();

            Packet fin =
                    Session.createFinPacket(
                            sender.nextSeq());

            byte[] finData =
                    fin.encode();

            DatagramPacket finPacket =
                    new DatagramPacket(
                            finData,
                            finData.length,
                            peer);

            boolean finAck = false;

            socket.setSoTimeout((int) rto);

            while (!finAck) {
                socket.send(finPacket);

                try {
                    byte[] buffer =
                            new byte[2048];

                    DatagramPacket received =
                            new DatagramPacket(
                                    buffer,
                                    buffer.length);

                    socket.receive(received);

                    Packet response;

                    try {
                        response =
                                Packet.decode(
                                        received.getData(),
                                        received.getLength());
                    } catch (CorruptPacketException e) {
                        continue;
                    }

                    if (response.type ==
                                Packet.TYPE_FINACK) {

                        finAck = true;
                        stats.setShaMatch(
                                response.payload != null &&
                                response.payload.length > 0 &&
                                response.payload[0] != 0);
                    }

                } catch (SocketTimeoutException e) {
                }
            }

            return stats;
        }
    }

    @Override
    public RunStats receive(
            Path file,
            int port,
            ProtocolConfig config)
            throws IOException {

        RunStats stats =
                new RunStats(
                        "sr",
                        windowSize,
                        config.getSequenceBits(),
                        config.getRtoMode(),
                        0);

        SelectiveRepeat receiver =
                new SelectiveRepeat(
                        windowSize,
                        sequenceSpace,
                        timeoutMs,
                        1);

        String expectedSha = null;

        try (DatagramSocket socket =
                     new DatagramSocket(port);
             OutputStream output =
                     Files.newOutputStream(file)) {

            boolean gotMeta = false;

            while (true) {

                byte[] buffer =
                        new byte[2048];

                DatagramPacket received =
                        new DatagramPacket(
                                buffer,
                                buffer.length);

                socket.receive(received);

                Packet packet;

                try {
                    packet =
                            Packet.decode(
                                    received.getData(),
                                    received.getLength());
                } catch (CorruptPacketException e) {
                    stats.onCorruptDropped();
                    continue;
                }

                InetSocketAddress sender =
                        new InetSocketAddress(
                                received.getAddress(),
                                received.getPort());

                if (!gotMeta &&
                    packet.type ==
                            Packet.TYPE_DATA &&
                    packet.seq == 0) {

                    Session.MetaInfo meta =
                            Session.parseMeta(
                                    packet.payload);

                    expectedSha = meta.sha256;

                    Packet ack =
                            Packet.ack(
                                    0,
                                    windowSize);

                    byte[] ackData =
                            ack.encode();

                    DatagramPacket ackPacket =
                            new DatagramPacket(
                                    ackData,
                                    ackData.length,
                                    sender);

                    socket.send(ackPacket);

                    gotMeta = true;
                    stats.start();

                    continue;
                }

                if (gotMeta &&
                    packet.type ==
                            Packet.TYPE_DATA) {

                    ReceiveResult result =
                            receiver.receiveData(packet);

                    if (result.ack() != null) {

                        byte[] ackData =
                                result.ack().encode();

                        DatagramPacket ackPacket =
                                new DatagramPacket(
                                        ackData,
                                        ackData.length,
                                        sender);

                        socket.send(ackPacket);
                    }

                    for (Packet data :
                            result.delivered()) {

                        output.write(
                                data.payload);
                    }

                    continue;
                }

                if (gotMeta &&
                    packet.type ==
                            Packet.TYPE_FIN) {

                    output.flush();

                    boolean shaMatch =
                            Session.verifySha256(
                                    file,
                                    expectedSha);

                    Packet finAck =
                            Session.createFinAckPacket(
                                    packet.seq,
                                    shaMatch);

                    byte[] finData =
                            finAck.encode();

                    DatagramPacket finPacket =
                            new DatagramPacket(
                                    finData,
                                    finData.length,
                                    sender);

                    socket.send(finPacket);

                    stats.setShaMatch(
                            shaMatch);

                    stats.stop();

                    return stats;
                }
            }
        }
    }
}