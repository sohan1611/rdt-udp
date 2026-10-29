# Experiment 4 Design Note: Trace-Based Retransmission Classification

**Author:** Sinjan Mishra (M2)
**Scope:** Experiment 4 — fixed versus adaptive retransmission timeout (RTO) policies

## Purpose

Experiment 4 compares fixed RTO multipliers (`fixed:1.2`, `fixed:1.5`, `fixed:2`, `fixed:3`) with adaptive RTO at 2% packet loss. Total retransmissions alone do not show whether a retransmission recovered lost data or was sent unnecessarily because a timer expired too soon. The emulator's JSONL trace records the fate and timing of packet copies, allowing each sender-originated DATA retransmission to be classified after a run.

## Trace inputs and replay

Each JSONL record contains the packet direction (`dir`), type (`type`: DATA or ACK), sequence/acknowledgement number, trace timestamp (`us`), configured delay (`delayMs`), and channel action (`action`). A DATA copy is considered delivered unless its action indicates a drop or corruption. Its modelled arrival time is `us + delayMs × 1000` microseconds. The replay preserves the emulator's in-order delivery rule within each direction except for packets explicitly marked as reordered.

DATA sequence number 0 is the session handshake and is excluded. For each data sequence number, the classifier records all copies the sender offered; emulator-generated duplicates are excluded because the sender did not retransmit them. It then replays delivered copies in arrival-time order to determine which copy the receiver would accept:

- **Stop-and-Wait and Go-Back-N:** the receiver accepts only the next expected sequence number; copies arriving out of order are discarded.
- **Selective Repeat:** any delivered copy can be accepted, because out-of-order data can be buffered.

This replay is necessary because a trace's `pass` action only means the channel delivered a copy; it does not guarantee the protocol receiver accepted it.

## Classification of retransmissions

For every sender-originated DATA copy after the first copy of that sequence number, the classifier records one retransmission and assigns a category:

- **Necessary:** no earlier-sent copy became the copy accepted by the receiver. Earlier copies may have been dropped, corrupted, or discarded as out of order, so sending the data again was needed for successful delivery.
- **Spurious early timeout (`spurious_early`):** an earlier-sent copy is ultimately accepted, but the sender retransmitted before a covering ACK had reached it. This includes cases where the original DATA copy or its ACK was still in flight, or the receiver had not yet sent a covering ACK. These events are the category most directly affected by an RTO that is too short.
- **Spurious because ACK was lost (`spurious_ack_lost`):** an earlier copy was accepted before the retransmission, at least one ACK covering that sequence number had already been sent, and every such ACK sent before the retransmission was dropped or corrupted. The classifier uses this label only when the trace provides evidence of ACK loss; the sender could not know that the receiver had accepted the data.
- **After ACK (`after_ack`):** a covering ACK had already reached the sender before the retransmission. This is recorded separately as an anomalous event, since a correct sender should not retransmit data after processing its ACK.

ACK coverage follows the protocol's configured rule: exact sequence equality for Stop-and-Wait and Selective Repeat, and the configured cumulative/next-expected rule for Go-Back-N. The classification therefore uses the ACK semantics of the protocol rather than treating every ACK as confirmation of every outstanding packet.

## Outputs and interpretation

`analysis/spurious.py` replays each trace and writes one row per trace to `results/exp4_spurious.csv`, including retransmission totals, counts for each category, spurious share, and malformed-line count. Emulator-generated duplicate copies are not counted as sender retransmissions. The experiment uses these per-run labels alongside goodput to compare RTO policies: a short timeout may improve recovery speed but create more early retransmissions; a longer timeout may reduce premature retransmissions but delay recovery from genuine loss. Adaptive RTO must be judged from the measured results, not assumed to be best in advance.

The method depends on the trace accurately recording packet actions and timing, and on the replay matching the emulator's delivery-order rules. For this reason, the classifier explicitly models receiver acceptance and ACK delivery instead of inferring retransmission cause from counts or elapsed time alone.
