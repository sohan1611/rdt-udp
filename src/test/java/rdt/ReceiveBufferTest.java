package rdt;

import java.util.List;

public final class ReceiveBufferTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + message);
        }
    }

    private static Packet packet(long seq) {
        return Packet.data(seq, new byte[] {(byte) seq});
    }

    private static void testInOrder() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        ReceiveBuffer.Result result = buffer.accept(packet(1));

        check(result.status() == ReceiveBuffer.Status.ACCEPTED,
                "packet 1 should be accepted");

        check(result.delivered().size() == 1,
                "packet should be delivered immediately");
    }

    private static void testOutOfOrder() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        check(buffer.accept(packet(1)).delivered().size() == 1,
                "packet 1 should be delivered");

        check(buffer.accept(packet(3)).delivered().isEmpty(),
                "packet 3 should wait for packet 2");

        check(buffer.accept(packet(4)).delivered().isEmpty(),
                "packet 4 should also wait");

        List<Packet> delivered = buffer.accept(packet(2)).delivered();

        check(delivered.size() == 3,
                "packets 2, 3 and 4 should be delivered together");

        check(delivered.get(0).seq == 2,
                "packet 2 should come first");

        check(delivered.get(1).seq == 3,
                "packet 3 should come second");

        check(delivered.get(2).seq == 4,
                "packet 4 should come last");
    }

    private static void testDuplicate() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        buffer.accept(packet(2));

        ReceiveBuffer.Result result = buffer.accept(packet(2));

        check(result.status() == ReceiveBuffer.Status.DUPLICATE,
                "duplicate packet should be detected");
    }

    private static void testPreviousWindow() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        buffer.accept(packet(1));

        ReceiveBuffer.Result result = buffer.accept(packet(1));

        check(result.status() == ReceiveBuffer.Status.PREVIOUS_WINDOW,
                "old packet should be marked as previous window");
    }

    private static void testOutsideWindow() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        ReceiveBuffer.Result result = buffer.accept(packet(5));

        check(result.status() != ReceiveBuffer.Status.ACCEPTED,
                "packet outside the window should not be accepted");
    }

    private static void testWindowBoundAtBufferLevel() {

        // Window 3 with sequence space 4 is not allowed.
        

        ReceiveBuffer illegalBuffer = new ReceiveBuffer(3, 0, 4);

        check(illegalBuffer.accept(packet(0)).status()
                        == ReceiveBuffer.Status.ACCEPTED,
                "packet 0 should be accepted");

        check(illegalBuffer.accept(packet(1)).status()
                        == ReceiveBuffer.Status.ACCEPTED,
                "packet 1 should be accepted");

        check(illegalBuffer.accept(packet(2)).status()
                        == ReceiveBuffer.Status.ACCEPTED,
                "packet 2 should be accepted");

        ReceiveBuffer.Result oldPacket =
                illegalBuffer.accept(packet(0));

        check(oldPacket.status() == ReceiveBuffer.Status.ACCEPTED,
                "old packet 0 shows the problem with a large window");


        // Window 2 with sequence space 4 is allowed.
        

        ReceiveBuffer legalBuffer = new ReceiveBuffer(2, 0, 4);

        check(legalBuffer.accept(packet(0)).status()
                        == ReceiveBuffer.Status.ACCEPTED,
                "packet 0 should be accepted");

        check(legalBuffer.accept(packet(1)).status()
                        == ReceiveBuffer.Status.ACCEPTED,
                "packet 1 should be accepted");

        ReceiveBuffer.Result oldPacketZero =
                legalBuffer.accept(packet(0));

        check(oldPacketZero.status()
                        == ReceiveBuffer.Status.PREVIOUS_WINDOW,
                "old packet 0 should be previous window");
    }

    private static void testWraparound() {
        ReceiveBuffer buffer = new ReceiveBuffer(3, 6, 8);

        check(buffer.accept(packet(6)).delivered().size() == 1,
                "packet 6 should be delivered");

        check(buffer.accept(packet(7)).delivered().size() == 1,
                "packet 7 should be delivered");

        check(buffer.accept(packet(0)).delivered().size() == 1,
                "packet 0 should be delivered after wraparound");

        check(buffer.accept(packet(1)).delivered().size() == 1,
                "packet 1 should be delivered");
    }

    public static void main(String[] args) {
        System.out.println("Running ReceiveBuffer tests...");

        testInOrder();
        testOutOfOrder();
        testDuplicate();
        testPreviousWindow();
        testOutsideWindow();
        testWindowBoundAtBufferLevel();
        testWraparound();

        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            throw new AssertionError("Some ReceiveBuffer tests failed");
        }
    }
}

