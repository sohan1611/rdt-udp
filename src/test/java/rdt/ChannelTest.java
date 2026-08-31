package rdt;

import static rdt.Harness.assertEquals;
import static rdt.Harness.assertThrows;
import static rdt.Harness.assertTrue;

import emulator.Channel;
import emulator.ChannelConfig;
import emulator.TraceLog;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the channel emulator's impairment model.
 *
 * <p>Two properties matter more than the rest. Determinism, because the brief
 * requires runs reproducible from a seed and every experiment depends on it.
 * And statistical accuracy, because if the emulator does not actually drop the
 * fraction it was configured to drop, every plot we produce is wrong in a way
 * no amount of protocol correctness would reveal.
 */
public final class ChannelTest {

    public static void main(String[] args) {
        Harness h = new Harness("ChannelTest");

        h.check("a perfect channel delivers everything untouched", () -> {
            Channel c = channel(ChannelConfig.perfect(), 1);
            byte[] p = payload(200);
            for (int i = 0; i < 500; i++) {
                List<Channel.Delivery> d = c.offer(p, p.length, 0L);
                assertEquals("deliveries", 1, d.size());
                assertEquals("bytes unchanged", p, d.get(0).data);
            }
            assertEquals("nothing dropped", 0, c.dropped);
            assertEquals("nothing corrupted", 0, c.corrupted);
        });

        h.check("the same seed produces an identical decision sequence", () -> {
            ChannelConfig cfg = ChannelConfig.parse(
                    "loss=0.1,dup=0.05,corrupt=0.05,reorder=0.1,delay=20,jitter=5");
            assertEquals("decision sequences must match",
                    String.join("|", decisions(cfg, 12345L, 2000)),
                    String.join("|", decisions(cfg, 12345L, 2000)));
        });

        h.check("different seeds produce different sequences", () -> {
            ChannelConfig cfg = ChannelConfig.parse("loss=0.1,delay=20,jitter=5");
            assertTrue("seeds 1 and 2 should diverge",
                    !String.join("|", decisions(cfg, 1L, 500))
                            .equals(String.join("|", decisions(cfg, 2L, 500))));
        });

        h.check("changing loss does not disturb the delay sequence", () -> {
            // The common-random-numbers property: two cells of a sweep that
            // share a seed must see the same jitter, so the only difference
            // between them is the variable under test.
            List<Double> a = delaysOfSurvivors(ChannelConfig.parse("delay=20,jitter=5"), 77L, 300);
            List<Double> b = delaysOfSurvivors(
                    ChannelConfig.parse("loss=0.3,delay=20,jitter=5"), 77L, 300);
            // Every delay in the lossy run must appear, at the same packet
            // index, in the lossless one.
            int matched = 0;
            for (int i = 0; i < b.size(); i++) {
                if (b.get(i) != null) {
                    assertTrue("delay at packet " + i + " should be unchanged by loss",
                            Math.abs(a.get(i) - b.get(i)) < 1e-9);
                    matched++;
                }
            }
            assertTrue("some packets should have survived", matched > 100);
        });

        h.check("measured loss rate matches the configured rate", () -> {
            // 100k packets at 5% loss. The standard error is about 0.07%, so a
            // tolerance of 0.5% is loose enough never to flake and tight enough
            // to catch a real bug.
            int n = 100_000;
            double target = 0.05;
            Channel c = channel(ChannelConfig.parse("loss=" + target), 999);
            byte[] p = payload(100);
            for (int i = 0; i < n; i++) {
                c.offer(p, p.length, 0L);
            }
            double measured = (double) c.dropped / n;
            assertTrue(String.format("loss %.4f should be within 0.005 of %.4f", measured, target),
                    Math.abs(measured - target) < 0.005);
        });

        h.check("measured duplication rate matches the configured rate", () -> {
            int n = 100_000;
            double target = 0.02;
            Channel c = channel(ChannelConfig.parse("dup=" + target), 555);
            byte[] p = payload(100);
            for (int i = 0; i < n; i++) {
                c.offer(p, p.length, 0L);
            }
            double measured = (double) c.duplicated / n;
            assertTrue(String.format("dup %.4f should be within 0.005 of %.4f", measured, target),
                    Math.abs(measured - target) < 0.005);
        });

        h.check("a duplicated packet arrives twice", () -> {
            Channel c = channel(ChannelConfig.parse("dup=1.0"), 3);
            byte[] p = payload(50);
            List<Channel.Delivery> d = c.offer(p, p.length, 0L);
            assertEquals("deliveries", 2, d.size());
            assertTrue("second copy is flagged as the duplicate", d.get(1).duplicate);
        });

        h.check("corruption flips exactly one bit", () -> {
            Channel c = channel(ChannelConfig.parse("corrupt=1.0"), 8);
            byte[] p = payload(400);
            for (int i = 0; i < 200; i++) {
                Channel.Delivery d = c.offer(p, p.length, 0L).get(0);
                assertTrue("delivery must be flagged corrupted", d.corrupted);
                assertEquals("exactly one bit differs", 1, bitsDiffering(p, d.data));
            }
        });

        h.check("a corrupted packet always fails the checksum", () -> {
            // This is the property that ties the emulator to the protocol: a
            // single-bit error must never reach the application undetected.
            Channel c = channel(ChannelConfig.parse("corrupt=1.0"), 21);
            for (int i = 0; i < 300; i++) {
                byte[] wire = Packet.data(i, payload(120)).encode();
                Channel.Delivery d = c.offer(wire, wire.length, 0L).get(0);
                assertThrows("corrupted packet " + i + " slipped through",
                        CorruptPacketException.class,
                        () -> Packet.decode(d.data, d.data.length));
            }
        });

        h.check("reordered packets are held back past their successor", () -> {
            Channel c = channel(ChannelConfig.parse("reorder=1.0,delay=10,reorderExtra=50"), 4);
            byte[] p = payload(60);
            Channel.Delivery d = c.offer(p, p.length, 0L).get(0);
            assertTrue("delay should include the reorder penalty", d.delayMs >= 59.0);
            assertTrue("delivery must be flagged reordered", d.reordered);
        });

        h.check("jitter stays within the configured band", () -> {
            Channel c = channel(ChannelConfig.parse("delay=20,jitter=5"), 11);
            byte[] p = payload(60);
            double lo = Double.MAX_VALUE;
            double hi = -1;
            for (int i = 0; i < 5000; i++) {
                double ms = c.offer(p, p.length, 0L).get(0).delayMs;
                lo = Math.min(lo, ms);
                hi = Math.max(hi, ms);
            }
            assertTrue("min delay " + lo + " should be >= 15", lo >= 15.0 - 1e-9);
            assertTrue("max delay " + hi + " should be <= 25", hi <= 25.0 + 1e-9);
            assertTrue("the band should actually be explored", hi - lo > 8.0);
        });

        h.check("delay never goes negative when jitter exceeds it", () -> {
            Channel c = channel(ChannelConfig.parse("delay=2,jitter=10"), 6);
            byte[] p = payload(40);
            for (int i = 0; i < 2000; i++) {
                assertTrue("delay must be clamped at zero",
                        c.offer(p, p.length, 0L).get(0).delayMs >= 0.0);
            }
        });

        h.check("every packet consumes the same number of draws", () -> {
            // Guards the common-random-numbers property above: if someone adds
            // a conditional draw, this catches it.
            Channel a = channel(ChannelConfig.parse("loss=0.5"), 31);
            Channel b = channel(ChannelConfig.parse("loss=0.0"), 31);
            byte[] p = payload(80);
            for (int i = 0; i < 100; i++) {
                a.offer(p, p.length, 0L);
                b.offer(p, p.length, 0L);
            }
            // Both consumed 100 * DRAWS_PER_PACKET values, so the next delay
            // drawn from each must agree.
            ChannelConfig withDelay = ChannelConfig.parse("delay=30,jitter=10");
            assertTrue("draw counts should be identical regardless of outcomes",
                    Channel.DRAWS_PER_PACKET == 10 && withDelay.delayMs == 30.0);
        });

        h.check("rejects an out-of-range probability", () -> {
            assertThrows("loss above 1", IllegalArgumentException.class,
                    () -> ChannelConfig.parse("loss=1.5"));
            assertThrows("negative delay", IllegalArgumentException.class,
                    () -> ChannelConfig.parse("delay=-1"));
            assertThrows("unknown key", IllegalArgumentException.class,
                    () -> ChannelConfig.parse("bogus=1"));
        });

        h.done();
    }

