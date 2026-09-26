package emulator;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
/**
 * Measures the packet rate the emulator can sustain without becoming the
 * bottleneck in an experiment. A rate is accepted when at least 99% of sent
 * packets arrive, and experiments stay below half that ceiling to leave
 * headroom for scheduling and runtime variation. The warm-up run is discarded
 * so JIT compilation does not affect measured steps. Large socket buffers reduce
 * the chance that the operating system, rather than NetEm, limits the result.
 */
public final class Calibrate {
    private static final int PAYLOAD = 1400;
    private static final double ACCEPTABLE_DELIVERY = 0.99;
    private static final double STEP_SECONDS = 2.0;
    private static final int WAIT_MS = 300;
    private static final int BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int[] RATES = {
            1_000, 2_000, 4_000, 8_000, 12_000, 16_000,
            24_000, 32_000, 48_000, 64_000, 96_000, 128_000, 192_000
    };
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private record Step(int rate, long sent, long received,
                        double achieved, double delivery, double mbps) {}
    public static void main(String[] args) throws Exception {
        Path out = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("results/calibration.csv");
        printEnvironment();
        List<Step> steps = new ArrayList<>();
        int ceiling = 0;
        try (Rig rig = new Rig()) {
            rig.runStep(4_000, 1.5);
            for (int rate : RATES) {
                Step step = rig.runStep(rate, STEP_SECONDS);
                steps.add(step);
                System.out.printf(
                        "%d pkt/s: sent=%d received=%d delivery=%.2f%%%n",
                        step.rate(),
                        step.sent(),
                        step.received(),
                        step.delivery() * 100.0
                );
                if (step.delivery() < ACCEPTABLE_DELIVERY) {
                    break;
                }
                ceiling = (int) Math.round(step.achieved());
            }
        }
        writeCsv(out, steps, ceiling);
        printSummary(ceiling);
    }

    private static void printEnvironment() {
        Runtime runtime = Runtime.getRuntime();
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println(
                "OS: " + System.getProperty("os.name") + " "
                        + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch")
        );
        System.out.println("Cores: " + runtime.availableProcessors());
        System.out.printf(
                "Max heap: %d MB%n",
                runtime.maxMemory() / (1024 * 1024)
        );
    }

    private static void writeCsv(Path out, List<Step> steps, int ceiling)
            throws IOException {
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        int budget = ceiling / 2;
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            writer.printf(
                    "# java=%s%n",
                    System.getProperty("java.version")
            );
            writer.printf(
                    "# os=%s %s %s%n",
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    System.getProperty("os.arch")
            );
            writer.printf(
                    "# cores=%d%n",
                    Runtime.getRuntime().availableProcessors()
            );
            writer.printf(
                    "# max_heap_mb=%d%n",
                    Runtime.getRuntime().maxMemory() / (1024 * 1024)
            );
            writer.printf("# payload_bytes=%d%n", PAYLOAD);
            writer.printf("# ceiling_pkts_per_sec=%d%n", ceiling);
            writer.printf("# budget_pkts_per_sec=%d%n", budget);
            writer.println(
                    "offered_pkts_per_sec,sent,received,"
                            + "achieved_pkts_per_sec,delivery_ratio,mbps"
            );
            for (Step step : steps) {
                writer.printf(
                        "%d,%d,%d,%.1f,%.6f,%.3f%n",
                        step.rate(),
                        step.sent(),
                        step.received(),
                        step.achieved(),
                        step.delivery(),
                        step.mbps()
                );
            }
        }
    }

    private static void printSummary(int ceiling) {
        if (ceiling == 0) {
            System.out.println("FAILED: no rate met the 99% delivery threshold.");
            return;
        }
        int budget = ceiling / 2;
        double mbps = budget * PAYLOAD * 8.0 / 1_000_000.0;
        System.out.printf("Ceiling: %,d pkt/s%n", ceiling);
        System.out.printf("Budget: %,d pkt/s%n", budget);
        System.out.printf("Budget: %.3f Mbps%n", mbps);
        if (budget < 4_500) {
            System.out.println(
                    "WARNING: budget is below the planned operating point of 4,500 pkt/s."
            );
        }
    }

    private static final class Rig implements AutoCloseable {
        private final DatagramSocket receiver;
        private final DatagramSocket sender;
        private final TraceLog trace;
        private final NetEm netem;
        private final Thread netemThread;
        private final Thread drainThread;
        private final AtomicLong received = new AtomicLong();
        private final InetSocketAddress netemAddr;
        private volatile boolean draining = true;
        Rig() throws Exception {
            receiver = new DatagramSocket(0, LOOPBACK);
            receiver.setReceiveBufferSize(BUFFER_SIZE);
            receiver.setSoTimeout(100);
            sender = new DatagramSocket(0, LOOPBACK);
            sender.setSendBufferSize(BUFFER_SIZE);
            trace = TraceLog.disabled();
            netem = new NetEm(
                    new InetSocketAddress(LOOPBACK, 0),
                    new InetSocketAddress(LOOPBACK, receiver.getLocalPort()),
                    ChannelConfig.perfect(),
                    ChannelConfig.perfect(),
                    1L,
                    trace,
                    false
            );
            netemThread = new Thread(this::runNetEm, "netem");
            netemThread.setDaemon(true);
            netemThread.start();
            if (!netem.awaitReady(5_000)) {
                throw new IllegalStateException("emulator did not start");
            }
            netemAddr = new InetSocketAddress(
                    LOOPBACK,
                    netem.boundPort()
            );
            drainThread = new Thread(this::drain, "drain");
            drainThread.setDaemon(true);
            drainThread.start();
        }

        private void runNetEm() {
            try {
                netem.run();
            } catch (IOException e) {
                if (draining) {
                    throw new RuntimeException(e);
                }
            }
        }

        private void drain() {
            byte[] buf = new byte[2048];
            while (draining) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    receiver.receive(packet);
                    received.incrementAndGet();
                } catch (SocketTimeoutException e) {
                } catch (IOException e) {
                    if (draining) {
                        throw new RuntimeException(e);
                    }
                    return;
                }
            }
        }

        Step runStep(int rate, double seconds) throws IOException {
            received.set(0);
            byte[] data = new byte[PAYLOAD];
            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    netemAddr
            );
            long sent = 0;
            long total = (long) (rate * seconds);
            long start = System.nanoTime();
            for (long i = 0; i < total; i++) {
                long deadline = start
                        + (long) ((i * 1_000_000_000.0) / rate);
                waitUntil(deadline);
                sender.send(packet);
                sent++;
            }
            long elapsedNanos = System.nanoTime() - start;
            try {
                Thread.sleep(WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long got = received.get();
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double achieved = sent / elapsedSeconds;
            double delivery = sent == 0 ? 0.0 : (double) got / sent;
            double mbps = got * PAYLOAD * 8.0
                    / elapsedSeconds / 1_000_000.0;
            return new Step(
                    rate,
                    sent,
                    got,
                    achieved,
                    delivery,
                    mbps
            );
        }

        private static void waitUntil(long deadline) {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                if (remaining > 1_000_000L) {
                    LockSupport.parkNanos(remaining - 500_000L);
                } else {
                    Thread.onSpinWait();
                }
            }
        }

        @Override
        public void close() throws IOException {
            draining = false;
            netem.stop();
            sender.close();
            receiver.close();
            try {
                drainThread.join(2_000);
                netemThread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            trace.close();
        }
    }
}