package emulator;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class NetEm {
    private static final int MAX_DATAGRAM = 65535;
    private static final long MAX_BLOCK_MS = 200;
    private static final class Pending {
        final byte[] data;
        final long releaseNanos;
        final boolean toReceiver;
        final long tiebreak;
        Pending(byte[] data, long releaseNanos, boolean toReceiver, long tiebreak) {
            this.data = data;
            this.releaseNanos = releaseNanos;
            this.toReceiver = toReceiver;
            this.tiebreak = tiebreak;
        }
    }

    private final InetSocketAddress listenAddr;
    private final InetSocketAddress receiverAddr;
    private final Channel c2s;
    private final Channel s2c;
    private final TraceLog trace;
    private final boolean verbose;
    private final PriorityQueue<Pending> queue =
            new PriorityQueue<>(
                    Comparator.<Pending>comparingLong(p -> p.releaseNanos)
                            .thenComparingLong(p -> p.tiebreak)
            );
    private long tiebreakCounter;
    private volatile boolean running = true;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile int boundPort = -1;
    private SocketAddress senderAddr;
    public NetEm(
            InetSocketAddress listenAddr,
            InetSocketAddress receiverAddr,
            ChannelConfig up,
            ChannelConfig down,
            long seed,
            TraceLog trace,
            boolean verbose) {
        this.listenAddr = listenAddr;
        this.receiverAddr = receiverAddr;
        this.trace = trace;
        this.verbose = verbose;
        c2s = new Channel("c2s", up, seed, trace);
        s2c = new Channel("s2c", down, seed ^ 0x5DEECE66DL, trace);
    }

    public void run() throws IOException {
        try (DatagramSocket sock = new DatagramSocket(listenAddr)) {
            boundPort = sock.getLocalPort();
            ready.countDown();
            System.err.printf(
                    "netem listening on port %d, forwarding to %s%n",
                    boundPort,
                    receiverAddr
            );
            System.err.printf(
                    "  c2s %s%n  s2c %s%n",
                    c2s,
                    s2c
            );
            byte[] buf = new byte[MAX_DATAGRAM];
            int lastTimeout = -1;
            while (running) {
                release(sock);
                int timeout = (int) blockMillis();
                if (lastTimeout != timeout) {
                    sock.setSoTimeout(timeout);
                    lastTimeout = timeout;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                    handle(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
            }
            release(sock);
        } finally {
            System.err.println(c2s.summary());
            System.err.println(s2c.summary());
            if (trace.isEnabled()) {
                System.err.printf("trace: %d lines%n", trace.lines());
            }
        }
    }

    private void handle(DatagramPacket p) {
        SocketAddress from = p.getSocketAddress();
        byte[] data = new byte[p.getLength()];
        System.arraycopy(
                p.getData(),
                p.getOffset(),
                data,
                0,
                p.getLength()
        );
        boolean fromReceiver = from.equals(receiverAddr);
        if (!fromReceiver) {
            if (senderAddr == null) {
                senderAddr = from;
                System.err.printf("netem: sender is %s%n", from);
            } else {
                if (!from.equals(senderAddr)) {
                    System.err.printf(
                            "netem: ignoring packet from unexpected source %s%n",
                            from
                    );
                    return;
                }
            }
        }
        Channel channel;
        if (fromReceiver) {
            channel = s2c;
        } else {
            channel = c2s;
        }
        List<Channel.Delivery> deliveries =
                channel.offer(data, data.length, System.nanoTime());
        for (Channel.Delivery d : deliveries) {
            boolean toReceiver = !fromReceiver;
            Pending pending = new Pending(
                    d.data,
                    d.releaseNanos,
                    toReceiver,
                    tiebreakCounter++
            );
            queue.add(pending);
            if (verbose) {
                String duplicate = d.duplicate ? " dup" : "";
                String corrupted = d.corrupted ? " corrupt" : "";
                String reordered = d.reordered ? " reorder" : "";
                System.err.printf(
                        "  %s %dB delay=%.1fms%s%s%s%n",
                        fromReceiver ? "s2c" : "c2s",
                        d.data.length,
                        d.delayMs,
                        duplicate,
                        corrupted,
                        reordered
                );
            }
        }
    }
    private void release(DatagramSocket sock) {
        long now = System.nanoTime();
        while (!queue.isEmpty()) {
            Pending p = queue.peek();
            if (p.releaseNanos > now) {
                break;
            }
            p = queue.poll();
            SocketAddress dest;
            if (p.toReceiver) {
                dest = receiverAddr;
            } else {
                dest = senderAddr;
            }
            if (dest == null) {
                continue;
            }
            try {
                DatagramPacket packet =
                        new DatagramPacket(p.data, p.data.length, dest);
                sock.send(packet);
            } catch (IOException e) {
                System.err.printf(
                        "netem: send to %s failed: %s%n",
                        dest,
                        e.getMessage()
                );
            }
        }
    }
    private long blockMillis() {
        Pending head = queue.peek();
        if (head == null) {
            return MAX_BLOCK_MS;
        }
        long deltaNanos =
                head.releaseNanos - System.nanoTime();
        if (deltaNanos <= 0) {
            return 1L;
        }
        long ms =
                (deltaNanos + 999_999L) / 1_000_000L;
        ms = Math.min(ms, MAX_BLOCK_MS);
        ms = Math.max(1L, ms);
        return ms;
    }
    public void stop() {
        running = false;
    }
    public boolean awaitReady(long timeoutMs) throws InterruptedException {
        return ready.await(timeoutMs, TimeUnit.MILLISECONDS);
    }
    public int boundPort() {
        return boundPort;
    }
    public static void main(String[] args) throws Exception {
        int listenPort = 9000;
        String receiverHost = "127.0.0.1";
        int receiverPort = 9001;
        long seed = 42;
        String upSpec = "";
        String downSpec = "";
        Path tracePath = null;
        boolean verbose = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--listen":
                    listenPort = Integer.parseInt(args[++i]);
                    break;
                case "--to":
                    String[] hp = args[++i].split(":");
                    receiverHost = hp[0];
                    receiverPort = Integer.parseInt(hp[1]);
                    break;
                case "--seed":
                    seed = Long.parseLong(args[++i]);
                    break;
                case "--up":
                    upSpec = args[++i];
                    break;
                case "--down":
                    downSpec = args[++i];
                    break;
                case "--both":
                    upSpec = args[++i];
                    downSpec = upSpec;
                    break;
                case "--trace":
                    tracePath = Paths.get(args[++i]);
                    break;
                case "--verbose":
                    verbose = true;
                    break;
                case "--help":
                    usage();
                    return;
                default:
                    System.err.println("unknown option: " + args[i]);
                    usage();
                    System.exit(2);
                    break;
            }
        }
        ChannelConfig up = ChannelConfig.parse(upSpec);
        ChannelConfig down = ChannelConfig.parse(downSpec);
        try (TraceLog trace = new TraceLog(tracePath)) {
            InetSocketAddress listenAddr =
                    new InetSocketAddress(listenPort);
            InetSocketAddress receiverAddr =
                    new InetSocketAddress(receiverHost, receiverPort);
            NetEm netem = new NetEm(
                    listenAddr,
                    receiverAddr,
                    up,
                    down,
                    seed,
                    trace,
                    verbose
            );
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(netem::stop));
            netem.run();
        }
    }

    private static void usage() {
        System.err.println("""
                usage: java emulator.NetEm [options]
                  --listen PORT      port to listen on            (default 9000)
                  --to HOST:PORT     receiver address             (default 127.0.0.1:9001)
                  --seed N           RNG seed                     (default 42)
                  --up SPEC          sender-to-receiver impairment
                  --down SPEC        receiver-to-sender impairment
                  --both SPEC        same impairment both ways
                  --trace FILE       write a JSONL decision trace
                  --verbose          log every packet to stderr
                SPEC is comma-separated key=value, any subset of:
                  loss, dup, corrupt, reorder   probabilities in [0,1]
                  delay, jitter, reorderExtra   milliseconds
                example:
                  java emulator.NetEm --listen 9000 --to 127.0.0.1:9001 --seed 7 \\
                      --both loss=0.05,delay=20,jitter=5 --trace run.jsonl
                """);
    }
}