# Reliable Data Transfer over UDP

CS-30003 Coding Assignment 1, Project P3. A file-transfer system over UDP with three
interchangeable ARQ transports (Stop-and-Wait, Go-Back-N, Selective Repeat) and a channel
emulator we wrote ourselves, used to measure how each protocol behaves under loss,
reordering, duplication, corruption, and delay.

Full project plan, including the experiment design and week-by-week schedule, is in
`docs/PLAN.md`.

## Reproducing the experiments

Run these commands from the repository root on the machine used for the measurements.

### 1. Requirements

- WSL2 Ubuntu (Linux) for timed experiments and Linux `tc netem` validation.
- JDK 21 or later (`javac` and `java` on `PATH`).
- Python 3 with matplotlib for the analysis pipeline.

Use WSL2 rather than native Windows for timed measurements: Windows timer wake-ups are
approximately 15.6 ms apart, which distorts short delays and retransmission timeouts.

### 2. Build and test

```bash
./build.sh
```

Or, where `make` is available:

```bash
make test
```

Both compile everything to `build/classes` and run the test suite. There are **no
third-party runtime dependencies** — only the JDK standard library.

### 3. Calibrate the emulator

Before running any experiment, measure what this machine can actually push through the
emulator:

```bash
java -Xms512m -Xmx512m -cp build/classes emulator.Calibrate
```

Calibration offers traffic through a perfect channel at increasing rates and reports the
highest rate that still delivers at least 99% of packets, writing `results/calibration.csv`.
On the measured WSL2 host, the sustainable ceiling for the current two-thread emulator was
**64,000 pkt/s**; the operating budget is **32,000 pkt/s** (half of that ceiling). The
next tested rate, 96,000 pkt/s, delivered only 89.8–91.5% of packets across two runs.
Recalibrate on the host used for the experiments; capacity measured on Windows is not
transferable to WSL2.

This prevents a host or emulator throughput limit from being mistaken for a protocol effect
when a goodput curve flattens. Planned experiment traffic stays comfortably below the budget.

### 4. Run an experiment

```bash
python3 analysis/run_matrix.py --config analysis/configs/exp1_loss.json
```

Use `--protocols stopwait` to limit a run to Stop-and-Wait. Use `--trace` for experiment 4
to retain emulator traces, which allow necessary and spurious retransmissions to be
classified; traces for each run are kept under `<out>/.work/<name>/traces/`.
The harness resumes interrupted experiments: rows already marked `ok` are retained,
while failed or incomplete rows are rerun. Results are written to `results/expN_*.csv`.

### 5. Validate against Linux netem

```bash
sudo python3 analysis/netem_validate.py --config analysis/configs/exp1_loss.json
```

This cross-check requires root privileges and an otherwise idle loopback interface.

### 6. Generate figures

```bash
python3 analysis/plots.py
```

The plotting script reads the experiment CSVs and writes generated plots to `figures/`.

### 7. Rules for valid timing

Do not run other workloads on the host during timed experiments. The `delay=1` smoke-test
configuration is for correctness checks, not for figures: its offered rate can exceed the
measured emulator capacity. Use the experiment configurations and an idle WSL2 host for
reported measurements.

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

Updated 28 October 2026. All components are merged into `main`; `make all` builds, runs every
test suite below and redraws Figures 1–5 from the committed results.

| Component | Owner | State | Tests |
|---|---|---|---|
| `Packet` — wire format and checksum | M1 | Merged (AI-assisted draft); hand-written rewrite by M1 in PR #41 — see `AI-USE.md` open items | `PacketTest` 13 |
| `Session` — handshake and teardown; sender/receiver CLIs | M1 | Merged | `HandshakeTest` 3, `SessionTest` 3 |
| `StopAndWait` | M1 | Merged | covered by `SessionTest` and the experiment runs |
| `TimerWheel` | M2 | Merged | `TimerWheelTest` 8 |
| `RttEstimator` | M2 | Merged | `RttEstimatorTest` 6 |
| `GoBackN` | M2 | Merged (#32) | `GoBackNTest` 4 |
| `SeqSpace` | M3 | Merged | `SeqSpaceTest` 21 |
| `ReceiveBuffer` | M3 | Merged | `ReceiveBufferTest` 23 |
| `SelectiveRepeat` | M3 | Merged (#31 via #37) | `SelectiveRepeatTest` 7 |
| `Channel`, `NetEm`, `TraceLog`, `ChannelConfig` — emulator | M4 | Merged (incl. #33 timing and ordering fixes) | `ChannelTest` 21, `NetEmSmokeTest` 8, `TraceLogTest` 9 |
| `Calibrate` — capacity calibration | M4 | Merged | run, not unit-tested (`results/calibration.csv`) |
| `RunStats`, experiment harness | M4 | Merged | `RunStatsTest` 12, `test_run_matrix.py` 21 |
| Plotting and retransmission classification | M4 | Merged (#35) | in `test_run_matrix.py`; `test_spurious.py` 18 |
| Linux `netem` validation | M4 | Merged (#30) | run (`results/netem_validation.csv`) |

All experiments are complete: 890 runs, every one `ok` with a matching received file (see `report/`).

## A note on the wire format

Sequence and acknowledgement numbers are unsigned 32-bit on the wire. Java has no unsigned
integer types, so they are held in `long` and widened through `Integer.toUnsignedLong` on
every read. Sixteen-bit fields go through `Short.toUnsignedInt`. Getting this wrong
produces negative sequence numbers above 2^31, which is why `PacketTest` checks it
explicitly.

## Running the channel emulator

`NetEm` is a standalone UDP middlebox. Start it between the sender and receiver:

```bash
java -cp build/classes emulator.NetEm \
    --listen 9000 --to 127.0.0.1:9001 --seed 7 \
    --both loss=0.05,delay=20,jitter=5 --trace results/run.jsonl
```

The sender then targets port 9000 instead of the receiver directly; the emulator learns the
sender's address from its first packet, so no extra configuration is needed. Impairment specs
accept `loss`, `dup`, `corrupt`, `reorder` (probabilities) and `delay`, `jitter`,
`reorderExtra` (milliseconds). `--up` and `--down` set the two directions separately.
Jitter preserves packet order within each direction; only the explicit `reorder` impairment
causes packets to overtake one another.

Runs are reproducible from `--seed`: `java.util.Random` is specified exactly by the Java
platform, so the same seed gives the same decisions on any machine. Every packet consumes a
fixed number of draws, which means changing the loss rate does not shift the delay sequence —
two cells of a sweep sharing a seed differ only in the variable under test.

`--trace` writes one JSON line per decision. That file is ground truth for whether a packet
was really lost, which is what lets Experiment 4 label a retransmission spurious or necessary.

## Contributors

This repository must show exactly the four team members as contributors. Dependabot stays
disabled, no bot pull requests are merged, and commit messages carry no AI co-author trailer.
AI use is disclosed in `AI-USE.md`, which is the channel the brief asks for.
