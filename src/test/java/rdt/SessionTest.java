
package rdt;

import emulator.ChannelConfig;
import emulator.NetEm;
import emulator.TraceLog;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class SessionTest
{
    private static final InetSocketAddress LOOPBACK =
            new InetSocketAddress("127.0.0.1", 0);

    private SessionTest()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Harness h = new Harness("SessionTest");

        h.check(
                "transfer survives FINACK loss",
                SessionTest::transferWithFinAckLoss
        );

        h.check(
                "transfer survives packet reordering",
                SessionTest::transferWithReordering
        );

        h.check(
                "GBN transfer survives packet loss",
                SessionTest::transferWithGbnPacketLoss
        );

        h.done();
    }

    private static void transferWithFinAckLoss() throws Exception
    {
        Path input = Files.createTempFile(
                "rdt-session-input",
                ".bin"
        );
        Path output = Files.createTempFile(
                "rdt-session-output",
                ".bin"
        );
        Path tracePath = Files.createTempFile(
                "rdt-session-trace",
                ".jsonl"
        );

        try
        {
            byte[] data = (
                    "SessionTest verifies FIN/FINACK retransmission "
                            + "when receiver-to-sender packets are lost.\n"
            ).getBytes(StandardCharsets.UTF_8);

            Files.write(input, data);
            Files.deleteIfExists(output);

            DatagramSocket receiverSocket =
                    new DatagramSocket(0);

            int receiverPort = receiverSocket.getLocalPort();
            receiverSocket.close();

            TraceLog trace = new TraceLog(tracePath);

            NetEm netem = new NetEm(
                    new InetSocketAddress("127.0.0.1", 0),
                    new InetSocketAddress(
                            "127.0.0.1",
                            receiverPort
                    ),
                    ChannelConfig.parse(""),
                    ChannelConfig.parse("loss=0.5"),
                    7L,
                    trace,
                    false
            );

            Thread netemThread = new Thread(
                    () ->
                    {
                        try
                        {
                            netem.run();
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    },
                    "session-test-netem"
            );

            netemThread.setDaemon(true);
            netemThread.start();

            if (!netem.awaitReady(5000))
            {
                throw new AssertionError(
                        "NetEm did not become ready"
                );
            }

            InetSocketAddress netemAddress =
                    new InetSocketAddress(
                            "127.0.0.1",
                            netem.boundPort()
                    );

            ProtocolConfig config =
                    new ProtocolConfig(
                            1,
                            "fixed:1.0",
                            32,
                            1400,
                            40
                    );

            StopAndWait receiver = new StopAndWait();

            Thread receiverThread = new Thread(
                    () ->
                    {
                        try
                        {
                            receiver.receive(
                                    output,
                                    receiverPort,
                                    config
                            );
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    },
                    "session-test-receiver"
            );

            receiverThread.start();

            StopAndWait sender = new StopAndWait();

            sender.send(
                    input,
                    netemAddress,
                    config
            );

            receiverThread.join(10000);

            if (receiverThread.isAlive())
            {
                throw new AssertionError(
                        "receiver did not finish"
                );
            }

            netem.stop();
            netemThread.join(3000);
            trace.close();

            Harness.assertTrue(
                    "received file exists",
                    Files.exists(output)
            );

            Harness.assertEquals(
                    "received file contents",
                    data,
                    Files.readAllBytes(output)
            );
        }
        finally
        {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(tracePath);
        }
    }

    private static void transferWithReordering() throws Exception
    {
        Path input = Files.createTempFile(
                "rdt-reorder-input",
                ".bin"
        );
        Path output = Files.createTempFile(
                "rdt-reorder-output",
                ".bin"
        );
        Path tracePath = Files.createTempFile(
                "rdt-reorder-trace",
                ".jsonl"
        );

        NetEm netem = null;
        TraceLog trace = null;
        Thread netemThread = null;
        Thread receiverThread = null;

        try
        {
            byte[] data = new byte[1400 * 20 + 317];

            for (int i = 0; i < data.length; i++)
            {
                data[i] = (byte) ((i * 31 + i / 7) & 0xff);
            }

            Files.write(input, data);
            Files.deleteIfExists(output);

            DatagramSocket receiverSocket =
                    new DatagramSocket(0);

            int receiverPort = receiverSocket.getLocalPort();
            receiverSocket.close();

            trace = new TraceLog(tracePath);

            netem = new NetEm(
                    new InetSocketAddress("127.0.0.1", 0),
                    new InetSocketAddress(
                            "127.0.0.1",
                            receiverPort
                    ),
                    ChannelConfig.parse(
                            "reorder=0.05,delay=20,jitter=5"
                    ),
                    ChannelConfig.parse(
                            "reorder=0.05,delay=20,jitter=5"
                    ),
                    4242L,
                    trace,
                    false
            );

            NetEm activeNetem = netem;

            netemThread = new Thread(
                    () ->
                    {
                        try
                        {
                            activeNetem.run();
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    },
                    "session-reorder-netem"
            );

            netemThread.setDaemon(true);
            netemThread.start();

            if (!netem.awaitReady(5000))
            {
                throw new AssertionError(
                        "NetEm did not become ready"
                );
            }

            InetSocketAddress netemAddress =
                    new InetSocketAddress(
                            "127.0.0.1",
                            netem.boundPort()
                    );

            ProtocolConfig config =
                    new ProtocolConfig(
                            1,
                            "fixed:1.0",
                            32,
                            1400,
                            40
                    );

            StopAndWait receiver = new StopAndWait();

            receiverThread = new Thread(
                    () ->
                    {
                        try
                        {
                            receiver.receive(
                                    output,
                                    receiverPort,
                                    config
                            );
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    },
                    "session-reorder-receiver"
            );

            receiverThread.setDaemon(true);
            receiverThread.start();

            StopAndWait sender = new StopAndWait();

            sender.send(
                    input,
                    netemAddress,
                    config
            );

            receiverThread.join(30000);

            if (receiverThread.isAlive())
            {
                throw new AssertionError(
                        "receiver did not finish"
                );
            }

            Harness.assertTrue(
                    "received file exists",
                    Files.exists(output)
            );

            Harness.assertEquals(
                    "reordered transfer preserves exact file contents",
                    data,
                    Files.readAllBytes(output)
            );
        }
        finally
        {
            if (netem != null)
            {
                netem.stop();
            }

            if (receiverThread != null)
            {
                receiverThread.join(3000);
            }

            if (netemThread != null)
            {
                netemThread.join(3000);
            }

            if (trace != null)
            {
                trace.close();
            }

            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(tracePath);
        }
    }

    private static void transferWithGbnPacketLoss() throws Exception
    {
        Path input = Files.createTempFile("rdt-gbn-input", ".bin");
        Path output = Files.createTempFile("rdt-gbn-output", ".bin");
        Path tracePath = Files.createTempFile("rdt-gbn-trace", ".jsonl");

        NetEm netem = null;
        TraceLog trace = null;
        Thread netemThread = null;
        Thread receiverThread = null;

        try
        {
            byte[] data = new byte[1400 * 8 + 317];
            for (int i = 0; i < data.length; i++)
            {
                data[i] = (byte) ((i * 31 + i / 7) & 0xff);
            }

            Files.write(input, data);
            Files.deleteIfExists(output);

            DatagramSocket socket = new DatagramSocket(0);
            int receiverPort = socket.getLocalPort();
            socket.close();

            trace = new TraceLog(tracePath);
            netem = new NetEm(
                    new InetSocketAddress("127.0.0.1", 0),
                    new InetSocketAddress("127.0.0.1", receiverPort),
                    ChannelConfig.parse("loss=0.2,delay=10"),
                    ChannelConfig.parse("loss=0.2,delay=10"),
                    4242L,
                    trace,
                    false
            );

            NetEm activeNetem = netem;
            netemThread = new Thread(() ->
            {
                try
                {
                    activeNetem.run();
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }, "session-gbn-netem");

            netemThread.setDaemon(true);
            netemThread.start();

            if (!netem.awaitReady(5000))
            {
                throw new AssertionError("NetEm did not become ready");
            }

            ProtocolConfig config = new ProtocolConfig(
                    4, "fixed:1.0", 32, 1400, 40
            );

            final Throwable[] receiverFailure = new Throwable[1];

            receiverThread = new Thread(() ->
            {
                try
                {
                    app.Receiver.main(new String[] {
                            "--port", Integer.toString(receiverPort),
                            "--out", output.toString(),
                            "--protocol", "gbn",
                            "--window", "4",
                            "--rto", "fixed:1.0",
                            "--seqbits", "32"
                    });
                }
                catch (Throwable e)
                {
                    receiverFailure[0] = e;
                }
            }, "session-gbn-receiver");

            receiverThread.setDaemon(true);
            receiverThread.start();

            app.Sender.main(new String[] {
                    "--file", input.toString(),
                    "--to", "127.0.0.1:" + netem.boundPort(),
                    "--protocol", "gbn",
                    "--window", "4",
                    "--rto", "fixed:1.0",
                    "--seqbits", "32",
                    "--base-rtt-ms", "40"
            });

            receiverThread.join(60000);
            if (receiverThread.isAlive())
            {
                throw new AssertionError("GBN receiver did not finish");
            }

            if (netem != null) netem.stop();
            if (netemThread != null) netemThread.join(3000);
            if (trace != null)
            {
                trace.close();
                trace = null;
            }

            if (receiverFailure[0] != null)
            {
                throw new AssertionError(
                        "GBN application receiver failed",
                        receiverFailure[0]
                );
            }

            Harness.assertTrue("GBN output file exists", Files.exists(output));
            Harness.assertEquals(
                    "GBN packet-loss transfer preserves exact contents",
                    data,
                    Files.readAllBytes(output)
            );

            java.util.List<String> traceLines =
                    Files.readAllLines(tracePath);
            Harness.assertTrue(
                    "GBN integration test must observe an actual packet drop",
                    traceLines.stream().anyMatch(
                            line -> line.contains("\"drop\"")
                    )
            );
        }
        finally
        {
            if (netem != null) netem.stop();
            if (receiverThread != null) receiverThread.join(3000);
            if (netemThread != null) netemThread.join(3000);
            if (trace != null) trace.close();

            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(tracePath);
        }
    }

}
