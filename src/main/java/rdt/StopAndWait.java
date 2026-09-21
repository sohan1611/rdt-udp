package rdt;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

import java.nio.file.Files;
import java.nio.file.Path;

public final class StopAndWait implements ArqProtocol
{
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

        stats.start();

        String expectedSha256 =
                Session.parseMeta(
                        Session.createMeta(file)
                ).sha256;

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

                socket.send(metaDatagram);
                stats.onDataSent(metaData.length, true);
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

                socket.send(datagram);
                stats.onDataSent(data.length, false);

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

        while (!finAcknowledged)
        {
            socket.send(finDatagram);

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

        stats.setShaMatch(
                Session.verifySha256(
                        file,
                        expectedSha256
                )
        );

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
                    Packet finAckPacket =
                            Session.createFinAckPacket(packet.seq);

                    byte[] finAckData = finAckPacket.encode();

                    DatagramPacket finAck = new DatagramPacket(
                            finAckData,
                            finAckData.length,
                            datagram.getAddress(),
                            datagram.getPort()
                    );

                                        socket.send(finAck);

                    if (expectedSha256 != null)
                    {
                        stats.setShaMatch(
                                Session.verifySha256(
                                        file,
                                        expectedSha256
                                )
                        );
                    }

                    break;
                }
                                if (packet.type == Packet.TYPE_DATA
                        && packet.flags == 0x01)
                {
                   Session.MetaInfo meta = Session.parseMeta(packet.payload);
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

                    stats.onAck(false);

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

                    stats.onAck(false);

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

                    stats.onAck(true);
                }
            }

            return stats;
        }
    }
}