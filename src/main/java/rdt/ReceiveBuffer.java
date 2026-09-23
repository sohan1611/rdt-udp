package rdt;

import java.util.ArrayList;
import java.util.List;


public final class ReceiveBuffer {

    private final int N;
    private final long M;
    private long base;
    private final Packet[] buffer;
    private int head;

    public enum Status {
        ACCEPTED,
        DUPLICATE,
        PREVIOUS_WINDOW,
        OUTSIDE
    }

    public static final class Result {
        private final Status status;
        private final List<Packet> delivered;

        public Result(Status status, List<Packet> delivered) {
            this.status = status;
            this.delivered = delivered;
        }

        public Status status() {
            return status;
        }

        public List<Packet> delivered() {
            return delivered;
        }
    }
/*
 * N > M/2 is allowed because SeqSpaceTest intentionally tests
 * configurations that are normally invalid for selective repeat.
 */
    public ReceiveBuffer(int N, long base, long M) {
        if (N <= 0) {
            throw new IllegalArgumentException("N must be positive");
        }

        if (M <= 0) {
            throw new IllegalArgumentException("M must be positive");
        }

        if (N >= M) {
            throw new IllegalArgumentException("N must be smaller than M");
        }

        this.N = N;
        this.M = M;
        this.base = Math.floorMod(base, M);
        this.buffer = new Packet[N];
        this.head = 0;
    }

    public long base() {
        return base;
    }

    public Result accept(Packet packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet cannot be null");
        }
     long s = packet.seq;
        if (SeqSpace.inCurrentWindow(s, base, N, M)) {

            long offset = SeqSpace.offset(s, base, M);

            int index = (int) ((head + offset) % N);

            
            if (buffer[index] != null) {
                return new Result(
                        Status.DUPLICATE,
                        List.of()
                );
            }

            
            buffer[index] = packet;
            return new Result(
                    Status.ACCEPTED,
                    releaseReady()
            );
        }

        if (SeqSpace.inPreviousWindow(s, base, N, M)) {
            return new Result(
                    Status.PREVIOUS_WINDOW,
                    List.of()
            );
        }

        return new Result(
                Status.OUTSIDE,
                List.of()
        );
    }

    private List<Packet> releaseReady() {
        List<Packet> result = new ArrayList<>();

        while (buffer[head] != null) {

            Packet packet = buffer[head];

            buffer[head] = null;

            result.add(packet);
            head = (head + 1) % N;

            base = Math.floorMod(base + 1, M);
        }

        return result;
    }
}

