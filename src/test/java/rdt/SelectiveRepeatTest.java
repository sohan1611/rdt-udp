
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

        check(sr.windowHasSpace(), "window should initially have space");

        sr.createDataPacket(new byte[] {1});
        sr.createDataPacket(new byte[] {2});
        sr.createDataPacket(new byte[] {3});
        sr.createDataPacket(new byte[] {4});

        check(!sr.windowHasSpace(), "window should be full");
        check(sr.sendBase() == 0, "send base should be 0");
        check(sr.nextSeq() == 4, "next sequence should be 4");
    }

    private static void testAckSliding() {
        SelectiveRepeat sr = new SelectiveRepeat(4, 8);

        sr.createDataPacket(new byte[] {1});
        sr.createDataPacket(new byte[] {2});
        sr.createDataPacket(new byte[] {3});

        check(sr.receiveAck(1), "ACK 1 should be accepted");
        check(sr.sendBase() == 0,
                "base should not move past unACKed packet 0");

        check(sr.receiveAck(0), "ACK 0 should be accepted");
        check(sr.sendBase() == 2,
                "base should slide over packets 0 and 1");

        check(sr.receiveAck(2), "ACK 2 should be accepted");
        check(sr.sendBase() == 3,
                "base should move to 3");
    }

    private static void testReceive() {
        SelectiveRepeat sr = new SelectiveRepeat(4, 8);

        Packet packet = Packet.data(0, new byte[] {10, 20});

        SelectiveRepeat.ReceiveResult result =
                sr.receiveData(packet);

        check(result.status() == ReceiveBuffer.Status.ACCEPTED,
                "packet should be accepted");

        check(result.ack() != null,
                "receiver should generate an ACK");

        check(result.ack().ack == 0,
                "ACK should contain sequence number 0");

        check(result.delivered().size() == 1,
                "packet should be delivered");
    }

    private static void testOnlyUnacknowledgedRetransmitted()
            throws InterruptedException {
        SelectiveRepeat sr = new SelectiveRepeat(4, 8, 50);

        for (int i = 0; i < 4; i++) {
            sr.createDataPacket(new byte[] {(byte) i});
        }

        check(sr.receiveAck(0), "ACK 0 should be accepted");
        check(sr.receiveAck(2), "ACK 2 should be accepted");

        Thread.sleep(80);

        Packet first = sr.pollExpiredRetransmission();
        Packet second = sr.pollExpiredRetransmission();

        check(first != null && first.seq == 1,
                "first retransmission should be packet 1");
        check(second != null && second.seq == 3,
                "second retransmission should be packet 3");
        check(sr.pollExpiredRetransmission() == null,
                "there should be no more expired packets");
    }

    private static void testTimerRestart()
            throws InterruptedException {
        SelectiveRepeat sr = new SelectiveRepeat(2, 4, 50);

        sr.createDataPacket(new byte[] {1});

        Thread.sleep(80);

        Packet retransmission = sr.pollExpiredRetransmission();
        check(retransmission != null && retransmission.seq == 0,
                "packet 0 should be retransmitted");

        check(sr.pollExpiredRetransmission() == null,
                "restarted timer should not expire immediately");

        Thread.sleep(80);

        Packet next = sr.pollExpiredRetransmission();
        check(next != null && next.seq == 0,
                "packet 0 should retransmit after its restarted timer");
    }

    private static void testWraparound() {
        SelectiveRepeat sr = new SelectiveRepeat(2, 4);

        long[] expected = {0, 1, 2, 3, 0, 1, 2, 3, 0, 1};

        for (long seq : expected) {
            Packet packet = sr.createDataPacket(new byte[] {1});

            check(packet != null, "packet should be created");
            check(packet.seq == seq,
                    "wrong sequence number across wraparound");

            check(sr.receiveAck(seq), "ACK should be accepted");
        }

        check(sr.sendBase() == 2,
                "send base should wrap to 2");
        check(sr.nextSeq() == 2,
                "next sequence should wrap to 2");
    }

    private static void testInvalidWindow() {
        boolean rejected = false;

        try {
            new SelectiveRepeat(3, 4);
        } catch (IllegalArgumentException e) {
            rejected = e.getMessage().contains(
                    "windowSize <= sequenceSpace/2");
        }

        check(rejected,
                "W=3, M=4 should be rejected with the window rule");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}