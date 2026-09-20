package rdt;

import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Session
{
    private static final byte META_FLAG = 0x01;

    private Session()
    {
    }

    public static byte[] createMeta(Path file) throws IOException
    {
        String filename = file.getFileName().toString();
        long size = Files.size(file);

        String sha256 = sha256(file);

        String meta =
                filename + "\n"
                        + size + "\n"
                        + sha256;

        return meta.getBytes(StandardCharsets.UTF_8);
    }

    public static Packet createMetaPacket(Path file) throws IOException
    {
        return new Packet(
                Packet.VERSION,
                Packet.TYPE_DATA,
                META_FLAG,
                0,
                0,
                0,
                0,
                createMeta(file)
        );
    }

    public static Packet createFinPacket(long seq)
    {
        return new Packet(
                Packet.VERSION,
                Packet.TYPE_FIN,
                (byte) 0,
                seq,
                0,
                0,
                0,
                new byte[0]
        );
    }

    public static Packet createFinAckPacket(long seq)
    {
        return new Packet(
                Packet.VERSION,
                Packet.TYPE_FINACK,
                (byte) 0,
                seq,
                0,
                0,
                0,
                new byte[0]
        );
    }

    public static MetaInfo parseMeta(byte[] payload) throws IOException
    {
        String meta = new String(payload, StandardCharsets.UTF_8);

        String[] lines = meta.split("\n", -1);

        if (lines.length != 3)
        {
            throw new IOException("Invalid META");
        }

        if (lines[0].contains("\r") || lines[0].contains("\n"))
        {
            throw new IOException("Invalid filename");
        }

        long size;

        try
        {
            size = Long.parseLong(lines[1]);
        }
        catch (NumberFormatException e)
        {
            throw new IOException("Invalid file size", e);
        }

        if (size < 0)
        {
            throw new IOException("Invalid file size");
        }

        return new MetaInfo(
                lines[0],
                size,
                lines[2]
        );
    }

    public static boolean verifySha256(Path file, String expected)
            throws IOException
    {
        return sha256(file).equalsIgnoreCase(expected);
    }

    public static final class MetaInfo
    {
        public final String filename;
        public final long size;
        public final String sha256;

        public MetaInfo(String filename, long size, String sha256)
        {
            this.filename = filename;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static String sha256(Path file) throws IOException
    {
        try
        {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");

            try (java.io.InputStream input = Files.newInputStream(file))
            {
                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1)
                {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            byte[] hash = digest.digest();

            StringBuilder result = new StringBuilder();

            for (byte b : hash)
            {
                result.append(String.format("%02x", b & 0xff));
            }

            return result.toString();
        }
        catch (java.security.NoSuchAlgorithmException e)
        {
            throw new IOException("SHA-256 not available", e);
        }
    }
}