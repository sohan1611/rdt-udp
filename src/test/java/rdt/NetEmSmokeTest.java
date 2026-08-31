package rdt;

import static rdt.Harness.assertEquals;
import static rdt.Harness.assertTrue;

import emulator.ChannelConfig;
import emulator.NetEm;
import emulator.TraceLog;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end tests that drive the real {@link NetEm} middlebox over real UDP
 * sockets.
 *
 * <p>{@link ChannelTest} covers the impairment model in isolation. This covers
 * the part that model cannot: the selector loop, the release queue, learning
 * the sender's address, and forwarding in both directions. Between them, a bug
 * in either half shows up somewhere.
 *
 * <p>Everything binds to port 0 and to the loopback interface, so these tests
 * never collide with a real service or with each other.
 */
public final class NetEmSmokeTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    public static void main(String[] args) throws Exception {
        Harness h = new Harness("NetEmSmokeTest");

        h.check("forwards every packet through a perfect channel", () -> {
            try (Fixture f = new Fixture("", "", 1, null)) {
                for (int i = 0; i < 200; i++) {
                    f.send(i);
                }
                List<Integer> got = f.drainReceiver(200, 2000);
                assertEquals("all packets should arrive", 200, got.size());
                for (int i = 0; i < 200; i++) {
                    assertEquals("order should be preserved at index " + i, i, got.get(i));
                }
            }
        });

        h.check("drops roughly the configured fraction", () -> {
            try (Fixture f = new Fixture("loss=0.3", "", 4242, null)) {
                int n = 1000;
                for (int i = 0; i < n; i++) {
                    f.send(i);
                }
                int arrived = f.drainReceiver(n, 3000).size();
                double lossRate = 1.0 - (double) arrived / n;
                assertTrue(String.format("loss %.3f should be near 0.30", lossRate),
                        Math.abs(lossRate - 0.30) < 0.06);
            }
        });

        h.check("carries the reverse path back to the sender", () -> {
            // The middlebox has to learn the sender address from the first
            // packet before it can route a reply.
            try (Fixture f = new Fixture("", "", 9, null)) {
                f.send(1);
                assertEquals("forward packet arrived", 1, f.drainReceiver(1, 2000).size());
                f.replyFromReceiver(77);
                Integer back = f.awaitSender(2000);
                assertTrue("a reply should come back to the sender", back != null);
                assertEquals("reply payload", 77, back.intValue());
            }
        });

        h.check("applies the configured delay", () -> {
            try (Fixture f = new Fixture("delay=60", "", 5, null)) {
                long t0 = System.nanoTime();
                f.send(1);
                assertEquals("packet arrived", 1, f.drainReceiver(1, 3000).size());
                double elapsedMs = (System.nanoTime() - t0) / 1e6;
                assertTrue("elapsed " + (long) elapsedMs + "ms should be at least 55",
                        elapsedMs >= 55.0);
                assertTrue("elapsed " + (long) elapsedMs + "ms should be well under 1000",
                        elapsedMs < 1000.0);
            }
        });

        h.check("delivers duplicates twice", () -> {
            try (Fixture f = new Fixture("dup=1.0", "", 3, null)) {
                for (int i = 0; i < 50; i++) {
                    f.send(i);
                }
                assertEquals("every packet should arrive twice",
                        100, f.drainReceiver(100, 3000).size());
            }
        });

        h.check("writes a readable JSONL trace", () -> {
            Path trace = Files.createTempFile("netem-trace", ".jsonl");
            try (Fixture f = new Fixture("loss=0.2,delay=5", "", 31, trace)) {
                for (int i = 0; i < 100; i++) {
                    f.send(i);
                }
                f.drainReceiver(100, 2000);
                f.stopAndJoin();

                List<String> lines = Files.readAllLines(trace);
                assertTrue("trace should have a line per decision, got " + lines.size(),
                        lines.size() >= 90);
                for (String line : lines) {
                    assertTrue("line should be a JSON object: " + line,
                            line.startsWith("{") && line.endsWith("}"));
                    assertTrue("line should name a direction: " + line, line.contains("\"dir\""));
                    assertTrue("line should name an action: " + line, line.contains("\"action\""));
                }
                assertTrue("some packets should have been dropped",
                        lines.stream().anyMatch(l -> l.contains("\"drop\"")));
            } finally {
                Files.deleteIfExists(trace);
            }
        });

