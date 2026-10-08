package rdt;

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * File-transfer adapter for the already-tested GBN/SR protocol cores.
 * It implements the project's Session META -> DATA -> FIN/FINACK framing.
 */
final class ArqFileTransfer {
    enum Mode { GBN, SR }

    private static final int FIN_RETRIES = 3;
    private static final int MAX_RETRIES = 20;

    private ArqFileTransfer() {}

    static RunStats send(Path file, InetSocketAddress peer,
                         ProtocolConfig cfg, String protocol,
                         Mode mode) throws IOException {
        validate(cfg, mode);

        long fileBytes = Files.size(file);
        int window = cfg.getWindowSize();
        long m = 1L << cfg.getSequenceBits();

        RttEstimator rtt = new RttEstimator(5);
        boolean fixed = cfg.getRtoMode().startsWith("fixed:");
        long fixedRtoMs = fixedRto(cfg);

        RunStats stats = new RunStats(
                protocol, window, cfg.getSequenceBits(),
                cfg.getRtoMode(), fileBytes);

        try (DatagramSocket socket = new DatagramSocket()) {
            sendMetaAndWait(socket, peer, file, cfg, fixed, fixedRtoMs, stats);

            byte[] all = Files.readAllBytes(file);
            List<Packet> packets = new ArrayList<>();

            long seq = 1;
            for (int off = 0; off < all.length; ) {
                int n = Math.min(cfg.getPayloadSize(), all.length - off);
                byte[] payload = new byte[n];
                System.arraycopy(all, off, payload, 0, n);
                packets.add(Packet.data(seq, payload));
                off += n;
                seq = inc(seq, m);
            }

            stats.start();

            Map<Long, Sent> outstanding = new HashMap<>();
            int nextIndex = 0;
            long base = packets.isEmpty() ? 1 : packets.get(0).seq;
            int retries = 0;

            while (nextIndex < packets.size() || !outstanding.isEmpty()) {
                while (nextIndex < packets.size()
                        && outstanding.size() < window) {
                    Packet packet = packets.get(nextIndex++);
                    sendPacket(socket, peer, packet);

                    outstanding.put(
                            packet.seq,
                            new Sent(packet, System.nanoTime(), false));

                    stats.onDataSent(packet.wireLength(), false);
                }

                if (outstanding.isEmpty()) {
                    continue;
                }

                long rtoMs = fixed ? fixedRtoMs : rtt.rtoMs();
                long timeoutMs = timeoutFor(
                        outstanding, base, rtoMs, mode);

                socket.setSoTimeout(
                        (int) Math.min(Integer.MAX_VALUE,
                                Math.max(1, timeoutMs)));

                Received received;

                try {
                    received = receive(socket, cfg);
                }
                catch (SocketTimeoutException e) {
                    stats.onTimeout();

                    if (mode == Mode.GBN
                            && ++retries > MAX_RETRIES) {
                        throw new IOException(
                                protocol + ": maximum retries exceeded");
                    }

                    if (!fixed) {
                        rtt.doubleRto();
                    }

                    long now = System.nanoTime();

                    if (mode == Mode.GBN) {
                        for (Sent old :
                                new ArrayList<>(outstanding.values())) {
                            sendPacket(socket, peer, old.packet);
                            outstanding.put(
                                    old.packet.seq,
                                    new Sent(old.packet, now, true));
                            stats.onDataSent(
                                    old.packet.wireLength(), true);
                        }
                    }
                    else {
                        long srRto = fixed ? fixedRtoMs : rtt.rtoMs();
                        for (Sent old :
                                expired(outstanding, now, srRto)) {
                            sendPacket(socket, peer, old.packet);
                            outstanding.put(
                                    old.packet.seq,
                                    new Sent(old.packet, now, true));
                            stats.onDataSent(
                                    old.packet.wireLength(), true);
                        }
                    }

                    continue;
                }
                catch (CorruptPacketException e) {
                    stats.onCorruptDropped();
                    continue;
                }

                Packet ack;
                try {
                    ack = received.packet;
                }
                catch (RuntimeException e) {
                    continue;
                }

                if (ack.type != Packet.TYPE_ACK) {
                    continue;
                }

                long now = System.nanoTime();

                if (mode == Mode.GBN) {
                    long ackNo = ack.ack;

                    if (ackNo == base) {
                        stats.onAck(true);
                        continue;
                    }

                    if (!isGbnAckValid(
                            ackNo, base, outstanding, m)) {
                        continue;
                    }

                    stats.onAck(false);

                    long s = base;
                    while (s != ackNo) {
                        Sent sent = outstanding.remove(s);

                        if (sent != null && !sent.retransmitted) {
                            rtt.update(now - sent.sentNanos);
                        }

                        s = inc(s, m);
                    }

                    base = ackNo;
                    retries = 0;
                    rtt.resetBackoff();
                }
                else {
                    Sent sent = outstanding.remove(ack.ack);

                    if (sent == null) {
                        stats.onAck(true);
                        continue;
                    }

                    stats.onAck(false);

                    if (!sent.retransmitted) {
                        rtt.update(now - sent.sentNanos);
                    }

                    if (ack.ack == base) {
                        base = firstOutstanding(
                                outstanding, base, m);
                    }

                    retries = 0;
                    rtt.resetBackoff();
                }
            }

            stats.stop();

            sendFinAndWait(
                    socket, peer, seq, cfg, stats);

            return stats;
        }
    }

