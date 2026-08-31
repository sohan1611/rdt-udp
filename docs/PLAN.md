# CN Coding Assignment 1 — Project Plan

## Context

`Coding Assignment 1.pdf` (the only file in `D:\Downloads\CN Assignment`) sets an 8-week, 4-person
networking project worth 10 marks: **Proposal 2 · Implementation 3 · Viva 5**. Duration is September
to end of October 2026. Today is **2026-08-25**, so the clock effectively starts now — the brief warns
plainly that "Groups that start in October will not finish."

Three constraints shape everything below:

1. **You implement the protocol, not call it.** Socket level only. Any library that implements the thing
   you were assigned to implement is banned.
2. **You must produce evidence.** A measurable claim, experiments, plots, analysis — not a working demo.
3. **You must explain every line.** The viva is individual and worth half the marks. "Code that runs but
   cannot be explained scores zero." The instructor will ask you to modify code live during the demo.

Nothing has been built yet. This plan takes the project from empty directory to submission.

---

## 1. Decision and rationale

| Choice | Decision |
|---|---|
| Path | **A** (catalogue) |
| Project | **P3 · Reliable Data Transfer over UDP** 🟢 |
| System language | **Java 21 LTS** (`java.net` / `java.nio`, stdlib only) — installed, Temurin 21.0.11 |
| Analysis language | **Python 3** (pandas + matplotlib), CSV in, figures out |
| Environment | **WSL2 Ubuntu** on this Windows machine (present, currently stopped) |
| Build | Gradle with committed wrapper, JUnit 5 |

