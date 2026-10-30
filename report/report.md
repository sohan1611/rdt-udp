# Reliable Data Transfer over UDP

**CS-30003 Coding Assignment 1 · Project P3**

**Team (CSE-14):** Sohan Mandal (2405912) · Sinjan Mishra (2405910) · Sohini Pandit (2405913) · Shaili Seth (2405901)

**Repository:** `github.com/sohan1611/rdt-udp` · October 2026

---


# 1. Introduction

## 1.1 Problem

User Datagram Protocol (UDP) offers a lightweight, connectionless datagram service. This means that there are no guarantees that messages will be delivered to the application, without duplication, or in-sequence [1]. To provide reliable delivery of file data, additional packet sequence numbers, error checking, acknowledgements and retransmission policies are required. These provide the foundation for Automatic Repeat Request (ARQ), whereby the sender keeps a copy of unacknowledged data and retransmits it if there is not enough evidence of it being delivered [6]. There are different ways to implement ARQ: Stop-and-Wait limits transmission to one data packet at a time, Go-Back-N (GBN) allows a sliding window of unacknowledged packets but retransmits from the earliest lost packet, and Selective Repeat (SR) acknowledges individual packets and keeps a buffer of out of sequence packets. Comparing each protocol over a faulty communication path will show the differences in cost.

## 1.2 Solution

A file transfer system was implemented in Java using a common ArqProtocol interface that could be configured to use any of the three ARQ protocols. A common 20 byte packet was used between the sender and receiver, with specific session rules for metadata transmission, data delivery and session closing. An independent channel emulator process was used to introduce packet loss, duplication, corruption, deliberate reordering and delays with jitter, all of which were configured independently. Using an external emulator ensures that no special cases within the protocol implementations can be used. The Python harness ran all the configurations, with new ports being created, counters and timings logged into CSV format, and the source and destination files compared using SHA-256 hashing. The emulator could be seeded ensuring consistency in its decisions. Data was collected to allow the analysis scripts to recreate the figures using make all. A total of 890 successful transfers with correct data integrity were recorded (see Chapters 2-3).

## 1.3 Claims

It was hypothesised that SR would have a higher goodput than GBN over a lossy and jittered channel. The goodput increase should be greater as the loss probability and sender window increased. For GBN, the fraction of useful packets delivered over a window of size $W$ should increase roughly proportional to $(1-p)^W$. SR should have a similar increase to $(1-p)$. Stop-and-Wait should still show poor performance with only around 1 data packet being sent per RTT even on a perfect channel.

It was hypothesised that an adaptive RTO using Jacobson/Karels RTT estimation and Karn's rule to remove ambiguous samples would reduce the number of unnecessary retransmissions. This would not cause a large drop in goodput [4-6]. Instead of guessing how many unnecessary retransmissions would occur based on the total number of retransmissions, packet information from the emulator was used to determine how many retransmissions were actually needed.

## 1.4 Results

The first hypothesis was proven to be true. At 20% loss with $W=32$, SR had a goodput of 2.59 times more than GBN. At 5% loss with $W=128$, SR achieved a goodput of 11.44 Mbit/s compared to 3.31 Mbit/s of GBN. Neither of the suggested functions took into account the effect of timeouts and packet waste. The second hypothesis was partially proven. While using an adaptive RTO reduced the number of early timers, setting a fixed timeout to a very low value caused less early timers. A larger timeout value gave less early timers. However, the smallest timeout gave the best goodput. When comparing to Linux netem, the results were very similar. The average goodput varied by no more than 1.5%.

## 1.5 Report Structure

Chapter 2 details the packet format, common session handling, ARQ implementations, timeout rules and tools. Chapter 3 details the testing environment, test design, setup, measurements, statistical analysis and verification. Chapter 4 discusses the goodput, retransmissions, window scaling, reordering and RTO rules seen and compares them to the claims. Finally, Chapter 5 provides a summary of the results, limitations and future work.


# 2. Design and Implementation

## 2.1 Packet Structure

The three transports all used a common application layer packet structure over UDP. All packets had a fixed 20 byte header, in network byte order. The header then contained a variable sized payload. Below shows the packet structure:

```text
ver(1) | type(1) | flags(1) | reserved(1)
seq (uint32)
ack (uint32)
payload_len(2) | window(2)
checksum(2) | sackCount(2)
```

The first 4 bytes indicated the version of the packet structure, type of packet and flags with 1 reserved byte. The next 8 bytes contained the sequence and ack values. Finally the last 8 bytes contained the payload length, advertised window, checksum and reserved sack entry count. Packet type can be one of the following:

DATA (0)

ACK (1)

SACK (2)

FIN (3)

FINACK (4)

The SACK packet type and sackCount allow for extension of the protocol. However the implementations considered did not rely on the sack fields. Normal DATA payloads contain at most 1400 bytes. This means the full application layer packet sizes were 1420 bytes. Therefore 8MiB of data was split into 5992 DATA packets. The last packet may be smaller.

The checksum is the 16-bit Internet Checksum described in [2] RFC 1071. To calculate this the checksum field was set to 0 and the header and payload were summed together, with each value as 16-bit words. The result of this sum has it's bits flipped and stored. On the receiving end the checksum is calculated, after resetting the stored value. If the received packet fails then it will be dropped. This means that we cannot deliver the packet reliably. This is done on a per-packet basis, as opposed to SHA-256 verification on the last packet. This ensures that clearly corrupted packets will not cause changes to the protocol state.

The sequence number was indicating the number of packets. Whilst 32 bits were used on the wire to store the sequence number, it was possible to limit the number of $k$ bits (Default: $k=32$). This was to allow us to observe packet wrapping and ensure the window size was within the correct bounds. We had to treat the values as unsigned integers. In Java this is not the default behaviour for int variables. Therefore the Integer.toUnsignedLong method was used to get the sequence and ack values. This ensured that values with the high-bit set would not be negative. The Short.toUnsignedInt method was used for the 16-bit values. Modular arithmetic was then used to ensure the sequence numbers were only in the range [0, $2^k-1$].

## 2.2 Connection Handshake

To ensure reliable delivery of data, it was important that the receiver could know what file it was receiving and determine when data had been fully received. This was achieved by using standard handshake procedures within the Session helpers. All three protocols used a common interface for sending and receiving data from the ArqProtocol. Before any file data was sent, the sender would send a DATA packet with sequence number 0 and the META flag. This packet would contain the filename, number of bytes and SHA-256 hash of the file. The receiver would acknowledge this packet and file data would be sent from sequence number 1 onwards. If a META packet was resent then it would be acknowledged.

Once data had been sent, the sender would send a FIN packet. The receiver would flush its output stream and compare the received file with the hash provided in the meta packet. The receiver would then respond with a FINACK indicating whether the file was intact. There was a limit to the number of times the final packets would be resent. However, the receiver would wait for a short period after receiving the FIN packet to ensure that a resent FIN could be responded to. This meant that a file that had been successfully received would not be reported as failed simply because the final control packets were lost. Using standard handshakes ensured that any differences between the protocols were due to the data transmission methods. A second check was done by the experiment harness to compare the file hashes using SHA-256 (3.6).

## 2.3 Stop-and-Wait

Only ever 1 DATA packet could be in flight. After sending the next packet in sequence the sender would wait for an ACK for that specific packet. If no ACK was received within the RTO then the packet would be resent. Once a valid ACK had been received the packet would be moved onto the next in sequence. This is a very simple method and ensures that duplicate delivery can easily be avoided. However, there is a lot of idle time on the path. The best case goodput is approximately 1 payload per RTT (4.1).

The sender would use the configured $k$ bit sequence space. This was an important correction discovered during pilot testing. Using only 1 bit for sequence numbers can cause issues if the old packet takes too long to arrive. This can cause the packet to be resent after wrap-around due to only having 1 bit. By using the configured $k$-bit sequence space with modular comparisons (`SeqSpace`), old copies are rejected, provided a stale packet cannot survive a full wrap of the sequence space. The stop-and-wait protocol used standard META handshakes, limited retries for the final packets and the receiver would wait for a short period of time after receiving the FIN packet. This would allow for resent FIN packets to be responded to. The independent hash of the file was used to detect this issue (3.8).

