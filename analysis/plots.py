#!/usr/bin/env python3
"""Turns results CSV files into figures with means and 95% confidence intervals."""

import argparse
import csv
import math
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

T95={1:12.706,2:4.303,3:3.182,4:2.776,5:2.571,6:2.447,7:2.365,8:2.306,9:2.262,10:2.228}

# csv, x, y, out, x scale, log y, series
# Figure 1 uses a log y axis because Stop-and-Wait sits about 30 times below
# GBN and SR, and on a linear axis its line is flat against zero.
FIGURES=[
    ("results/exp1_loss.csv","loss","goodput_bps","figures/fig1_goodput_vs_loss.png","symlog",True,"protocol"),
    ("results/exp2_window.csv","window","goodput_bps","figures/fig2_goodput_vs_window.png","log2",False,"protocol,loss"),
    ("results/exp3_reorder.csv","reorder","retransmissions","figures/fig3_retx_vs_reorder.png","symlog",False,"protocol"),
    ("results/exp4_rto.csv","rto","retransmissions","figures/fig4_retx_vs_rto.png","linear",False,"protocol"),
]

# column -> (axis label, factor applied to the value before plotting)
LABELS={
    "goodput_bps":("Goodput (Mbit/s)",1e-6),
    "throughput_bps":("Throughput on the wire (Mbit/s)",1e-6),
    "elapsed_ms":("Transfer time (s)",1e-3),
    "retransmissions":("Retransmissions",1),
    "timeouts":("Timeouts",1),
    "loss":("Loss probability",1),
    "reorder":("Reorder probability",1),
    "window":("Window size (packets)",1),
    "rto":("RTO policy",1),
}


def label(column):
    return LABELS.get(column,(column,1))[0]


def factor(column):
    return LABELS.get(column,(column,1))[1]


def load_ok_rows(path):
    with open(path,newline="",encoding="utf-8") as f:
        return [r for r in csv.DictReader(f) if r.get("status")=="ok"]


def series_name(row,by):
    keys=by.split(",")
    return ", ".join([row[keys[0]]]+[f"{key}={row[key]}" for key in keys[1:]])


def group(rows,by,x,y,scale=1.0):
    out=defaultdict(lambda:defaultdict(list))
    for r in rows:
        series=series_name(r,by)
        try:
            xv=float(r[x])
        except ValueError:
            xv=r[x]
        out[series][xv].append(float(r[y])*scale)
    return out


def mean_ci(values):
    n=len(values)
    m=statistics.mean(values)
    if n<2:
        return m,0.0
    t=T95.get(n-1,1.96)
    return m,t*statistics.stdev(values)/math.sqrt(n)


def plot(groups,x,y,out,xscale="linear",logy=False):
    fig,ax=plt.subplots(figsize=(7,4.5))
    categorical=any(not isinstance(v,(int,float)) for points in groups.values() for v in points)

    if categorical:
        categories=[]
        for points in groups.values():
            for value in points:
                if value not in categories:
                    categories.append(value)

        width=0.8/max(len(groups),1)

        for i,series in enumerate(sorted(groups)):
            positions=[]
            means=[]
            errors=[]

            for j,category in enumerate(categories):
                if category not in groups[series]:
                    continue
                m,h=mean_ci(groups[series][category])
                positions.append(j-0.4+width/2+i*width)
                means.append(m)
                errors.append(h)

            ax.bar(positions,means,width=width,yerr=errors,capsize=3,label=series)

        ax.set_xticks(range(len(categories)))
        ax.set_xticklabels(categories)
    else:
        for series in sorted(groups):
            xs=sorted(groups[series])
            stats=[mean_ci(groups[series][v]) for v in xs]
            ax.errorbar(xs,[m for m,_ in stats],yerr=[h for _,h in stats],marker="o",capsize=3,label=series)

        if xscale=="symlog":
            ax.set_xscale("symlog",linthresh=0.001)      # linear near 0, log beyond
        elif xscale=="log2":
            ax.set_xscale("log",base=2)
            ticks=sorted({v for points in groups.values() for v in points})
            ax.set_xticks(ticks)
            ax.set_xticklabels([f"{t:g}" for t in ticks])

    if logy:
        ax.set_yscale("log",nonpositive="clip")

    ax.set_xlabel(label(x))
    ax.set_ylabel(label(y))
    ax.set_title(f"{label(y)} vs {label(x).lower()}\n"
                 "mean over seeds, bars are 95% confidence intervals",fontsize=10)
    ax.grid(True,alpha=0.3)
    ax.legend()
    Path(out).parent.mkdir(parents=True,exist_ok=True)
    fig.tight_layout()
    fig.savefig(out,dpi=150)
    plt.close(fig)
    print("wrote",out)


def make_figure(csv_path,x,y,out,xscale,logy,by):
    if not Path(csv_path).exists():
        print("skip",csv_path)
        return

    rows=load_ok_rows(csv_path)
    if not rows:
        print("skip",csv_path)
        return

    groups=group(rows,by,x,y,factor(y))
    plot(groups,x,y,out,xscale,logy)


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--csv")
    ap.add_argument("--x")
    ap.add_argument("--y")
    ap.add_argument("--by",default="protocol")
    ap.add_argument("--out")
    ap.add_argument("--xscale",choices=["linear","symlog","log2"],default="linear")
    ap.add_argument("--logx",action="store_true",help="same as --xscale symlog")
    ap.add_argument("--logy",action="store_true")
    args=ap.parse_args()

    if args.csv:
        if not args.x or not args.y or not args.out:
            ap.error("--x, --y and --out are required with --csv")
        xscale="symlog" if args.logx else args.xscale
        make_figure(args.csv,args.x,args.y,args.out,xscale,args.logy,args.by)
        return

    for csv_path,x,y,out,xscale,logy,by in FIGURES:
        make_figure(csv_path,x,y,out,xscale,logy,by)


if __name__=="__main__":
    main()