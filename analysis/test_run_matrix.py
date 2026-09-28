#!/usr/bin/env python3
"""Tests for the experiment harness: run_matrix.py and plots.py.

Run from the repository root:  python3 analysis/test_run_matrix.py

No Java and no network are needed. Where a test needs a transfer to happen,
run_one is replaced by a stand-in that returns a finished row, so these tests
check the harness's own logic: the integrity check, the protocol filter,
resuming a sweep, and what reaches the plots.
"""

import csv
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import plots        # noqa: E402
import run_matrix   # noqa: E402

SCRIPT = str(HERE / "run_matrix.py")


def write_config(folder, protocols=("stopwait", "gbn"), seeds=(1, 2)):
    cfg = {
        "name": "t",
        "file_bytes": 1024,
        "protocols": list(protocols),
        "windows": [4],
        "rtos": ["fixed:1.5"],
        "seqbits": 32,
        "seeds": list(seeds),
        "channel": {"delay": 20},
        "sweep": {"loss": [0.0, 0.1]},
        "timeout_s": 5,
    }
    path = Path(folder) / "t.json"
    path.write_text(json.dumps(cfg), encoding="utf-8")
    return path


def fake_row(run, status):
    """A finished row as run_one would build it, without running Java."""
    row = run_matrix.base_row(run)
    row.update({"rto_mode": run["rto"], "file_bytes": 1024, "elapsed_ms": 10.0,
                "goodput_bps": 819200.0, "wire_bytes": 1100, "throughput_bps": 880000.0,
                "data_sent": 1, "retransmissions": 0, "timeouts": 0, "dup_acks": 0,
                "acks_received": 1, "corrupt_dropped": 0, "sha256_match": status == "ok"})
    row["file_match"] = status == "ok"
    row["status"] = status
    row["wall_s"] = 0.1
    return row


