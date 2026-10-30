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