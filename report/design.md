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

To ensure reliable delivery of data, it was important that the receiver could know what file it was receiving and determine when data had been fully received. This was achieved by using standard handshake procedures within the Session helpers. Both transports used a common interface for sending and receiving data from the ArqProtocol. Before any file data was sent, the sender would send a DATA packet with sequence number 0 and the META flag. This packet would contain the filename, number of bytes and SHA-256 hash of the file. The receiver would acknowledge this packet and file data would be sent from sequence number 1 onwards. If a META packet was resent then it would be acknowledged.

Once data had been sent, the sender would send a FIN packet. The receiver would flush it's output stream and compare the received file with the hash provided in the meta packet. The receiver would then respond with a FINACK indicating whether the file was intact. There was a limit to the number of times the final packets would be resent. However, the receiver would wait for a short period after receiving the FIN packet to ensure that a resent FIN could be responded to. This meant that a file that had been successfully received would not be reported as failed simply because the final control packets were lost. Using standard handshakes ensured that any differences between the protocols were due to the data transmission methods. A second check was done by the experiment harness to compare the file hashes using SHA-256 (3.6).

## 2.3 Stop-and-Wait

Only ever 1 DATA packet could be in flight. After sending the next packet in sequence the sender would wait for an ACK for that specific packet. If no ACK was received within the RTO then the packet would be resent. Once a valid ACK had been received the packet would be moved onto the next in sequence. This is a very simple method and ensures that duplicate delivery can easily be avoided. However, there is a lot of idle time on the path. The best case goodput is approximately 1 payload per RTT (4.1).

The sender would use the configured $k$ bit sequence space. This was an important correction discovered during pilot testing. Using only 1 bit for sequence numbers can cause issues if the old packet takes too long to arrive. This can cause the packet to be resent after wrap-around due to only having 1 bit. By using full sequence numbers and comparing them we can ensure that old copies are rejected. The stop-and-wait protocol used standard META handshakes, limited retries for the final packets and the receiver would wait for a short period of time after receiving the FIN packet. This would allow for resent FIN packets to be responded to. The independent hash of the file was used to detect this issue (3.8).

## 2.4 Go-Back-N

Up to $W$ DATA packets could be in flight. However this was limited by $W \le 2^k-1$. The sequence space restriction means that the sender window will never occupy the full modular space of sequence numbers. This means that there will not be ambiguity between old and new sequence numbers in flight. The sender will advance it's left edge as ACKs are received. New sequence numbers will be filled with new DATA packets. As only the next expected sequence number is accepted by the receiver, any packets after this will be dropped. The ACK field will be cumulative. An ACK of $n$ indicates that all packets up to and including $n-1$ have been accepted. The next expected sequence number is $n$.

The sender would keep track of the effective re-transmission timer for the oldest packet in flight. If the timer expired then the sender would re-transmit all packets in flight from the oldest packet. This gives loss recovery, however there could be many redundant copies sent. The duplicate cumulative ACKs were used as diagnostic information. They would not cause fast re-transmissions. This means that loss recovery depends on the configured RTO. This is particularly important for strong losses caused by reordering (3) and the plateauing observed with large windows (2).

## 2.5 Selective Repeat

Whilst the sender can still send packets in a window, acknowledgements and re-transmissions are handled on a per-packet basis. The receiver will accept any packets that fall within the current receive window. These packets are stored in a ring buffer and only the contiguous section starting from the receive base will be released. This allows for useful out-of-order packets to be kept. The sender keeps track of the acknowledgement flag, send time and timer for each occupied slot. The timer heap was exposed via the TimerWheel class. This would allow correct events to occur. Canceled events would be lazily removed. If the event was no longer in flight then it could be safely ignored. The receive buffer and sender would use the SeqSpace class and Math.floorMod function to handle modular sequence numbers.