        h.done();
    }

    /**
     * A running middlebox plus a sender socket and a receiver socket, all on
     * loopback with ephemeral ports.
     */
    private static final class Fixture implements AutoCloseable {
        private final NetEm netem;
        private final Thread thread;
        private final TraceLog trace;
        private final DatagramSocket sender;
        private final DatagramSocket receiver;
        private final InetSocketAddress netemAddr;

        Fixture(String upSpec, String downSpec, long seed, Path tracePath) throws Exception {
            receiver = new DatagramSocket(0, LOOPBACK);
            receiver.setSoTimeout(50);
            sender = new DatagramSocket(0, LOOPBACK);
            sender.setSoTimeout(50);

            trace = new TraceLog(tracePath);
            netem = new NetEm(
                    new InetSocketAddress(LOOPBACK, 0),
                    new InetSocketAddress(LOOPBACK, receiver.getLocalPort()),
                    ChannelConfig.parse(upSpec), ChannelConfig.parse(downSpec),
                    seed, trace, false);

            thread = new Thread(() -> {
                try {
                    netem.run();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, "netem-test");
            thread.setDaemon(true);
            thread.start();

            if (!netem.awaitReady(5000)) {
                throw new IllegalStateException("netem did not bind within 5s");
            }
            netemAddr = new InetSocketAddress(LOOPBACK, netem.boundPort());
        }

        /** Sends a four-byte payload carrying {@code value}, via the middlebox. */
        void send(int value) throws IOException {
            byte[] b = encode(value);
            sender.send(new DatagramPacket(b, b.length, netemAddr));
        }

        void replyFromReceiver(int value) throws IOException {
            byte[] b = encode(value);
            receiver.send(new DatagramPacket(b, b.length, netemAddr));
        }

        /** Collects up to {@code max} payloads, giving up after {@code budgetMs}. */
        List<Integer> drainReceiver(int max, long budgetMs) throws IOException {
            List<Integer> out = new ArrayList<>();
            long deadline = System.nanoTime() + budgetMs * 1_000_000L;
            byte[] buf = new byte[64];
            while (out.size() < max && System.nanoTime() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    receiver.receive(p);
                    out.add(decode(p.getData()));
                } catch (SocketTimeoutException e) {
                    // Keep waiting until the budget runs out; on a lossy
                    // channel the expected count may never arrive.
                }
            }
            return out;
        }

        Integer awaitSender(long budgetMs) throws IOException {
            long deadline = System.nanoTime() + budgetMs * 1_000_000L;
            byte[] buf = new byte[64];
            while (System.nanoTime() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sender.receive(p);
                    return decode(p.getData());
                } catch (SocketTimeoutException e) {
                    // retry until the budget runs out
                }
            }
            return null;
        }

        /** Stops the loop and waits for the trace to be flushed. */
        void stopAndJoin() throws Exception {
            netem.stop();
            thread.join(3000);
            trace.close();
        }

        private static byte[] encode(int v) {
            return String.format("%08d", v).getBytes(StandardCharsets.US_ASCII);
        }

        private static int decode(byte[] b) {
            return Integer.parseInt(new String(b, 0, 8, StandardCharsets.US_ASCII));
        }

        @Override
        public void close() throws Exception {
            netem.stop();
            thread.join(3000);
            try {
                trace.close();
            } catch (IOException ignored) {
                // already closed by stopAndJoin in some tests
            }
            sender.close();
            receiver.close();
        }
    }
}
