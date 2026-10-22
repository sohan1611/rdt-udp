#!/usr/bin/env python3
"""Tests for spurious.py, on small hand-made traces.

Run from the repository root:  python3 analysis/test_spurious.py

Each trace is built the way the emulator writes one: the time "us" the emulator
received a packet, its direction, type, seq, ack, what the channel did, and the
delay applied. Every case is one Stop-and-Wait exchange unless it says otherwise.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import spurious  # noqa: E402

DELAY = 20.0         # ms each way
RTO_US = 60_000      # fixed:1.5 of a 40 ms base RTT


def data(us, seq, action="pass", delay=DELAY):
    return {"us": us, "dir": "c2s", "type": 0, "seq": seq, "ack": 0, "action": action, "delayMs": delay}


def ack(us, n, action="pass", delay=DELAY):
    return {"us": us, "dir": "s2c", "type": 1, "seq": 0, "ack": n, "action": action, "delayMs": delay}


def counts(records, protocol="stopwait", rule="exact"):
    return spurious.classify(records, protocol, rule)


class StopAndWait(unittest.TestCase):

    def test_no_loss_means_no_retransmissions(self):
        c = counts([data(0, 1), ack(20_000, 1), data(40_000, 2), ack(60_000, 2)])
        self.assertEqual(c["retransmissions"], 0)
        self.assertEqual(c["data_packets"], 2)

    def test_lost_data_makes_the_retransmission_necessary(self):
        c = counts([data(0, 1, "drop", 0.0), data(RTO_US, 1), ack(RTO_US + 20_000, 1)])
        self.assertEqual((c["retransmissions"], c["necessary"]), (1, 1))

    def test_corrupted_data_counts_as_lost(self):
        c = counts([data(0, 1, "corrupt"), data(RTO_US, 1), ack(RTO_US + 20_000, 1)])
        self.assertEqual(c["necessary"], 1)

    def test_timer_firing_while_the_ack_is_on_its_way_is_spurious_early(self):
        # The data arrives at 20 ms, the ACK leaves then and is held 45 ms by
        # jitter, so it reaches the sender at 65 ms: after a 60 ms timer.
        c = counts([data(0, 1), ack(20_000, 1, delay=45.0), data(RTO_US, 1), ack(RTO_US + 20_000, 1)])
        self.assertEqual((c["retransmissions"], c["spurious_early"]), (1, 1))

    def test_timer_firing_before_the_data_even_arrived_is_spurious_early(self):
        c = counts([data(0, 1, delay=70.0), data(RTO_US, 1), ack(70_000, 1)])
        self.assertEqual(c["spurious_early"], 1)

    def test_lost_ack_makes_the_retransmission_spurious_but_unavoidable(self):
        c = counts([data(0, 1), ack(20_000, 1, "drop", 0.0), data(RTO_US, 1), ack(RTO_US + 20_000, 1)])
        self.assertEqual((c["spurious_ack_lost"], c["spurious_early"]), (1, 0))

    def test_no_ack_sent_yet_is_early_not_ack_lost(self):
        # The data arrived at 20 ms, but the trace records no covering ACK sent
        # before the 60 ms resend (here it leaves only at 61 ms). Nothing was lost;
        # the timer fired before the receiver answered.
        c = counts([data(0, 1), data(RTO_US, 1), ack(61_000, 1)])
        self.assertEqual((c["spurious_early"], c["spurious_ack_lost"]), (1, 0))

    def test_no_ack_at_all_is_not_counted_as_ack_lost(self):
        c = counts([data(0, 1), data(RTO_US, 1)])
        self.assertEqual(c["spurious_ack_lost"], 0)

    def test_retransmitting_after_the_ack_arrived_is_flagged(self):
        c = counts([data(0, 1), ack(20_000, 1), data(RTO_US, 1)])
        self.assertEqual(c["after_ack"], 1)

    def test_an_emulator_duplicate_is_not_a_retransmission(self):
        c = counts([data(0, 1), data(0, 1, "dup"), ack(20_000, 1)])
        self.assertEqual(c["retransmissions"], 0)

    def test_handshake_is_ignored(self):
        c = counts([data(0, 0, "drop", 0.0), data(RTO_US, 0)])
        self.assertEqual((c["retransmissions"], c["data_packets"]), (0, 0))


class GoBackN(unittest.TestCase):

    def test_out_of_order_copies_resent_after_a_loss_are_necessary(self):
        # 1 is lost; 2 and 3 arrive but the in-order receiver discards them.
        # Go-back resends all three: every resend is needed.
        recs = [data(0, 1, "drop", 0.0), data(100, 2), data(200, 3),
                ack(20_100, 1), ack(20_200, 1),                       # next expected is still 1
                data(RTO_US, 1), data(RTO_US + 100, 2), data(RTO_US + 200, 3)]
        c = counts(recs, "gbn", "next")
        self.assertEqual((c["retransmissions"], c["necessary"]), (3, 3))

    def test_ack_rule_decides_which_acks_cover_a_packet(self):
        # Packet 1 arrives; its ACK is in flight when the timer fires.
        recs = [data(0, 1), ack(20_000, 2, delay=45.0), data(RTO_US, 1)]
        self.assertEqual(counts(recs, "gbn", "next")["spurious_early"], 1)      # ack 2 > 1 covers 1
        # Under "exact", ack 2 does not cover 1, so no covering ACK was ever sent:
        # no evidence of a lost ACK, so the timer simply fired early.
        self.assertEqual(counts(recs, "gbn", "exact")["spurious_early"], 1)
        recs_lost_ack = [data(0, 1), ack(20_000, 2, "drop", 0.0), data(RTO_US, 1)]   # next expected 2: covers 1, dropped
        self.assertEqual(counts(recs_lost_ack, "gbn", "next")["spurious_ack_lost"], 1)


    def test_timestamp_jitter_does_not_fake_reordering(self):
        # Packet 2's us + delayMs lands 0.05 ms before packet 1's, an artefact of
        # when the trace time is stamped; the emulator kept them in order. A later
        # resend of 2 while its ACK is still in flight is spurious, not necessary.
        recs = [data(0, 1, delay=20.0), data(100, 2, delay=19.85),
                ack(20_000, 3, delay=45.0), data(RTO_US, 2)]          # next expected 3: covers 2
        c = counts(recs, "gbn", "next")
        self.assertEqual((c["necessary"], c["spurious_early"]), (0, 1))

    def test_a_reordered_packet_can_still_be_overtaken(self):
        # 1 is deliberately reordered (+20 ms) so 2 arrives first and is discarded
        # by the in-order receiver: resending 2 is necessary.
        recs = [data(0, 1, "reorder", 40.0), data(100, 2), ack(40_000, 2), data(RTO_US, 2)]
        c = counts(recs, "gbn", "next")
        self.assertEqual(c["necessary"], 1)


class SelectiveRepeat(unittest.TestCase):

    def test_out_of_order_copy_is_buffered_so_resending_it_is_spurious(self):
        # 1 is lost and resent (necessary); 2 arrived and was buffered, so a
        # resend of 2 while its ACK is in flight is spurious.
        recs = [data(0, 1, "drop", 0.0), data(100, 2), ack(20_100, 2, delay=45.0),
                data(RTO_US, 1), data(RTO_US + 100, 2)]
        c = counts(recs, "sr", "exact")
        self.assertEqual((c["necessary"], c["spurious_early"]), (1, 1))


class Files(unittest.TestCase):

    def test_settings_come_from_the_trace_name(self):
        meta = spurious.parse_name("stopwait_w32_fixed-1.2_k32_loss0.02_delay20_jitter5_seed7.jsonl")
        self.assertEqual(meta, {"protocol": "stopwait", "rto": "fixed:1.2", "loss": "0.02", "seed": "7"})

    def test_a_line_cut_off_mid_way_is_skipped_and_counted(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "stopwait_w1_adaptive_k32_loss0.02_delay20_seed1.jsonl"
            lines = [json.dumps(r) for r in (data(0, 1, "drop", 0.0), data(RTO_US, 1))]
            path.write_text("\n".join(lines) + '\n{"us":12,"dir":"c2', encoding="utf-8")
            row = spurious.analyse(path)
        self.assertEqual((row["bad_lines"], row["necessary"], row["rto"]), (1, 1, "adaptive"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
