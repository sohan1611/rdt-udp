# Reliable Data Transfer over UDP

CS-30003 Coding Assignment 1, Project P3. A file-transfer system over UDP with three
interchangeable ARQ transports (Stop-and-Wait, Go-Back-N, Selective Repeat) and a channel
emulator we wrote ourselves, used to measure how each protocol behaves under loss,
reordering, duplication, corruption, and delay.

Full project plan, including the experiment design and week-by-week schedule, is in
`docs/PLAN.md`.

## Requirements

- JDK 21 or later (`javac`, `java` on PATH)
- Python 3 with pandas and matplotlib, for the analysis pipeline only
- Linux for the experiments — WSL2 Ubuntu is fine, and is needed for the `tc netem`
  cross-validation

## Build and test

```bash
./build.sh
```

Or, where `make` is available:

```bash
make test
```

Both compile everything to `build/classes` and run the test suite. There are **no
third-party runtime dependencies** — only the JDK standard library.

## Layout

| Path | Contents |
|---|---|
| `src/main/java/rdt/` | Wire format, the three ARQ protocols, timers, RTT estimation |
| `src/main/java/emulator/` | Standalone impairment middlebox (`NetEm`, `Channel`, `TraceLog`) |
| `src/main/java/app/` | Sender and receiver CLIs |
| `src/test/java/` | Tests, plus a minimal zero-dependency harness |
| `analysis/` | Python experiment runner and plotting (evidence pipeline) |
| `results/` | Committed CSVs — these are evidence, not build output |
| `figures/` | Committed PNGs, all script-generated |

## Status

- [x] `Packet` — 20-byte header, RFC 1071 checksum, 13 tests passing
- [x] `NetEm` — channel emulator, 20 tests passing (14 model + 6 end-to-end)
- [x] `Calibrate` — capacity calibration (Week 1 gate for M4)
- [ ] `StopAndWait`
- [ ] `GoBackN`
- [ ] `SelectiveRepeat`
- [ ] `RttEstimator` — Jacobson/Karels + Karn
- [ ] Experiment harness and plots

## A note on the wire format

Sequence and acknowledgement numbers are unsigned 32-bit on the wire. Java has no unsigned
integer types, so they are held in `long` and widened through `Integer.toUnsignedLong` on
every read. Sixteen-bit fields go through `Short.toUnsignedInt`. Getting this wrong
produces negative sequence numbers above 2^31, which is why `PacketTest` checks it
explicitly.

## Running the channel emulator

`NetEm` is a standalone UDP middlebox. Start it between the sender and receiver:

```bash
java -cp build/classes emulator.NetEm     --listen 9000 --to 127.0.0.1:9001 --seed 7     --both loss=0.05,delay=20,jitter=5 --trace results/run.jsonl
```

The sender then targets port 9000 instead of the receiver directly; the emulator learns the
sender's address from its first packet, so no extra configuration is needed. Impairment specs
accept `loss`, `dup`, `corrupt`, `reorder` (probabilities) and `delay`, `jitter`,
`reorderExtra` (milliseconds). `--up` and `--down` set the two directions separately.

Runs are reproducible from `--seed`: `java.util.Random` is specified exactly by the Java
platform, so the same seed gives the same decisions on any machine. Every packet consumes a
fixed number of draws, which means changing the loss rate does not shift the delay sequence —
two cells of a sweep sharing a seed differ only in the variable under test.

`--trace` writes one JSON line per decision. That file is ground truth for whether a packet
was really lost, which is what lets Experiment 4 label a retransmission spurious or necessary.

## Capacity calibration

Before running any experiment, measure what this machine can actually push through the
emulator:

```bash
java -Xms512m -Xmx512m -cp build/classes emulator.Calibrate
```

It offers traffic through a perfect channel at increasing rates and reports the highest
rate that still delivers 99% of packets, writing `results/calibration.csv`. Every
experiment must then run below half that ceiling.

This matters because a goodput curve that flattens because the machine ran out of capacity
looks identical on a plot to one that flattens because the protocol did something
interesting. Publishing the ceiling is how we tell those apart, and it is the answer to
"how do you know your numbers reflect the protocol and not the JVM?"

Run it on the machine that will actually run the experiments — a Windows result does not
carry over to WSL2.

## Contributors

This repository must show exactly the four team members as contributors. Dependabot stays
disabled, no bot pull requests are merged, and commit messages carry no AI co-author trailer.
AI use is disclosed in `AI-USE.md`, which is the channel the brief asks for.
