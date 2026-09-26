# AI Use Disclosure

Required by Section 5 of the assignment brief. **Every member logs here**, not just one.
Kept current as work proceeds, not written the night before submission.

## What the brief says

| Permitted | Not permitted |
|---|---|
| Explaining concepts | Generating our core protocol implementation and submitting it as our work |
| Debugging | |
| Reviewing our code | |
| Generating tests | |
| Boilerplate and plotting scripts | |
| Improving our writing | |

> Honest disclosure carries no penalty. Undisclosed use discovered at viva does.

## Our team rule

All four of us have access to an AI assistant, so this is worth stating plainly rather
than assuming.

**You may use AI to understand your module. You write the code.**

The viva is individual and worth 5 of the 10 marks. Sir will ask you to modify your code
live. Code you did not write is code you cannot modify under pressure, and it scores zero
regardless of whether it runs. There is a second risk: a chatbot asked to write Go-Back-N
in Java produces roughly the same answer for everyone, and the brief treats
identical-looking submissions across groups as collusion.

**Enforcement, at pull request time:** the reviewer asks two or three "why" questions
about the code before approving. *Why this data structure? What happens if this ACK is
lost? Why this bound and not that one?* If the author cannot answer, it does not merge.

Five minutes per PR. It is a viva rehearsal every week, and it surfaces copy-pasted code
in September while there is still time to fix it.

---

# Member log

Add an entry whenever AI materially helped. Use this shape:

```
### YYYY-MM-DD — <component>
**What:** <what the AI did>
**Permitted under:** <which row of the table above>
**Mine:** <what you did yourself>
```

## M1 — Framing & Session · Shaili Seth

### 2026-09-16 — Packet.java
**What:** AI explained the existing Packet implementation, including the 20-byte header,
encoding/decoding, validation, and RFC 1071 checksum logic, and reviewed my rewrite for
correctness.

**Permitted under:** explaining concepts; debugging; reviewing our code.

**Mine:** I rewrote `Packet.java` myself, including the implementation changes, helper
and variable naming, and formatting. I ran the tests and verified that `PacketTest`
passed all 13 tests.

### 2026-09-17 — CorruptPacketException.java
**What:** AI reviewed my rewrite of `CorruptPacketException.java` and helped verify that
the changes preserved its functionality.

**Permitted under:** explaining concepts; reviewing our code.

**Mine:** I rewrote `CorruptPacketException.java` myself, including its formatting and
code structure, and verified the project tests after the change.

### 2026-09-19 — ProtocolConfig.java and CONVENTIONS.md

**What:** AI explained the purpose of the shared `ProtocolConfig` settings and helped me
review the protocol conventions needed by the ARQ implementations.

**Permitted under:** explaining concepts; reviewing our code.

**Mine:** I added `ProtocolConfig.java` and `docs/CONVENTIONS.md`, then committed the changes.

### 2026-09-20 — Session, CLIs, ArqProtocol and Stop-and-Wait

**What:** AI explained the Session-layer responsibilities, including metadata, SHA-256
verification, and FIN/FINACK, and helped me review and debug the Sender/Receiver CLI and
Stop-and-Wait implementation.

**Permitted under:** explaining concepts; debugging; reviewing our code; boilerplate and
plotting scripts.

**Mine:** I added the Session helpers, Sender and Receiver CLI files, `ArqProtocol.java`,
and `StopAndWait.java`. I ran the project build and verified 0 failed, then performed a
local Sender-to-Receiver transfer test and checked the resulting statistics.

### 2026-09-21 — SHA-256 integrity and FIN/FINACK integration

**What:** AI explained and helped me debug the META packet and SHA-256 integrity exchange, including storing the expected hash, verifying the received file, and checking the FIN/FINACK teardown. AI also reviewed the resulting Git diff and helped verify the end-to-end transfer statistics.

**Permitted under:** explaining concepts; debugging; reviewing our code.