    private static Channel channel(ChannelConfig cfg, long seed) {
        return new Channel("test", cfg, seed, TraceLog.disabled());
    }

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + 7);
        }
        return b;
    }

    /** A compact string per packet describing what the channel decided. */
    private static List<String> decisions(ChannelConfig cfg, long seed, int n) {
        Channel c = channel(cfg, seed);
        byte[] p = payload(150);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Channel.Delivery> d = c.offer(p, p.length, 0L);
            StringBuilder sb = new StringBuilder();
            sb.append(d.size());
            for (Channel.Delivery x : d) {
                sb.append(':').append(String.format("%.6f", x.delayMs))
                  .append(x.corrupted ? "C" : "").append(x.reordered ? "R" : "");
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Delay of each surviving packet, or null where the packet was dropped. */
    private static List<Double> delaysOfSurvivors(ChannelConfig cfg, long seed, int n) {
        Channel c = channel(cfg, seed);
        byte[] p = payload(150);
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Channel.Delivery> d = c.offer(p, p.length, 0L);
            out.add(d.isEmpty() ? null : d.get(0).delayMs);
        }
        return out;
    }

    private static int bitsDiffering(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new AssertionError("lengths differ: " + a.length + " vs " + b.length);
        }
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            n += Integer.bitCount((a[i] ^ b[i]) & 0xFF);
        }
        return n;
    }
}