def read_rows(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


class FilesMatch(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.sent = Path(self.dir.name) / "sent.bin"
        self.sent.write_bytes(os.urandom(5000))

    def tearDown(self):
        self.dir.cleanup()

    def copy(self, data):
        path = Path(self.dir.name) / "got.bin"
        path.write_bytes(data)
        return path

    def test_identical_file_matches(self):
        self.assertTrue(run_matrix.files_match(self.sent, self.copy(self.sent.read_bytes())))

    def test_one_flipped_bit_does_not_match(self):
        data = bytearray(self.sent.read_bytes())
        data[100] ^= 1
        self.assertFalse(run_matrix.files_match(self.sent, self.copy(bytes(data))))

    def test_truncated_file_does_not_match(self):
        self.assertFalse(run_matrix.files_match(self.sent, self.copy(self.sent.read_bytes()[:4000])))

    def test_missing_output_does_not_match(self):
        self.assertFalse(run_matrix.files_match(self.sent, Path(self.dir.name) / "never-written.bin"))

    def test_unreadable_output_raises_an_os_error(self):
        # A directory in place of the output file: same size check passes nothing,
        # so reading it must fail with OSError, which run_one records as an error.
        folder = Path(self.dir.name) / "not-a-file"
        folder.mkdir()
        with self.assertRaises(OSError):
            run_matrix.file_sha256(folder)


class ProtocolFilter(unittest.TestCase):

    def run_script(self, *extra):
        with tempfile.TemporaryDirectory() as d:
            cfg = write_config(d)
            return subprocess.run([sys.executable, SCRIPT, "--config", str(cfg), "--dry-run", *extra],
                                  capture_output=True, text=True)

    def test_without_filter_every_protocol_runs(self):
        out = self.run_script()
        self.assertEqual(out.returncode, 0)
        self.assertTrue(out.stdout.startswith("8 runs"), out.stdout)

    def test_filter_keeps_only_the_named_protocol(self):
        out = self.run_script("--protocols", "stopwait")
        self.assertEqual(out.returncode, 0)
        self.assertTrue(out.stdout.startswith("4 runs"), out.stdout)
        self.assertNotIn('"gbn"', out.stdout)

    def test_empty_selection_is_rejected(self):
        for value in ("", ",", " , "):
            out = self.run_script("--protocols", value)
            self.assertNotEqual(out.returncode, 0, f"--protocols {value!r} should fail")
            self.assertIn("at least one protocol", out.stderr)

    def test_unknown_protocol_is_rejected(self):
        out = self.run_script("--protocols", "tcp")
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("not in this config", out.stderr)


class ResumeAndSubsets(unittest.TestCase):
    """Sweeps resume from the CSV; only ok rows count as done."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.cfg = write_config(self.dir.name)
        self.csv = Path(self.dir.name) / "out.csv"
        self.calls = []
        self.real_run_one = run_matrix.run_one

    def tearDown(self):
        run_matrix.run_one = self.real_run_one
        self.dir.cleanup()

    def sweep(self, status_for, *extra):
        def fake(run, cp, workdir):
            self.calls.append((run["protocol"], run["loss"], run["seed"]))
            return fake_row(run, status_for(run))
        run_matrix.run_one = fake
        argv = ["run_matrix.py", "--config", str(self.cfg), "--out", str(self.csv), *extra]
        old = sys.argv
        sys.argv = argv
        try:
            run_matrix.main()
        finally:
            sys.argv = old

    def test_corrupt_runs_are_retried_and_ok_runs_are_kept(self):
        # First pass: seed 2 at 10% loss comes back corrupt.
        self.sweep(lambda r: "corrupt" if (r["loss"], r["seed"]) == (0.1, 2) else "ok",
                   "--protocols", "stopwait")
        first = read_rows(self.csv)
        self.assertEqual(len(first), 4)

        # Second pass: only the corrupt one runs again; the three ok rows are untouched.
        self.calls.clear()
        self.sweep(lambda r: "ok", "--protocols", "stopwait")
        self.assertEqual(self.calls, [("stopwait", 0.1, 2)])
        rows = read_rows(self.csv)
        self.assertEqual(rows[:4], first, "earlier rows must be preserved exactly")
        self.assertEqual(len(rows), 5)

    def test_a_subset_run_does_not_disturb_the_other_protocols(self):
        self.sweep(lambda r: "ok", "--protocols", "stopwait")
        stopwait_rows = read_rows(self.csv)

        self.calls.clear()
        self.sweep(lambda r: "ok", "--protocols", "gbn")
        self.assertTrue(all(p == "gbn" for p, _, _ in self.calls))
        rows = read_rows(self.csv)
        self.assertEqual(rows[:4], stopwait_rows)
        self.assertEqual(sorted({r["protocol"] for r in rows}), ["gbn", "stopwait"])

        # Everything is now done: a full rerun does nothing.
        self.calls.clear()
        self.sweep(lambda r: "ok")
        self.assertEqual(self.calls, [])

    def test_one_crashing_run_does_not_stop_the_sweep(self):
        def fake(run, cp, workdir):
            self.calls.append(run["seed"])
            if run["seed"] == 1 and run["loss"] == 0.0:
                raise RuntimeError("simulated harness failure")
            return fake_row(run, "ok")
        run_matrix.run_one = fake
        old = sys.argv
        sys.argv = ["run_matrix.py", "--config", str(self.cfg), "--out", str(self.csv), "--protocols", "stopwait"]
        try:
            run_matrix.main()
        finally:
            sys.argv = old
        rows = read_rows(self.csv)
        self.assertEqual(len(self.calls), 4, "the remaining runs must still happen")
        self.assertEqual([r["status"] for r in rows].count("error"), 1)
        self.assertEqual([r["status"] for r in rows].count("ok"), 3)


class Plots(unittest.TestCase):

    def test_only_ok_rows_reach_the_plots(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "r.csv"
            fields = ["protocol", "loss", "goodput_bps", "status"]
            with path.open("w", newline="", encoding="utf-8") as f:
                w = csv.DictWriter(f, fieldnames=fields)
                w.writeheader()
                for status in ("ok", "corrupt", "timeout", "error", "no_result", "ok"):
                    w.writerow({"protocol": "stopwait", "loss": 0.0, "goodput_bps": 1, "status": status})
            rows = plots.load_ok_rows(path)
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(r["status"] == "ok" for r in rows))


if __name__ == "__main__":
    unittest.main(verbosity=2)
