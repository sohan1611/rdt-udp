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
 * Measures how many packets per second this machine can push through the
 * emulator before it starts losing them for reasons that have nothing to do
 * with the configured loss rate.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every experiment in this project measures goodput. If the harness itself
 * saturates, a goodput curve flattens because the machine ran out of capacity,
 * not because Go-Back-N did something interesting — and the two look identical
 * on a plot. The headline claim would be quietly contaminated with no visible
 * symptom.
 *
 * <p>So we measure the ceiling once per machine, state it in the report, and
 * keep every experiment below half of it. If someone asks at the viva how we
 * know our numbers reflect the protocol rather than the JVM, this is the
 * answer.
 *
 * <h2>Method</h2>
 *
 * <p>The channel is configured perfect: no loss, no delay, no jitter. Anything
 * that fails to arrive was therefore dropped by a socket buffer or lost to the
 * emulator falling behind. We offer traffic at a fixed rate for a fixed
 * duration and record the delivery ratio, stepping the rate up until delivery
 * falls below {@link #ACCEPTABLE_DELIVERY}. The last rate that held is the
 * sustainable ceiling.
 *
 * <p>A warm-up run is discarded first so the JIT has compiled the hot paths
 * before anything is timed.
 */
public final class Calibrate {

    /** Below this delivery ratio the emulator is considered saturated. */
    private static final double ACCEPTABLE_DELIVERY = 0.99;

    /** Payload size, matching the experiments. */
    private static final int PAYLOAD = 1400;

    /** Seconds of traffic per rate step. */
    private static final double STEP_SECONDS = 2.0;

    /** Offered rates in packets per second. */
    private static final int[] RATES = {
        1_000, 2_000, 4_000, 8_000, 12_000, 16_000, 24_000, 32_000, 48_000, 64_000, 96_000
    };

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** Result of one rate step. */
    private record Step(int offeredRate, long sent, long received, double achievedRate,
                        double deliveryRatio, double megabitsPerSec) { }

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "results/calibration.csv");

        System.out.println("Emulator capacity calibration");
        System.out.println("  payload      " + PAYLOAD + " bytes");
        System.out.println("  channel      perfect (no loss, no delay)");
        System.out.printf("  step length  %.1f s%n", STEP_SECONDS);
        System.out.println();
        printEnvironment();
        System.out.println();

        try (Rig rig = new Rig()) {
            System.out.println("warming up (discarded)...");
            rig.runStep(4_000, 1.5);

            System.out.printf("%-12s %10s %10s %10s %9s%n",
                    "offered", "sent", "received", "achieved", "delivered");
            System.out.println("-".repeat(56));

            List<Step> steps = new ArrayList<>();
            int ceiling = 0;
            for (int rate : RATES) {
                Step s = rig.runStep(rate, STEP_SECONDS);
                steps.add(s);
                System.out.printf("%-12d %10d %10d %10.0f %8.2f%%%n",
                        s.offeredRate(), s.sent(), s.received(),
                        s.achievedRate(), s.deliveryRatio() * 100);

                if (s.deliveryRatio() >= ACCEPTABLE_DELIVERY) {
                    ceiling = (int) Math.round(s.achievedRate());
                } else {
                    System.out.println("-".repeat(56));
                    System.out.printf("saturated at %d pkt/s offered%n", rate);
                    break;
                }
            }

            write(out, steps, ceiling);
            report(ceiling);
        }
    }

    private static void report(int ceiling) {
        System.out.println();
        if (ceiling == 0) {
            System.out.println("FAILED: no rate met the delivery threshold. Investigate before");
            System.out.println("running any experiment — the harness is not usable in this state.");
            return;
        }
        int budget = ceiling / 2;
        double mbps = budget * PAYLOAD * 8.0 / 1e6;
        System.out.printf("sustained ceiling      %,d pkt/s%n", ceiling);
        System.out.printf("experiment budget      %,d pkt/s  (50%% of ceiling)%n", budget);
        System.out.printf("       equivalent to   %.1f Mbps at %d-byte payloads%n", mbps, PAYLOAD);
        System.out.println();
        System.out.println("Put the ceiling in the report methodology section, and keep every");
        System.out.println("experiment below the budget. The plan's operating point is ~4,500 pkt/s.");
        if (budget < 4_500) {
            System.out.println();
            System.out.println("WARNING: the budget is below the planned operating point of");
            System.out.println("4,500 pkt/s. Lower the emulated link capacity, or raise the");
            System.out.println("payload size, so the interesting part of each curve sits lower.");
        }
    }

    private static void printEnvironment() {
        System.out.println("  java         " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("  os           " + System.getProperty("os.name")
                + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        System.out.println("  cores        " + Runtime.getRuntime().availableProcessors());
        System.out.printf("  max heap     %d MB%n", Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    private static void write(Path out, List<Step> steps, int ceiling) throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# emulator capacity calibration");
            w.printf("# java=%s os=%s cores=%d payload=%d%n",
                    System.getProperty("java.version"), System.getProperty("os.name"),
                    Runtime.getRuntime().availableProcessors(), PAYLOAD);
            w.printf("# sustained_ceiling_pkts_per_sec=%d experiment_budget=%d%n",
                    ceiling, ceiling / 2);
            w.println("offered_pkts_per_sec,sent,received,achieved_pkts_per_sec,delivery_ratio,mbps");
            for (Step s : steps) {
                w.printf("%d,%d,%d,%.1f,%.6f,%.3f%n", s.offeredRate(), s.sent(), s.received(),
                        s.achievedRate(), s.deliveryRatio(), s.megabitsPerSec());
            }
        }
        System.out.println();
        System.out.println("wrote " + out.toAbsolutePath());
    }

    /**
     * A running emulator plus a paced sender and a draining receiver, wired up
     * over loopback on ephemeral ports.
     */
    private static final class Rig implements AutoCloseable {
        private final NetEm netem;
        private final Thread netemThread;
        private final TraceLog trace = TraceLog.disabled();
        private final DatagramSocket sender;
        private final DatagramSocket receiver;
        private final InetSocketAddress netemAddr;
        private final AtomicLong received = new AtomicLong();
        private final Thread drainer;
        private volatile boolean draining = true;

        Rig() throws Exception {
            receiver = new DatagramSocket(0, LOOPBACK);
            receiver.setSoTimeout(100);
            receiver.setReceiveBufferSize(4 * 1024 * 1024);
            sender = new DatagramSocket(0, LOOPBACK);
            sender.setSendBufferSize(4 * 1024 * 1024);

            netem = new NetEm(
                    new InetSocketAddress(LOOPBACK, 0),
                    new InetSocketAddress(LOOPBACK, receiver.getLocalPort()),
                    ChannelConfig.perfect(), ChannelConfig.perfect(),
                    1, trace, false);

            netemThread = new Thread(() -> {
                try {
                    netem.run();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, "netem");
            netemThread.setDaemon(true);
            netemThread.start();
            if (!netem.awaitReady(5000)) {
                throw new IllegalStateException("emulator did not start");
            }
            netemAddr = new InetSocketAddress(LOOPBACK, netem.boundPort());

            drainer = new Thread(this::drain, "drainer");
            drainer.setDaemon(true);
            drainer.start();
        }

        private void drain() {
            byte[] buf = new byte[MAX_READ];
            while (draining) {
                try {
                    receiver.receive(new DatagramPacket(buf, buf.length));
                    received.incrementAndGet();
                } catch (SocketTimeoutException e) {
                    // idle between steps
                } catch (IOException e) {
                    return;      // socket closed on shutdown
                }
            }
        }

        private static final int MAX_READ = 2048;

        /** Offers {@code rate} packets per second for {@code seconds}. */
        Step runStep(int rate, double seconds) throws Exception {
            received.set(0);
            byte[] payload = new byte[PAYLOAD];
            DatagramPacket p = new DatagramPacket(payload, payload.length, netemAddr);

            long count = (long) (rate * seconds);
            long intervalNanos = 1_000_000_000L / rate;
            long start = System.nanoTime();
            long next = start;
            long sent = 0;

            for (long i = 0; i < count; i++) {
                pauseUntil(next);
                sender.send(p);
                sent++;
                next += intervalNanos;
            }
            long elapsed = System.nanoTime() - start;

            // Let anything still in flight land before counting.
            Thread.sleep(300);
            long got = received.get();

            double achieved = sent / (elapsed / 1e9);
            double ratio = sent == 0 ? 0 : (double) got / sent;
            double mbps = got * PAYLOAD * 8.0 / (elapsed / 1e9) / 1e6;
            return new Step(rate, sent, got, achieved, ratio, mbps);
        }

        /**
         * Waits until {@code deadline}. Parks for long waits so the emulator
         * thread is not starved of CPU, and spins for the last fraction of a
         * millisecond where parking would overshoot.
         */
        private static void pauseUntil(long deadline) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 1_000_000L) {
                LockSupport.parkNanos(remaining - 500_000L);
            }
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        @Override
        public void close() throws Exception {
            draining = false;
            netem.stop();
            drainer.join(2000);
            netemThread.join(2000);
            sender.close();
            receiver.close();
            trace.close();
        }
    }
}
