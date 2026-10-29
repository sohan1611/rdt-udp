M3 — Selective Repeat (SR)
Reliable Data Transfer over UDP
Student: Sohini Pandit
Component: M3 — Selective Repeat
Status: Successful file-transfer experiment
Date: 26/10/2026
1. Objective
The objective of M3 was to implement and evaluate Selective Repeat (SR) for reliable data transfer over UDP.

UDP does not itself guarantee delivery, ordering, or retransmission. Selective Repeat uses sequence numbers, sender and receiver windows, acknowledgements, buffering, and retransmission to provide reliable delivery.
2. Selective Repeat Overview
2.1 Sequence-number space and window
For a sequence-number field of \(k\) bits, the sequence-number space is:
\[
M = 2^k
\]
Selective Repeat limits its window size to at most half the sequence-number space:
\[
W \leq \frac{M}{2}
\]
This prevents ambiguity between old and new packets when sequence numbers wrap around.

For the reported experiment, the sender used a window size of 4 and 3 sequence-number bits, giving a sequence-number space of 8.
2.2 Sender behavior
The sender tracks packets currently outstanding in its sending window. When window space is available, it sends a DATA packet.

When an ACK is received, the sender marks the corresponding packet as acknowledged. The sender's base advances when the packets at the beginning of the window have been acknowledged.

If an outstanding packet's timer expires, Selective Repeat retransmits that packet individually rather than retransmitting the complete window.
2.3 Receiver behavior
The receiver accepts packets that belong to its receive window and stores them in its receive buffer.

Packets that arrive out of order can remain buffered while the receiver waits for the missing packet. When the gap is filled, the receiver can deliver the buffered data in order.
3. Timer and retransmission
The implementation uses the project's TimerWheel component to schedule retransmission timers for outstanding packets.

When a packet is sent, its timer is scheduled. When its acknowledgement arrives, the corresponding timer is cancelled. If the timer expires before acknowledgement, that packet is retransmitted.

This allows the sender to recover from packet loss without unnecessarily retransmitting already acknowledged packets.
4. File-transfer experiment
4.1 Configuration
Parameter	Value
Protocol	Selective Repeat (sr)
Window size	4
Sequence-number bits	3
Retransmission timeout	Fixed 1.5 seconds
Input file size	1,048,576 bytes (1 MiB)
Emulator loss	5% in each direction
Emulator delay	20 ms
Emulator jitter	5 ms
Emulator seed	7
Input file	/tmp/in.bin
Output file	/tmp/out.bin


4.2 Successful sender result
The completed sender run reported:
RESULT {
  "protocol": "sr",
  "window": 4,
  "seqbits": 3,
  "rto_mode": "fixed:1.5",
  "file_bytes": 1048576,
  "elapsed_ms": 11809.581,
  "goodput_bps": 710322.251,
  "wire_bytes": 1194196,
  "throughput_bps": 808967.581,
  "data_sent": 841,
  "retransmissions": 92,
  "timeouts": 92,
  "dup_acks": 0,
  "acks_received": 749,
  "corrupt_dropped": 0,
  "sha256_match": true
}
The sender completed the transfer and reported sha256_match: true.
4.3 SHA-256 integrity verification
The input and output files were checked with SHA-256.

Input file (/tmp/in.bin):
69cac7fdcd748d4133f50e87a1e3c985b82302ebf9002010c82e69f2f5e0e6d6
Output file (/tmp/out.bin):
69cac7fdcd748d4133f50e87a1e3c985b82302ebf9002010c82e69f2f5e0e6d6
The hashes are identical. This verifies that the received file matched the original file in this experiment.
5. Results and analysis
The experiment transferred a 1 MiB file using Selective Repeat under the configured emulator conditions.
Metric	Result
File bytes	1,048,576
Elapsed time	11,809.581 ms
Goodput	710,322.251 bps
Wire bytes	1,194,196
Throughput	808,967.581 bps
DATA packets sent	841
Retransmissions	92
Timeouts	92
ACKs received	749
Duplicate ACKs	0
Corrupt packets dropped	0
Sender SHA-256 match	true


The sender reported 92 retransmissions and 92 timeouts during the transfer. The sender's result indicated a successful SHA-256 match, and the independent hashes of the input and output files were also identical.
6. Conclusion
The Selective Repeat file-transfer experiment completed successfully. The sender transferred a 1 MiB file and reported successful integrity verification.

The matching SHA-256 hashes of /tmp/in.bin and /tmp/out.bin confirm that the received file was identical to the original in this run. The experiment provides evidence that the implementation transferred the file correctly under the configured network loss, delay, and jitter.
Experiment outcome: SUCCESS — sender integrity flag was true and input/output SHA-256 hashes matched.
