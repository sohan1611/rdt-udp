package rdt; 
import java.util.PriorityQueue; 
public final class TimerWheel { 
    private static class TimerEntry implements Comparable<TimerEntry> { 
        final long deadlineNanos; 
        final String tag; 
        final long insertionOrder; 
        boolean cancelled = false; 
        TimerEntry(long deadlineNanos, String tag, long insertionOrder) { 
            this.deadlineNanos = deadlineNanos; 
            this.tag = tag; this.insertionOrder = insertionOrder; 
        } 
        @Override
        public int compareTo(TimerEntry other) { 
            int cmp = Long.compare(this.deadlineNanos, other.deadlineNanos); 
            if (cmp != 0) 
                return cmp; 
            return Long.compare(this.insertionOrder, other.insertionOrder); 
        } 
    } 
    public static class TimerHandle { 
        private final TimerEntry entry; 
        private TimerHandle(TimerEntry entry) { 
            this.entry = entry; 
        } 
        public String tag() { 
            return entry.tag; 
        } 
        public long deadlineNanos() { 
            return entry.deadlineNanos; 
        } 
        public boolean isCancelled() { 
            return entry.cancelled; 
        } 
    } 
    private final PriorityQueue<TimerEntry> heap = new PriorityQueue<>(); 
    private long insertionCounter = 0; 
    private int liveCount = 0; 
    public TimerHandle schedule(long delayMs, String tag) { 
        if (delayMs <= 0) 
            throw new IllegalArgumentException("delayMs must be positive"); 
        long deadlineNanos = System.nanoTime() + delayMs * 1_000_000L; 
        TimerEntry entry = new TimerEntry(deadlineNanos, tag, insertionCounter++); 
        heap.offer(entry); 
        liveCount++; 
        return new TimerHandle(entry); 
    } 
    public TimerHandle schedule(long delayMs) { 
        return schedule(delayMs, null); 
    } 
    public void cancel(TimerHandle handle) { 
        if (handle != null && !handle.entry.cancelled) {
             handle.entry.cancelled = true; liveCount--; 
        } 
    } 
    public long earliestDeadline() { 
        purgeCancelledHead(); 
        TimerEntry head = heap.peek(); 
        return (head == null) ? Long.MAX_VALUE : head.deadlineNanos; 
    } 
    public long timeUntilNextMs() { 
        long deadline = earliestDeadline(); 
        if (deadline == Long.MAX_VALUE) 
            return 0L; 
        long remaining = deadline - System.nanoTime(); 
        if (remaining <= 0) 
            return 1L; 
        long ms = (remaining + 999_999L) / 1_000_000L; 
        return Math.min(ms, (long) Integer.MAX_VALUE); 
    } 
    public TimerHandle poll() { 
        purgeCancelledHead(); 
        TimerEntry head = heap.peek(); 
        if (head == null) 
            return null; 
        if (head.deadlineNanos <= System.nanoTime()) {
             heap.poll(); 
             liveCount--; 
             return new TimerHandle(head); 
        } 
        return null; 
    } 
    public int size() { 
        return liveCount; 
    }
    public boolean isEmpty() { 
        return liveCount == 0; 
    } 
    private void purgeCancelledHead() { 
        while (!heap.isEmpty() && heap.peek().cancelled) 
            heap.poll(); 
    } 
}