**Mine:** I implemented the changes in `Session.java` and `StopAndWait.java`, ran the project build, performed an end-to-end Sender-to-Receiver test, and verified the received file contents and the SHA-256 integrity result reported by the receiver. I committed and pushed the changes as `13913e1`.

### 2026-09-23 — Stop-and-Wait teardown and integrity fixes

**What:** AI helped me debug and review the Stop-and-Wait FIN/FINACK teardown and SHA-256 result exchange, including FINACK loss/retransmission, receiver linger, receiver-side SHA verification, and RunStats-related fixes. AI also reviewed the staged diff and helped verify the project build.

**Permitted under:** explaining concepts; debugging; reviewing our code.

**Mine:** I implemented the changes in `Session.java`, `StopAndWait.java`, and `Receiver.java`, ran `./build.sh` and verified 0 failed tests, reviewed the staged changes, and committed and pushed them as `ae16592`.

### 2026-09-26 — Stop-and-Wait RTO and session tests
**What:** AI helped me debug and review the Stop-and-Wait RTO changes and helped structure `HandshakeTest` and `SessionTest` from the handbook requirements.

**Permitted under:** explaining concepts; debugging; reviewing code; generating tests.

**Mine:** I implemented the Stop-and-Wait RTO changes, added and verified `HandshakeTest` and `SessionTest`, ran `./build.sh`, confirmed 0 failed tests, and verified HandshakeTest (3/3) and SessionTest (1/1). I committed the changes as `352185a`.

## M2 — Timers & Go-Back-N · Sinjan Mishra

### 2026-09-16 — TimerWheel.java 
**What:** AI explained the min-heap timer design: PriorityQueue structure, lazy cancellation, System.nanoTime() deadlines, 
and how to derive the socket timeout from the earliest deadline. It did not produce the implementation file. 

**Permitted under:** explaining concepts.

**Mine:** I wrote TimerWheel.java from scratch using the design guidance. All code, naming, and structure are my own.

### 2026-09-20 — TimerWheel package fix and TimerWheelTest rewrite 
**What:** AI guided the move of TimerWheel.java from src/main/timer/ (package timer) to src/main/java/rdt/ (package rdt), 
rewrote TimerWheelTest using the project's Harness style (matching SeqSpaceTest) without JUnit, 
and updated build.sh and Makefile to include rdt.TimerWheelTest. 

**Permitted under:** explaining concepts; reviewing our code. 

**Mine:** I made all file changes, ran the tests to confirm 3/3 passing, committed on branch fix/timer-wheel-rdt, and opened the PR. 

### 2026-09-21 — PR review questions (Shaili's Packet PR and PR #7) 
**What:** AI drafted three "why" review questions for Shaili's Packet PR (seq as long, RFC 1071 checksum property, wireLength parameter)
and three for PR #7 emulator (drawTen() fixed draws, tiebreakCounter ordering, seed XOR constant). 

**Permitted under:** reviewing our code. 

**Mine:** I posted all questions, read the answers, and made the approve/comment decisions independently.

### 2026-09-22 — RttEstimator.java 
**What:** AI explained the Jacobson/Karels algorithm (SRTT, RTTVAR, RTO) and Karn's algorithm 
(no sampling on retransmits, double RTO on timeout). It did not produce the implementation file. 

**Permitted under:** explaining concepts. 

**Mine:** I wrote RttEstimator.java from scratch using the design guidance. All code, naming, and structure are my own. I also wrote RttEstimatorTest with 5 hand-worked test cases and verified all pass.

### 2026-09-23 — GoBackN.java 
**What:** AI explained the Go-Back-N algorithm: sliding window, cumulative ACKs, 
single base timer, and retransmit-on-timeout. It did not produce the implementation file. 

**Permitted under:** explaining concepts. 

**Mine:** I wrote GoBackN.java from scratch using the design guidance. All code, naming, and structure are my own. I also wrote GoBackNTest with 4 tests and verified all pass.

### 2026-09-25 — GoBackN.java (fixes) 
**What:** AI explained five protocol bugs: adaptive RTO runaway under loss, 
duplicate ACKs restarting the timer, sender hanging on last ACK loss, slot() collision at wraparound, 
and TimerWheel double-decrement. It did not produce the fixed implementation. 