## 2.4 Go-Back-N

Up to $W$ DATA packets could be in flight. However this was limited by $W \le 2^k-1$. The sequence space restriction means that the sender window will never occupy the full modular space of sequence numbers. This means that there will not be ambiguity between old and new sequence numbers in flight. The sender will advance it's left edge as ACKs are received. New sequence numbers will be filled with new DATA packets. As only the next expected sequence number is accepted by the receiver, any packets after this will be dropped. The ACK field will be cumulative. An ACK of $n$ indicates that all packets up to and including $n-1$ have been accepted. The next expected sequence number is $n$.

The sender would keep track of the effective re-transmission timer for the oldest packet in flight. If the timer expired then the sender would re-transmit all packets in flight from the oldest packet. This gives loss recovery, however there could be many redundant copies sent. The duplicate cumulative ACKs were used as diagnostic information. They would not cause fast re-transmissions. This means that loss recovery depends on the configured RTO. This is particularly important for strong losses caused by reordering (3) and the plateauing observed with large windows (2).

## 2.5 Selective Repeat

Whilst the sender can still send packets in a window, acknowledgements and re-transmissions are handled on a per-packet basis. The receiver will accept any packets that fall within the current receive window. These packets are stored in a ring buffer and only the contiguous section starting from the receive base will be released. This allows for useful out-of-order packets to be kept. The sender keeps track of the acknowledgement flag, send time and timer for each occupied slot. The timer heap was exposed via the TimerWheel class. This would allow correct events to occur. Canceled events would be lazily removed. If the event was no longer in flight then it could be safely ignored. The receive buffer and sender would use the SeqSpace class and Math.floorMod function to handle modular sequence numbers.

An important correctness consideration was ensuring that the window size did not exceed $W \le 2^{k-1}$. This is less than GBN. This is because the sender and receiver windows are both maintained at the same time. This means that the combined window size must not exceed the number of unique sequence numbers ($2^k$). For example, if $k=2$ then there are 4 sequence numbers (0,1,2,3). If the window size was illegally 3 then packets 0,1,2 could be sent. The receive window would move to 3,0,1. However a re-transmission of the old packet 0 would be accepted as it was within the current window. Whereas if the window size was legally 2 then after receiving packets 0,1 the window would move to 2,3. The old packet 0 would no longer be in flight. The implementation also took into account packets that had been sent in a previous window. These would be re-ACK'd. Otherwise the sender could be stuck waiting forever if an ACK was lost. This was documented in docs/sr-window-bound.md and the relevant SR tests.

The sender used the same RTT estimator as the other protocols. However each in-flight packet could expire. The exponential back-off was dependent on the base/oldest packet timing out. This would not cause multiple inflations of the RTT as multiple packets could timeout during an effective loss recovery period. The use of buffering, individual ACKs and individual re-transmissions meant that far fewer redundant copies were sent compared to GBN (4.1-4.4).

## 2.6 Retransmission Timeout

There were two options for the timeout. One was a fixed timeout (fixed:X) and the other was an adaptive timeout via ProtocolConfig. When using a fixed timeout the harness would pass --base-rtt-ms with the value being the sum of the two configured one-way delays. For this example 20ms was used for both, meaning the base RTT was 40ms. If the timeout was set to fixed:1.5 then the timeout would be 60ms. If set to fixed:1.2 then the timeout would be 48ms. The jitter was not added to the base RTT as it would be varying around 0. This meant that the timeout would not be affected by the configured delay.

The adaptive RTO used the Jacobson/Karels method of using a smoothed RTT and RTT variance. This method was based on the principles described in RFC 6298 [3, 4]. The estimator would update the smoothed RTT (SRTT) and RTT variance (RTTVAR). It would then calculate the RTO using the formula: SRTT + max(G, 4 x RTTVAR). Karn's rule was used to ensure that the new RTT sample was only taken from a packet that had not been re-transmitted. This would avoid ambiguity (5). If the timeout occurred then an exponential back-off would happen. When progress was made with acknowledgments then the multiplier would be reset. The setup was intentionally chosen to give a minimum and maximum RTO of 5ms and 5s, the same for all three protocols (each uses `RttEstimator(5)`). This would allow for investigation of a nominal 40ms path. However it was more aggressive than RFC 6298 which suggests a conservative lower bound of 1s. It also suggested allowing for a minimum of 60s upper bound. This was a choice made for the experiment and does not mean that it fully conforms to TCP.

## 2.7 Channel Emulator

The channel emulator was a standalone UDP middlebox. This meant that for each experiment a master seed was provided. From this seed two separate java.util.Random instances were created for the DATA and ACK directions. Each packet would consume exactly 10 random numbers. This would ensure that if an impairment was disabled or a packet was decided to be dropped, the same random choices would be made. The impairments that were applied were loss, duplication, bit-flip, explicit reordering and base delay with uniform jitter. Any packets that were not explicitly reordered would follow a monotonic release time. This would avoid a packet being released due to jitter occurring after an earlier packet.

Packets were timestamped on arrival by the receive thread. These would then be added to a thread safe DelayQueue. A separate release thread would release the packets at the correct time. The packets would be released in the order they were received. This ensured that packets with the same time would be released in the correct order. This corrected the issue with the previous design of having a 1 thread which would cause late releases rounded to the nearest millisecond. If enabled JSONL traces would be saved. This would allow us to see what type of packet it was, it's sequence and ack number and whether it was decided to be impaired. This would give evidence of whether re-transmissions were spurious or necessary. The rules for scheduling were detailed (3.2).

## 2.8 Experiment and Analysis

The run_matrix.py script in Python was used to run each configuration. Fresh ports were used for each configuration and there was a limit on how long each would run for. Each result would be stored as a single row in the CSV. The script would then continue running, only considering rows with status ok as complete. Garbage collection was logged for the sender and receiver. The SHA-256 was also verified independently using the file_match function. Corrupt results would be excluded and re-run. For experiment 4 the traces would be saved for analysis using spurious.py. This would show what impairments were applied to each packet and categorise them into necessary recovery, early timeouts, lost ACKs and re-transmissions after ACK. The plots were generated using plots.py. The Makefile had a make all target which would compile and test the Java code. The harness tests would be run and the plots regenerated. This would not repeat the long experiment matrix. The experiment was repeatable and the statistical choices were documented (3.6-3.7).

## 2.9 Deviations from the proposal

There are four places where the final implementation has deviated from the original plan. However, these changes do not affect the controlled environment.

Timers & I/O

Whilst the original plan was to use a single NIO selector loop to interact with a timer heap, final implementations use blocking UDP sockets with setSoTimeout. Selective Repeat uses the TimerWheel to schedule each individual packet and then updates the socket timeout to when the next deadline is. Go-Back-N sets the timeout to the oldest unacknowledged packet. Stop-and-Wait sets its timeout to the lone outstanding packet. Both endpoints have one socket and one protocol thread, so using a selector would have added unnecessary complexity.

Warm-up & Allocation

In our original plan we were going to ignore warm up runs and provide a zero allocation hot path. However, there was no warm up transfer and each run of the measurements used new JVMs. There was a consistent start up overhead included. As the transfers were long, this overhead was low especially with Stop-and-Wait. We did not assume that no JVM activity would be occurring so we have recorded garbage collection within the harness. We saw no full GC in any of the 890 runs (see Sections 3.6 & 3.9).

Selective Acknowledgements

Our original plan stated that we would include selective acknowledgements. A stretch goal was to include SACK blocks. However, no SACK blocks have been included. Selective Repeat includes individual acknowledgements. Whilst our packet includes SACK type and sackCount fields, they are not used. As we wanted to ensure that our implementation was correct we focused on testing and fixing the emulator, so including SACK blocks has been left for later (see Section 5.5).

Analysis

Our original plan had stated that we would use Python with pandas and Matplotlib. However, we have used the csv and statistics modules from Python's standard library. As we just need to calculate means and confidence intervals there is no need for the pandas library. This reduces our dependencies and makes running make all easier.


