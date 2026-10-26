#!/usr/bin/env python3
"""Classifies every retransmission in an emulator trace as necessary or spurious.

Experiment 4 asks how many retransmissions each RTO policy sends for nothing. The
sender cannot tell, but the emulator's trace (run_matrix.py --trace) records the
fate of every copy of every packet, so the question can be answered afterwards.

For each data packet (sequence number s >= 1; seq 0 is the handshake), the trace
gives every copy the sender offered and what the channel did to it. A copy is
*delivered* if it was neither dropped nor corrupted (the receiver discards a
corrupted copy). The receiver is replayed from the delivery times to find the copy
that was *accepted*:

  - stopwait, gbn: in order only. A copy is accepted when it arrives while s is
    the next sequence number the receiver expects; anything else is discarded.
  - sr: any delivered copy is accepted (the window is wide enough to buffer it).

Then every copy after the first is a retransmission, sent at time t, and is:

  necessary         no earlier copy was the accepted one. The data really had to
                    be sent again (lost, corrupted, or discarded out of order).
  spurious_early    an earlier copy was accepted, and either it had not yet
                    arrived at t, an ACK covering s was still on its way back, or
                    the receiver had not sent a covering ACK at all before t.
                    The timer fired too early: this is the part an RTO policy
                    controls.
  spurious_ack_lost an earlier copy was accepted before t, at least one ACK
                    covering s was sent before t, and every such ACK was dropped
                    or corrupted. Only with that evidence is the loss counted;
                    unavoidable, since the sender could not have known.
  after_ack         an ACK covering s had already reached the sender before t.
                    A correct sender never does this; any count here is a bug.

Which ACKs cover s depends on the protocol's ACK rule (--ack-rule):
  exact    ack == s            stopwait, sr (CONVENTIONS section 3)
  through  ack >= s            gbn as CONVENTIONS section 3 defines it
  next     ack >  s            gbn sending the next expected number

Times are the emulator's: a copy is offered at its trace time "us" and arrives
at us + delayMs. The emulator never lets a packet that is not being reordered
overtake the one before it in the same direction, but "us" is stamped a few
microseconds after the time the delay was measured from, so us + delayMs can
appear to invert two back-to-back packets by a fraction of a millisecond. The
replay therefore applies the same in-order rule as the emulator. Sequence numbers are compared as plain integers, which is safe
for 32-bit sequence spaces and transfers far shorter than 2^32 packets.

Usage:
    python3 analysis/spurious.py results/.work/exp4_rto/traces --out results/exp4_spurious.csv
"""

import argparse
import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

DATA = 0
ACK = 1

DEFAULT_ACK_RULE = {"stopwait": "exact", "sr": "exact", "gbn": "next"}
ACK_RULES = {
    "exact": lambda ack, seq: ack == seq,
    "through": lambda ack, seq: ack >= seq,
    "next": lambda ack, seq: ack > seq,
}

FIELDS = ["trace", "protocol", "rto", "loss", "seed", "ack_rule", "data_packets",
          "retransmissions", "necessary", "spurious_early", "spurious_ack_lost",
          "after_ack", "spurious_share", "bad_lines"]

NAME = re.compile(r"^(?P<protocol>[a-z]+)_w(?P<window>\d+)_(?P<rto>.+?)_k(?P<k>\d+)_(?P<rest>.*)_seed(?P<seed>\d+)\.jsonl$")


def parse_name(path):
    """Run settings from a trace file name written by run_matrix.trace_name()."""
    m = NAME.match(Path(path).name)
    if not m:
        return {"protocol": "", "rto": "", "loss": "", "seed": ""}
    loss = re.search(r"(?:^|_)loss([0-9.]+)", m["rest"])
    return {"protocol": m["protocol"], "rto": m["rto"].replace("fixed-", "fixed:"),
            "loss": loss.group(1) if loss else "0", "seed": m["seed"]}