**Permitted under:** explaining concepts and debugging guidance. 

**Mine:** I applied all five fixes to GoBackN.java and RttEstimator.java myself. I also added the backoffResets() test, verified 6/6 and 4/4 tests pass, and pushed the updated PR. I answered both viva questions (W <= 2^k-1, GBN vs SR tradeoff) in my own words from the RFC.

## M3 — Selective Repeat · Sohini Pandit

### SeqSpace and ReceiveBufer.
## M3 — Selective Repeat · Sohini Pandit

### 2026-09-16 — SeqSpace
**What:** ChatGPT helped me understand compiler errors, Java API mismatches, sequence-number window logic, and test failures while implementing and debugging SeqSpace.

**Permitted under:** explaining concepts; debugging code; reviewing code.

**Mine:** I wrote and made the final changes to the SeqSpace implementation and tests myself.

### 2026-09-18 — ReceiveBuffer
**What:** ChatGPT helped me understand the ReceiveBuffer requirements, Java API mismatches, receive-window logic, ring-buffer indexing, and test failures while implementing and debugging ReceiveBuffer.

**Permitted under:** explaining concepts; debugging code; reviewing code; generating tests.

**Mine:** I wrote and made the final changes to the ReceiveBuffer implementation and tests myself.

## M4 — Channel & Evidence · Sohan Mandal

### 2026-08-31 — Project planning and role split
**What:** Claude extracted the assignment PDF, compared the eight catalogue projects
against our constraints, and drafted the project plan and team plan (schedule, work
split, experiment design, risk register).**Permitted under:** explaining concepts; improving our writing.
**Mine:** Choosing P3, choosing Java over Python, and the role allocation were our
decisions. We supplied the constraint that most of the group is stronger in Java, which
is what changed the language recommendation.

### 2026-08-31 — Language benchmark
**What:** Claude benchmarked the RFC 1071 checksum and UDP send/receive in Python and
Java to test the brief's warning that Python bottlenecks high-throughput work. A naive
Python byte-loop checksum caps at ~14k packets/second; the same textbook loop in Java
runs at ~1.68M. This is why we chose Java.
**Permitted under:** explaining concepts.

### 2026-09-14 — Member playbook and per-member handbooks
**What:** Claude drafted the day-by-day member playbook (first written 31 August, revised
14 September for the post-exam sprint) and one handbook per member: concepts with worked
examples, test and debugging guides, experiment designs, report outlines, and viva question
banks. The handbooks contain explanations and design guidance only — no protocol
implementation code.
**Permitted under:** explaining concepts; improving our writing.
**Mine:** The sprint schedule and role allocation were team decisions. Each member writes
their own module.

### 2026-09-15 — emulator rewrite guidance
**What:** Claude set out the contract each emulator class must keep, the order to build them
in, which test proves each step, and the traps to avoid — the fixed ten draws per packet,
copying the datagram before corrupting it, and locale-independent number formatting.
**Permitted under:** explaining concepts.
**Mine:** all code.

### 2026-09-16 — review of pull request #5
**What:** Claude checked Sohini's `SeqSpace` PR against our conventions, ran the suite on her
branch, and drafted the review comments. I posted them and made the request-changes, approve
and merge decisions.
**Permitted under:** reviewing our code.

### 2026-09-17 — TraceLog tests
**What:** Claude wrote `TraceLogTest` — nine cases covering the disabled path, the JSON line
shape, three-decimal delays, locale independence, one line per record, close being safe to
call twice, and the `type`/`ack` fields including that a DATA line and an ACK line no longer
write the same text — and registered it in `build.sh` and the `Makefile`.
**Permitted under:** generating tests.
**Mine:** the `TraceLog` implementation the tests run against, including the Locale.ROOT fix
and the `type`/`ack` fields with the four-argument overload.

