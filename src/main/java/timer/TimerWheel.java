/*
 * ============================================================ 
* TimerWheel.java -- M2: Sinjan Mishra 
* CS-30003 Reliable Data Transfer over UDP 
* ============================================================ 
* 
* USAGE EXAMPLE (socket receive loop) 
* ------------------------------------ 
* 
* TimerWheel wheel = new TimerWheel(); 
* 
* // Schedule a retransmit timeout of 500 ms for packet seq=3 
* TimerWheel.TimerHandle h = wheel.schedule(500, "retransmit:seq=3"); 
* 
* // Main receive loop 
* while (running) { 
*    long waitMs = wheel.timeUntilNextMs();    // how long to block 
*    socket.setSoTimeout((int) waitMs);        // 0 = return immediately 
*    try { 
*        socket.receive(packet); // ACK arrived 
*        int seq = parseAck(packet); 
*        wheel.cancel(pendingHandles[seq]);    // cancel its timer 
*        processAck(seq); 
*    } catch (SocketTimeoutException e) {      // timer fired 
*        TimerWheel.TimerHandle fired = wheel.poll(); 
*        if (fired != null) retransmit(fired.tag()); 
*    } 
* }
* 
* // To cancel before it fires:
* wheel.cancel(h);
*
* ============================================================ 
*/ 
package timer; 
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

 // TimerHandle — returned to caller, used to cancel 
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

/*  Schedule a timer delayMs ms from now. 
 *  Returns handle to cancel later. 
*/ 
    public TimerHandle schedule(long delayMs, String tag) { 
        if (delayMs <= 0){ 
            throw new IllegalArgumentException(
                  "delayMs must be positive");
        }           
        long deadlineNanos = System.nanoTime() + delayMs * 1_000_000L; 
        TimerEntry entry = new TimerEntry(deadlineNanos, tag); 
        heap.offer(entry); 
        return new TimerHandle(entry); 
    } 
    public TimerHandle schedule(long delayMs) { 
        return schedule(delayMs, null); 
    } 

/** Lazy O(1) cancel — marks entry, discarded when it surfaces at head. 
*/ 

    public void cancel(TimerHandle handle) { 
        if (handle != null){ 
            handle.entry.cancelled = true; 
        } 
    } 

/** Earliest live deadline in nanos, or Long.MAX_VALUE if none. 
*/ 

    public long earliestDeadline() { 
        purgeCancelledHead(); 
        TimerEntry head = heap.peek(); 
        return (head == null) ? Long.MAX_VALUE : head.deadlineNanos; 
    } 

/** Ms until next timer fires — pass directly to setSoTimeout(). 
*/ 

    public long timeUntilNextMs() { 
        long deadline = earliestDeadline(); 
        if (deadline == Long.MAX_VALUE){ 
            return Long.MAX_VALUE;
        }     
        long remaining = deadline - System.nanoTime(); 
        if (remaining <= 0){ 
            return 0L; 
        }    
        return (remaining + 999_999L) / 1_000_000L; 
    } 
    
/** Call after SocketTimeoutException — returns fired handle or null. 
*/ 
    public TimerHandle poll() {
         purgeCancelledHead(); 
         TimerEntry head = heap.peek(); 
         if (head == null) return null; 
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