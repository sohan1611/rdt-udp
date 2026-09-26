#!/usr/bin/env python3
import argparse
import csv
import itertools
import json
import random
import socket
import subprocess
import time
from pathlib import Path

RUN_FIELDS=["experiment","protocol","window","rto","seqbits","seed"]
CHANNEL_FIELDS=["loss","dup","corrupt","reorder","delay","jitter"]
NETEM_FIELDS=["loss","dup","corrupt","reorder","delay","jitter","reorderExtra"]
TAIL_FIELDS=["status","wall_s"]
FIXED_FIELDS=set(RUN_FIELDS+CHANNEL_FIELDS+TAIL_FIELDS)

def load_config(path):
    with Path(path).open("r",encoding="utf-8") as f:
        return json.load(f)

def expand(cfg):
    sweep_keys=list(cfg["sweep"].keys())
    sweep_values=[cfg["sweep"][key] for key in sweep_keys]
    runs=[]
    for combo in itertools.product(cfg["protocols"],cfg["windows"],cfg["rtos"],*sweep_values,cfg["seeds"]):
        protocol=combo[0]
        window=combo[1]
        rto=combo[2]
        values=combo[3:3+len(sweep_keys)]
        seed=combo[-1]
        run={
            "experiment":cfg["name"],
            "protocol":protocol,
            "window":window,
            "rto":rto,
            "seqbits":cfg["seqbits"],
            "seed":seed,
            "timeout_s":cfg["timeout_s"]
        }
        run.update(cfg["channel"])
        run.update(dict(zip(sweep_keys,values)))
        runs.append(run)
    return runs

def channel_spec(run):
    return ",".join(f"{key}={run[key]}" for key in NETEM_FIELDS if key in run)

def base_rtt_ms(run):
    # CONVENTIONS section 7: the base RTT is the emulator delay up plus down.
    # The emulator is always started with --both, so both directions share one
    # delay. Jitter is deliberately left out. The sender needs this to turn
    # --rto fixed:X into X times the base RTT; at least 1 ms, so X never
    # multiplies zero.
    return max(1,round(2*float(run.get("delay",0))))

def make_input(path,size):
    path=Path(path)
    size=int(size)
    if path.exists() and path.stat().st_size==size:
        return
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(random.Random(12345).randbytes(size))

def free_port():
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        sock.bind(("127.0.0.1",0))
        return sock.getsockname()[1]

def kill_process(proc):
    if proc is None:
        return
    if proc.poll() is None:
        proc.kill()
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass

def base_row(run):
    row={
        "experiment":run["experiment"],
        "protocol":run["protocol"],
        "window":run["window"],
        "rto":run["rto"],
        "seqbits":run["seqbits"],
        "seed":run["seed"]
    }
    for key in CHANNEL_FIELDS:
        row[key]=run.get(key,0)
    return row

