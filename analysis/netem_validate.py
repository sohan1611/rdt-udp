#!/usr/bin/env python3
"""Cross-validates our channel emulator against Linux's own netem.

For each loss level in a config, this applies a netem queue to the loopback
interface and runs the sender straight to the receiver, with no emulator process
in between. If our emulator is honest, the goodput curve measured here agrees
with the one run_matrix.py measures through emulator.NetEm.

On loopback a netem queue applies once per direction, so
"delay D jitter J loss P" on lo matches our --both "delay=D,jitter=J,loss=P":
a round trip of 2D, with loss applied to data and to ACKs.

Must run as root, because tc needs it, and while nothing else is using
loopback, because the queue delays and drops every loopback packet:

    sudo python3 analysis/netem_validate.py --config analysis/configs/exp1_loss.json

netem cannot be seeded, so each level is repeated --repeats times instead.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_matrix as rm  # noqa: E402

FIELDS = (["experiment", "protocol", "rto", "repeat", "loss", "delay", "jitter"]
          + ["rto_mode", "file_bytes", "elapsed_ms", "goodput_bps", "wire_bytes",
             "throughput_bps", "data_sent", "retransmissions", "timeouts", "dup_acks",
             "acks_received", "corrupt_dropped", "sha256_match"]
          + ["file_match", "status", "wall_s"])


def tc(*args):
    return subprocess.run(["tc", *args], capture_output=True, text=True)


def set_netem(iface, delay, jitter, loss):
    spec = ["delay", f"{delay}ms"]
    if jitter:
        spec.append(f"{jitter}ms")
    if loss:
        spec += ["loss", f"{loss * 100:g}%"]
    out = tc("qdisc", "replace", "dev", iface, "root", "netem", *spec)
    if out.returncode != 0:
        raise RuntimeError("tc failed: " + out.stderr.strip())
    return " ".join(spec)


def clear_netem(iface):
    tc("qdisc", "del", "dev", iface, "root")      # fine if nothing was set


def other_transfers_running():
    out = subprocess.run(["pgrep", "-f", r"app\.(Sender|Receiver)|emulator\.NetEm"],
                         capture_output=True, text=True)
    return [line for line in out.stdout.split() if line.strip()]


def transfer(cp, protocol, rto, base_rtt, seqbits, window, input_path, output_path, timeout):
    """One transfer straight from sender to receiver over loopback."""
    if output_path.exists():
        output_path.unlink()
    port = rm.free_port()
    java = ["java", "-Xms512m", "-Xmx512m", "-cp", str(cp)]
    receiver = subprocess.Popen(java + ["app.Receiver", "--port", str(port), "--out", str(output_path),
                                        "--protocol", protocol, "--window", str(window),
                                        "--seqbits", str(seqbits)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    result, status, started = {}, "error", time.monotonic()
    try:
        time.sleep(0.5)
        done = subprocess.run(java + ["app.Sender", "--file", str(input_path), "--to", f"127.0.0.1:{port}",
                                      "--protocol", protocol, "--window", str(window), "--rto", rto,
                                      "--seqbits", str(seqbits), "--base-rtt-ms", str(base_rtt)],
                              capture_output=True, text=True, timeout=timeout)
        line = next((l for l in done.stdout.splitlines() if l.startswith("RESULT ")), None)
        if line is not None and done.returncode == 0:
            result, status = json.loads(line[len("RESULT "):]), "ok"
        else:
            status = "no_result"
        try:
            receiver.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
    except subprocess.TimeoutExpired:
        status = "timeout"
    finally:
        rm.kill_process(receiver)
    match = rm.files_match(input_path, output_path)
    if status == "ok" and not match:
        status = "corrupt"
    return result, match, status, round(time.monotonic() - started, 3)


def done_keys(path):
    if not path.exists():
        return set()
    import csv
    with path.open(newline="", encoding="utf-8") as f:
        return {(r["protocol"], r["rto"], r["loss"], r["repeat"]) for r in csv.DictReader(f) if r["status"] == "ok"}


def append(path, row):
    import csv
    new = not path.exists()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        if new:
            w.writeheader()
        w.writerow(row)


def main():
    ap = argparse.ArgumentParser(description="Cross-validate the emulator against Linux netem")
    ap.add_argument("--config", default="analysis/configs/exp1_loss.json")
    ap.add_argument("--protocols", default="stopwait", help="comma-separated, e.g. stopwait")
    ap.add_argument("--repeats", type=int, default=5)
    ap.add_argument("--iface", default="lo")
    ap.add_argument("--cp", default="build/classes")
    ap.add_argument("--out", default="results/netem_validation.csv")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    cfg = rm.load_config(args.config)
    if set(cfg["sweep"]) != {"loss"}:
        ap.error("netem validation compares loss sweeps only; the config must sweep 'loss'")
    protocols = [p.strip() for p in args.protocols.split(",") if p.strip()]
    if not protocols or set(protocols) - set(cfg["protocols"]):
        ap.error("--protocols must name protocols from the config")
    delay = cfg["channel"].get("delay", 0)
    jitter = cfg["channel"].get("jitter", 0)
    window = cfg["windows"][0]
    base_rtt = max(1, round(2 * float(delay)))
    plan = [(p, rto, loss, rep) for p in protocols for rto in cfg["rtos"]
            for loss in cfg["sweep"]["loss"] for rep in range(1, args.repeats + 1)]

    if args.dry_run:
        print(f"{len(plan)} transfers through netem on {args.iface}: delay {delay}ms jitter {jitter}ms, base RTT {base_rtt} ms")
        for p, rto, loss, rep in plan[:args.repeats + 1]:
            print(f"  {p} rto={rto} loss={loss} repeat={rep}")
        print("  ...")
        return
    if os.geteuid() != 0:
        sys.exit("tc needs root: run this with sudo")
    busy = other_transfers_running()
    if busy:
        sys.exit("another transfer or emulator is running (pids " + ", ".join(busy) + "). "
                 "A netem queue on loopback would distort it; wait until it finishes.")

    out = Path(args.out)
    work = Path("results/.work/netem")
    work.mkdir(parents=True, exist_ok=True)
    input_path, output_path = work / "input.bin", work / "out.bin"
    rm.make_input(input_path, cfg["file_bytes"])
    done = done_keys(out)
    current = None
    try:
        for i, (p, rto, loss, rep) in enumerate(plan, 1):
            key = (p, rto, str(loss), str(rep))
            label = f"[{i}/{len(plan)}] {p} rto={rto} loss={loss} repeat={rep}"
            if key in done:
                print(label, "-> skipped", flush=True)
                continue
            if current != loss:
                spec = set_netem(args.iface, delay, jitter, loss)
                current = loss
                print(f"netem on {args.iface}: {spec}", flush=True)
            result, match, status, wall = transfer(args.cp, p, rto, base_rtt, cfg["seqbits"], window,
                                                   input_path, output_path, cfg["timeout_s"])
            row = {"experiment": "netem_" + cfg["name"], "protocol": p, "rto": rto, "repeat": rep,
                   "loss": loss, "delay": delay, "jitter": jitter, **result,
                   "file_match": match, "status": status, "wall_s": wall}
            append(out, row)
            print(label, "->", status, f"{wall:.1f} s", flush=True)
    finally:
        clear_netem(args.iface)
        print(f"netem removed from {args.iface}", flush=True)


if __name__ == "__main__":
    main()
