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