def run_one(run,cp,workdir):
    workdir=Path(workdir)
    workdir.mkdir(parents=True,exist_ok=True)
    input_path=(workdir/"input.bin").resolve()
    output_path=(workdir/"out.bin").resolve()
    if output_path.exists():
        output_path.unlink()
    receiver=None
    netem=None
    result={}
    status="error"
    detail=""
    started=time.monotonic()
    try:
        receiver_port=free_port()
        netem_port=free_port()
        while netem_port==receiver_port:
            netem_port=free_port()
        receiver_cmd=[
            "java","-Xms512m","-Xmx512m","-cp",str(cp),
            "app.Receiver",
            "--port",str(receiver_port),
            "--out",str(output_path),
            "--protocol",str(run["protocol"]),
            "--window",str(run["window"]),
            "--seqbits",str(run["seqbits"])
        ]
        receiver=subprocess.Popen(receiver_cmd,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        netem_cmd=[
            "java","-cp",str(cp),
            "emulator.NetEm",
            "--listen",str(netem_port),
            "--to",f"127.0.0.1:{receiver_port}",
            "--seed",str(run["seed"]),
            "--both",channel_spec(run)
        ]
        netem=subprocess.Popen(netem_cmd,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        time.sleep(0.5)
        sender_cmd=[
            "java","-Xms512m","-Xmx512m","-cp",str(cp),
            "app.Sender",
            "--file",str(input_path),
            "--to",f"127.0.0.1:{netem_port}",
            "--protocol",str(run["protocol"]),
            "--window",str(run["window"]),
            "--rto",str(run["rto"]),
            "--seqbits",str(run["seqbits"]),
            "--base-rtt-ms",str(base_rtt_ms(run))
        ]
        try:
            completed=subprocess.run(sender_cmd,capture_output=True,text=True,timeout=float(run["timeout_s"]))
        except subprocess.TimeoutExpired:
            status="timeout"
            detail=f"sender exceeded {run['timeout_s']} s"
        else:
            result_line=next((line for line in completed.stdout.splitlines() if line.startswith("RESULT ")),None)
            if result_line is None:
                status="no_result" if completed.returncode==0 else "error"
                detail="sender printed no RESULT line"
            else:
                try:
                    parsed=json.loads(result_line[7:])
                    if not isinstance(parsed,dict):
                        raise ValueError("RESULT JSON is not an object")
                    result=parsed
                except (json.JSONDecodeError,ValueError) as exc:
                    status="error"
                    detail=f"bad RESULT JSON: {exc}"
                else:
                    expected_overlap={"protocol","window","seqbits"}
                    overlap=FIXED_FIELDS.intersection(result.keys())
                    unexpected=overlap-expected_overlap
                    mismatch=[]
                    for key in expected_overlap:
                        if key in result and str(result[key])!=str(run[key]):
                            mismatch.append(key)
                    if unexpected:
                        status="error"
                        detail="unexpected RESULT field collision: "+",".join(sorted(unexpected))
                    elif mismatch:
                        status="error"
                        detail="RESULT settings mismatch: "+",".join(sorted(mismatch))
                    elif completed.returncode!=0:
                        status="error"
                        detail=f"sender exited {completed.returncode}"
                    elif len(result)!=16:
                        status="error"
                        detail=f"expected 16 RESULT fields, got {len(result)}"
                    else:
                        status="ok"
            if receiver is not None:
                try:
                    receiver_rc=receiver.wait(timeout=10)
                    if status=="ok" and receiver_rc!=0:
                        status="error"
                        detail=f"receiver exited {receiver_rc}"
                except subprocess.TimeoutExpired:
                    if status=="ok":
                        status="error"
                        detail="receiver did not exit within 10 s"
    except Exception as exc:
        status="error"
        detail=f"{type(exc).__name__}: {exc}"
    finally:
        kill_process(netem)
        kill_process(receiver)
    row=base_row(run)
    row.update(result)
    row["status"]=status
    row["wall_s"]=round(time.monotonic()-started,3)
    if detail:
        row["_detail"]=detail
    return row

def canon(value):
    if value is None or value=="":
        return ""
    return str(value)

def run_key(mapping):
    return tuple(canon(mapping.get(key,"")) for key in RUN_FIELDS)+tuple(canon(mapping.get(key,0)) for key in CHANNEL_FIELDS)

def done_keys(csv_path):
    csv_path=Path(csv_path)
    if not csv_path.exists() or csv_path.stat().st_size==0:
        return set()
    with csv_path.open("r",newline="",encoding="utf-8") as f:
        return {run_key(row) for row in csv.DictReader(f) if row.get("status")=="ok"}

def result_fields(row):
    return [key for key in row if key not in FIXED_FIELDS and not key.startswith("_")]

def append_row(csv_path,row):
    csv_path=Path(csv_path)
    csv_path.parent.mkdir(parents=True,exist_ok=True)
    incoming=result_fields(row)
    if not csv_path.exists() or csv_path.stat().st_size==0:
        fields=RUN_FIELDS+CHANNEL_FIELDS+incoming+TAIL_FIELDS
        with csv_path.open("a",newline="",encoding="utf-8") as f:
            writer=csv.DictWriter(f,fieldnames=fields,extrasaction="ignore")
            writer.writeheader()
            writer.writerow(row)
            f.flush()
        return
    with csv_path.open("r",newline="",encoding="utf-8") as f:
        reader=csv.DictReader(f)
        old_fields=reader.fieldnames or []
        unknown=[key for key in incoming if key not in old_fields]
        old_rows=list(reader) if unknown else None
    if unknown:
        old_result=[key for key in old_fields if key not in FIXED_FIELDS and not key.startswith("_")]
        new_result=old_result+unknown
        if len(new_result)>16:
            raise ValueError(f"expected 16 RESULT fields, got {len(new_result)}")
        new_fields=RUN_FIELDS+CHANNEL_FIELDS+new_result+TAIL_FIELDS
        tmp=csv_path.with_name(csv_path.name+".tmp")
        with tmp.open("w",newline="",encoding="utf-8") as f:
            writer=csv.DictWriter(f,fieldnames=new_fields,extrasaction="ignore")
            writer.writeheader()
            writer.writerows(old_rows)
            f.flush()
        tmp.replace(csv_path)
        old_fields=new_fields
    with csv_path.open("a",newline="",encoding="utf-8") as f:
        writer=csv.DictWriter(f,fieldnames=old_fields,extrasaction="ignore")
        writer.writerow(row)
        f.flush()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--config",required=True)
    parser.add_argument("--cp",default="build/classes")
    parser.add_argument("--out")
    parser.add_argument("--dry-run",action="store_true")
    args=parser.parse_args()
    cfg=load_config(args.config)
    runs=expand(cfg)
    if args.dry_run:
        print(f"{len(runs)} runs")
        for i,run in enumerate(runs,1):
            shown={key:value for key,value in run.items() if key!="timeout_s"}
            print(f"[{i}/{len(runs)}] {json.dumps(shown,sort_keys=True)}")
        return
    out_path=Path(args.out) if args.out else Path("results")/f"{cfg['name']}.csv"
    workdir=out_path.parent/".work"/cfg["name"]
    make_input(workdir/"input.bin",cfg["file_bytes"])
    done=done_keys(out_path)
    sweep_keys=list(cfg["sweep"].keys())
    for i,run in enumerate(runs,1):
        key=run_key(base_row(run))
        sweep_text=" ".join(f"{name}={run[name]}" for name in sweep_keys)
        prefix=f"[{i}/{len(runs)}] {run['protocol']} W={run['window']}"
        if sweep_text:
            prefix+=f" {sweep_text}"
        prefix+=f" seed={run['seed']}"
        if key in done:
            print(f"{prefix} -> skipped")
            continue
        row=run_one(run,args.cp,workdir)
        append_row(out_path,row)
        done.add(key)
        detail=row.get("_detail")
        suffix=f" ({detail})" if detail else ""
        print(f"{prefix} -> {row['status']} {row['wall_s']:.1f} s{suffix}",flush=True)

if __name__=="__main__":
    main()