# 3. Methodology

## 3.1 Test environment

Experiments were run under WSL2 on Linux 6.18 using a 32-core host and Java 21.0.12. The sender and receiver JVMs each used a fixed 512 MB heap (`-Xms512m -Xmx512m`) to avoid heap sizing becoming an uncontrolled source of variation; the emulator used Java's default heap. All three processes ran on the same host and communicated over the loopback interface. WSL2 was used rather than a native Windows because Windows would wake the emulator on an approximately 15.6 ms timer tick, causing short delays and timeout wake-ups to be rounded upwards. With otherwise identical settings of 5% loss, 20 ms delay and `fixed:1.5`, one Stop-and-Wait run produced 112 timeouts on Windows compared with 17 on Linux.

All experiments ran from a copy of the repository on WSL's own Linux filesystem rather than the mounted Windows drives (`/mnt/c` or `/mnt/d`). Writing the received file to a Windows drive occasionally stalled the receiver for approximately 40 ms. In GBN tests at 0% loss, timeouts occurred in 4 of 12 runs on a Windows drive but in 0 of 12 runs on the Linux filesystem. Using the Linux filesystem therefore removed a source of receiver stalls that could otherwise have distorted the measured timeout behaviour.

## 3.2 The channel emulator

The channel emulator ran as a separate process between the sender and receiver. Keeping the impairments out of the protocol implementations meant that the sender and receiver contained no test-only branches and did not behave differently because they were under test. Traffic in the sender-to-receiver and receiver-to-sender directions was impaired independently. For each packet, the emulator could apply loss, duplication, corruption, reordering and delay. Corruption was implemented by flipping one bit in a copy of the packet rather than modifying the original buffer, while reordering added 20 ms to its scheduled release time. Jitter was sampled uniformly in the range ± the configured jitter value and added to the base delay; the resulting total delay was clamped to 0 ms if the sum would otherwise be negative. To model a single path whose delay varied without itself reordering packets, the emulator preserved arrival order among packets not explicitly selected for reordering. If a packet's jittered release time would have put it ahead of the preceding in-order packet, its release time was raised to that packet's scheduled release time; the arrival-order tie-break then ensured it left after the earlier packet, without waiting beyond that scheduled release time. Packets selected by the `reorder` parameter (experiment 3) were exempt from this rule and did not update the in-order release time, so later packets could overtake them without being held up behind them. This distinction was necessary because independently sampled jitter had previously reordered back-to-back packets: GBN, which discards out-of-order data, made approximately 112,000 retransmissions while sending 6,000 packets at 0% loss. After enforcing in-order jitter, the same case recorded no retransmissions. Keeping path-delay variation separate from deliberate reordering also made the two impairment parameters independently interpretable.

Packets awaiting delivery were held in a thread-safe `DelayQueue` ordered first by scheduled release time and then by arrival order. Arrival order was used as the tie-break so that packets with identical release times were handled deterministically rather than being subject to implementation-dependent ordering. NetEm used a dedicated receive thread to timestamp packets as they arrived and add them to the queue, while a separate release thread took each packet out when it became due and sent it. The earlier single-threaded receive-and-release loop used whole-millisecond socket timeouts, making packet releases about 1 ms late in each direction. An intermediate fix used `parkNanos` to wait out the last fraction of a millisecond but blocked reception during bursts and increased lateness. With separate receive and release threads, mean lateness was approximately 0.12 ms for individual packets; for bursts of 32 packets, mean lateness was approximately 0.00 ms and p99 lateness was about 0.2–0.7 ms across the observed runs. Two timing tests covered single packets and bursts. Random decisions were generated using `java.util.Random`. One master seed deterministically produced separate seeds for the two traffic directions, keeping their random streams independent but repeatable. `java.util.Random` was chosen because its algorithm is fixed by the Java specification, so the same seed and sequence of calls produce the same pseudorandom sequence across compliant JVMs and different computers. Generators such as `ThreadLocalRandom` and `SecureRandom` do not provide the same guarantee.

Exactly 10 random values were consumed for every packet, regardless of the impairment settings and regardless of whether an earlier decision had already caused that packet to be dropped. Consequently, packet i always consumed the same ten positions in its direction's random stream whatever experimental settings were active. This kept random decisions aligned between neighbouring experimental configurations. For example, changing only the loss probability did not also shift the random values subsequently used for delay, jitter or another impairment. Without this rule, changing one independent variable could unintentionally change the realised pattern of other impairments, making neighbouring experimental points differ in more than the variable under test.

When tracing was enabled, the emulator recorded every impairment decision in a JSONL trace. Each record contained the packet `type`, sequence number, acknowledgement number and resulting `action`. The `type` field distinguished DATA from ACK packets, while recording the acknowledgement number was necessary because ACK packets themselves carried sequence number 0. The `action` recorded what the emulator did to the packet, such as pass, drop, duplicate, corrupt or reorder. The final experiment 4 dataset was collected with tracing enabled, producing 150 JSONL traces, one for each run. These traces allowed retransmissions to be classified as necessary or spurious by checking whether the original copy actually reached the receiver. All Stop-and-Wait results were rerun on the corrected emulator rather than retaining the earlier untraced measurements. The harness stopped NetEm with SIGTERM; its shutdown hook allowed the main thread to finish and close the trace before the process exited, preserving the final buffered trace records.

## 3.3 Capacity calibration and operating points

The emulator's processing capacity was measured separately before the main experiments so that protocol results would not be limited by the test infrastructure itself. Calibration used a perfect channel with no deliberate impairments. A warm-up interval was discarded before measurement to reduce the effect of JVM start-up and other initial transients, after which each offered rate was measured for 2 s. A rate was considered sustainable when at least 99% of the sent packets were delivered, so the chosen ceiling represented reliable operation rather than a point at which the emulator was already losing a substantial fraction of traffic.

The highest tested rate meeting this criterion was 64,000 pkt/s. At the next tested rate of 96,000 pkt/s, only 89.8–91.5% of the sent packets were delivered across two runs, showing that the emulator had moved beyond its sustainable operating region. The experimental budget was therefore set to 32,000 pkt/s, half of the measured 64,000 pkt/s ceiling, to leave substantial processing headroom during the protocol experiments. Earlier, increasing the UDP socket buffers to 4 MB raised the ceiling from 64,000 to 96,000 pkt/s. The later change to a two-thread receive-and-release design brought it back to 64,000 pkt/s in two calibration runs—coincidentally the same ceiling as before the buffer increase—because handing packets through a `DelayQueue` introduced a throughput cost. This was an acceptable trade-off: accurate release timing matters more than capacity that the planned experiments do not approach.

The most demanding planned operating point was also checked against this budget. With a window of 128 packets and the shortest possible jittered RTT of 30 ms, the sender could inject approximately 128 ÷ 0.030 ≈ 4,267 data pkt/s. Allowing one ACK for every data packet gives approximately 8,533 pkt/s passing through the emulator. This represented 26.7% of the 32,000 pkt/s experimental budget, leaving a wide margin below the calibrated capacity even at the highest planned protocol load.

## 3.4 Experiment design

Each run transferred an 8 MiB file using 1,400-byte payloads. The channel added a base delay of 20 ms in each direction, giving a base RTT of 40 ms before jitter. Uniform jitter of ±5 ms was then applied independently in each direction, so the shortest possible RTT was 30 ms and the longest was 50 ms. Each experimental point was repeated using the same set of 10 seeds so that protocols and parameter levels were compared under corresponding random conditions rather than unrelated impairment sequences.

A fixed retransmission timeout written as `fixed:X` represented X times the configured base RTT supplied through `--base-rtt-ms`. Defining the timeout as a multiple of the RTT allowed it to scale with the configured path delay, whereas one absolute timeout value would only be appropriate for a particular delay setting. Stop-and-Wait ignored the configured window because, by definition, it allowed only one unacknowledged data packet to be outstanding.

The four experiments isolated loss, window size, reordering and retransmission-timeout policy respectively, using the following configurations:

| Exp | Independent variable | Levels | Protocols | Window | RTO |
| --- | --- | --- | --- | --- | --- |
| 1 | Loss probability | 0, 0.1, 0.5, 1, 2, 5, 10, 20 % | S&W, GBN, SR | 32 | fixed:1.5 |
| 2 | Window size | 1, 2, 4, 8, 16, 32, 64, 128 | GBN, SR | swept | fixed:1.5, at 1 % and 5 % loss |
| 3 | Reorder probability | 0, 1, 2, 5, 10, 20 % | S&W, GBN, SR | 32 | fixed:1.5 |
| 4 | RTO policy | fixed 1.2 / 1.5 / 2 / 3 × base RTT, adaptive | S&W, GBN, SR | 32 | swept, at 2 % loss |

For reproducibility, Stop-and-Wait and GBN were measured on commit `6b4ac52`, while SR was measured on `2a75718`. The only changes between these commits were the SR implementation and its two CLI `case` lines, so the earlier protocol and emulator implementations remained comparable.

## 3.5 What is measured

The timed part of each transfer began when data packet sequence number 1 was first sent and ended when the acknowledgement for the final data packet was received. Connection setup and FIN exchange were excluded so that the measured duration represented the data-transfer behaviour being compared rather than fixed start-up and shutdown costs. Goodput was calculated as the original file size in bits divided by this elapsed time, while throughput used the sender's total transmitted wire bytes over the same interval.

Both measures were retained because they answer different questions. Goodput measures the rate at which useful application data reached its destination, whereas throughput also includes the 20-byte DATA header and any retransmitted DATA packets. ACK traffic is not included because `wire_bytes` is recorded by the sender only. A protocol can therefore achieve similar goodput to another while transmitting more data-side wire traffic to do so.

Each run also recorded retransmissions, timeout events, duplicate ACKs and packets discarded after corruption was detected. The program emitted these measurements in a single `RESULT` line at the end of a run. Using one structured result record gave the experiment harness an unambiguous source from which to extract measurements and reduced the risk of confusing diagnostic output with experimental data.

## 3.6 The experiment harness

The experiment harness ran one command for each configuration and allocated fresh ports for each run; each run also had a timeout to be safe in the face of a stalled sender, receiver or emulator. The result of each run was written as a single CSV row, giving a single tabular dataset for later analysis.

The harness was designed to resume safely after interruption. Upon resuming, only rows whose status was `ok` were considered to have completed successfully; failed, incomplete or otherwise invalid runs remained eligible to be executed again. This avoided silently converting a crash or transient failure into an absence of experimental observation.

Correctness was verified independently of the protocol's own success indication. After each transfer, the harness calculated SHA-256 hashes of the sent and received files and recorded the comparison as `file_match`. Mismatched hashes classified the run as `corrupt`; such a run was never included in plots and was always retried. This external check was necessary as a protocol implementation might have a defect which caused it to report successful completion even when the delivered file was wrong, so the protocol could not be trusted to vouch for its own correctness. The proposal had also called for a separate integrity test suite covering random files, channel profiles and seeds with SHA-256 verification; in the final project, the experiment matrix served this role through 890 transfers across all three protocols and the tested loss, reordering and jitter conditions, each independently verified by `file_match`, with every transfer passing.


Garbage-collection activity for the sender and receiver JVMs was also logged for each run and reduced to the `full_gc` indicator. This made it possible to check whether long garbage-collection pauses were a likely explanation for anomalous timing results rather than assuming every outlier was due to the network protocol. The harness was covered by 20 tests checking experiment orchestration, result handling and recovery behaviour independently of the protocol implementations. It could retain a full emulator trace for each run using `--trace` and stopped NetEm with SIGTERM so that its buffered trace records could be written before exit.

## 3.7 Statistics

Every protocol and parameter level used the same set of 10 random seeds. Reusing the seeds provided more controlled comparisons as corresponding configurations were exposed to reproducible random streams rather than ten unrelated samples of channel behaviour. Only runs whose final status was `ok` were included in the statistical summaries; corrupt or failed runs were excluded and rerun by the harness rather than being incorporated as valid observations.

For each experimental point, the reported centre was the arithmetic mean of the 10 successful runs; uncertainty was reported as a 95% confidence interval calculated as mean ± t × s / √n, where s was the sample standard deviation and n = 10. With nine degrees of freedom, the 95% two-sided Student's t critical value was 2.262.

Student's t was used rather than the normal critical value of 1.96 as the population variance was unknown and had to be estimated from only 10 observations. The t distribution compensates for that additional uncertainty by producing a wider interval for a small sample. As the sample size increases its critical value approaches the corresponding normal value, but for n = 10 the difference was large enough that using 1.96 would understate the uncertainty.

## 3.8 Validating the method: the pilot run

A pilot run was done before the full experiment matrix to check that Stop-and-Wait behaved as expected under controlled packet loss. Stop-and-Wait was chosen as its behaviour can be represented by a simple analytic model. Rather than assuming that a successful packet exchange took exactly the configured 40 ms base RTT, the model was calibrated from the 0% loss runs, which measured an average per-packet time of 42.4 ms including processing and transmission overheads. The 0% result therefore established the model's baseline and was not an independent validation point. Later cross-validation against Linux `netem` showed that approximately 2 ms of the original 42.4 ms baseline came from the emulator's millisecond-rounded packet release, rather than the protocol itself. After correcting the release timing, a shorter 2 MiB validation transfer measured approximately 40.8 ms per packet; the final 8 MiB runs established a loss-free baseline of 40.54 ms per packet. The pilot model still fitted the original loss experiments because its loss-free calibration had already included the extra emulator delay.

For a packet loss probability p, both the DATA packet and its ACK need to arrive, giving a successful-attempt probability of $(1-p)^2$. A failed attempt incurs a 60 ms retransmission timeout before another attempt is made. Predictions generated from the calibrated 42.4 ms baseline were compared with the measurements at loss levels from 0.1% through 20%. These non-zero-loss points provided the genuine validation of the model. Agreement remained within approximately 1–2%; at 20% loss, for example, measured goodput was 147.5 kbit/s compared with a predicted 147.9 kbit/s. This agreement provided an independent check that loss and retransmission behaviour were being measured as intended.

The pilot also exposed a correctness problem that performance measurements alone would not have found. The harness's independent `file_match` check found 58 corrupt transfers: 50 occurred among 50 runs involving reordering and a further 8 occurred among 80 loss runs. Investigation showed that the original one-bit alternating sequence-number scheme implicitly assumed a FIFO channel. Once packets could be reordered, an old packet could be in a position where its single-bit sequence number was once again current, allowing stale data to be accepted. The protocol was corrected and all affected data were rerun rather than storing measurements produced by an implementation known to be incorrect. This incident also demonstrated why end-to-end file hashing was required independently of the protocol's own completion signal.

An early, untraced cell of the main experiment 4 run recorded 629 retransmissions at 2% loss with the `fixed:1.2` RTO policy; the pre-correction reference count was 645. With a 40 ms base RTT, `fixed:1.2` sets an RTO of 48 ms, although jitter can extend the RTT to 50 ms, and the older emulator could add approximately another 2 ms of release lateness per round trip. In the corrected emulator's traced rerun, Stop-and-Wait at `fixed:1.2` averaged 416 retransmissions per run: 123 necessary, 129 due to lost ACKs, 130 early-timer and 34 after-ACK retransmissions. The reduction from 645 to 416—about 230 retransmissions—was attributable to the older emulator's release lateness rather than to packet-loss recovery. The classification and RTO trade-off are discussed in Section 4.4.

## 3.9 Threats to validity

Each measured run spawned new sender, receiver and emulator processes, rather than performing a separate warm-up transfer within long-lived JVMs. The sender and receiver could therefore still be affected by JVM class loading and JIT compilation near the beginning of a run. This may introduce a small start-up effect into the measurements, although its influence is limited by the duration of the main runs, many of which last more than 4 min. The same process-start procedure was also used consistently across all configurations, so this source of overhead was not selectively applied to one protocol.

