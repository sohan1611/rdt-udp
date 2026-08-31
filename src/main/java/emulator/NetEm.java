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

/**
 * A UDP middlebox that sits between the sender and the receiver and impairs the
 * traffic passing through it.
 *
 * <pre>
 *   sender  &lt;--&gt;  NetEm (this process)  &lt;--&gt;  receiver
 *              c2s: sender to receiver
 *              s2c: receiver to sender
 * </pre>
 *
 * <p>The two directions are impaired independently, each with its own
 * {@link Channel} and its own seeded generator, so loss on the data path does
 * not disturb the ACK path.
 *
 * <h2>Why this is a separate process</h2>
 *
 * <p>The obvious alternative is to hook impairment into the protocol's own
 * socket wrapper. We deliberately do not, for three reasons. The protocol code
 * ends up with no test-only branches at all, so at the viva we can say the
 * protocol does not know it is being tested and mean it. It works unchanged
 * when the endpoints are on different machines. And swapping it for Linux
 * {@code tc netem} in the cross-validation experiment becomes a change of
 * command line rather than a change of code.
 *
 * <h2>Timing</h2>
 *
 * <p>One thread, one socket, one {@link PriorityQueue} of packets waiting for
 * their release time. The receive timeout is set from the head of that queue,
 * so a blocking {@code receive} returns either because a packet arrived or
 * because a held packet is due. No busy loop, no second thread, and no
 * {@link java.nio.channels.Selector} — with a single socket a selector would
 * add a platform dependency and buy nothing.
 *
 * <p>Release timing is therefore accurate to about a millisecond, which is the
 * granularity of {@link DatagramSocket#setSoTimeout}. Against the 5–60 ms
 * delays we emulate that is under a few percent, and it applies equally to
 * every configuration, so it cannot bias a comparison between protocols.
 */
public final class NetEm {

    /** Larger than any datagram we send; oversized reads are truncated by UDP anyway. */
    private static final int MAX_DATAGRAM = 65535;

    /** Cap on the receive timeout, so {@link #stop} is acted on promptly. */
    private static final long MAX_BLOCK_MS = 200;

    /** One packet waiting for its release time. */
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

    /**
     * Ordered by release time. The tiebreak keeps packets scheduled for the
     * same instant in arrival order, so the queue never introduces reordering
     * the channel model did not ask for.
     */
    private final PriorityQueue<Pending> queue = new PriorityQueue<>(
            Comparator.<Pending>comparingLong(p -> p.releaseNanos)
                    .thenComparingLong(p -> p.tiebreak));

    private long tiebreakCounter;
    private volatile boolean running = true;

    /** Signals that the socket is bound and the loop is about to start. */
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile int boundPort = -1;

    /** Learned from the first packet that arrives, so the sender need not be told. */
    private SocketAddress senderAddr;

    public NetEm(InetSocketAddress listenAddr, InetSocketAddress receiverAddr,
                 ChannelConfig up, ChannelConfig down, long seed,
                 TraceLog trace, boolean verbose) {
        this.listenAddr = listenAddr;
        this.receiverAddr = receiverAddr;
        this.trace = trace;
        this.verbose = verbose;
        // Two different derived seeds so the directions are independent but
        // both still reproduce from the single seed the user supplied.
        this.c2s = new Channel("c2s", up, seed, trace);
        this.s2c = new Channel("s2c", down, seed ^ 0x5DEECE66DL, trace);
    }

