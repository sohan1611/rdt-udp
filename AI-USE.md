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

## M1 — Framing & Session · _[name]_

_No entries yet._

## M2 — Timers & Go-Back-N · _[name]_

_No entries yet._

## M3 — Selective Repeat · _[name]_

_No entries yet._

## M4 — Channel & Evidence · _[name]_

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

**Status:** [ ] not yet rewritten — M1: sign and date here when done

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

**Status:** [ ] not yet rewritten — M4: sign and date here when done
