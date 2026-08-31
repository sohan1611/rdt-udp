# CS-30003 · Coding Assignment 1 · Project Proposal

**Team:** _[4 names, roll numbers, section]_
**Repository:** _[URL]_
**Project:** P3 · Reliable Data Transfer over UDP
**Path:** A (catalogue)

## 1. What we are building

A reliable file-transfer system over UDP with three interchangeable ARQ transports —
Stop-and-Wait, Go-Back-N, and Selective Repeat — behind a single application interface.
It sits at the transport layer, providing the reliability, ordering, and integrity
guarantees UDP does not. A separate channel-emulator process sits on the path and applies
configurable, seeded impairments, so every measurement is reproducible from its seed.

## 2. Core deliverables

- 20-byte hand-packed header; RFC 1071 Internet checksum written by hand.
- Stop-and-Wait, Go-Back-N, and Selective Repeat as swappable transports behind one interface.
- Sequence numbers, cumulative and selective ACKs, and retransmission timers on a
  single-threaded timer heap driven by one NIO selector loop.
- Adaptive RTO using Jacobson/Karels RTT estimation, with Karn's algorithm.
- A channel emulator we write ourselves: loss, duplication, reordering, corruption, and
  delay with jitter; deterministic by seed; every impairment decision logged to a
  replayable trace.
- SHA-256 integrity verification of source against received file.

## 3. The claim we will test

**Claim.** Selective Repeat sustains higher goodput than Go-Back-N under loss, and the gap
widens with both loss rate and window size: Go-Back-N degrades approximately as `(1-p)^W`
while Selective Repeat degrades as `(1-p)`.

**Experiment.** Independent variables: loss rate (0–20%) and window size (1–128).
Controls held fixed: file size 8 MiB, payload 1400 bytes, RTT, and emulator seed set.
Metrics: goodput (application bytes / wall time) and retransmission count, reported
separately from wire throughput so retransmission overhead is visible. Ten seeds per
cell, presented as mean with 95% confidence intervals. We validate the emulator itself by
reproducing the loss-rate curve under Linux `tc netem` and showing the two agree.

## 4. Stack and justification

Java 21 (`java.net`, `java.nio`) for the protocol and emulator; Python with
pandas/matplotlib for analysis; Linux via WSL2. Java gives byte-level control through
`ByteBuffer`, which is big-endian by default and therefore already in network byte order.
Its speed lets us write the checksum exactly as RFC 1071 specifies rather than in an
optimised form: we measured 0.60 µs/packet against an experiment operating point of
~4,500 packets/second. We calibrate harness capacity in week 1 and report it as a bound on
every result.

## 5. Work split

| Owner | Component |
|---|---|
| _[name]_ | Wire format, checksum, protocol interface, Stop-and-Wait |
| _[name]_ | Go-Back-N, timer heap, cumulative ACKs |
| _[name]_ | Selective Repeat, receiver buffer, RTT/RTO estimation |
| _[name]_ | Channel emulator, experiment harness, statistics, plots |

## 6. Risks

- **Selective Repeat overruns week 4** — the receiver buffer plus per-packet timers is the
  hardest piece. *Fallback:* a single timer on the oldest unacked packet, still correct and
  still ahead of Go-Back-N, with the simplification documented.
- **JVM timing jitter contaminates the measurements** — JIT warm-up and GC pauses look like
  network delay. *Fallback:* discard a warm-up transfer per JVM, pin the heap, keep the hot
  path allocation-free, and log GC to confirm no collection occurred during timed runs.

## 7. Weekly plan

| Week | Dates | Target | Owner |
|---|---|---|---|
| 1 | Sep 1–7 | Header, checksum, emulator v0, Stop-and-Wait | A, D |
| 2 | Sep 8–14 | Full emulator, timer heap, S&W under impairment | D, B |
| 3 | Sep 15–21 | Go-Back-N, metrics pipeline, first plot | B, D |
| 4 | Sep 22–28 | Selective Repeat, adaptive RTO — Core complete | C, A |
| 5 | Sep 29–Oct 5 | Experiments 1–3, methodology write-up | D, C |
| 6 | Oct 6–12 | RTO experiment, tc/netem validation, cross-teaching | D, all |
| 7 | Oct 13–19 | Stretch: SACK blocks; report draft | C, A |
| 8 | Oct 20–26 | Freeze, final report, viva preparation | all |
