package rdt;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An RDT packet: a fixed 20-byte header followed by an optional payload.
 *
 * <p>Wire format, big-endian (which is what {@link ByteBuffer} gives us by
 * default, and is also network byte order, so no conversion is needed):
 *
 * <pre>
 *  0        1        2        3
 * +--------+--------+--------+--------+
 * |  ver   |  type  | flags  |  resv  |
 * +--------+--------+--------+--------+
 * |            seq  (uint32)          |
 * +-----------------------------------+
 * |            ack  (uint32)          |
 * +--------+--------+--------+--------+
 * |   payloadLen    |     window      |
 * +--------+--------+--------+--------+
 * |    checksum     |    sackCount    |
 * +--------+--------+--------+--------+
 * </pre>
 *
 * <p>Java has no unsigned integer types, so the 32-bit seq and ack fields are
 * held in a long and the 16-bit fields in an int. Every read out of the buffer
 * is widened through {@link Integer#toUnsignedLong} or
 * {@link Short#toUnsignedInt} so that a sequence number above 2^31 does not
 * come back negative.
 */
public final class Packet {

    public static final int HEADER_LEN = 20;
    public static final byte VERSION = 1;

    public static final byte TYPE_DATA = 0;
    public static final byte TYPE_ACK = 1;
    public static final byte TYPE_SACK = 2;
    public static final byte TYPE_FIN = 3;
    public static final byte TYPE_FINACK = 4;

    /** Byte offset of the checksum field within the header. */
    private static final int CHECKSUM_OFFSET = 16;

    private static final byte[] NO_PAYLOAD = new byte[0];

    public final byte version;
    public final byte type;
    public final byte flags;
    public final long seq;        // unsigned 32-bit
    public final long ack;        // unsigned 32-bit
    public final int window;      // unsigned 16-bit
    public final int sackCount;   // unsigned 16-bit
    public final byte[] payload;

    public Packet(byte type, long seq, long ack, int window, int sackCount, byte[] payload) {
        this(VERSION, type, (byte) 0, seq, ack, window, sackCount, payload);
    }

    public Packet(byte version, byte type, byte flags, long seq, long ack,
                  int window, int sackCount, byte[] payload) {
        byte[] p = (payload == null) ? NO_PAYLOAD : payload;
        if (p.length > 0xFFFF) {
            throw new IllegalArgumentException("payload too large: " + p.length);
        }
        requireUnsigned32("seq", seq);
        requireUnsigned32("ack", ack);
        requireUnsigned16("window", window);
        requireUnsigned16("sackCount", sackCount);
        this.version = version;
        this.type = type;
        this.flags = flags;
        this.seq = seq;
        this.ack = ack;
        this.window = window;
        this.sackCount = sackCount;
        this.payload = p;
    }

    /** Convenience factory for a DATA packet. */
    public static Packet data(long seq, byte[] payload) {
        return new Packet(TYPE_DATA, seq, 0, 0, 0, payload);
    }

    /** Convenience factory for a cumulative ACK. */
    public static Packet ack(long ack, int window) {
        return new Packet(TYPE_ACK, 0, ack, window, 0, NO_PAYLOAD);
    }

    /**
     * Serialises this packet, computing and inserting the checksum.
     *
     * @return a newly allocated array of exactly HEADER_LEN + payload.length bytes
     */
    public byte[] encode() {
        byte[] out = new byte[HEADER_LEN + payload.length];
        ByteBuffer buf = ByteBuffer.wrap(out);
        buf.put(version);
        buf.put(type);
        buf.put(flags);
        buf.put((byte) 0);                    // reserved
        buf.putInt((int) seq);
        buf.putInt((int) ack);
        buf.putShort((short) payload.length);
        buf.putShort((short) window);
        buf.putShort((short) 0);              // checksum, filled in below
        buf.putShort((short) sackCount);
        buf.put(payload);

        // The checksum is computed with its own field zeroed, which it already
        // is, then written back into that field.
        int sum = checksum(out, 0, out.length);
        out[CHECKSUM_OFFSET] = (byte) (sum >>> 8);
        out[CHECKSUM_OFFSET + 1] = (byte) sum;
        return out;
    }

    /**
     * Parses and validates a received datagram.
     *
     * @param buf buffer holding the datagram
     * @param len number of valid bytes in buf
     * @throws CorruptPacketException if the datagram is truncated, declares a
     *         length inconsistent with what arrived, carries an unknown
     *         version, or fails the checksum
     */
    public static Packet decode(byte[] buf, int len) throws CorruptPacketException {
        if (len < HEADER_LEN) {
            throw new CorruptPacketException("runt packet: " + len + " bytes");
        }

        // Verify before trusting any field. Recompute over a copy with the
        // checksum field zeroed and compare against the value that arrived.
        int received = ((buf[CHECKSUM_OFFSET] & 0xFF) << 8) | (buf[CHECKSUM_OFFSET + 1] & 0xFF);
        byte[] zeroed = Arrays.copyOf(buf, len);
        zeroed[CHECKSUM_OFFSET] = 0;
        zeroed[CHECKSUM_OFFSET + 1] = 0;
        int computed = checksum(zeroed, 0, len);
        if (computed != received) {
            throw new CorruptPacketException(
                    String.format("checksum mismatch: got 0x%04X, computed 0x%04X", received, computed));
        }

        try {
            ByteBuffer b = ByteBuffer.wrap(buf, 0, len);
            byte version = b.get();
            if (version != VERSION) {
                throw new CorruptPacketException("unsupported version: " + version);
            }
            byte type = b.get();
            byte flags = b.get();
            b.get();                                        // reserved
            long seq = Integer.toUnsignedLong(b.getInt());
            long ack = Integer.toUnsignedLong(b.getInt());
            int payloadLen = Short.toUnsignedInt(b.getShort());
            int window = Short.toUnsignedInt(b.getShort());
            b.getShort();                                   // checksum, already verified
            int sackCount = Short.toUnsignedInt(b.getShort());

            if (payloadLen != len - HEADER_LEN) {
                throw new CorruptPacketException(
                        "declared payload " + payloadLen + " but "
                                + (len - HEADER_LEN) + " bytes arrived");
            }
            byte[] payload = new byte[payloadLen];
            b.get(payload);
            return new Packet(version, type, flags, seq, ack, window, sackCount, payload);
        } catch (BufferUnderflowException e) {
            throw new CorruptPacketException("truncated header");
        }
    }

    /**
     * The 16-bit one's-complement Internet checksum of RFC 1071.
     *
     * <p>Sixteen-bit words are summed with end-around carry, and the result is
     * complemented. An odd trailing byte is treated as the high half of a final
     * word. Because the sum is end-around, a receiver that sums the whole
     * datagram including the checksum field gets 0xFFFF; we use the more
     * explicit zero-and-recompute check in {@link #decode} instead, since it
     * reports the two values separately when they disagree.
     */
    public static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        int i = off;
        int end = off + len;
        for (; i + 1 < end; i += 2) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            sum = (sum & 0xFFFF) + (sum >>> 16);   // fold the carry back in
        }
        if (i < end) {                              // odd trailing byte
            sum += (b[i] & 0xFF) << 8;
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return ~sum & 0xFFFF;
    }

    public int wireLength() {
        return HEADER_LEN + payload.length;
    }

    private static void requireUnsigned32(String field, long v) {
        if (v < 0 || v > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(field + " out of range: " + v);
        }
    }

    private static void requireUnsigned16(String field, int v) {
        if (v < 0 || v > 0xFFFF) {
            throw new IllegalArgumentException(field + " out of range: " + v);
        }
    }

    public static String typeName(byte type) {
        switch (type) {
            case TYPE_DATA:   return "DATA";
            case TYPE_ACK:    return "ACK";
            case TYPE_SACK:   return "SACK";
            case TYPE_FIN:    return "FIN";
            case TYPE_FINACK: return "FINACK";
            default:          return "TYPE(" + type + ")";
        }
    }

    @Override
    public String toString() {
        return String.format("%s seq=%d ack=%d win=%d len=%d",
                typeName(type), seq, ack, window, payload.length);
    }
}
