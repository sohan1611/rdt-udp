# Team Conventions

## 1. Protocol interface

One `ArqProtocol` interface has two operations:

- send a file to a peer
- receive a file on a port

Both use the shared `ProtocolConfig`, containing:

- window
- RTO mode
- sequence-space bits
- payload size

Both operations return `RunStats`.

The session layer handles the metadata handshake, FIN teardown, and hash
check outside the ARQ protocols.

---

## 2. Sequence numbers

The handshake is a DATA packet with:

- `seq = 0`
- META flag `0x01` set

File data starts at `seq = 1`.

The sequence space uses `k` bits and is configured with `--seqbits`.
The default is 32 bits.

Sequence-number comparisons use modular arithmetic.

---

## 3. ACK numbers

The `ack` field always contains a sequence number that was received.

- Stop-and-Wait acknowledges exactly that packet.
- Selective Repeat acknowledges exactly that packet.
- Go-Back-N uses cumulative ACKs: ACK `n` means everything through `n`
  arrived in order.

Each ARQ protocol restates its ACK rule in its class comment.

---

## 4. Payload size

DATA packets use a payload size of 1400 bytes.

The final DATA packet may be shorter.

An 8 MiB file therefore contains 5,992 data packets plus the handshake.

---
## 5. Command-line flags

### Sender

    --file
    --to host:port
    --protocol stopwait|gbn|sr
    --window N
    --rto adaptive|fixed:X
    --base-rtt-ms N
    --seqbits k
    --verbose

### Receiver

    --port
    --out
    --protocol stopwait|gbn|sr
    --window N
    --seqbits k
    --verbose

---

## 6. Ports

For the manual emulator:

- emulator port: 9000
- receiver port: 9001

The test harness uses fresh ports.

---

## 7. Fixed RTO

`--rto fixed:1.5` means 1.5 times the base RTT.

The base RTT is the emulator delay up + down.

With a 20 ms delay:

- base RTT = 40 ms
- RTO = 60 ms

The sender cannot measure the emulator's delay, so it is told: `--base-rtt-ms N`.
`run_matrix.py` computes it as delay up + down (jitter excluded) and passes it on every
run. The sender uses it only for `fixed:X`; `adaptive` ignores it.

---

## 8. RESULT line

At the end of a transfer the sender prints exactly one line beginning
with `RESULT` followed by a single JSON object of `RunStats` fields.

Nothing else may print a line starting with `RESULT`.

`run_matrix.py` reads only that line.

---

## 9. Goodput

Goodput = file bytes × 8 ÷ elapsed seconds.

The elapsed time runs from sending `seq 1` to receiving the ACK that
covers the last data packet.

Handshake and FIN are excluded.

Wire throughput = every byte the sender put on the wire, headers and
retransmissions included, × 8 ÷ the same elapsed time.

---

## 10. RunStats

`RunStats` contains:

- `protocol`
- `window`
- `seqbits`
- `rto_mode`
- `file_bytes`
- `elapsed_ms`
- `goodput_bps`
- `wire_bytes`
- `throughput_bps`
- `data_sent`
- `retransmissions`
- `timeouts`
- `dup_acks`
- `acks_received`
- `corrupt_dropped`
- `sha256_match`

The harness adds the run settings itself:

- `seed`
- `loss`
- `dup`
- `corrupt`
- `reorder`
- `delay`
- `jitter`