Garbage collection was monitored rather than assumed to be negligible. GC information was recorded for both protocol JVMs on every run, and the derived `full_gc` field remained false in all 890 runs. The fixed 512 MB sender and receiver heaps were intended to reduce heap-size variation between runs. The absence of recorded full collections is evidence that full-GC pauses have not been driving the measured results, although GC logging remains part of the harness so that this assumption can continue to be checked.

The experiments used loopback communication on a single host rather than a physical network. This provided repeatability and allowed individual impairments to be controlled precisely, but it does not reproduce every feature of a real network, such as competing traffic, router queues, varying routes or hardware-specific timing effects. Similarly, the emulator modelled jitter with a uniform distribution while preserving packet order within each direction unless explicit reordering was selected. Real network delay variation may be bursty, asymmetric or have a different distribution, so results concerning jitter should be interpreted as being under this defined, in-order single-path model rather than as a complete representation of Internet traffic.

Host load was another potential source of timing bias. Two experimental cells were affected: the 1% loss cell in experiment 1 and the `fixed:3` cell in experiment 4. Both had retransmission counts matching their reruns but lower goodput under load. For the 1% loss cell, the estimated cost was about 115 ms per retransmission under load, compared with approximately 45–60 ms in the idle-host rerun. The evidence for the `fixed:3` cell was different: its goodput was 231.3 kbit/s under load and 237.2 kbit/s on rerun, while a model allowing one 120 ms timeout for each lost packet predicted approximately 236 kbit/s, supporting the idle-host result. Both cells were rerun with the host idle; the replaced CSV rows were retained for auditability rather than silently discarded. Subsequent timed experiments were conducted without other workloads on the host.

The custom emulator was also a potential source of methodological bias due to an error in its impairment logic which could affect every protocol. Its behaviour was therefore cross-validated against Linux `netem`; the method and findings are described in Section 3.10.

## 3.10 Validation against Linux netem

To validate the custom emulator independently, `netem_validate.py` ran the same Stop-and-Wait transfers directly over loopback using Linux `netem`, configured with a 20 ms delay, 5 ms jitter and the same loss levels as the emulator comparison. Five seeded repeats were performed at each loss level. A `netem` seed controlled its loss draws but not its jitter draws, so the two implementations could not be compared packet by packet; the validation compared aggregate timing and goodput statistically.

In the initial comparison, the custom emulator produced goodput about 2–5% below Linux `netem`, with the largest gap at low loss (4.7% at 0% loss, falling to 1.7% at 20% loss). This pattern supported a fixed-latency explanation rather than a disagreement in the loss model: the extra approximately 2 ms per packet exchange made up a smaller percentage of total transfer time as packet loss added longer timeout waits. A timing investigation traced this additional round-trip delay to whole-millisecond rounding in the emulator's original release loop. After separating packet reception from release, a shorter 2 MiB validation transfer measured 40.77 ms per packet with the custom emulator, compared with 40.4 ms using Linux `netem`: a gap of about 0.9%, consistent with the extra process hop. The response to loss also had the same shape. At 5% loss, both setups retained approximately 86% of their respective loss-free goodput.

After the corrected-emulator rerun, the mean goodput was within 1.5% of Linux `netem` at every tested loss level, and the 95% confidence intervals overlapped at three of the eight levels. The custom emulator was typically about 0.5% slower in absolute goodput, consistent with the additional process-hop overhead identified in the timing investigation, while its goodput response to increasing loss closely matched that of `netem`. Section 4.5 and Figure 5 present the final per-level comparison.


# 4. Results and discussion

The experiments tested two claims. **The primary claim** was that Selective
Repeat (SR) would achieve greater goodput than Go-Back-N (GBN) on a lossy,
jittered path, with the difference growing as loss probability and window size
increased. As a first approximation, GBN's useful delivery probability over a
window of size $W$ was expected to scale like $(1-p)^W$, whereas SR's was
expected to scale more closely with $(1-p)$. Stop-and-Wait was expected to be
limited to roughly one data packet per round trip even without loss. These are
predictions to test, not identities guaranteed by the implementation.

**The secondary claim** was that an adaptive retransmission timeout (RTO) using
Jacobson/Karels estimation and Karn's algorithm would reduce spurious
retransmissions relative to fixed timeouts, without a substantial goodput
penalty. The emulator's JSONL trace provides the evidence needed to separate
spurious retransmissions from retransmissions made necessary by loss.

Unless stated otherwise, the figures use 8 MiB transfers,
1,400-byte data payloads, 20 ms base delay in each direction, uniform
$\pm5$ ms jitter, and 10 seeds per experimental cell. Plotted centres are
arithmetic means of successful runs; error bars show two-sided **95%
confidence intervals** using Student's $t$ distribution. Failed and corrupt
runs were excluded and rerun. All reported results use the corrected-emulator
datasets. All 890 final experiment runs completed with status `ok` and matching sent and received files (`file_match` true), so no run was excluded from the figures.

## 4.1 Experiment 1: goodput versus packet loss

**Question.** How does increasing packet-loss probability affect the goodput
of Stop-and-Wait, GBN, and SR at a fixed window size of 32?

**Prediction from theory.** If the loss probability on each direction is $p$,
a Stop-and-Wait attempt succeeds only when both the DATA packet and its ACK
arrive, so the probability of success is $q=(1-p)^2$. The expected number of
failed attempts before success is $(1-q)/q$. In a simple timeout-cost model,

$$
G_{\mathrm{SW}} \approx
\frac{8\,\mathrm{payload\ bytes}}
     {RTT_{\mathrm{baseline}} + RTO\,(1-q)/q},
\qquad q=(1-p)^2.
$$

The pilot used a measured loss-free baseline, rather than the configured RTT
alone, because processing and packet release also contributed to transfer
time (Section 3.8); the corrected-emulator rerun established the 40.54 ms baseline.
GBN may have to resend a substantial part of its window after one loss,
whereas SR selectively resends the affected packet. Thus SR was expected to
lose less goodput as $p$ rose; the heuristic $(1-p)^W$ for GBN versus $(1-p)$
for SR gives a shape to compare against, not a precise fit to all timeouts
and ACK-loss effects. The loss levels were 0, 0.1, 0.5, 1, 2, 5, 10, and 20%.

![Figure 1: Mean goodput versus loss](../figures/fig1_goodput_vs_loss.png)

*Figure 1. Experiment 1 ($W = 32$, `fixed:1.5`, 8 MiB, delay 20 ms ± 5 ms). Mean goodput of Stop-and-Wait, GBN, and SR against configured packet
loss, with $W=32$ for the windowed protocols. Error bars show 95% confidence
intervals over 10 successful seeds per point.*

**Results and discussion.** Experiment 1 investigated the effect of packet loss on the goodput of Stop-and-Wait (S&W), Go-Back-N (GBN) and Selective Repeat (SR), with a fixed window size of 32 packets (Figure 1). In loss-free conditions, GBN and SR achieved identical goodput of 7.97 Mbit/s, or approximately 29 times the 0.276 Mbit/s achieved by S&W. Below 1% of loss, the two windowed protocols remained within approximately 1% of each other because retransmissions were relatively rare. For higher levels of loss, however, SR began to significantly outperform GBN: 1.45× at 5% loss, 1.92× at 10% loss, and 2.59× at 20% loss. This confirmed the predicted order and widening gap between their performance, as SR could retransmit individual missing packets whereas GBN could retransmit multiple packets after a loss.

| Loss | S&W (Mbit/s) | GBN (Mbit/s) | SR (Mbit/s) | SR / GBN |
|---|---:|---:|---:|---:|
| 0% | 0.276 | 7.97 | 7.97 | 1.00 |
| 0.1% | 0.276 | 7.58 | 7.49 | 0.99 |
| 1% | 0.268 | 5.51 | 5.61 | 1.02 |
| 2% | 0.260 | 4.32 | 4.85 | 1.12 |
| 5% | 0.237 | 2.56 | 3.72 | 1.45 |
| 10% | 0.202 | 1.44 | 2.76 | 1.92 |
| 20% | 0.151 | 0.71 | 1.83 | 2.59 |

