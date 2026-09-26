package rdt;

public final class SelectiveRepeatTest {

    public static void main(String[] args) {
        testWindow();
        testAckSliding();
        testReceive();

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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}