def read_trace(path):
    """Records in file order, and the number of lines that could not be parsed."""
    records, bad = [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                bad += 1            # a trace cut off mid-line by an unclean shutdown
    return records, bad


def delivered(record):
    return "drop" not in record["action"] and "corrupt" not in record["action"]


def classify(records, protocol, ack_rule):
    covers = ACK_RULES[ack_rule]
    in_order = protocol != "sr"

    # Every copy of every data packet: (offered_at, arrives_at or None, is_emulator_duplicate).
    copies = defaultdict(list)
    acks = []                                       # (sent_at, arrives_at or None, ack)
    last_in_order = {}                              # direction -> latest in-order arrival
    for r in records:
        sent = r["us"]
        arrives = sent + r["delayMs"] * 1000.0 if delivered(r) else None
        if arrives is not None and "reorder" not in r["action"]:
            arrives = max(arrives, last_in_order.get(r["dir"], arrives))
            last_in_order[r["dir"]] = arrives
        if r["dir"] == "c2s" and r["type"] == DATA and r["seq"] >= 1:
            copies[r["seq"]].append((sent, arrives, r["action"].startswith("dup")))
        elif r["dir"] == "s2c" and r["type"] == ACK:
            acks.append((sent, arrives, r["ack"]))

    # Replay the receiver: which copy of each sequence number was accepted, and when.
    arrivals = sorted((arr, seq, i) for seq, cs in copies.items()
                      for i, (_, arr, _) in enumerate(cs) if arr is not None)
    accepted = {}                                   # seq -> (index of copy, arrival time)
    expected = 1
    for arr, seq, i in arrivals:
        if seq in accepted:
            continue
        if not in_order or seq == expected:
            accepted[seq] = (i, arr)
            if in_order:
                expected += 1
                while expected in accepted:         # never true in order; kept for clarity
                    expected += 1

    counts = dict(retransmissions=0, necessary=0, spurious_early=0, spurious_ack_lost=0, after_ack=0)
    for seq, cs in copies.items():
        # A duplicate made by the emulator is not something the sender sent.
        offered = [(i, sent) for i, (sent, _, dup) in enumerate(cs) if not dup]
        good = accepted.get(seq)
        for n, (i, t) in enumerate(offered):
            if n == 0:
                continue
            counts["retransmissions"] += 1
            if good is None or cs[good[0]][0] >= t:
                counts["necessary"] += 1
                continue
            covering = [(s, a) for s, a, ack in acks if covers(ack, seq) and s < t]
            if any(a is not None and a <= t for s, a in covering):
                counts["after_ack"] += 1
            elif covering and all(a is None for s, a in covering) and good[1] <= t:
                # Evidence of loss: the receiver did send a covering ACK before t,
                # and every one of them was dropped or corrupted.
                counts["spurious_ack_lost"] += 1
            else:
                # The data had not arrived yet, an ACK was still in flight, or the
                # receiver had not answered at all before t: the timer fired early.
                counts["spurious_early"] += 1
    counts["data_packets"] = len(copies)
    return counts


def analyse(path, ack_rule=None):
    meta = parse_name(path)
    rule = ack_rule or DEFAULT_ACK_RULE.get(meta["protocol"], "exact")
    records, bad = read_trace(path)
    counts = classify(records, meta["protocol"], rule)
    retx = counts["retransmissions"]
    spurious = counts["spurious_early"] + counts["spurious_ack_lost"]
    return {"trace": Path(path).name, **meta, "ack_rule": rule, **counts,
            "spurious_share": round(spurious / retx, 4) if retx else 0.0, "bad_lines": bad}


def trace_files(paths):
    for p in map(Path, paths):
        if p.is_dir():
            yield from sorted(p.glob("*.jsonl"))
        else:
            yield p


def main():
    ap = argparse.ArgumentParser(description="Label retransmissions in emulator traces as necessary or spurious")
    ap.add_argument("traces", nargs="+", help="trace files, or folders of them")
    ap.add_argument("--out", help="CSV to write (default: print to stdout)")
    ap.add_argument("--ack-rule", choices=sorted(ACK_RULES),
                    help="override the per-protocol ACK rule (see the module docstring)")
    args = ap.parse_args()

    rows = [analyse(p, args.ack_rule) for p in trace_files(args.traces)]
    if not rows:
        ap.error("no trace files found")
    out = open(args.out, "w", newline="", encoding="utf-8") if args.out else sys.stdout
    try:
        w = csv.DictWriter(out, fieldnames=FIELDS, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    finally:
        if args.out:
            out.close()
            print(f"wrote {len(rows)} rows to {args.out}")


if __name__ == "__main__":
    main()