**Why P3.** It is the only 🟢 project whose evidence is fully under your control. You write the channel
emulator, so every plot is reproducible from a seed — which matters because 3 of the 10 marks ride on
experiments and plots. It needs no admin privileges, no live-internet flakiness (P2's real risk), and no
TCP state machine (P4, which the brief itself flags as the most demanding option). It also splits into
four genuinely independent modules, which the proposal explicitly demands.

**Why Java for the system.** The viva is 5 of 10 marks and is assessed individually — every member has to
explain every line under questioning. Most of the group is stronger in Java, so Java maximises the team's
total viva score; that outweighs any one member's personal fluency. Java is explicitly on the brief's
allowed list, and `ByteBuffer` is big-endian by default, which *is* network byte order — hand-rolling a
wire format is more natural here than in Python. It also removes the throughput question entirely (§1.1).

**Why Python for analysis.** The brief allows plotting and analysis libraries outright and says mixing is
fine. Keeping the evidence pipeline in pandas/matplotlib gives a clean separation between the *system*
and the *measurement of the system* — which is exactly the separation the grading rewards.

**Why WSL2.** Real Linux sockets, plus `tc`/`netem` for the cross-validation experiment in §6 that
answers the inevitable viva question "how do you know your emulator is correct?"

**Known risk — P3 is a popular pick.** The brief states identical-looking submissions across groups will
be treated as collusion. Three design choices in this plan exist specifically to make your submission
structurally distinct: the **out-of-process emulator middlebox** (§4.4), the **replayable JSONL impairment
trace** (§4.4), and **spurious-retransmission labelling using emulator ground truth** (§6, Exp 4). Write
your own report prose; do not share code or figures between groups.

### 1.1 The brief's language warning, answered with measurements

The brief says: *"Python will bottleneck a high-throughput packet analyzer, and C will cost you weeks on
a visualization-heavy project."* Measured on this machine, same textbook RFC 1071 algorithm:

| Operation | Python 3 | **Java 21** |
|---|---|---|
| Internet checksum, plain byte loop | 69.4 µs/pkt → 14,400 pkt/s | **0.60 µs/pkt → 1,677,000 pkt/s** |
| Internet checksum, optimised (`array('H')`) | 6.0 µs/pkt → 168,000 pkt/s | n/a — not needed |
| Header pack (`struct` / `ByteBuffer`) | — | 0.02 µs/pkt → 49,000,000 pkt/s |
| UDP send + receive, 1400 B loopback | 6.4 µs/pkt → 157,000 pkt/s | **4.48 µs/pkt → 223,000 pkt/s** |

The warning is real: in Python, a textbook byte-by-byte checksum caps the whole system at ~14k pkt/s, and
avoiding that requires knowing to sum 16-bit words via `array('H')`. In Java you write the checksum
exactly as the RFC describes and get 115× that rate with no tricks to explain away at viva.

**Headroom.** The experiment operating point — 1400-byte payload, ~50 Mbps emulated link — needs
**~4,500 pkt/s**. The emulator middlebox relays both directions, so its ceiling is roughly half the UDP
figure, ~100k pkt/s. That is **20× headroom**, and the checksum is no longer anywhere near the critical
path.

**Why this still matters.** If the harness ever saturates, the goodput-vs-window curve plateaus because
of the JVM rather than because of protocol behaviour, silently contaminating the headline claim. So W1
retains a **capacity calibration** gate (§7), and the measured ceiling goes into the report as a stated
bound with every experiment point shown below it. Java changes the risk from *throughput* to *timing
jitter* — see §8.

---

## 2. What "Core complete" means

Straight from the catalogue entry — this checklist *is* the definition of done:

- [ ] File transfer over UDP with **three pluggable ARQ protocols**: Stop-and-Wait, Go-Back-N, Selective
      Repeat. Same application, swappable transport.
- [ ] Sequence numbers, checksums, **cumulative and selective** acknowledgements, retransmission timers.
- [ ] **Adaptive RTO** via Jacobson/Karels RTT estimation, with Karn's algorithm.
- [ ] **A channel emulator you write yourself**: configurable loss, duplication, reordering, corruption,
      delay with jitter. Deterministic via seed.
- [ ] Integrity verified by **hash comparison** of source and received file.

Experiments required: goodput vs loss rate (all three) · goodput vs window size · retransmission count vs
reordering probability · RTO tuning sensitivity. "All plotted, all explained."

Stretch (only after Core is solid): SACK blocks · sliding-window flow control · connection setup and
teardown · Nagle-style coalescing.

---

## 3. Repository layout

One Git repo, shared with the instructor at proposal time.

```
rdt-udp/
├─ README.md                     # run instructions, one-command repro
├─ AI-USE.md                     # REQUIRED by §5 of the brief
├─ build.gradle · gradlew        # wrapper committed: ./gradlew test works on all 4 machines
├─ Makefile                      # make test | make experiments | make figures | make all
├─ proposal/proposal.md          # -> proposal.pdf, one page
├─ src/main/java/rdt/
│  ├─ Packet.java                # header pack/unpack via ByteBuffer, Internet checksum
│  ├─ ArqProtocol.java           # interface: sendFile / recvFile
│  ├─ StopAndWait.java
│  ├─ GoBackN.java
│  ├─ SelectiveRepeat.java
│  ├─ TimerWheel.java            # PriorityQueue timer heap, single-threaded
│  ├─ RttEstimator.java          # Jacobson/Karels + Karn's algorithm
│  └─ RunStats.java              # per-run counters -> CSV row
├─ src/main/java/emulator/
│  └─ NetEm.java                 # standalone impairment middlebox process
├─ src/main/java/app/
│  ├─ Sender.java                # CLI
│  └─ Receiver.java              # CLI
├─ src/test/java/                # JUnit 5
├─ analysis/                     # Python 3 — evidence pipeline only
│  ├─ run_matrix.py              # sweeps x seeds x repeats -> results/*.csv
│  ├─ plots.py                   # results/*.csv -> figures/*.png
│  └─ configs/*.yaml             # locked experiment configs
├─ results/                      # committed CSVs (evidence)
├─ figures/                      # committed PNGs, all script-generated
└─ report/report.md
```

Gradle rather than bare `javac` because the wrapper guarantees all four members run an identical
toolchain and `./gradlew test` behaves the same everywhere — worth the small setup cost on a team.
There are **zero third-party runtime dependencies**; JUnit 5 is test-scope only.

---

## 4. Design decisions to lock before coding

These are the choices you will be asked to defend. Decide them now, write them into the proposal, and
do not drift.

### 4.1 Wire format — fixed 20-byte header

Big-endian, which `ByteBuffer` gives you by default — no byte-order juggling:

```
 0        1        2        3
+--------+--------+--------+--------+
|  ver   |  type  | flags  |  resv  |
+--------+--------+--------+--------+
|            seq  (uint32)          |
+-----------------------------------+
|            ack  (uint32)          |
+--------+--------+--------+--------+
|   payload_len   |     window      |
+--------+--------+--------+--------+
|    checksum     |   sackCount     |
+--------+--------+--------+--------+
```

- `type`: `DATA=0, ACK=1, SACK=2, FIN=3, FINACK=4`
- `checksum`: 16-bit one's-complement Internet checksum (RFC 1071) over header-with-checksum-zeroed plus
  payload. Write it as the RFC describes — it is a guaranteed viva question, it is ten lines, and in Java
  it costs 0.6 µs. No optimisation needed or wanted.
- `sackCount` reserves the selective-ACK path from day one so Selective Repeat does not need a format
  change in week 4.
- Java has no unsigned types: read `seq`/`ack` into `long` via `Integer.toUnsignedLong()`, and the 16-bit
  fields via `Short.toUnsignedInt()`. Get this wrong once and you will chase it for a day — write the
  round-trip test first.

**Sequence numbers are packet-based, not byte-based.** This matches the textbook GBN/SR treatment you
will be examined on. Make the sequence space size **configurable** so tests can force wraparound with a
tiny space — most groups never test this and it is where the classic window-size bugs live
(GBN needs `W ≤ 2^k − 1`, SR needs `W ≤ 2^(k−1)`).

### 4.2 Metadata handshake

Sender sends `seq=0` DATA carrying `{filename, size, sha256}`; receiver ACKs; data starts at `seq=1`;
`FIN`/`FINACK` closes. This gives you integrity verification and a rudimentary connection setup for free,
and it is a clean stepping stone to the connection-teardown stretch goal. `MessageDigest.getInstance(
"SHA-256")` covers the hashing.

### 4.3 Concurrency: one selector loop, one timer heap — no timer threads

The brief names the hard part explicitly: *"timer management under concurrency."* Sidestep it by design.
Use a non-blocking `DatagramChannel` registered on a `Selector`, plus a `PriorityQueue<TimerEntry>`
ordered by deadline from `System.nanoTime()`. The selector's block timeout is derived from the head of
the timer heap. Single thread, no locks.

This is a deliberate, defensible answer to a viva question ("how do you avoid races between a timeout and
an arriving ACK?" — *there are none; both are events on one loop*). It is also far easier to debug than
a thread-per-timer design, and it removes scheduler noise from your measurements.

Explicitly avoid `ScheduledExecutorService` and `Timer` here. They work, but they reintroduce exactly the
concurrency the brief warns about, and they are harder to defend.

### 4.4 The emulator is a separate process, not an in-process shim

`emulator/NetEm.java` is a UDP middlebox: sender → emulator → receiver, and back. Both directions
impaired independently, each with its own seeded RNG.

Why this over an in-process hook:
- The protocol code contains **zero test-only branches**. At viva you can say your protocol does not know
  it is being tested — because it doesn't.
- It works unchanged across machines, and it is the honest analogue of a real network path.
- It makes the tc/netem cross-validation (§6) a drop-in substitution.

**Use `java.util.Random(seed)`** — its LCG algorithm is exactly specified in the Java documentation, so the
same seed produces identical output on any JVM, any platform, any version. That is a *stronger*
reproducibility guarantee than most languages give you, and it directly satisfies the brief's
"deterministic via seed" requirement. Do **not** use `ThreadLocalRandom`, `SecureRandom`, or an unseeded
`RandomGenerator` — none of them guarantee this.

Every impairment decision is appended to a **JSONL trace**: `{t, dir, seq, action, delayMs}` where
action ∈ `pass|drop|dup|corrupt|reorder`. That trace is what makes Experiment 4 possible and is strong
viva material — you can replay any anomalous run packet by packet.

Delay and jitter are implemented as a release-time `PriorityQueue`; reordering emerges naturally from
per-packet jitter, plus an explicit `reorderProb` that swaps release order.

---

## 5. Work split — four named owners

The brief rejects "we will all work on everything" outright. Assign real names to these four roles in the
proposal.

| Owner | Component | Files |
|---|---|---|
| **A** | Wire format, checksum, protocol interface, **Stop-and-Wait**, integrity hashing | `Packet.java`, `ArqProtocol.java`, `StopAndWait.java` |
| **B** | **Go-Back-N**, timer heap, cumulative ACK logic | `GoBackN.java`, `TimerWheel.java` |
| **C** | **Selective Repeat**, receiver buffer, selective ACKs, **RTT/RTO + Karn** | `SelectiveRepeat.java`, `RttEstimator.java` |
| **D** | **Channel emulator** (Java) + **experiment harness, statistics, plots** (Python), tc/netem validation | `emulator/`, `analysis/`, `RunStats.java` |

**Owner D is the natural slot for the strongest Python developer** — the emulator is a core Java
component everyone depends on, and the analysis pipeline is where the 3 implementation marks for
"experiments, plots, and analysis" are actually earned. It is not a lesser role.

Rules that protect the individual viva mark:
- Each owner writes the tests for their own module.
- Every PR is reviewed by one other member — rotate, so everyone reads everyone's code.
- **Integration owner rotates weekly.** Owner D in particular must be able to explain Selective Repeat;
  a member who only produced plots will not survive an individual viva.
- Week 6 includes a mandatory **cross-teaching session** (§7).

Commit discipline (the brief makes commit history evidence): every member commits under their own name
and email, ≥3 commits per week, small and frequent. A history of three commits in the final week is
explicitly called out as a red flag.

---

## 6. The claim and the experiments

### Primary claim (proposal §3 — one sentence, falsifiable)

> Under a lossy, jittered channel, Selective Repeat sustains higher goodput than Go-Back-N, and the gap
> widens with both loss rate and bandwidth-delay product: GBN's effective goodput degrades roughly as
> `(1−p)^W` where SR degrades as `(1−p)`, while Stop-and-Wait is bounded by `MSS/RTT` regardless of loss.

This is falsifiable because it predicts a *functional form*, not just an ordering. If the measured GBN
curve does not track `(1−p)^W`, you say so and explain why — that analysis is worth more marks than a
curve that happens to match.

### Secondary claim

> Adaptive RTO (Jacobson/Karels with Karn's algorithm) reduces spurious retransmissions by a
> substantial margin versus any fixed RTO on a jittered path, at negligible goodput cost.

### Experiment matrix

Fixed across all runs: 8 MiB file, 1400-byte payload, **10 seeds per cell**, report mean with 95% CI,
all runs on one designated machine (spec recorded in the report), JVM warmed before timing (§8).

| # | Independent variable | Levels | Measured |
|---|---|---|---|
| 1 | Loss rate `p` | 0, 0.1, 0.5, 1, 2, 5, 10, 20 % | Goodput, all 3 protocols, W=32 |
| 2 | Window size `W` | 1, 2, 4, 8, 16, 32, 64, 128 | Goodput, GBN vs SR, at p=1% and p=5% |
| 3 | Reorder probability | 0, 1, 2, 5, 10, 20 % | Retransmission count, all 3 |
| 4 | RTO policy | fixed 1.2/1.5/2/3×RTT vs adaptive J/K | Spurious-retransmit fraction, total time |

**Experiment 4 is your differentiator.** Using the emulator's JSONL trace as ground truth, you can label
each retransmission as *spurious* (the original did arrive) or *necessary*. Almost no group will be able
to do this. It turns a vague "adaptive RTO is better" into a measured number.

**Validation experiment (do not skip).** Re-run Experiment 1 with Linux `tc netem` on a WSL2 veth pair
instead of your own emulator, and show the goodput curves agree within confidence intervals. This
pre-empts the sharpest available viva question. Report any divergence honestly and explain it — netem's
loss model is not identical to yours, and knowing that is the point.

Report **goodput** (application bytes / wall time) and **throughput** (all bytes on wire) separately, so
retransmission overhead is visible rather than hidden.

---

## 7. Eight-week schedule

Each week ends at a **gate** — a binary, testable condition. If a gate slips, the fallback in §8 fires
immediately rather than at the end.

| Week | Dates | Target | Gate |
|---|---|---|---|
| **W0** | Aug 25–31 | Team confirmed, proposal written and submitted, repo + Gradle wrapper + CI, WSL2 Ubuntu started and JDK 21 installed inside it | Proposal submitted; repo shared with instructor; `./gradlew test` green on all 4 machines |
| **W1** | Sep 1–7 | `Packet.java` + checksum + tests; emulator v0 (loss only, seeded); Stop-and-Wait end to end; **capacity calibration** | SHA-256 match on 8 MiB at p=0 **and** measured emulator ceiling recorded in `results/` |
| **W2** | Sep 8–14 | Emulator v1: loss, dup, corrupt, delay+jitter, reorder, JSONL trace. Timer heap. S&W hardened | SHA-256 match at p=10% across 10 seeds |
| **W3** | Sep 15–21 | Go-Back-N complete; stats/CSV plumbing; first real plot | GBN integrity: 5 channel profiles × 10 seeds |
| **W4** | Sep 22–28 | Selective Repeat complete; Jacobson/Karels RTO + Karn | **Core functionally done** — all 3 protocols pass one shared integrity suite |
| **W5** | Sep 29–Oct 5 | Run experiments 1–3 at full seed count; write report methodology | Figures 1–3 generated by script from committed CSVs |
| **W6** | Oct 6–12 | Experiment 4 + tc/netem cross-validation; **cross-teaching session** | Figure 4 done; every member can explain the full packet path |
| **W7** | Oct 13–19 | **One** stretch goal (recommend SACK blocks — natural extension of SR); report draft complete | Report draft reviewed by all four |
| **W8** | Oct 20–26 | Code freeze; final report; viva drills | `make all` reproduces every figure from a clean clone |
| **Buffer** | Oct 27–31 | Submission | — |

**Viva drills (W8), run against each other:** "explain the checksum, byte by byte" · "why does SR need
`W ≤ 2^(k−1)`?" · "why `Integer.toUnsignedLong` on the seq field?" · "change GBN's window from 32 to 8
while it's running" · "add a new packet type" · "walk me through what happens when an ACK is lost but the
data arrived." The brief promises live code modification during the demo — rehearse it.

---

## 8. Risks and fallbacks

The proposal asks for the two most likely failures. These are them.

**Risk 1 — Selective Repeat overruns week 4.** The receiver buffer plus per-packet timers is the single
hardest piece.
*Fallback:* ship SR with one retransmission timer for the oldest unacked packet. It is still correct and
still beats GBN; document the simplification explicitly and move per-packet timers to stretch. Take this
fallback on **Sep 25** if SR is not passing integrity tests, not in October.

**Risk 2 — JVM timing jitter contaminates the measurements.** This is the real cost of choosing Java, and
it replaces the throughput risk Python would have carried. Two mechanisms: JIT compilation makes the first
few thousand packets of every run slower than the rest, and GC pauses inject latency spikes that look like
network delay.
*Mitigation, applied to every measured run:*
- **Warm-up transfer per JVM before any timed run**, discarded. Standard JVM benchmarking discipline.
- **Pre-allocate and reuse** `ByteBuffer`s and packet objects; zero allocation in the hot path.
- **Pin the heap**: `-Xms512m -Xmx512m` so it never resizes.
- **Log GC** with `-Xlog:gc` and assert no full GC occurred during measured runs. Report this in the
  methodology section — "no full GC was observed during any timed run" is a strong, checkable claim and
  turns the risk into evidence.

*Internal third risk (not for the proposal):* P3 is a popular pick and the brief treats look-alike
submissions as collusion. Mitigated by the design choices in §4.4 and Experiment 4.

---

## 9. Verification

How you prove the thing works — this is what turns implementation marks into viva marks.

0. **Capacity calibration** (W1, once per experiment host, committed to `results/`): drive the emulator
   with no impairments, find max sustained packets/sec. Every experiment must operate at ≤50% of it.
   This number goes in the report's methodology section as a stated bound — see §1.1.
1. **Unit tests** (JUnit 5): header round-trip including unsigned-field handling; checksum detects every
   single-bit flip; RTT estimator matches hand-computed Jacobson/Karels values; timer heap ordering.
2. **Determinism test:** same seed produces a byte-identical emulator JSONL trace, on any machine.
   Non-negotiable — the brief requires "deterministic via seed," and `java.util.Random` guarantees it.
3. **Emulator statistical test:** over 100k packets, measured drop/dup/corrupt rates fall within tolerance
   of the configured values.
4. **Integrity harness:** N random files × M channel profiles × K seeds, asserting SHA-256 equality.
   This is the "tests that prove correctness rather than showing one successful transfer" the brief asks
   for.
5. **Wraparound test:** shrink the sequence space, drive a transfer through several wraps, confirm both
   the legal and the *illegal* window sizes behave as theory predicts.
6. **Analytic cross-check:** measured goodput vs the `(1−p)^W` model.
7. **tc/netem cross-validation:** independent confirmation the emulator is honest.
8. **`make all` from a clean clone** regenerates every figure in the report. No hand-made plots.

---

## 10. Ground-rules compliance

- **Allowed:** `java.net`, `java.nio`, `java.util.concurrent`, `MessageDigest`, JUnit, Gradle;
  Python + pandas/matplotlib/numpy for analysis; build tooling.
- **Not allowed:** any library implementing reliable transport over UDP. Concretely, for Java that means
  **no Netty, no Aeron, no KryoNet, no JGroups, no QUIC library**. `java.net.DatagramSocket` /
  `java.nio.DatagramChannel` only. No TCP anywhere on the data path.
- **`AI-USE.md` is required** in the repo, recording what AI was used for. The brief is explicit: honest
  disclosure carries no penalty, undisclosed use found at viva does. Update it as you go, not at the end.
- One Git repo, shared with the instructor at proposal time; every member commits under their own
  name and email.

---

## 11. Proposal draft (one page, submit as PDF)

Fill in the bracketed fields. Keep it to one page — the template says so.

```
CS-30003 · Coding Assignment 1 · Project Proposal

Team          : [4 names, roll numbers, section]
Repository    : [URL]
Project       : P3 · Reliable Data Transfer over UDP
Path          : A (catalogue)

1. WHAT WE ARE BUILDING
   A reliable file-transfer system over UDP with three interchangeable ARQ
   transports — Stop-and-Wait, Go-Back-N, and Selective Repeat — behind one
   application interface. It sits at the transport layer, providing the
   reliability, ordering, and integrity guarantees that UDP does not. A
   separate channel-emulator process sits on the path and applies configurable,
   seeded impairments so that every measurement is reproducible.

2. CORE DELIVERABLES
   - 20-byte hand-packed header; RFC 1071 Internet checksum written by hand.
   - Stop-and-Wait, Go-Back-N, Selective Repeat as swappable transports.
   - Cumulative and selective ACKs; retransmission timers on a single-threaded
     timer heap driven by one NIO selector loop.
   - Adaptive RTO (Jacobson/Karels) with Karn's algorithm.
   - Standalone emulator: loss, duplication, reordering, corruption, delay with
     jitter; deterministic by seed; every decision logged to a replayable trace.
   - SHA-256 integrity verification of source vs received file.

3. THE CLAIM WE WILL TEST
   Selective Repeat sustains higher goodput than Go-Back-N under loss, and the
   gap widens with loss rate and window size: GBN degrades approximately as
   (1-p)^W while SR degrades as (1-p).
   Experiment: vary loss rate (0-20%) and window size (1-128) as independent
   variables; hold file size (8 MiB), payload (1400 B), and RTT fixed; 10 seeds
   per cell; measure goodput and retransmission count; report mean with 95%
   confidence intervals. We validate the emulator by reproducing the loss-rate
   curve under Linux tc/netem.

4. STACK AND JUSTIFICATION
   Java 21 (java.net, java.nio) for the protocol and emulator; Python with
   pandas/matplotlib for analysis; Linux via WSL2. Java gives byte-level control
   through ByteBuffer, which is big-endian by default, and its performance means
   we can write the checksum exactly as RFC 1071 specifies rather than in an
   optimised form: we measure 0.60 us/packet against an experiment operating
   point of ~4,500 packets/second. We calibrate harness capacity in week 1 and
   report it as a bound on every result.

5. WORK SPLIT
   [A] wire format, checksum, protocol interface, Stop-and-Wait
   [B] Go-Back-N, timer heap, cumulative ACKs
   [C] Selective Repeat, receiver buffer, RTT/RTO estimation
   [D] channel emulator, experiment harness, statistics, plots

6. RISKS
   - Selective Repeat's receiver buffer and per-packet timers overrun week 4.
     Fallback: single oldest-unacked timer, documented as a simplification.
   - JVM timing jitter (JIT warm-up, GC pauses) contaminating latency results.
     Fallback: warm-up runs discarded, pinned heap, zero-allocation hot path,
     GC logged and reported.

7. WEEKLY PLAN
   W1 Sep 1-7    Header, checksum, emulator v0, Stop-and-Wait          [A,D]
   W2 Sep 8-14   Full emulator, timer heap, S&W under impairment       [D,B]
   W3 Sep 15-21  Go-Back-N, metrics pipeline, first plot               [B,D]
   W4 Sep 22-28  Selective Repeat, adaptive RTO — Core complete        [C,A]
   W5 Sep 29-O5  Experiments 1-3, methodology write-up                 [D,C]
   W6 Oct 6-12   RTO experiment, tc/netem validation, cross-teaching   [D,all]
   W7 Oct 13-19  Stretch: SACK blocks; report draft                    [C,A]
   W8 Oct 20-26  Freeze, final report, viva preparation                [all]
```

---

## 12. Open questions for the instructor

Ask these in the lab before submitting — the brief says five minutes in the lab saves you a rejected week.

1. **The PDF appears truncated.** It cross-references "Section 6" for individual moderation and
   "Section 7" for rules on scanning networks you do not own, but the document ends at Section 6
   (the proposal template) with no Section 7. Ask for the complete brief.
2. **No proposal deadline or submission mechanism is stated** anywhere in the document.
3. **Team size inconsistency:** the header says "Team size: 4 (strictly)", but the Path B bar says scope
   should be "roughly 8 weeks for three people."

---

## First actions after approval

1. Start WSL2 Ubuntu (currently stopped) and install JDK 21 inside it — the host JDK 21.0.11 is a
   Windows install and the experiments must run on Linux.
2. Create the repo, push the Gradle skeleton with the wrapper committed, share with the instructor.
3. Fill in the four names in §5 and §11, export the proposal to PDF, submit.
4. Scaffold `Packet.java` + its round-trip and checksum tests until `./gradlew test` is green — that is
   W1's foundation and it is a two-hour job.