    public void run() throws IOException {
        try (DatagramSocket sock = new DatagramSocket(listenAddr)) {
            boundPort = sock.getLocalPort();
            ready.countDown();

            System.err.printf("netem listening on port %d, forwarding to %s%n",
                    boundPort, receiverAddr);
            System.err.printf("  c2s %s%n  s2c %s%n", c2s, s2c);

            byte[] buf = new byte[MAX_DATAGRAM];
            int lastTimeout = -1;

            while (running) {
                // Anything already due goes out before we block again.
                release(sock);

                // setSoTimeout is a syscall, and at high packet rates this loop
                // runs tens of thousands of times a second. Only pay for it
                // when the value actually changes.
                int timeout = (int) blockMillis();
                if (timeout != lastTimeout) {
                    sock.setSoTimeout(timeout);
                    lastTimeout = timeout;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;      // a held packet is due; loop round and release it
                }
                handle(p);
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
        System.arraycopy(p.getData(), p.getOffset(), data, 0, p.getLength());

        boolean fromReceiver = from.equals(receiverAddr);
        if (!fromReceiver) {
            // Anything that is not the receiver is taken to be the sender.
            // Remembering it is what lets the sender use an ephemeral port.
            if (senderAddr == null) {
                senderAddr = from;
                System.err.printf("netem: sender is %s%n", from);
            } else if (!from.equals(senderAddr)) {
                System.err.printf("netem: ignoring packet from unexpected source %s%n", from);
                return;
            }
        }

        Channel channel = fromReceiver ? s2c : c2s;
        List<Channel.Delivery> deliveries = channel.offer(data, data.length, System.nanoTime());

        for (Channel.Delivery d : deliveries) {
            queue.add(new Pending(d.data, d.releaseNanos, !fromReceiver, tiebreakCounter++));
            if (verbose) {
                System.err.printf("  %s %dB delay=%.1fms%s%s%s%n",
                        fromReceiver ? "s2c" : "c2s", d.data.length, d.delayMs,
                        d.duplicate ? " dup" : "", d.corrupted ? " corrupt" : "",
                        d.reordered ? " reorder" : "");
            }
        }
        // A dropped packet produces no deliveries at all, which is the point.
    }

    /** Sends everything whose release time has arrived. */
    private void release(DatagramSocket sock) {
        long now = System.nanoTime();
        while (!queue.isEmpty() && queue.peek().releaseNanos <= now) {
            Pending p = queue.poll();
            SocketAddress dest = p.toReceiver ? receiverAddr : senderAddr;
            if (dest == null) {
                continue;      // a reply before we ever saw the sender; nothing to do
            }
            try {
                sock.send(new DatagramPacket(p.data, p.data.length, dest));
            } catch (IOException e) {
                System.err.printf("netem: send to %s failed: %s%n", dest, e.getMessage());
            }
        }
    }

    /**
     * How long to block in {@code receive}: until the next packet is due, or
     * {@link #MAX_BLOCK_MS} if nothing is queued. Never zero, because
     * {@link DatagramSocket#setSoTimeout} treats zero as "block forever".
     */
    private long blockMillis() {
        Pending head = queue.peek();
        if (head == null) {
            return MAX_BLOCK_MS;
        }
        long deltaNanos = head.releaseNanos - System.nanoTime();
        if (deltaNanos <= 0) {
            return 1L;
        }
        // Round up so we never wake a hair early and spin.
        long ms = (deltaNanos + 999_999L) / 1_000_000L;
        return Math.max(1L, Math.min(ms, MAX_BLOCK_MS));
    }

    public void stop() {
        running = false;
    }

    /**
     * Blocks until the socket is bound, so a caller that passed port 0 can find
     * out which port it got. Returns false on timeout.
     */
    public boolean awaitReady(long timeoutMs) throws InterruptedException {
        return ready.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** The port actually bound, valid once {@link #awaitReady} has returned true. */
    public int boundPort() {
        return boundPort;
    }

    // ---- command line ----

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
            switch (args[i]) {
                case "--listen":  listenPort = Integer.parseInt(args[++i]); break;
                case "--to": {
                    String[] hp = args[++i].split(":");
                    receiverHost = hp[0];
                    receiverPort = Integer.parseInt(hp[1]);
                    break;
                }
                case "--seed":    seed = Long.parseLong(args[++i]); break;
                case "--up":      upSpec = args[++i]; break;
                case "--down":    downSpec = args[++i]; break;
                case "--both":    upSpec = downSpec = args[++i]; break;
                case "--trace":   tracePath = Paths.get(args[++i]); break;
                case "--verbose": verbose = true; break;
                case "--help":    usage(); return;
                default:
                    System.err.println("unknown option: " + args[i]);
                    usage();
                    System.exit(2);
            }
        }

        ChannelConfig up = ChannelConfig.parse(upSpec);
        ChannelConfig down = ChannelConfig.parse(downSpec);

        try (TraceLog trace = new TraceLog(tracePath)) {
            NetEm netem = new NetEm(
                    new InetSocketAddress(listenPort),
                    new InetSocketAddress(receiverHost, receiverPort),
                    up, down, seed, trace, verbose);
            Runtime.getRuntime().addShutdownHook(new Thread(netem::stop));
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
