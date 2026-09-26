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

## M3 — Selective Repeat · Sohini Pandit

### 2026-09-16 — M3 / SeqSpace

**What:** Used ChatGPT to understand sequence-number space logic, debug Java compiler errors, and resolve the duplicate-class issue involving `SeqSpace.java` and `SeqSpaceTest.java`. Also discussed modular arithmetic and sequence-number wraparound.

**Permitted under:** Course AI-use policy for explanation, debugging, and review assistance.

**Mine:** I created and organized `SeqSpace.java` and `SeqSpaceTest.java`, corrected the class and file organization issues, checked the implementation, and committed the work as `8ea6088` (Implement sequence number space).

### 2026-09-18 — M3 / ReceiveBuffer

**What:** Used ChatGPT to understand the receive-window logic, ring-buffer indexing, sequence-number wraparound, and how to handle packets inside and outside the current receive window. Also used it to troubleshoot test failures and Java test-file errors.

**Permitted under:** Course AI-use policy for explanation, debugging, and review assistance.

**Mine:** I implemented and debugged `ReceiveBuffer.java`, worked through the failing outside-window packet test, corrected the test and implementation issues, and ran the build and ReceiveBuffer tests. I checked that the tests passed before merging the ReceiveBuffer work into the Selective Repeat branch.

### 2026-09-26 — M3 / Selective Repeat tests

**What:** Used ChatGPT to understand and troubleshoot the Selective Repeat implementation and its tests, including sender-window behavior, ACK handling, receive-buffer integration, and build-script test execution.

**Permitted under:** Course AI-use policy for explanation, debugging, and review assistance.

**Mine:** I worked on `SelectiveRepeat.java` and `SelectiveRepeatTest.java`, integrated the work with the existing project structure, and ran `./build.sh`. The Selective Repeat tests passed, along with the existing Packet, Channel, NetEmSmoke, SeqSpace, and ReceiveBuffer tests.

### 2026-09-26 — M3 / TimerWheel integration

**What:** Used ChatGPT to understand the TimerWheel integration with Selective Repeat, troubleshoot source-directory and build issues, and interpret compiler warnings and test results.

**Permitted under:** Course AI-use policy for explanation, debugging, and review assistance.

**Mine:** I integrated TimerWheel with `SelectiveRepeat.java`, moved `TimerWheel.java` into `src/main/java/timer/` so it was included in the build, updated `build.sh` to run the Selective Repeat tests, and ran the tests. I committed and pushed the changes in commit `2720daa` (`Complete Selective Repeat timer integration`) to `m3/selective-repeat`.

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

**Status:** [ ] not yet rewritten. A first attempt on `m4/emulator-rewrite` (16 Sep) did not
meet the requirement: the committed files were the AI draft with its comments removed, so
`Channel`, `TraceLog` and `NetEm` were unchanged in substance. Being rewritten by hand; this
box stays unticked until that is done. — M4: sign and date here when complete
