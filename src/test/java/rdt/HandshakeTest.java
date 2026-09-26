package rdt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HandshakeTest
{
    private HandshakeTest()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Harness h = new Harness("HandshakeTest");

        h.check(
                "format then parse preserves metadata",
                HandshakeTest::formatThenParse
        );

        h.check(
                "bad file size is rejected",
                HandshakeTest::badSizeRejected
        );

        h.check(
                "filename containing line break is rejected",
                HandshakeTest::badFilenameRejected
        );

        h.done();
    }

    private static String sha256(Path file) throws Exception
    {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");

        byte[] data = Files.readAllBytes(file);
        byte[] hash = digest.digest(data);

        StringBuilder result = new StringBuilder();
        for (byte b : hash)
        {
            result.append(String.format("%02x", b & 0xff));
        }
        return result.toString();
    }

    private static void formatThenParse() throws Exception
    {
        Path file = Files.createTempFile(
                "handshake-test",
                ".txt"
        );

        try
        {
            byte[] data =
                    "HandshakeTest metadata check\n"
                            .getBytes(StandardCharsets.UTF_8);

            Files.write(file, data);

            byte[] meta = Session.createMeta(file);
            Session.MetaInfo info = Session.parseMeta(meta);

            Harness.assertEquals(
                    "filename",
                    file.getFileName().toString(),
                    info.filename
            );

            Harness.assertEquals(
                    "file size",
                    Files.size(file),
                    info.size
            );

            Harness.assertEquals(
                    "sha256",
                    sha256(file),
                    info.sha256
            );
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    private static void badSizeRejected() throws Exception
    {
        byte[] meta = (
                "test.txt\n"
                        + "not-a-number\n"
                        + "abc123"
        ).getBytes(StandardCharsets.UTF_8);

        Harness.assertThrows(
                "bad size",
                java.io.IOException.class,
                () -> Session.parseMeta(meta)
        );
    }

    private static void badFilenameRejected() throws Exception
    {
        byte[] meta = (
                "bad\nname.txt\n"
                        + "10\n"
                        + "abc123"
        ).getBytes(StandardCharsets.UTF_8);

        Harness.assertThrows(
                "filename containing line break",
                java.io.IOException.class,
                () -> Session.parseMeta(meta)
        );
    }
}