    static RunStats receive(Path file, int port,
                            ProtocolConfig cfg, String protocol,
                            Mode mode) throws IOException {
        validate(cfg, mode);

        RunStats stats = new RunStats(
                protocol,
                cfg.getWindowSize(),
                cfg.getSequenceBits(),
                cfg.getRtoMode(),
                0);

        long m = 1L << cfg.getSequenceBits();

        try (DatagramSocket socket = new DatagramSocket(port)) {
            Received metaReceived;

            while (true) {
                try {
                    metaReceived = receive(socket, cfg);
                }
                catch (CorruptPacketException e) {
                    stats.onCorruptDropped();
                    continue;
                }

                Packet meta = metaReceived.packet;

                if (meta.type == Packet.TYPE_DATA
                        && meta.flags == 0x01) {
                    break;
                }
            }

            Session.MetaInfo info =
                    Session.parseMeta(metaReceived.packet.payload);

            sendAck(
                    socket,
                    metaReceived.datagram,
                    0,
                    cfg.getWindowSize());

            if (mode == Mode.GBN) {
                return receiveGbn(
                        socket, file, cfg, stats, info, m);
            }

            return receiveSr(
                    socket, file, cfg, stats, info, m);
        }
    }

    private static RunStats receiveGbn(
            DatagramSocket socket,
            Path file,
            ProtocolConfig cfg,
            RunStats stats,
            Session.MetaInfo info,
            long m) throws IOException {

        long expected = 1;

        try (var output = new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024)) {
            while (true) {
                Received received;

                try {
                    received = receive(socket, cfg);
                }
                catch (CorruptPacketException e) {
                    stats.onCorruptDropped();
                    continue;
                }

                Packet packet = received.packet;

                if (packet.type == Packet.TYPE_DATA
                        && packet.flags == 0x01) {
                    // Duplicate META: re-ACK it in case the original ACK was lost.
                    sendAck(
                            socket,
                            received.datagram,
                            0,
                            cfg.getWindowSize());
                    continue;
                }

                if (packet.type == Packet.TYPE_FIN) {
                    if (packet.seq == expected) {
                        output.flush();

                        boolean match =
                                Session.verifySha256(
                                        file, info.sha256);

                    stats.setShaMatch(match);

                    sendFinAck(
                            socket,
                            received.datagram,
                            packet,
                            match);

                    lingerFin(
                            socket,
                            cfg,
                            received.datagram,
                            packet,
                            match);

                    return stats;
                    }

                    sendAck(
                            socket,
                            received.datagram,
                            expected,
                            cfg.getWindowSize());

                    continue;
                }

                if (packet.type != Packet.TYPE_DATA
                        || packet.flags != 0) {
                    continue;
                }

                if (packet.seq == expected) {
                    output.write(packet.payload);
                    expected = inc(expected, m);
                }

                // GBN cumulative ACK = next expected sequence number.
                sendAck(
                        socket,
                        received.datagram,
                        expected,
                        cfg.getWindowSize());
            }
        }
    }

    private static RunStats receiveSr(
            DatagramSocket socket,
            Path file,
            ProtocolConfig cfg,
            RunStats stats,
            Session.MetaInfo info,
            long m) throws IOException {

        long base = 1;
        Map<Long, Packet> buffer = new HashMap<>();
        Set<Long> delivered = new HashSet<>();

        try (var output = Files.newOutputStream(file)) {
            while (true) {
                Received received;

                try {
                    received = receive(socket, cfg);
                }
                catch (CorruptPacketException e) {
                    stats.onCorruptDropped();
                    continue;
                }

                Packet packet = received.packet;

                if (packet.type == Packet.TYPE_DATA
                        && packet.flags == 0x01) {
                    // Duplicate META: re-ACK it in case the original ACK was lost.
                    sendAck(
                            socket,
                            received.datagram,
                            0,
                            cfg.getWindowSize());
                    continue;
                }

                if (packet.type == Packet.TYPE_FIN) {
                    if (packet.seq == base) {                    boolean match =
                            Session.verifySha256(
                                    file, info.sha256);

                    stats.setShaMatch(match);

                    sendFinAck(
                            socket,
                            received.datagram,
                            packet,
                            match);

                    lingerFin(
                            socket,
                            cfg,
                            received.datagram,
                            packet,
                            match);

                    return stats;
                    }

                    sendAck(
                            socket,
                            received.datagram,
                            packet.seq,
                            cfg.getWindowSize());

                    continue;
                }

                if (packet.type != Packet.TYPE_DATA
                        || packet.flags != 0) {
                    continue;
                }

                if (SeqSpace.inCurrentWindow(
                        packet.seq,
                        base,
                        cfg.getWindowSize(),
                        m)) {

                    buffer.putIfAbsent(packet.seq, packet);

                    // SR ACK identifies the received packet.
                    sendAck(
                            socket,
                            received.datagram,
                            packet.seq,
                            cfg.getWindowSize());

                    while (buffer.containsKey(base)) {
                        Packet ready = buffer.remove(base);
                        output.write(ready.payload);
                        delivered.add(ready.seq);
                        base = inc(base, m);
                    }
                }
                else if (SeqSpace.inPreviousWindow(
                        packet.seq,
                        base,
                        cfg.getWindowSize(),
                        m)
                        || delivered.contains(packet.seq)) {

                    // Re-ACK packets whose earlier ACK may have been lost.
                    sendAck(
                            socket,
                            received.datagram,
                            packet.seq,
                            cfg.getWindowSize());
                }
            }
        }
    }

    private static void sendMetaAndWait(
            DatagramSocket socket,
            InetSocketAddress peer,
            Path file,
            ProtocolConfig cfg,
            boolean fixed,
            long fixedRtoMs,
            RunStats stats) throws IOException {

        Packet meta = Session.createMetaPacket(file);

        while (true) {
            sendPacket(socket, peer, meta);

            try {
                long rto = fixed ? fixedRtoMs : 1000;

                socket.setSoTimeout(
                        (int) Math.min(
                                Integer.MAX_VALUE,
                                Math.max(1, rto)));

                Received received = receive(socket, cfg);
                Packet ack = received.packet;

                if (ack.type == Packet.TYPE_ACK
                        && ack.ack == 0) {
                    return;
                }
            }
            catch (SocketTimeoutException e) {
                // Retry META.
            }
            catch (CorruptPacketException e) {
                stats.onCorruptDropped();
            }
        }
    }

    private static void sendFinAndWait(
            DatagramSocket socket,
            InetSocketAddress peer,
            long seq,
            ProtocolConfig cfg,
            RunStats stats) throws IOException {

        Packet fin = Session.createFinPacket(seq);

        for (int attempt = 0;
             attempt < FIN_RETRIES;
             attempt++) {

            sendPacket(socket, peer, fin);

            long deadline = System.nanoTime()
                    + 1_000_000_000L;

            while (true) {
                long remainingNanos =
                        deadline - System.nanoTime();

                if (remainingNanos <= 0) {
                    break;
                }

                int timeoutMs = (int) Math.max(
                        1,
                        Math.min(
                                1000,
                                (remainingNanos + 999_999L)
                                        / 1_000_000L));

                socket.setSoTimeout(timeoutMs);

                try {
                    Received received = receive(socket, cfg);
                    Packet packet = received.packet;

                    if (packet.type != Packet.TYPE_FINACK
                            || packet.seq != seq) {
                        // Ignore delayed/stale ACKs or unrelated packets.
                        continue;
                    }

                    String result =
                            new String(
                                    packet.payload,
                                    StandardCharsets.UTF_8);

                    boolean match =
                            Boolean.parseBoolean(result);

                    stats.setShaMatch(match);

                    if (!match) {
                        throw new IOException(
                                "sr: receiver SHA-256 mismatch");
                    }

                    return;
                }
                catch (SocketTimeoutException e) {
                    break;
                }
                catch (CorruptPacketException e) {
                    stats.onCorruptDropped();
                }
            }
        }

        throw new IOException(
                "sr: FINACK timeout after "
                + FIN_RETRIES + " attempts");
    }

    private static void lingerFin(

            DatagramSocket socket,
            ProtocolConfig cfg,
            DatagramPacket peer,
            Packet fin,
            boolean shaMatch) throws IOException {

        socket.setSoTimeout(1000);

        for (int i = 0; i < FIN_RETRIES; i++) {
            try {
                Received received = receive(socket, cfg);

                if (received.packet.type == Packet.TYPE_FIN
                        && received.packet.seq == fin.seq) {

                    sendFinAck(
                            socket,
                            received.datagram,
                            fin,
                            shaMatch);
                }
            }
            catch (SocketTimeoutException e) {
                return;
            }
            catch (CorruptPacketException e) {
                // Ignore corrupted linger traffic.
            }
        }
    }

    private static void sendFinAck(
            DatagramSocket socket,
            DatagramPacket peer,
            Packet fin,
            boolean shaMatch) throws IOException {

        byte[] raw =
                Session.createFinAckPacket(
                        fin.seq,
                        shaMatch).encode();

        socket.send(
                new DatagramPacket(
                        raw,
                        raw.length,
                        peer.getAddress(),
                        peer.getPort()));
    }

    private static void sendAck(
            DatagramSocket socket,
            DatagramPacket peer,
            long ack,
            int window) throws IOException {

        byte[] raw =
                Packet.ack(ack, window).encode();

        socket.send(
                new DatagramPacket(
                        raw,
                        raw.length,
                        peer.getAddress(),
                        peer.getPort()));
    }

    private static void sendPacket(
            DatagramSocket socket,
            InetSocketAddress peer,
            Packet packet) throws IOException {

        byte[] raw = packet.encode();

        socket.send(
                new DatagramPacket(
                        raw,
                        raw.length,
                        peer));
    }

    private static Received receive(
            DatagramSocket socket,
            ProtocolConfig cfg)
            throws IOException, CorruptPacketException {

        byte[] buffer =
                new byte[
                        Packet.HEADER_LEN
                                + cfg.getPayloadSize()];

        DatagramPacket datagram =
                new DatagramPacket(
                        buffer,
                        buffer.length);

        socket.receive(datagram);

        Packet packet =
                Packet.decode(
                        datagram.getData(),
                        datagram.getLength());

        return new Received(packet, datagram);
    }

    private static void validate(
            ProtocolConfig cfg,
            Mode mode) {

        int bits = cfg.getSequenceBits();

        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException(
                    "seqbits must be between 1 and 32");
        }

        long m = 1L << bits;
        int window = cfg.getWindowSize();

        if (window < 1) {
            throw new IllegalArgumentException(
                    "window must be positive");
        }

        if (mode == Mode.GBN && window > m - 1) {
            throw new IllegalArgumentException(
                    "GBN requires W <= 2^k - 1");
        }

        if (mode == Mode.SR && window > m / 2) {
            throw new IllegalArgumentException(
                    "Selective Repeat requires W <= 2^(k-1)");
        }

        if (cfg.getPayloadSize() <= 0
                || cfg.getPayloadSize() > 1400) {
            throw new IllegalArgumentException(
                    "payload size must be 1..1400");
        }
    }

    private static long fixedRto(ProtocolConfig cfg) {
        if (!cfg.getRtoMode().startsWith("fixed:")) {
            return 0;
        }

        if (cfg.getBaseRttMs() <= 0) {
            throw new IllegalArgumentException(
                    "fixed RTO requires a positive base RTT");
        }

        double multiplier =
                Double.parseDouble(
                        cfg.getRtoMode().substring(6));

        return Math.max(
                1,
                Math.round(
                        multiplier * cfg.getBaseRttMs()));
    }

    private static long timeoutFor(
            Map<Long, Sent> outstanding,
            long base,
            long rtoMs,
            Mode mode) {

        if (mode == Mode.GBN) {
            Sent first = outstanding.get(base);

            if (first == null) {
                return rtoMs;
            }

            long elapsed =
                    (System.nanoTime() - first.sentNanos)
                            / 1_000_000L;

            return Math.max(1, rtoMs - elapsed);
        }

        long now = System.nanoTime();
        long minimum = rtoMs;

        for (Sent sent : outstanding.values()) {
            long elapsed =
                    (now - sent.sentNanos)
                            / 1_000_000L;

            minimum =
                    Math.min(
                            minimum,
                            Math.max(1, rtoMs - elapsed));
        }

        return Math.max(1, minimum);
    }

    private static List<Sent> expired(
            Map<Long, Sent> outstanding,
            long now,
            long rtoMs) {

        List<Sent> result = new ArrayList<>();

        for (Sent sent : outstanding.values()) {
            long elapsed =
                    (now - sent.sentNanos)
                            / 1_000_000L;

            if (elapsed >= rtoMs) {
                result.add(sent);
            }
        }

        return result;
    }

    private static boolean isGbnAckValid(
            long ack,
            long base,
            Map<Long, Sent> outstanding,
            long m) {

        long distance =
                SeqSpace.offset(
                        ack,
                        base,
                        m);

        return distance > 0
                && distance <= outstanding.size();
    }

    private static long firstOutstanding(
            Map<Long, Sent> outstanding,
            long fallback,
            long m) {

        if (outstanding.isEmpty()) {
            return fallback;
        }

        long best = fallback;
        long bestDistance = Long.MAX_VALUE;

        for (long seq : outstanding.keySet()) {
            long distance =
                    SeqSpace.offset(
                            seq,
                            fallback,
                            m);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = seq;
            }
        }

        return best;
    }

    private static long inc(long value, long m) {
        return (value + 1) % m;
    }

    private static final class Sent {
        final Packet packet;
        final long sentNanos;
        final boolean retransmitted;

        Sent(Packet packet,
             long sentNanos,
             boolean retransmitted) {
            this.packet = packet;
            this.sentNanos = sentNanos;
            this.retransmitted = retransmitted;
        }
    }

    private static final class Received {
        final Packet packet;
        final DatagramPacket datagram;

        Received(Packet packet,
                 DatagramPacket datagram) {
            this.packet = packet;
            this.datagram = datagram;
        }
    }
}
