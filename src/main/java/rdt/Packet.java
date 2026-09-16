package rdt;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
public final class Packet
{
    public static final int HEADER_LEN = 20;
    public static final byte VERSION = 1;
    public static final byte TYPE_DATA = 0;
    public static final byte TYPE_ACK = 1;
    public static final byte TYPE_SACK = 2;
    public static final byte TYPE_FIN = 3;
    public static final byte TYPE_FINACK = 4;
    private static final int CHECKSUM_OFFSET = 16;
    private static final byte[] NO_PAYLOAD = new byte[0];
    public final byte version;
    public final byte type;
    public final byte flags;
    public final long seq;
    public final long ack;
    public final int window;
    public final int sackCount;
    public final byte[] payload;
    public Packet(byte type, long seq, long ack, int window, int sackCount, byte[] payload)
    {
        this(VERSION, type, (byte) 0, seq, ack, window, sackCount, payload);
    }
    public Packet(byte version, byte type, byte flags, long seq, long ack, int window, int sackCount, byte[] payload)
    {
        byte[] data = (payload == null) ? NO_PAYLOAD : payload;
        if (data.length > 0xFFFF)
        {
            throw new IllegalArgumentException("Payload too large: " + data.length);
        }
        checkUnsigned32("seq", seq);
        checkUnsigned32("ack", ack);
        checkUnsigned16("window", window);
        checkUnsigned16("sackCount", sackCount);
        this.version = version;
        this.type = type;
        this.flags = flags;
        this.seq = seq;
        this.ack = ack;
        this.window = window;
        this.sackCount = sackCount;
        this.payload = data;
    }
    public static Packet data(long seq, byte[] payload)
    {
        return new Packet(TYPE_DATA, seq, 0, 0, 0, payload);
    }

    public static Packet ack(long ack, int window)
    {
        return new Packet(TYPE_ACK, 0, ack, window, 0, NO_PAYLOAD);
    }
    public byte[] encode()
    {
        byte[] result = new byte[HEADER_LEN + payload.length];
        ByteBuffer buffer = ByteBuffer.wrap(result);
        buffer.put(version);
        buffer.put(type);
        buffer.put(flags);
        buffer.put((byte) 0);
        buffer.putInt((int) seq);
        buffer.putInt((int) ack);
        buffer.putShort((short) payload.length);
        buffer.putShort((short) window);
        buffer.putShort((short) 0);
        buffer.putShort((short) sackCount);
        buffer.put(payload);
        int sum = checksum(result, 0, result.length);
        result[CHECKSUM_OFFSET] = (byte) (sum >>> 8);
        result[CHECKSUM_OFFSET + 1] = (byte) sum;
        return result;
    }
    public static Packet decode(byte[] buf, int len) throws CorruptPacketException
    {
        if (len < HEADER_LEN)
        {
            throw new CorruptPacketException("runt packet: " + len + " bytes");
        }
        int received = ((buf[CHECKSUM_OFFSET] & 0xFF) << 8) | (buf[CHECKSUM_OFFSET + 1] & 0xFF);
        byte[] checkData = Arrays.copyOf(buf, len);
        checkData[CHECKSUM_OFFSET] = 0;
        checkData[CHECKSUM_OFFSET + 1] = 0;
        int computed = checksum(checkData, 0, len);
        if (computed != received)
        {
            throw new CorruptPacketException(String.format("checksum mismatch: got 0x%04X, computed 0x%04X", received, computed));
        }
        try
        {
            ByteBuffer buffer = ByteBuffer.wrap(buf, 0, len);
            byte version = buffer.get();
            if (version != VERSION)
            {
                throw new CorruptPacketException("unsupported version: " + version);
            }
            byte type = buffer.get();
            byte flags = buffer.get();
            buffer.get();
            long seq = Integer.toUnsignedLong(buffer.getInt());
            long ack = Integer.toUnsignedLong(buffer.getInt());
            int payloadLen = Short.toUnsignedInt(buffer.getShort());
            int window = Short.toUnsignedInt(buffer.getShort());
            buffer.getShort();
            int sackCount = Short.toUnsignedInt(buffer.getShort());
            if (payloadLen != len - HEADER_LEN)
            {
                throw new CorruptPacketException("declared payload " + payloadLen + " but " + (len - HEADER_LEN) + " bytes arrived");
            }
            byte[] payload = new byte[payloadLen];
            buffer.get(payload);
            return new Packet(version, type, flags, seq, ack, window, sackCount, payload);
        }
        catch (BufferUnderflowException e)
        {
            throw new CorruptPacketException("truncated header");
        }
    }
    public static int checksum(byte[] b, int off, int len)
    {
        int sum = 0;
        int i = off;
        int end = off + len;
        for (; i + 1 < end; i += 2)
        {
            int word = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            sum += word;
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        if (i < end)
        {
            sum += (b[i] & 0xFF) << 8;
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return ~sum & 0xFFFF;
    }
    public int wireLength()
    {
        return HEADER_LEN + payload.length;
    }
    private static void checkUnsigned32(String field, long value)
    {
        if (value < 0 || value > 0xFFFFFFFFL)
        {
            throw new IllegalArgumentException(field + " out of range: " + value);
        }
    }
    private static void checkUnsigned16(String field, int value)
    {
        if (value < 0 || value > 0xFFFF)
        {
            throw new IllegalArgumentException(field + " out of range: " + value);
        }
    }
    public static String typeName(byte type)
    {
        switch (type)
        {
            case TYPE_DATA:
                return "DATA";
            case TYPE_ACK:
                return "ACK";
            case TYPE_SACK:
                return "SACK";
            case TYPE_FIN:
                return "FIN";
            case TYPE_FINACK:
                return "FINACK";
            default:
                return "TYPE(" + type + ")";
        }
    }
    @Override
    public String toString()
    {
        return String.format("%s seq=%d ack=%d win=%d len=%d",typeName(type), seq, ack, window, payload.length);
    }
}