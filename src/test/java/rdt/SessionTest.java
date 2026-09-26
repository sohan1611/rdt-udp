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
                            1400
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
}