An important correctness consideration was ensuring that the window size did not exceed $W \le 2^{k-1}$. This is less than GBN. This is because the sender and receiver windows are both maintained at the same time. This means that the combined window size must not exceed the number of unique sequence numbers ($k=32$0__). For example, if $k=32$1__ then there are 4 sequence numbers (0,1,2,3). If the window size was illegally 3 then packets 0,1,2 could be sent. The receive window would move to 3,0,1. However a re-transmission of the old packet 0 would be accepted as it was within the current window. Whereas if the window size was legally 2 then after receiving packets 0,1 the window would move to 2,3. The old packet 0 would no longer be in flight. The implementation also took into account packets that had been sent in a previous window. These would be re-ACK'd. Otherwise the sender could be stuck waiting forever if an ACK was lost. This was documented in docs/sr-window-bound.md and the relevant SR tests.

The sender used the same RTT estimator as the other protocols. However each in-flight packet could expire. The exponential back-off was dependent on the base/oldest packet timing out. This would not cause multiple inflations of the RTT as multiple packets could timeout during an effective loss recovery period. The use of buffering, individual ACKs and individual re-transmissions meant that far fewer redundant copies were sent compared to GBN (4.1-4.4).

## 2.6 Retransmission Timeout

There were two options for the timeout. One was a fixed timeout (fixed:X) and the other was an adaptive timeout via ProtocolConfig. When using a fixed timeout the harness would pass --base-rtt-ms with the value being the sum of the two configured one-way delays. For this example 20ms was used for both, meaning the base RTT was 40ms. If the timeout was set to fixed:1.5 then the timeout would be 60ms. If set to fixed:1.2 then the timeout would be 48ms. The jitter was not added to the base RTT as it would be varying around 0. This meant that the timeout would not be affected by the configured delay.

The adaptive RTO used the Jacobson/Karels method of using a smoothed RTT and RTT variance. This method was based on the principles described in RFC 6298 [3, 4]. The estimator would update the smoothed RTT (SRTT) and RTT variance (RTTVAR). It would then calculate the RTO using the formula: SRTT + max(G, 4 x RTTVAR). Karn's rule was used to ensure that the new RTT sample was only taken from a packet that had not been re-transmitted. This would avoid ambiguity (5). If the timeout occurred then an exponential back-off would happen. When progress was made with acknowledgments then the multiplier would be reset. The setup was intentionally chosen to give a minimum and maximum RTO of 5ms and 5s. This would allow for investigation of a nominal 40ms path. However it was more aggressive than RFC 6298 which suggests a conservative lower bound of 1s. It also suggested allowing for a minimum of 60s upper bound. This was a choice made for the experiment and does not mean that it fully conforms to TCP.

## 2.7 Channel Emulator

The channel emulator was a standalone UDP middlebox. This meant that for each experiment a master seed was provided. From this seed two separate java.util.Random instances were created for the DATA and ACK directions. Each packet would consume exactly 10 random numbers. This would ensure that if an impairment was disabled or a packet was decided to be dropped, the same random choices would be made. The impairments that were applied were loss, duplication, bit-flip, explicit reordering and base delay with uniform jitter. Any packets that were not explicitly reordered would follow a monotonic release time. This would avoid a packet being released due to jitter occurring after an earlier packet.

Packets were timestamped on arrival by the receive thread. These would then be added to a thread safe DelayQueue. A separate release thread would release the packets at the correct time. The packets would be released in the order they were received. This ensured that packets with the same time would be released in the correct order. This corrected the issue with the previous design of having a 1 thread which would cause late releases rounded to the nearest millisecond. If enabled JSONL traces would be saved. This would allow us to see what type of packet it was, it's sequence and ack number and whether it was decided to be impaired. This would give evidence of whether re-transmissions were spurious or necessary. The rules for scheduling were detailed (3.2).

## 2.8 Experiment and Analysis

The run_matrix.py script in Python was used to run each configuration. Fresh ports were used for each configuration and there was a limit on how long each would run for. Each result would be stored as a single row in the CSV. The script would then continue running, only considering rows with status ok as complete. Garbage collection was logged for the sender and receiver. The SHA-256 was also verified independently using the file_match function. Corrupt results would be excluded and re-run. For experiment 4 the traces would be saved for analysis using spurious.py. This would show what impairments were applied to each packet and categorise them into necessary recovery, early timeouts, lost ACKs and re-transmissions after ACK. The plots were generated using plots.py. The Makefile had a make all target which would compile and test the Java code. The harness tests would be run and the plots regenerated. This would not repeat the long experiment matrix. The experiment was repeatable and the statistical choices were documented (3.6-3.7).