The Stop-and-Wait measurements were also compared to an analytical model using the corrected emulator's measured loss-free, per-packet time of 40.54 ms. For a loss probability $p$, the probability that both the DATA packet and its ACK arrive is $q=(1-p)^2$, and the expected goodput can be calculated: $G = 8\times\mathrm{payload}/[T_0+RTO\times(1-q)/q]$, using a 1,400-byte payload, $T_0 = 40.54$ ms and RTO = 60 ms. The model correctly predicted the measured goodput within 0.4% at every tested level of loss except at 10% loss where the deviation was approximately −1.6%. For example, at 20% loss it predicted 150.8 kbit/s against the measured 150.6 kbit/s. This agreement confirmed the correctness of the Stop-and-Wait timing model, and showed that its limited performance was primarily due to allowing just one outstanding packet per round trip.

However, the simplified functional prediction for GBN and SR's goodput could not accurately describe their full behaviour. The table below compares their measured goodput, normalised to the loss-free result, with the theoretical approximations.

| Loss | GBN measured | $(1-p)^{32}$ | SR measured | $(1-p)$ |
|---|---:|---:|---:|---:|
| 1% | 0.69 | 0.73 | 0.71 | 0.99 |
| 2% | 0.54 | 0.52 | 0.61 | 0.98 |
| 5% | 0.32 | 0.19 | 0.47 | 0.95 |
| 20% | 0.09 | 0.001 | 0.23 | 0.80 |

The approximation for GBN, $(1-p)^{32}$, remained reasonably close to the measurements up to about 2% loss but substantially underestimated goodput for higher levels of loss. This was because the approximation effectively treated a loss as wasting an entire window, whereas GBN in fact retains successful delivery of packets before the missing one, and only retransmits from that point. Conversely, SR performed far below the $(1-p)$ approximation because it ignored the effect of timeout stalls. When a packet was lost, SR could retain subsequent packets, but its sending window could not advance past the missing packet until retransmission recovered it. The prediction's ranking and widening gap were confirmed; its functional forms were not.

## 4.2 Experiment 2: goodput versus window size

**Question.** How does window size affect GBN and SR goodput at 1% and 5%
loss?

**Prediction from theory.** With a base RTT of 40 ms, a sender limited to
$W$ outstanding packets can offer at most $W/RTT$ new packets per second in
the no-loss idealisation. Increasing $W$ should initially raise goodput until
roughly $W\times\mathrm{payload}$ reaches the path's effective
bandwidth-delay product, at which point SR should flatten rather than grow
indefinitely. On this experimental loopback path the effective bottleneck and
any plateau must be inferred from the data, not assumed to be a configured
link bandwidth. Under loss, GBN's wasted window retransmissions should be
more pronounced at larger $W$; SR should be better able to retain the gain
from a larger window. The tested window sizes were 1, 2, 4, 8, 16, 32,
64, and 128.

![Figure 2: Mean goodput versus window size](../figures/fig2_goodput_vs_window.png)

*Figure 2. Experiment 2 (loss 1% and 5%, `fixed:1.5`). Mean GBN and SR goodput versus $W$, separately at 1% and 5% loss.
Error bars show 95% confidence intervals over 10 successful seeds per point.*

**Results and discussion.** Experiment 2 investigated the effect of window size on GBN and SR's performance at 1% and 5% packet loss (Figure 2). With a window size of one both behaved like Stop-and-Wait, achieving approximately 0.27 Mbit/s at 1% loss. That confirmed correctness because neither windowed protocol could have more than one unacknowledged packet outstanding. Increasing the window initially improved goodput for both protocols by allowing multiple packets to remain in flight. The ideal reference goodput was calculated using W × 1,400 bytes × 8 / 41 ms, representing the maximum application-data rate possible with a given window and reference RTT, without loss-affected delays.

| Window | GBN 1% | SR 1% | GBN 5% | SR 5% | Ideal $W/RTT$ |
|---:|---:|---:|---:|---:|---:|
| 1 | 0.27 | 0.27 | 0.24 | 0.24 | 0.27 |
| 8 | 1.88 | 1.82 | 1.31 | 1.30 | 2.19 |
| 32 | 5.51 | 5.63 | 2.56 | 3.72 | 8.74 |
| 64 | 8.48 | 9.75 | 3.03 | 6.47 | 17.48 |
| 128 | 11.20 | 16.71 | 3.31 | 11.44 | 34.97 |

All goodput values are in Mbit/s. The complete eight-window dataset is recorded in `results/exp2_window.csv`.

The gap between them became most significant at 5% loss: increasing the window from 32 to 128 packets only raised GBN goodput from 2.56 to 3.31 Mbit/s, indicating diminishing returns from larger windows. A larger GBN window meant there were more later packets which would be discarded and then retransmitted after a loss, offsetting the benefit of the additional outstanding packets. SR did not suffer the same retransmission amplification because it buffered out-of-order packets and selectively recovered missing ones: its goodput increased from 3.72 to 11.44 Mbit/s over the same window range, or about 3.5 times GBN at $W = 128$. At 1% loss, both continued improving, but SR's advantage began to become more pronounced at $W = 64$ and $W = 128$.

No conventional bandwidth-delay-product plateau was observed because the loopback path had no deliberate bandwidth limit. The window-to-RTT ratio instead provided an ideal reference, with retransmissions and recovery delays keeping actual goodput below that. At $W = 128$ and 1% loss, SR achieved 16.71 Mbit/s, or approximately 48% of the ideal 34.97 Mbit/s. This was equivalent to approximately 1,500 DATA packets/s or 3,000 packets/s including ACKs, substantially below the emulator's calibrated operating budget. The reduced scaling and eventual flattening in GBN were therefore attributable primarily to protocol behaviour under loss, and not the experimental infrastructure.

## 4.3 Experiment 3: sensitivity to deliberate reordering

**Question.** How does increasing the deliberate packet-reordering
probability affect retransmissions in Stop-and-Wait, GBN, and SR?

**Prediction from theory.** Jitter by itself kept packets in order along each
emulated path, so any induced reordering in this experiment came from the
explicit `reorder` parameter. Deliberately delayed Stop-and-Wait packets
could prompt a spurious resend when the resulting RTT exceeded the RTO. GBN
receivers discard packets arriving after the delayed packet because they are
out of order. The sender may receive duplicate cumulative ACKs but only counts
them; it does not fast-retransmit. When its retransmission timer expires, the
sender resends the outstanding window, even if some copies had already arrived.
SR can buffer out-of-order packets and retransmit only entries that remain unacknowledged, so its retransmission
count should rise less sharply. The tested reorder probabilities were
0, 1, 2, 5, 10, and 20%.

![Figure 3: Retransmissions versus reordering](../figures/fig3_retx_vs_reorder.png)

*Figure 3. Experiment 3 (no loss, $W = 32$, `fixed:1.5`); symmetric-log axis. Mean retransmission count for Stop-and-Wait, GBN, and SR versus
configured reordering probability, with $W=32$ for GBN and SR. Error bars
show 95% confidence intervals over 10 successful seeds per point.*

**Results and discussion.** Experiment 3 investigated the three protocols' sensitivity to reordering, without introducing packet loss (Figure 3). The results demonstrated that reordering alone could massively reduce goodput, particularly for GBN: increasing reorder probability from 0% to 20% reduced GBN goodput from 7.97 to 0.88 Mbit/s, or around 89%. Over the same range, SR goodput decreased from 7.96 to 5.39 Mbit/s (around 32%), and S&W from 0.274 to 0.230 Mbit/s. The retransmission counts revealed an even larger difference: at 20% reordering, GBN recorded 39,831 retransmissions against 1,456 for SR.

| Reorder | Retx S&W | Retx GBN | Retx SR | S&W (Mbit/s) | GBN (Mbit/s) | SR (Mbit/s) |
|---|---:|---:|---:|---:|---:|---:|
| 0% | 0 | 0 | 3 | 0.274 | 7.97 | 7.96 |
| 1% | 65 | 2,714 | 79 | 0.272 | 5.61 | 7.37 |
| 5% | 310 | 12,088 | 392 | 0.263 | 2.63 | 6.46 |
| 20% | 1,056 | 39,831 | 1,456 | 0.230 | 0.88 | 5.39 |

