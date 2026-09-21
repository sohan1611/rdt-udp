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
                "in-order packet should be accepted");

        check(result.delivered().size() == 1,
                "in-order packet should be delivered immediately");
    }

    private static void testOutOfOrder() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        check(buffer.accept(packet(1)).delivered().size() == 1,
                "packet 1 should release");

        check(buffer.accept(packet(3)).delivered().isEmpty(),
                "packet 3 should wait");

        check(buffer.accept(packet(4)).delivered().isEmpty(),
                "packet 4 should wait");

        List<Packet> delivered = buffer.accept(packet(2)).delivered();

        check(delivered.size() == 3,
                "packets 2, 3, 4 should release together");

        check(delivered.get(0).seq == 2,
                "first released packet should be 2");

        check(delivered.get(1).seq == 3,
                "second released packet should be 3");

        check(delivered.get(2).seq == 4,
                "third released packet should be 4");
    }

    private static void testDuplicate() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        buffer.accept(packet(2));

        ReceiveBuffer.Result result = buffer.accept(packet(2));

        check(result.status() == ReceiveBuffer.Status.DUPLICATE,
                "stored duplicate should be reported as duplicate");
    }

    private static void testPreviousWindow() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        buffer.accept(packet(1));

        ReceiveBuffer.Result result = buffer.accept(packet(1));

        check(result.status() == ReceiveBuffer.Status.PREVIOUS_WINDOW,
                "delivered packet should be previous-window");
    }

    private static void testOutsideWindow() {
        ReceiveBuffer buffer = new ReceiveBuffer(4, 1, 8);

        ReceiveBuffer.Result result = buffer.accept(packet(5));

        check(result.status() != ReceiveBuffer.Status.ACCEPTED,
                "outside packet should not be accepted");
    }

    private static void testWraparound() {
        ReceiveBuffer buffer = new ReceiveBuffer(3, 6, 8);

        check(buffer.accept(packet(6)).delivered().size() == 1,
                "6 should release");

        check(buffer.accept(packet(7)).delivered().size() == 1,
                "7 should release");

        check(buffer.accept(packet(0)).delivered().size() == 1,
                "0 should release");

        check(buffer.accept(packet(1)).delivered().size() == 1,
                "1 should release");
    }

    public static void main(String[] args) {
        System.out.println("Running ReceiveBuffer tests...");

        testInOrder();
        testOutOfOrder();
        testDuplicate();
        testPreviousWindow();
        testOutsideWindow();
        testWraparound();

        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            throw new AssertionError(
                    "ReceiveBuffer tests failed! Check logs above."
            );
        }
    }
}


