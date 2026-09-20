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
                            byte[] ackBuffer =
                                    new byte[
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

            while (true)
            {
                byte[] buffer =
                        new byte[
                                Packet.HEADER_LEN
                                        + config.getPayloadSize()
                        ];

                DatagramPacket datagram =
                        new DatagramPacket(
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

                if (packet.type != Packet.TYPE_DATA)
                {
                    continue;
                }

                if (packet.seq == expectedSeq)
                {
                    output.write(packet.payload);

                    byte[] ackData =
                            Packet.ack(packet.seq, 0).encode();

                    DatagramPacket ack =
                            new DatagramPacket(
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

                    DatagramPacket ack =
                            new DatagramPacket(
                                    ackData,
                                    ackData.length,
                                    datagram.getAddress(),
                                    datagram.getPort()
                            );

                    socket.send(ack);

                    stats.onAck(true);
                }
            }
        }
    }
}