GBN suffered because it discarded packets which appeared out of order, even when the previous packet was merely delayed. Once GBN's retransmission timer expired, it would retransmit outstanding packets from oldest unacknowledged, generating substantial unnecessary retransmission traffic. SR simply buffered out-of-order packets, allowing them to be retained until the missing packet arrived. Its retransmission count remained far lower. Stop-and-Wait also experienced retransmissions despite having only one outstanding DATA packet, so conventional reorder between outstanding DATA packets was impossible. These retransmissions occurred because the additional 20 ms reorder delay could extend the RTT to 70 ms, exceeding the configured 60 ms RTO and triggering premature retransmission.

The loss-free, zero-reordering configuration also served as evidence that the emulator's scheduling correction was effective. GBN recorded zero retransmissions under these conditions, whereas the earlier emulator implementation had produced approximately 112,000 retransmissions because independent per-packet jitter reordered back-to-back packets. Removing that artificial reordering permitted the experiment to observe the intended effects of reordering. Overall, the results showed that SR's ability to accept and buffer out-of-order packets provided a major advantage even if the network delivered every original packet.

## 4.4 Experiment 4: fixed versus adaptive RTO

**Question.** How do fixed and adaptive RTO policies trade off spurious
retransmissions, necessary recovery, and goodput at 2% loss?

**Prediction from theory.** With 20 ms base delay in each direction, the base
RTT is 40 ms; with $\pm5$ ms jitter in each direction, an unimpeded RTT can
reach 50 ms. Thus `fixed:1.2` is just 48 ms, shorter than some legitimate
round trips, and was expected to trigger spurious timeouts. Conversely,
`fixed:3` is 120 ms and should avoid many such timeouts but wait longer to
recover genuine loss. Intermediate fixed choices (`fixed:1.5`, `fixed:2`)
trade those costs against one another. The adaptive RTO should track RTT
variation and avoid many premature timeouts without consistently paying the
long delay of an oversized fixed RTO; this advantage must be checked, not
assumed. The corrected emulator timing matters here because the original
release loop added around 2 ms per round trip, enough to influence the
48 ms policy.

![Figure 4: RTO policy and retransmissions](../figures/fig4_retx_vs_rto.png)

*Figure 4. Experiment 4 (2% loss, $W = 32$). Mean total retransmissions by RTO policy for Stop-and-Wait, GBN,
and SR at 2% loss. Error bars show 95% confidence intervals over 10 successful
seeds per point.*

**Results and discussion.** Experiment 4 compared fixed and adaptive retransmission timeout policies under 2% packet loss (Figure 4). Retransmissions were classified using emulator traces into necessary retransmissions, early-timer retransmissions, lost-ACK retransmissions and retransmissions occurring after the relevant ACK had arrived. The `spurious.py` script replayed each emulator trace and, for every retransmission, identified which packet copy the receiver actually accepted. It then checked whether a covering ACK had been sent and was still in flight, had been sent and subsequently dropped, or had never been sent; the `ACK lost` category was assigned only when a covering ACK had been sent and dropped. Necessary retransmissions represented packets which required recovery, and lost-ACK retransmissions occurred because the sender had not received the acknowledgement confirming successful delivery. Early-timer retransmissions occurred when the original transmission had succeeded, but the timeout expired before its ACK arrived. The additional after-ACK cases were associated with near-simultaneous timer and ACK processing, often separated by fractions of a millisecond, and were included when measuring RTO-related overhead. The table reports early-timer counts with these after-ACK cases included in parentheses.

| Protocol | Policy | Goodput | Early timer (+ after ACK) | ACK lost | Necessary |
|---|---|---:|---:|---:|---:|
| S&W | fixed:1.2 | 262 kbit/s | 130 (+34) | 129 | 123 |
| S&W | fixed:1.5 / 2 / 3 | 260 / 255 / 246 kbit/s | 0 | 128 | 123 |
| S&W | adaptive | 261 kbit/s | 20 (+3) | 128 | 123 |
| GBN | fixed:1.2 | 5.17 Mbit/s | 3,035 (+358) | 2 | 3,742 |
| GBN | fixed:1.5 | 4.32 Mbit/s | 60 | 2 | 5,282 |
| GBN | fixed:2 | 3.55 Mbit/s | 29 | 0 | 5,316 |
| GBN | fixed:3 | 2.62 Mbit/s | 16 | 0 | 5,314 |
| GBN | adaptive | 4.63 Mbit/s | 690 (+55) | 2 | 4,974 |
| SR | fixed:1.2 | 5.21 Mbit/s | 1,440 (+336) | 132 | 123 |
| SR | fixed:1.5 | 4.86 Mbit/s | 0 | 128 | 123 |
| SR | fixed:2 | 4.17 Mbit/s | 0 | 128 | 123 |
| SR | fixed:3 | 3.24 Mbit/s | 0 | 128 | 123 |
| SR | adaptive | 4.59 Mbit/s | 57 (+27) | 127 | 123 |

The results partially confirmed the prediction that adaptive RTO would reduce unnecessary retransmissions. Compared with the aggressive `fixed:1.2` policy, adaptive RTO substantially reduced early-timer events: from 130 to 20 for S&W, 3,035 to 690 for GBN, and 1,440 to 57 for SR, excluding the separately reported after-ACK cases. However, adaptive RTO did not always outperform every fixed policy on this measure. The more conservative `fixed:1.5` and `fixed:3` policies produced few or no early-timer retransmissions, but at the cost of longer recovery delays which reduced goodput. Lost-ACK retransmissions were relatively stable across policies for S&W and SR (about 128 per run) because changing the timeout could not recover a missing acknowledgement without retransmission. GBN's much larger necessary-retransmission counts were due to its receiver discarding out-of-order packets, forcing later packets to be retransmitted even if their original copies had reached the receiver.

The shortest timeout still delivered the highest goodput for all three protocols, for example 5.21 Mbit/s with SR's `fixed:1.2` compared with 4.86 Mbit/s at `fixed:1.5` and 3.24 Mbit/s at `fixed:3`. At `fixed:1.2`, the RTO was 48 ms, below the maximum unimpeded RTT of 50 ms, so legitimate exchanges could trigger premature timeouts that adaptive RTO could reduce. By contrast, the fixed policies from `fixed:1.5` to `fixed:3` used 60–120 ms timeouts, already above the maximum unimpeded RTT, leaving few early retransmissions for adaptive RTO to eliminate; their main cost was slower recovery from genuine losses. The longer timeout prevented premature retransmissions but delayed recovery from genuine losses, with about 33% lower goodput at `fixed:3` than at `fixed:1.5`. Adaptive RTO provided an intermediate trade-off with neither the highest goodput nor the fewest unnecessary retransmissions. The correction to the emulator had also materially changed these results: S&W at `fixed:1.2` produced 416 retransmissions with the corrected emulator, compared with 645 previously, and so approximately 230 of the earlier retransmissions were associated with emulator scheduling lateness. These results illustrate that preferred RTO policy depends on whether the goal is maximum useful-data rate or reduced retransmission overhead.

## 4.5 Validation against Linux netem

The custom emulator was independently compared with Linux `netem` using
Stop-and-Wait at matching delay, jitter, and loss settings. Section 3.10
explains why this is a statistical comparison rather than a packet-by-packet
replay and records the preliminary timing diagnosis. The final comparison uses the corrected-emulator results.

![Figure 5: Linux netem validation](../figures/fig5_emulator_vs_netem.png)

*Figure 5. Validation (Stop-and-Wait, delay 20 ms ± 5 ms, same loss levels as experiment 1). Mean Stop-and-Wait goodput versus packet loss, comparing the custom
emulator with Linux `netem`. Error bars show 95% confidence intervals; the
Linux `netem` data use five seeded repeats per loss level; the custom
emulator uses ten seeds per loss level.*

