# Timer Heap Design Note 
**Author:** Sinjan Mishra (M2) **Date:** 16 September 2026 **File:** `docs/timer-heap-design.md` 
## 1. What a Timer Heap Is 
A timer heap is a min-heap — implemented here as a `PriorityQueue<TimerEntry>` — whose elements are deadlines expressed as
absolute nanosecond timestamps from `System.nanoTime()`. The heap invariant guarantees that the entry with the **earliest 
expiry is always at the head**, so both insertion (`schedule`) and minimum-deadline lookup (`earliestDeadline`) are O(log n)
and O(1) respectively. Cancelled entries are not removed eagerly; they carry a `cancelled` flag and are discarded lazily when 
they surface at the head during a poll. 
## 2. Why a Single Thread Instead of a Timer Per Packet 
A per-packet timer model requires each outstanding packet to own a concurrent timer object (e.g. `ScheduledExecutorService`, 
`java.util.Timer`). This introduces a **race condition**: a timer thread can fire a retransmit at the same moment the I/O 
thread processes an incoming ACK for the same packet, requiring locks or atomic state to arbitrate. In a student 
implementation that race is the dominant source of heisenbugs. The single-threaded model eliminates the race entirely. There 
is exactly one thread of control: the socket receive loop. A timeout and an arriving ACK are **both just events on that one 
loop** — the thread either returns from `receive()` with a packet, or it times out. No synchronisation is needed because there
is nothing to synchronise. 
## 3. How the Socket Receive Timeout Derives from the Earliest Deadline
At the top of every loop iteration the receiver thread consults the heap:
```java long timeoutNs = 
timerWheel.earliestDeadline() - System.nanoTime();
if (timeoutNs <= 0) { handleTimeout(); continue; }
socket.setSoTimeout((int) TimeUnit.NANOSECONDS.toMillis(timeoutNs));
socket.receive(packet);
```
The difference earliestDeadline() - System.nanoTime() is the exact duration to
block. If it is already non-positive the timeout has expired before the call and is
handled immediately. Otherwise setSoTimeout configures the socket to unblock after
exactly that many milliseconds. The result is a tight, non-drifting timeout
derived mechanically from the heap, with no busy-waiting and no separate scheduling
thread.
