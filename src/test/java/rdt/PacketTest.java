package rdt;

import static rdt.Harness.assertEquals;
import static rdt.Harness.assertThrows;
import static rdt.Harness.assertTrue;

import java.util.Random;

/** Tests for the wire format and the RFC 1071 checksum. */
public final class PacketTest {

    public static void main(String[] args) {
        Harness h = new Harness("PacketTest");

        h.check("round-trips a DATA packet with payload", () -> {
            byte[] payload = bytes(1400, 7);
            Packet sent = Packet.data(12345, payload);
            Packet got = Packet.decode(sent.encode(), sent.wireLength());
            assertEquals("type", Packet.TYPE_DATA, got.type);
            assertEquals("seq", 12345, got.seq);
            assertEquals("payload", payload, got.payload);
        });

        h.check("round-trips a packet with no payload", () -> {
            Packet sent = Packet.ack(99, 32);
            Packet got = Packet.decode(sent.encode(), sent.wireLength());
            assertEquals("type", Packet.TYPE_ACK, got.type);
            assertEquals("ack", 99, got.ack);
            assertEquals("window", 32, got.window);
            assertEquals("payload length", 0, got.payload.length);
        });

        h.check("round-trips an odd-length payload", () -> {
            // Exercises the trailing-byte branch of the checksum.
            byte[] payload = bytes(1401, 3);
            Packet sent = Packet.data(1, payload);
            Packet got = Packet.decode(sent.encode(), sent.wireLength());
            assertEquals("payload", payload, got.payload);
        });

        h.check("survives sequence numbers above 2^31", () -> {
            // The classic Java trap: a raw int would come back negative here.
            long seq = 0xFFFFFFFFL;
            Packet got = Packet.decode(Packet.data(seq, bytes(8, 1)).encode(), Packet.HEADER_LEN + 8);
            assertEquals("seq", seq, got.seq);
            assertTrue("seq must not be negative", got.seq > 0);
        });

        h.check("survives maximum 16-bit field values", () -> {
            Packet sent = new Packet(Packet.TYPE_SACK, 0, 0, 0xFFFF, 0xFFFF, new byte[0]);
            Packet got = Packet.decode(sent.encode(), sent.wireLength());
            assertEquals("window", 0xFFFF, got.window);
            assertEquals("sackCount", 0xFFFF, got.sackCount);
        });

        h.check("detects every single-bit flip", () -> {
            // The Internet checksum is guaranteed to catch any single-bit
            // error. This walks all 8 bits of all 148 bytes.
            byte[] clean = Packet.data(42, bytes(128, 11)).encode();
            int checked = 0;
            for (int i = 0; i < clean.length; i++) {
                for (int bit = 0; bit < 8; bit++) {
                    byte[] corrupt = clean.clone();
                    corrupt[i] ^= (byte) (1 << bit);
                    final int fi = i, fbit = bit;
                    assertThrows("bit " + fbit + " of byte " + fi + " went undetected",
                            CorruptPacketException.class,
                            () -> Packet.decode(corrupt, corrupt.length));
                    checked++;
                }
            }
            assertEquals("bits checked", clean.length * 8, checked);
        });

        h.check("rejects a runt packet", () -> {
            assertThrows("short buffer", CorruptPacketException.class,
                    () -> Packet.decode(new byte[Packet.HEADER_LEN - 1], Packet.HEADER_LEN - 1));
        });

        h.check("rejects a declared length that does not match what arrived", () -> {
            byte[] buf = Packet.data(1, bytes(64, 5)).encode();
            buf[12] = 0;                    // payloadLen high byte
            buf[13] = 63;                   // claim 63 bytes, 64 arrived
            reseal(buf);                    // make the checksum valid again
            CorruptPacketException e = assertThrows("length mismatch",
                    CorruptPacketException.class, () -> Packet.decode(buf, buf.length));
            assertTrue("message should mention the declared payload",
                    e.getMessage().contains("declared payload"));
        });

        h.check("rejects an unknown version", () -> {
            byte[] buf = Packet.data(1, bytes(16, 2)).encode();
            buf[0] = 99;
            reseal(buf);
            CorruptPacketException e = assertThrows("bad version",
                    CorruptPacketException.class, () -> Packet.decode(buf, buf.length));
            assertTrue("message should mention the version",
                    e.getMessage().contains("version"));
        });

        h.check("checksum satisfies the RFC 1071 end-around property", () -> {
            // Summing the whole datagram, checksum field included, must give
            // 0xFFFF -- equivalently, the complement of that sum is zero.
            byte[] buf = Packet.data(777, bytes(200, 9)).encode();
            assertEquals("complement of full-datagram sum", 0, Packet.checksum(buf, 0, buf.length));
        });

        h.check("checksum is stable across runs", () -> {
            // Regression guard: if someone "tidies" the checksum loop and
            // changes its behaviour, this catches it.
            byte[] fixed = new byte[64];
            for (int i = 0; i < fixed.length; i++) {
                fixed[i] = (byte) i;
            }
            // Hand-derived: word i = 514i + 1, so the sum over i=0..31 is
            // 514*496 + 32 = 254976 = 0x3E400. End-around fold gives
            // 0xE400 + 0x3 = 0xE403, and the complement is 0x1BFC.
            assertEquals("known checksum of 0..63", 0x1BFC, Packet.checksum(fixed, 0, fixed.length));
        });

        h.check("rejects oversized payloads at construction", () -> {
            assertThrows("payload over 65535", IllegalArgumentException.class,
                    () -> Packet.data(1, new byte[0x10000]));
        });

        h.check("rejects out-of-range sequence numbers at construction", () -> {
            assertThrows("seq above 2^32-1", IllegalArgumentException.class,
                    () -> Packet.data(0x100000000L, new byte[0]));
        });

        h.done();
    }

    /** Deterministic pseudo-random bytes, so failures reproduce exactly. */
    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** Recomputes and rewrites the checksum after hand-editing a header field. */
    private static void reseal(byte[] buf) {
        buf[16] = 0;
        buf[17] = 0;
        int sum = Packet.checksum(buf, 0, buf.length);
        buf[16] = (byte) (sum >>> 8);
        buf[17] = (byte) sum;
    }
}
