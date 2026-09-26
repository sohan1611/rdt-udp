# Emulator operating points

## Purpose

Every experiment must run well below the emulator's measured capacity. Otherwise a goodput curve could flatten because the host or emulator has reached its processing limit rather than because of the protocol, and those two effects would look the same in the resulting plot.

## Measured ceiling

| Item | Value |
|---|---|
| Host | WSL2, Linux 6.18, 32 cores, Java 21.0.12, 512 MB heap |
| Payload | 1400 bytes |
| Ceiling (≥ 99% delivered) | **96,000 pkt/s** |
| First failing rate | 128,000 pkt/s, only 72.5% delivered |
| Budget (50% of ceiling) | **48,000 pkt/s** |

The calibration used a perfect channel with no configured loss or delay, discarded a warm-up step, and held each tested packet rate for 2 seconds.

The previous emulator reached a ceiling of 64,000 pkt/s. Increasing the UDP socket buffers to 4 MB raised the measured ceiling to 96,000 pkt/s.

## Why use only 50% of the ceiling?

The measured ceiling comes from quiet, ideal calibration conditions. Real experiments also exercise the reverse path and may experience garbage-collection pauses or contention from other processes, so using only half of the measured ceiling leaves substantial headroom for those effects.

## Planned operating points

For a sliding-window protocol with window size $W$, at most $W$ data packets can be in flight during one round trip. Its maximum possible data-packet rate is therefore:

$$
\text{data rate}=\frac{W}{RTT}
$$

Loss can only reduce this rate.

The planned channel uses `delay=20` in each direction, giving:

$$
RTT=20\text{ ms}+20\text{ ms}=40\text{ ms}=0.04\text{ s}
$$

The emulator carries traffic in both directions. Each successfully received data packet generates an ACK, so the emulator processes approximately twice the data-packet rate. This total bidirectional packet rate must be compared with the 48,000 pkt/s operating budget.

| Experiment | Protocol | W | Data rate W / 0.04 | With ACKs (×2) | Share of 48,000 budget |
|---|---|---:|---:|---:|---:|
| 1, 3, 4 | Stop-and-Wait | 1 | 25 pkt/s | 50 pkt/s | 0.1% |
| 1, 3, 4 | GBN / SR | 32 | 800 pkt/s | 1,600 pkt/s | 3.3% |
| 2 (largest window) | GBN / SR | 128 | 3,200 pkt/s | 6,400 pkt/s | **13.3%** |

With `jitter=5`, each one-way delay varies between 15 and 25 ms, so the shortest RTT is 30 ms. The true worst case is therefore 128 / 0.03 ≈ 4,267 data pkt/s, or about 8,533 pkt/s with ACKs: 17.8% of the budget, still well within it.

For the largest window:

$$
\frac{128}{0.04}=3200\text{ pkt/s}
$$

Including ACK traffic:

$$
3200\times2=6400\text{ pkt/s}
$$

Relative to the 48,000 pkt/s operating budget:

$$
\frac{6400}{48000}=0.133\approx13.3\%
$$

The corresponding worst-case data bandwidth is approximately:

$$
3200\times1420\times8=36{,}352{,}000\text{ bit/s}\approx36\text{ Mbit/s}
$$

where 1420 bytes is the 1400-byte payload plus a 20-byte header.

The most demanding planned experiment therefore uses about 13% of the operating budget, or about 7% of the measured 96,000 pkt/s ceiling. Under these planned operating points, the emulator has substantial capacity headroom and should not be the limiting factor in the experimental results.

## Cautions

1. The smoke-test configurations use `delay=1` and are intended for correctness testing only. A 1 ms delay in each direction gives a 2 ms RTT, so with $W=128$:

   $$
   \frac{128}{0.002}=64{,}000\text{ data pkt/s}
   $$

   Including ACKs gives approximately 128,000 pkt/s, which is above the measured emulator ceiling. Results from `delay=1` runs must therefore not be used for experimental figures.

2. Run the experiments in WSL2 only. Windows timer wake-ups are approximately 15.6 ms and can distort experiments that depend on short delays.

3. Re-run the calibration whenever the host machine or emulator implementation changes. `calibration.csv` records the Java version, operating system and core count so that the measured capacity is tied to the environment in which it was obtained.