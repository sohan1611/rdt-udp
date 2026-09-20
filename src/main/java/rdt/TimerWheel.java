package rdt; 
import java.util.PriorityQueue; 
public class TimerWheel { 
    private static class TimerEntry implements Comparable<TimerEntry> { 
        final long deadlineNanos; 
        final String tag; 
        volatile boolean cancelled = false; 
        TimerEntry(long deadlineNanos, String tag) { 
            this.deadlineNanos = deadlineNanos; 
            this.tag = tag; 
        } 
        @Override 
        public int compareTo(TimerEntry other) { 
            return Long.compare(this.deadlineNanos, other.deadlineNanos); 
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
    public TimerHandle schedule(long delayMs, String tag) { 
        if (delayMs <= 0) 
            throw new IllegalArgumentException("delayMs must be positive"); 
        long deadlineNanos = System.nanoTime() + delayMs * 1_000_000L; 
        TimerEntry entry = new TimerEntry(deadlineNanos, tag); 
        heap.offer(entry); 
        return new TimerHandle(entry); 
    } 
    public TimerHandle schedule(long delayMs) { 
        return schedule(delayMs, null); 
    } 
    public void cancel(TimerHandle handle) { 
        if (handle != null) 
            handle.entry.cancelled = true; 
    } 
    public long earliestDeadline() { 
        purgeCancelledHead(); 
        TimerEntry head = heap.peek(); 
        return (head == null) ? Long.MAX_VALUE : head.deadlineNanos; 
    } 
    public long timeUntilNextMs() { 
        long deadline = earliestDeadline(); 
        if (deadline == Long.MAX_VALUE) 
            return Long.MAX_VALUE; 
        long remaining = deadline - System.nanoTime(); 
        if (remaining <= 0) 
            return 0L; 
        return (remaining + 999_999L) / 1_000_000L; 
    } 
    public TimerHandle poll() { 
        purgeCancelledHead(); 
        TimerEntry head = heap.peek(); 
        if (head == null) 
            return null; 
        if (head.deadlineNanos <= System.nanoTime()) { 
            heap.poll(); 
            return new TimerHandle(head); 
        } 
        return null; 
    } 
    public int size() { 
        return heap.size(); 
    } 
    public boolean isEmpty() { 
        return earliestDeadline() == Long.MAX_VALUE; 
    } 
    private void purgeCancelledHead() { 
        while (!heap.isEmpty() && heap.peek().cancelled) 
            heap.poll(); 
    } 
}