### 2026-09-19 — NetEm and ChannelConfig review, NaN test
**What:** Claude compared my NetEm and ChannelConfig changes against the draft. It found
that my new range check accepted NaN, so `loss=NaN` silently dropped nothing, and added a
ChannelTest case rejecting NaN for every probability key. It confirmed the test fails
against the buggy check.
**Permitted under:** reviewing our code; generating tests.
**Mine:** the fix to the range check.

### 2026-09-20 — RunStats tests and review
**What:** Claude wrote `RunStatsTest` — eleven cases covering the RESULT line's shape, the
sixteen keys in CONVENTIONS order, quoting, the goodput and throughput arithmetic, the
timed window for wire bytes and packet counts, the retransmission and duplicate-ACK
subsets, runs that never started or never finished, and locale independence — and
registered it in `build.sh` and the `Makefile`.
It also reviewed my first version of `RunStats` and found two problems:
1. If a transfer never finished, `stop()` was never called, so `elapsed_ms` in the RESULT
   line came out as a large negative number (-9,715,193 ms).
2. The handshake packet was counted in `data_sent` but not in `wire_bytes`, so the two
   fields described different sets of packets.
**Permitted under:** generating tests; reviewing our code.
**Mine:** `RunStats` itself, and the fixes for both problems.

### 2026-09-20 — Calibrate review
**What:** Claude reviewed my rewrite of `Calibrate` and ran it end to end. It found four
problems in my first version:
1. The ceiling recorded the offered rate, not the achieved rate, so a machine whose sender
   could not keep pace would report a higher ceiling than it actually sustained.
2. If NetEm failed to start, its non-daemon thread kept the JVM alive, so a failed
   calibration hung instead of exiting.
3. `close()` declared `throws Exception`, which caused two compiler warnings on every build.
4. The class had no comment explaining why the calibration exists.
**Permitted under:** reviewing our code.
**Mine:** the `Calibrate` rewrite and all four fixes.

### 2026-09-20 — NetEmSmokeTest fix for Linux
**What:** On the first run under WSL2, the "drops roughly the configured fraction" test
failed with a measured loss of 77.9% against a configured 30%. Claude traced it to the
test, not the emulator: it sent 1,000 datagrams before reading any, and Linux's default
212,992-byte receive buffer holds only about 220 of them, so the kernel's drops were
counted as the emulator's. The original draft emulator failed identically. Claude
changed the test to send in batches of 50 and read between them.
**Permitted under:** debugging; generating tests.
**Mine:** running the suite on the experiment host, which is what exposed it.

### 2026-09-24 — run_matrix.py and experiment configs
**What:** Claude set out a stage-by-stage design for the sweep harness with example code
for each stage, reviewed my script, and found two bugs: every run was marked as an error
because the RESULT line's protocol, window and seqbits were treated as collisions, and
failed runs were never retried. It also added `results/.work/` to `.gitignore`, gave
exp4 a 2% loss so the RTO policies can differ, sped up input-file generation, and
silenced the child processes' output. On 26 Sep, at my request, it added the
`--base-rtt-ms` flag that passes the base RTT to the sender, and documented it in
CONVENTIONS sections 5 and 7. Also on 26 Sep, at my request, it added the harness's own
file comparison (`file_match`, status `corrupt` on a mismatch) after finding that the
sender reported `sha256_match` false for files that had arrived intact, and a
`--protocols` filter so one protocol's cells can run on their own.
**Permitted under:** boilerplate and plotting scripts; reviewing our code.
**Mine:** `run_matrix.py` as written, including the RESULT validation (16 fields, and a
check that protocol, window and seqbits match what was requested), resume on crash, and
the four experiment configs.

### 2026-09-24 — plots.py
**What:** Claude set out a stage-by-stage design for the plotting script with example
code, including the 95% confidence interval using t critical values for small samples,
then tested my script against synthetic data for all four figure shapes. It added a log
y-axis so Stop-and-Wait is visible next to GBN and SR, a base-2 axis for window sizes,
unit-labelled axes (goodput in Mbit/s), shorter legend labels, and a two-line title.
**Permitted under:** boilerplate and plotting scripts.
**Mine:** `plots.py` as written, including the categorical bar chart for the RTO
policies, series made from two columns for the window figure, and the figure list
that lets `make figures` run with no arguments.

