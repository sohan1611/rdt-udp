
package rdt;

public final class SelectiveRepeatTest {

    public static void main(String[] args) throws InterruptedException {
        testWindow();
        testAckSliding();
        testReceive();

        testOnlyUnacknowledgedRetransmitted();
        testTimerRestart();
        testWraparound();
        testInvalidWindow();

        System.out.println("All SelectiveRepeat tests passed");
    }

    private static void testWindow() {
        SelectiveRepeat sr = new SelectiveRepeat(4, 8);

        check(sr.windowHasSpace(),
                "window should have space at start");

        sr.createDataPacket(new byte[] {1});
        sr.createDataPacket(new byte[] {2});
        sr.createDataPacket(new byte[] {3});
        sr.createDataPacket(new byte[] {4});

        check(!sr.windowHasSpace(),
                "window should be full after 4 packets");

        check(sr.sendBase() == 0,
                "send base should be 0");

        check(sr.nextSeq() == 4,
                "next sequence should be 4");
    }

    private static void testAckSliding() {
        SelectiveRepeat sr = new SelectiveRepeat(4, 8);

        sr.createDataPacket(new byte[] {1});
        sr.createDataPacket(new byte[] {2});
        sr.createDataPacket(new byte[] {3});

        check(sr.receiveAck(1),
                "ACK 1 should be received");

        check(sr.sendBase() == 0,
                "base should stay at 0 because packet 0 is not ACKed");

        check(sr.receiveAck(0),
                "ACK 0 should be received");

        check(sr.sendBase() == 2,
                "base should move to 2 after ACKs 0 and 1");

        check(sr.receiveAck(2),
                "ACK 2 should be received");

        check(sr.sendBase() == 3,
                "base should move to 3");
    }

    private static void testReceive() {
        SelectiveRepeat sr = new SelectiveRepeat(4, 8);

        Packet packet = Packet.data(0, new byte[] {10, 20});

        SelectiveRepeat.ReceiveResult result =
                sr.receiveData(packet);

        check(result.status() == ReceiveBuffer.Status.ACCEPTED,
                "received packet should be accepted");

        check(result.ack() != null,
                "receiver should send an ACK");

        check(result.ack().ack == 0,
                "ACK should have sequence number 0");

        check(result.delivered().size() == 1,
                "received packet should be delivered");
    }

    private static void testOnlyUnacknowledgedRetransmitted()
            throws InterruptedException {

        SelectiveRepeat sr = new SelectiveRepeat(4, 8, 50);

        for (int i = 0; i < 4; i++) {
            sr.createDataPacket(new byte[] {(byte) i});
        }

        check(sr.receiveAck(0),
                "ACK 0 should be received");

        check(sr.receiveAck(2),
                "ACK 2 should be received");

        Thread.sleep(80);

        Packet first = sr.pollExpiredRetransmission();
        Packet second = sr.pollExpiredRetransmission();

        check(first != null && first.seq == 1,
                "packet 1 should be retransmitted");

        check(second != null && second.seq == 3,
                "packet 3 should be retransmitted");

        check(sr.pollExpiredRetransmission() == null,
                "no other packet should be retransmitted");
    }

    private static void testTimerRestart()
            throws InterruptedException {

        SelectiveRepeat sr = new SelectiveRepeat(2, 4, 50);

        sr.createDataPacket(new byte[] {1});

        Thread.sleep(80);

        Packet retransmission =
                sr.pollExpiredRetransmission();

        check(retransmission != null && retransmission.seq == 0,
                "packet 0 should be retransmitted after timeout");

        check(sr.pollExpiredRetransmission() == null,
                "packet 0 timer should restart");

        Thread.sleep(80);

        Packet next = sr.pollExpiredRetransmission();

        check(next != null && next.seq == 0,
                "packet 0 should retransmit again after timeout");
    }

    private static void testWraparound() {
        SelectiveRepeat sr = new SelectiveRepeat(2, 4);

        long[] expected = {
                0, 1, 2, 3, 0, 1, 2, 3, 0, 1
        };

        for (long seq : expected) {
            Packet packet =
                    sr.createDataPacket(new byte[] {1});

            check(packet != null,
                    "packet should be created");

            check(packet.seq == seq,
                    "sequence number should wrap correctly");

            check(sr.receiveAck(seq),
                    "ACK should be received");
        }

        check(sr.sendBase() == 2,
                "send base should wrap to 2");

        check(sr.nextSeq() == 2,
                "next sequence should wrap to 2");
    }

    private static void testInvalidWindow() {

        // Window 3 with sequence space 4 is too large.

        boolean rejectedSmallSequenceSpace = false;

        try {
            new SelectiveRepeat(3, 4);
        } catch (IllegalArgumentException e) {
            rejectedSmallSequenceSpace = true;
        }

        check(rejectedSmallSequenceSpace,
                "window 3 should be rejected for sequence space 4");


        // With 3 sequence bits, sequence space is 8.Window 5 is too large because the limit is 4.

        boolean rejectedWindowFive = false;

        try {
            new SelectiveRepeat(5, 8);
        } catch (IllegalArgumentException e) {
            rejectedWindowFive = true;
        }

        check(rejectedWindowFive,
                "window 5 should be rejected for sequence space 8");


        // Window 4 is exactly half of sequence space 8.
        

        boolean acceptedWindowFour = true;

        try {
            new SelectiveRepeat(4, 8);
        } catch (IllegalArgumentException e) {
            acceptedWindowFour = false;
        }

        check(acceptedWindowFour,
                "window 4 should be accepted for sequence space 8");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