**Results and discussion.** As shown in Figure 5, the custom channel emulator was independently tested against Linux `netem` by comparing Stop-and-Wait goodput across eight packet-loss levels, with a 20 ms delay and ±5 ms jitter in each direction. The custom emulator used ten seeds per level, and netem used five seeded repetitions. Each dataset was summarised with its mean and 95% Student's t confidence interval (critical values: 2.262 and 2.776 respectively). For each loss level, the relative gap was calculated as (emulator mean − netem mean) / netem mean using the Stop-and-Wait goodput values. This tested whether two separately implemented sources of impairment yielded similar performance trends under equivalent nominal network conditions. The mean goodput values differed by no more than 1.5% across the full loss range. The netem dataset was regenerated on the same build used for the other results after a review identified stale counters in the earlier validation run.

| Loss | Custom emulator (kbit/s) | Linux netem (kbit/s) | Relative gap | 95% CIs overlap |
|---|---:|---:|---:|---|
| 0% | 276.3 ± 0.6 | 277.6 ± 0.5 | −0.5% | No |
| 0.1% | 275.7 ± 0.3 | 276.7 ± 0.5 | −0.4% | No |
| 0.5% | 272.1 ± 0.3 | 273.6 ± 0.8 | −0.5% | No |
| 1% | 267.6 ± 0.5 | 269.7 ± 0.6 | −0.8% | No |
| 2% | 259.7 ± 0.6 | 260.9 ± 1.2 | −0.4% | Yes |
| 5% | 237.2 ± 1.0 | 238.7 ± 1.4 | −0.6% | Yes |
| 10% | 201.8 ± 1.3 | 204.9 ± 1.3 | −1.5% | No |
| 20% | 150.6 ± 0.9 | 150.8 ± 1.1 | −0.1% | Yes |

The 95% confidence intervals overlapped at three of the eight loss levels, although the mean goodput values were within 0.1–1.5% of one another throughout. The custom emulator was systematically slower, typically by about 0.5%, consistent with the approximately 0.4 ms additional round-trip overhead of its separate process hop identified in Section 3.10. The narrow confidence intervals made this small absolute difference visible at several loss levels. However, the response to increasing packet loss followed nearly the same shape in both setups, and the curves in Figure 5 lie close together. This agreement in the loss-response pattern supports the custom emulator's loss model, even though the two implementations were not statistically identical in absolute goodput. Its slightly lower absolute values are conservative, while comparisons among S&W, GBN and SR remain on a common basis because each protocol was tested through the same emulator.


# 5. Conclusion

## 5.1 Main Claim

The experiment proved the main claim. Selective Repeat outperformed Go-Back-N in terms of useful throughput at appreciable packet loss rates, with improvements increasing with window size. With a window size of 32, SR had a goodput 1.45x greater than GBN at 5% loss, and 2.59x greater at 20% loss. The difference was minimal at low loss rates i.e. 1%. At 5% loss, GBN was suffering from diminishing returns with a window size of 128, achieving only ~3.31Mbit/s. SR, however, achieved 11.44Mbit/s. Stop-And-Wait was also limited to 1 packet per RTT, giving a goodput of only ~0.28Mbit/s. The timeout model was simple and matched the loss rate fairly accurately at all loss rates (within 0.4%), except at 10% the error was ~1.6%. The general models were not accurate. $(1-p)^W$ was not accurate as it did not account for the fact the packets prior to the loss are still counted as delivered. $(1-p)$ overestimated SR’s goodput as the sending window could be held up waiting for the RTO of the lost packet. The main claim was therefore proven to be true, as SR had a higher goodput than GBN, and the difference increased with window sizes. However, the heuristic models were not able to predict the curves (Sections 4.1 & 4.2).

## 5.2 Sub-Claim

The adaptive timeout estimation greatly reduced the early timer re-transmissions compared to the aggressive `fixed:1.2` setting. The average early timer re-transmissions for SR reduced from 1440 to 57, and GBN reduced from 3035 to 690 (not including post-ACK). However, adaptive RTO could not improve upon the negligible early timer re-transmissions when using `fixed:1.5` to `fixed:3`. This is because with `fixed:1.2`, the timeout on a 40ms base RTT path is only 48ms. The max unimpeded jittered RTT is only 50ms. Longer timeouts such as `fixed:1.5` to `fixed:3` will have timeouts of 60ms to 120ms respectively and will therefore not suffer from this issue. The shorter timeout gave the best goodput for all protocols as losses were recovered quicker. The adaptive estimation provides a good trade-off between unnecessary data and quicker recovery (Section 4.4).

## 5.3 Test Method

The test method was very important to ensure results were reliable. Initially using independent jitter meant that packets could arrive out-of-order if they were sent consecutively. This caused around 112000 re-transmissions for GBN at 0% loss. Also, rounding packets to the nearest millisecond caused an extra ~2ms delay during a DATA-ACK round trip. Using ordered jitter, burst timing and comparing independently to Linux `netem` highlighted and helped to resolve these issues. Once the emulator was working correctly, Stop-And-Wait had a mean goodput within 1.5% of `netem` for all loss rates. However, only 3/8 loss rates had overlapping confidence intervals. The harness also provided independent verification using SHA-256 `file_match`. This highlighted a flaw with the original one-bit Stop-And-Wait design to accept old packets when reordered. This was an issue with the correct operation of the protocol and would not have been discovered if only looking at goodput (Sections 3.2, 3.8 & 4.5).

## 5.4 Limitations

All transfers were done on a single host using loopback so there was no contention. No physical network was used and there was no configured link speed limit.

Jitter was always uniform, and packets were ordered unless the reordering impairment was enabled. Real paths may have correlated jitter and complex queues.

The conclusions were drawn from transferring an 8MiB file, with 10 seeds for each cell in the emulator. This may not be representative of arbitrary file sizes.

The absolute goodput for Stop-And-Wait in the custom emulator was generally ~0.5% less than Linux `netem` due to the extra hop. The protocols were compared using the same emulator so this is not a major source of bias.

The adaptive RTO was deliberately set to have a minimum 5ms timeout, and max timeout of 5s for the short RTT test path. RFC 6298 recommends a conservative minimum timeout of 1s. It also allows for an upper bound of at least 60s. This should not be taken as a fully compliant TCP implementation.

## 5.5 Future Work

It would be natural to implement meaningful SACK blocks within the existing `sackCount`. This would allow the sender to have multiple ranges of received packets. GBN could also be improved by implementing fast re-transmit. Currently duplicate cumulative ACKs are counted but not acted upon. Repeating the experiment on physical hosts, or with a controlled rate limit using `netem`, would allow us to see if the trends still hold true with real bottlenecks. We would also be able to see a true plateau in bandwidth delay product.


# References

[1] J. Postel, *User Datagram Protocol*, RFC 768, August 1980.

[2] R. Braden, D. Borman and C. Partridge, *Computing the Internet Checksum*, RFC 1071, September 1988.

[3] V. Paxson, M. Allman, J. Chu and M. Sargent, *Computing TCP's Retransmission Timer*, RFC 6298, June 2011.

[4] V. Jacobson and M. J. Karels, *Congestion Avoidance and Control*, Proceedings of ACM SIGCOMM '88, 1988.

[5] P. Karn and C. Partridge, *Improving Round-Trip Time Estimates in Reliable Transport Protocols*, ACM SIGCOMM Computer Communication Review, vol. 17, no. 5, pp. 2–7, 1987.

[6] J. F. Kurose and K. W. Ross, *Computer Networking: A Top-Down Approach*, 8th ed., Pearson, 2021.


# Appendix A: Reproducing the results

Requirements: WSL2 (Linux), JDK 21, Python 3 with matplotlib. From the repository root:

```bash
make all          # build, run all Java and Python tests, redraw Figures 1–5 from the committed CSVs
make experiments  # rerun the full matrix (about a day; idle host, WSL Linux filesystem)
```

The Linux `netem` cross-check needs root: `sudo python3 analysis/netem_validate.py --config analysis/configs/exp1_loss.json`.
The README describes each step in detail.