### 2026-09-26 — ChannelConfig and NetEm rewrite: review and tests
**What:** Claude set out the contract ChannelConfig and NetEm must keep and the order to build
NetEm in, then reviewed my rewrites. It found two command-line crashes in NetEm: the default
channel spec "perfect" was rejected by the parser, and --verbose printed a double delay with
an integer format. It measured both files against the original draft, and added tests: a second
RunStats.start() must not reset the clock, and ChannelConfig rejects non-numeric, infinite and
malformed values while tolerating spaces and a trailing comma. It also gave the structure and
the figures for docs/operating-points.md, including the worst case under jitter, and converted
its equations to GitHub's math syntax. At my request, it restored NetEm's default command-line
ports (9000 and 9001, from CONVENTIONS section 6) after review.
**Permitted under:** explaining concepts; reviewing our code; generating tests; improving our writing.
**Mine:** the ChannelConfig and NetEm rewrites, including both fixes, the master-seed scheme,
the 4 MB socket buffers, the RunStats.start() guard, and the text of the operating-point note.

---

# Open items — must be cleared before submission

Two drafts in this repository were AI-generated. Both are core deliverables, so both must
be rewritten by hand before we submit. **The tests stay** — generating tests is explicitly
permitted, and they are what tell you the rewrite is correct.

### 1. `Packet.java`, `CorruptPacketException.java` — owner M1

Claude drafted the 20-byte header encoder/decoder and the RFC 1071 checksum, plus
`PacketTest` (13 tests).

The brief bans generating our core protocol implementation. `Packet.java` is exactly
that. M1 rewrites it, using `PacketTest` as the specification and the draft as reference
at most.

**Status:** [ ] code merged 17 Sep (PR #6) and passing its 13 tests, but the rewrite is
not done: the merged files are the AI draft with locals renamed and the formatting changed,
so the logic is unchanged in substance. Measured against the draft, 95% of the code is
character-identical once whitespace is normalised. M1 is rewriting it against `PacketTest`;
this box stays unticked until that is done. — M1: sign and date here when complete

### 2. `Channel.java`, `NetEm.java`, `ChannelConfig.java`, `TraceLog.java`, `Calibrate.java` — owner M4

Claude drafted the channel emulator and the capacity calibration tool, plus `ChannelTest`
and `NetEmSmokeTest` (20 tests).

This one is stricter than the rule above, because the catalogue entry does not merely
permit us to write the emulator, it requires it in those words: *"A channel emulator **you
write yourself**: configurable packet loss, duplication, reordering, corruption, and delay
with jitter."* An AI-written emulator fails that requirement directly.

Three design decisions are worth understanding before rewriting, because they are what
you will be asked to defend:

- The emulator runs as a **separate process**, so the protocol code contains no test-only
  branches and genuinely does not know it is being tested.
- It seeds `java.util.Random`, whose algorithm is fixed by the Java specification, so a
  seed reproduces byte-identically on any machine. `ThreadLocalRandom` and `SecureRandom`
  give no such guarantee.
- Every packet consumes a **fixed number of random draws** whether or not each is needed,
  so changing the loss rate does not shift the delay sequence. Two cells of a sweep
  sharing a seed then differ only in the variable under test, which lowers variance
  between neighbouring points on a curve.

**Status:** [x] rewritten. A first attempt on `m4/emulator-rewrite` (16 Sep) did not meet the
requirement: the committed files were the AI draft with its comments removed. All five files
have since been written by hand: `TraceLog.java` and `Channel.java` on 17 Sep, `Calibrate.java`
on 20 Sep, and `ChannelConfig.java` and `NetEm.java` on 25-26 Sep. Measured against the original
draft, 15-18% of statements are shared in the two rewritten last; the overlap is the field
declarations other classes read, and arithmetic with one correct form.
— M4 Sohan Mandal, 2026-09-26
