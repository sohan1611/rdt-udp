package rdt;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stop-and-Wait uses alternating-bit sequence numbers.
 * The shared seqbits configuration is reported for consistency
 * across protocols, but only one sequence bit is needed here.
 */
public final class StopAndWait implements ArqProtocol
{
    private static final int FIN_RETRIES = 3;

    @Override
    public RunStats send(Path file, InetSocketAddress peer,
                         ProtocolConfig config) throws IOException
    {
        try (DatagramSocket socket = new DatagramSocket())
        {
            socket.setSoTimeout(1000);

            long fileBytes = Files.size(file);

            RunStats stats = new RunStats(
                    "stopwait",
                    config.getWindowSize(),
                    config.getSequenceBits(),
                    config.getRtoMode(),
                    fileBytes
            );

            Packet metaPacket = Session.createMetaPacket(file);
            byte[] metaData = metaPacket.encode();

            DatagramPacket metaDatagram = new DatagramPacket(
                    metaData,
                    metaData.length,
                    peer
            );

            boolean metaAcknowledged = false;

            while (!metaAcknowledged)
            {
                socket.send(metaDatagram);
                stats.onDataSent(metaData.length, false);

                try
                {
                    byte[] ackBuffer = new byte[
                            Packet.HEADER_LEN
                                    + config.getPayloadSize()
                    ];

                    DatagramPacket ackDatagram =
                            new DatagramPacket(
                                    ackBuffer,
                                    ackBuffer.length
                            );

                    socket.receive(ackDatagram);

                    try
                    {
                        Packet ackPacket = Packet.decode(
                                ackDatagram.getData(),
                                ackDatagram.getLength()
                        );

                        if (ackPacket.type != Packet.TYPE_ACK)
                        {
                            continue;
                        }

                        if (ackPacket.ack != 0)
                        {
                            continue;
                        }

                        stats.onAck(false);
                        metaAcknowledged = true;
                    }
                    catch (CorruptPacketException e)
                    {
                        stats.onCorruptDropped();
                    }
                }
                catch (SocketTimeoutException e)
                {
                    stats.onTimeout();
                }
            }

            long seq = 1;

            byte[] buffer = new byte[config.getPayloadSize()];

            try (InputStream input = Files.newInputStream(file))
            {
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1)
                {
                    byte[] payload = new byte[bytesRead];

                    System.arraycopy(
                            buffer,
                            0,
                            payload,
                            0,
                            bytesRead
                    );

                    Packet packet = new Packet(
                            Packet.TYPE_DATA,
                            seq,
                            0,
                            0,
                            0,
                            payload
                    );

                    byte[] data = packet.encode();

                    DatagramPacket datagram = new DatagramPacket(
                            data,
                            data.length,
                            peer
                    );

                   boolean acknowledged = false;

                        if (seq == 1)
                        {
                        stats.start();
                        }

                        socket.send(datagram);
                    while (!acknowledged)
                    {
                        try
                        {
                            byte[] ackBuffer = new byte[
                                    Packet.HEADER_LEN
                                            + config.getPayloadSize()
                            ];

                            DatagramPacket ackDatagram =
                                    new DatagramPacket(
                                            ackBuffer,
                                            ackBuffer.length
                                    );

                            socket.receive(ackDatagram);

                            try
                            {
                                Packet ackPacket = Packet.decode(
                                        ackDatagram.getData(),
                                        ackDatagram.getLength()
                                );

                                if (ackPacket.type != Packet.TYPE_ACK)
                                {
                                    continue;
                                }

                                if (ackPacket.ack != seq)
                                {
                                    continue;
                                }

                                stats.onAck(false);
                                acknowledged = true;
                            }
                            catch (CorruptPacketException e)
                            {
                                stats.onCorruptDropped();
                            }
                        }
                        catch (SocketTimeoutException e)
                        {
                            stats.onTimeout();

                            socket.send(datagram);
                            stats.onDataSent(data.length, true);
                        }
                    }

                    seq = 1 - seq;
                }
            }

            Packet finPacket = Session.createFinPacket(seq);
            byte[] finData = finPacket.encode();

            DatagramPacket finDatagram = new DatagramPacket(
                    finData,
                    finData.length,
                    peer
            );

            boolean finAcknowledged = false;
            int finAttempts = 0;

            while (!finAcknowledged && finAttempts < FIN_RETRIES)
            {
                socket.send(finDatagram);
                finAttempts++;

                try
                {
                    byte[] finAckBuffer = new byte[
                            Packet.HEADER_LEN
                                    + config.getPayloadSize()
                    ];

                    DatagramPacket finAckDatagram =
                            new DatagramPacket(
                                    finAckBuffer,
                                    finAckBuffer.length
                            );

                    socket.receive(finAckDatagram);

                    Packet finAckPacket = Packet.decode(
                            finAckDatagram.getData(),
                            finAckDatagram.getLength()
                    );

                    if (finAckPacket.type == Packet.TYPE_FINACK
                            && finAckPacket.seq == seq)
                    {
                        String shaResult = new String(
                                finAckPacket.payload,
                                StandardCharsets.UTF_8
                        );

                        stats.setShaMatch(
                                Boolean.parseBoolean(shaResult)
                        );

                        finAcknowledged = true;
                    }
                }
                catch (SocketTimeoutException e)
                {
                    stats.onTimeout();
                }
                catch (CorruptPacketException e)
                {
                    stats.onCorruptDropped();
                }
            }

            if (!finAcknowledged)
            {
                System.err.println(
                        "FINACK not received after "
                                + FIN_RETRIES
                                + " attempts; transfer already completed"
                );
            }

            stats.stop();

            return stats;
        }
    }

    @Override
    public RunStats receive(Path file, int port,
                            ProtocolConfig config) throws IOException
    {
        RunStats stats = new RunStats(
                "stopwait",
                config.getWindowSize(),
                config.getSequenceBits(),
                config.getRtoMode(),
                0
        );

        try (DatagramSocket socket = new DatagramSocket(port);
             OutputStream output = Files.newOutputStream(file))
        {
            long expectedSeq = 1;
            String expectedSha256 = null;

            while (true)
            {
                byte[] buffer = new byte[
                        Packet.HEADER_LEN
                                + config.getPayloadSize()
                ];

                DatagramPacket datagram = new DatagramPacket(
                        buffer,
                        buffer.length
                );

                socket.receive(datagram);

                Packet packet;

                try
                {
                    packet = Packet.decode(
                            datagram.getData(),
                            datagram.getLength()
                    );
                }
                catch (CorruptPacketException e)
                {
                    stats.onCorruptDropped();
                    continue;
                }

                if (packet.type == Packet.TYPE_FIN)
                {
                    boolean shaMatch = false;

                    if (expectedSha256 != null)
                    {
                        shaMatch = Session.verifySha256(
                                file,
                                expectedSha256
                        );

                        stats.setShaMatch(shaMatch);
                    }

                    Packet finAckPacket =
                            Session.createFinAckPacket(
                                    packet.seq,
                                    shaMatch
                            );

                    byte[] finAckData = finAckPacket.encode();

                    DatagramPacket finAck = new DatagramPacket(
                            finAckData,
                            finAckData.length,
                            datagram.getAddress(),
                            datagram.getPort()
                    );

                    socket.send(finAck);

                    socket.setSoTimeout(1000);

                    int lingerTimeouts = 0;

                    while (lingerTimeouts < FIN_RETRIES)
                    {
                        try
                        {
                            byte[] lingerBuffer = new byte[
                                    Packet.HEADER_LEN
                                            + config.getPayloadSize()
                            ];

                            DatagramPacket lingerDatagram =
                                    new DatagramPacket(
                                            lingerBuffer,
                                            lingerBuffer.length
                                    );

                            socket.receive(lingerDatagram);

                            Packet lingerPacket;

                            try
                            {
                                lingerPacket = Packet.decode(
                                        lingerDatagram.getData(),
                                        lingerDatagram.getLength()
                                );
                            }
                            catch (CorruptPacketException e)
                            {
                                stats.onCorruptDropped();
                                continue;
                            }

                            if (lingerPacket.type == Packet.TYPE_FIN
                                    && lingerPacket.seq == packet.seq)
                            {
                                socket.send(finAck);
                                continue;
                            }
                        }
                        catch (SocketTimeoutException e)
                        {
                            lingerTimeouts++;
                        }
                    }

                    break;
                }

                if (packet.type == Packet.TYPE_DATA
                        && packet.flags == 0x01)
                {
                    Session.MetaInfo meta =
                            Session.parseMeta(packet.payload);

                    expectedSha256 = meta.sha256;

                    byte[] ackData =
                            Packet.ack(packet.seq, 0).encode();

                    DatagramPacket ack = new DatagramPacket(
                            ackData,
                            ackData.length,
                            datagram.getAddress(),
                            datagram.getPort()
                    );

                    socket.send(ack);

                    continue;
                }

                if (packet.type != Packet.TYPE_DATA)
                {
                    continue;
                }

                if (packet.seq == expectedSeq)
                {
                    output.write(packet.payload);

                    byte[] ackData =
                            Packet.ack(packet.seq, 0).encode();

                    DatagramPacket ack = new DatagramPacket(
                            ackData,
                            ackData.length,
                            datagram.getAddress(),
                            datagram.getPort()
                    );

                    socket.send(ack);

                    expectedSeq = 1 - expectedSeq;
                }
                else if (packet.seq == 1 - expectedSeq)
                {
                    byte[] ackData =
                            Packet.ack(packet.seq, 0).encode();

                    DatagramPacket ack = new DatagramPacket(
                            ackData,
                            ackData.length,
                            datagram.getAddress(),
                            datagram.getPort()
                    );

                    socket.send(ack);

                }
            }

            return stats;
        }
    }
}