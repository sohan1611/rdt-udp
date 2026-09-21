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

## M2 — Timers & Go-Back-N · Sinjan Mishra

### 2026-09-16 — TimerWheel.java 
**What:** AI explained the min-heap timer design, helped draft the TimerWheel implementation 
(PriorityQueue with lazy cancellation, System.nanoTime() deadlines, and socket timeout derivation), 
and reviewed the code for correctness. 

**Permitted under:** explaining concepts; reviewing our code. 

**Mine:** I wrote TimerWheel.java myself using the design guidance. I ran PacketTest and TimerWheelTest and verified all tests passed. 

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

## M3 — Selective Repeat · Sohini Pandit

_No entries yet._

## M4 — Channel & Evidence · Sohan Mandal

### 2026-08-31 — Project planning and role split
**What:** Claude extracted the assignment PDF, compared the eight catalogue projects
against our constraints, and drafted the project plan and team plan (schedule, work
split, experiment design, risk register).
**Permitted under:** explaining concepts; improving our writing.
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

**Status:** [ ] partly done. A first attempt on `m4/emulator-rewrite` (16 Sep) did not meet
the requirement: the committed files were the AI draft with its comments removed, so
`Channel`, `TraceLog` and `NetEm` were unchanged in substance.

`TraceLog.java` and `Channel.java` were rewritten by hand on 17 Sep, and `Calibrate.java`
on 20 Sep — M4 Sohan Mandal. `TraceLog` also gained the `type` and `ack` fields the
decision trace needs before Experiment 4 can label a retransmission spurious.

`NetEm.java` and `ChannelConfig.java` were restructured on 19 Sep: expressions split out,
ternaries expanded, loops reshaped, and a new range check on `reorderExtra`. They keep
the draft's design, names and messages, so they do not yet count as rewritten. This box
stays unticked until all five are rewritten.
— M4: sign and date here when complete
