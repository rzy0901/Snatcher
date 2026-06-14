#!/usr/bin/env python3
"""Spatial-temporal clustering of MAC-rotating Find My devices (Level 3).

Controlled demonstration. Two or three co-located mimicked AirTags -- the
provided ESP32 firmware (esp32_airtag_mac_rotation), identified in the log by
their base MAC -- advertise Find My packets at different distances. For a
controlled, reproducible setup with exact ground truth, MAC-address rotation is
applied IN SOFTWARE to their logged readings: every --period seconds each device
is given a fresh random MAC, and the devices rotate INDEPENDENTLY (each on its
own phase, as devices powered on at different times do). The clustering then
re-associates the rotating MACs into per-device clusters using only timing and
RSSI -- it never sees the ground-truth device identity.

Method -- the paper's two-stage spatial-temporal clustering:

  (1) STATIC PROFILING (first ``INIT_SECONDS``, attacker stationary). A physical
      device cannot advertise under two MAC addresses at once, so MACs whose
      lifetimes OVERLAP in time must belong to DIFFERENT devices. Colouring this
      temporal-conflict graph (the paper's 2-colouring, generalised here to any
      number of co-located devices) seeds one cluster per device, and the gaps
      between a cluster's successive MACs give that device's rotation period.

  (2) DYNAMIC STITCHING (the rest, attacker moving). Each newly-appearing MAC is
      stitched to a cluster: temporal non-overlap is DECISIVE (a MAC can only
      continue a cluster whose previous MAC has already ended); when several
      clusters are free the tie is broken by RSSI continuity (90%) plus a match
      to the cluster's rotation period (10%).

Input : a BLE scan log (Timestamp, MAC_Address, RSSI, ...; see README)
Output: clustering_trace[_<tag>].pdf, named after the input log (+ printed
        per-cluster summary and accuracy vs the software-rotation ground truth)

Usage: python cluster.py [--log data/clustering_log.csv] [--period 30]
"""
import argparse
import os

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))

TOL = 1.0                       # temporal-overlap tolerance (s)
INIT_SECONDS = 60.0             # static-profiling window (capped at half the log)
W_RSSI, W_PERIOD = 0.9, 0.1     # dynamic-stitching tie-break weights
A_RSSI, A_PERIOD = 0.2, 0.3     # EMA learning rates for a cluster's RSSI / period
RSSI_SCALE = 30.0               # dB span used to normalise the RSSI-continuity term
SEED = 0
# base MAC -> label of the controlled mimicked devices. A log with only the first
# two is clustered as two devices; the 3-device log adds the third.
DEVICES = {"E7:4F:E3:A1:19:CF": "Device 1",
           "C1:42:43:44:45:46": "Device 2",
           "C0:10:83:10:51:87": "Device 3"}


def load(path):
    df = pd.read_csv(path)[["Timestamp", "MAC_Address", "RSSI"]]
    df = df[df.MAC_Address.isin(DEVICES)].copy()
    df["t"] = (pd.to_datetime(df.Timestamp)
               - pd.to_datetime(df.Timestamp).min()).dt.total_seconds()
    df["device"] = df.MAC_Address.map(DEVICES)          # ground truth (by construction)
    return df.sort_values("t").reset_index(drop=True)


def apply_software_rotation(df, period, seed=SEED):
    """Give each device a fresh random MAC every `period` seconds.

    Devices rotate independently: each gets its own phase offset, so they do not
    all switch MAC at the same instant. This mirrors real devices (powered on at
    different times) and is what the temporal-overlap step exploits -- when one
    device rotates while the others keep advertising, its new MAC overlaps theirs
    in time and therefore cannot belong to the same device.
    """
    rng = np.random.default_rng(seed)
    devices = sorted(df.device.unique())
    phase = {d: i * period / len(devices) for i, d in enumerate(devices)}
    pool, smac = {}, []
    for _, r in df.iterrows():
        key = (r.device, int((r.t + phase[r.device]) // period))
        if key not in pool:
            pool[key] = "%02X:%02X:%02X:%02X:%02X:%02X" % tuple(rng.integers(0, 256, 6))
        smac.append(pool[key])
    df = df.copy()
    df["shuffled_mac"] = smac
    return df


def _spans(df):
    """Per shuffled-MAC lifetime + RSSI summary."""
    return {m: dict(start=s.t.min(), end=s.t.max(),
                    rssi=float(s.RSSI.mean()), first=float(s.RSSI.iloc[0]))
            for m, s in df.groupby("shuffled_mac")}


def _overlap(a, b):
    return a["start"] <= b["end"] + TOL and b["start"] <= a["end"] + TOL


def static_profiling(spans, macs):
    """Phase I: colour the temporal-conflict graph (overlapping MACs -> different
    clusters) and read off each cluster's rotation period."""
    clusters = []
    for m in sorted(macs, key=lambda m: spans[m]["start"]):
        v = spans[m]
        free = [c for c in clusters
                if not any(_overlap(v, spans[mm]) for mm in c["macs"])]
        c = min(free, key=lambda c: abs(c["rssi"] - v["rssi"])) if free else None
        if c is None:                                   # new device
            c = dict(macs=[], rssi=v["rssi"])
            clusters.append(c)
        c["macs"].append(m)
        c["rssi"] = float(np.mean([spans[mm]["rssi"] for mm in c["macs"]]))
    assign = {m: i for i, c in enumerate(clusters) for m in c["macs"]}
    for c in clusters:                                  # per-device rotation period
        starts = sorted(spans[m]["start"] for m in c["macs"])
        gaps = [b - a for a, b in zip(starts, starts[1:])]
        c["period"] = float(np.median(gaps)) if gaps else INIT_SECONDS
        c["end"] = max(spans[m]["end"] for m in c["macs"])
        c["last_start"] = starts[-1]
    return clusters, assign


def dynamic_stitching(clusters, assign, spans, macs):
    """Phase II: stitch each later MAC -- temporal non-overlap is decisive, ties
    broken by RSSI continuity (90%) and rotation-period match (10%)."""
    def score(c, v):
        rssi = abs(c["rssi"] - v["first"]) / RSSI_SCALE
        gap = v["start"] - c["last_start"]
        period_dev = abs(gap / c["period"] - round(gap / c["period"]))
        return W_RSSI * rssi + W_PERIOD * period_dev

    for m in sorted(macs, key=lambda m: spans[m]["start"]):
        v = spans[m]
        free = [c for c in clusters if v["start"] >= c["end"] - TOL]
        if len(free) == 1:                              # temporal constraint decides
            c = free[0]
        elif not free:                                  # rare: fall back to RSSI
            c = min(clusters, key=lambda c: abs(c["rssi"] - v["first"]))
        else:                                           # tie-break
            c = min(free, key=lambda c: score(c, v))
        c["rssi"] = (1 - A_RSSI) * c["rssi"] + A_RSSI * v["rssi"]
        c["period"] = (1 - A_PERIOD) * c["period"] + A_PERIOD * (v["start"] - c["last_start"])
        c["end"], c["last_start"] = v["end"], v["start"]
        assign[m] = clusters.index(c)
    return assign


def cluster(df):
    """Two-stage clustering: static profiling -> dynamic stitching."""
    spans = _spans(df)
    t0 = min(v["start"] for v in spans.values())
    span = max(v["end"] for v in spans.values()) - t0
    cut = t0 + min(INIT_SECONDS, 0.5 * span)            # keep some data for phase II
    init = [m for m, v in spans.items() if v["start"] < cut]
    stream = [m for m, v in spans.items() if v["start"] >= cut]
    clusters, assign = static_profiling(spans, init)
    assign = dynamic_stitching(clusters, assign, spans, stream)
    return assign, clusters


def accuracy(df):
    """Fraction of readings whose cluster matches the ground-truth device."""
    truth = df.groupby("cluster")["device"].agg(lambda s: s.mode().iloc[0])
    return (df["cluster"].map(truth) == df["device"]).mean()


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--log", default="data/clustering_log.csv")
    p.add_argument("--period", type=float, default=30.0, help="rotation period (s)")
    p.add_argument("--out", default=None,
                   help="output PDF (default: derived from the --log name)")
    args = p.parse_args()
    if args.out is None:                                # name the figure after its log
        args.out = (os.path.basename(args.log)
                    .replace("clustering_log", "clustering_trace")
                    .replace(".csv", ".pdf"))

    df = apply_software_rotation(load(args.log), args.period)
    assign, clusters = cluster(df)
    df["cluster"] = df.shuffled_mac.map(assign)
    periods = ", ".join(f"{c['period']:.0f}" for c in clusters)
    print(f"[info] static profiling seeded {len(clusters)} clusters "
          f"(periods ~ {periods} s); dynamic stitching assigned "
          f"{df.shuffled_mac.nunique()} rotated MACs.")
    print(f"[info] ground truth: {df.device.nunique()} devices; "
          f"accuracy = {accuracy(df) * 100:.1f}%")

    fig, (a_raw, a_dis) = plt.subplots(1, 2, figsize=(10, 4), sharey=True)
    for _, s in df.groupby("shuffled_mac"):
        a_raw.scatter(s.t, s.RSSI, s=35)
    a_raw.set_title("Raw stream")
    a_raw.set_xlabel("Time (s)")
    a_raw.set_ylabel("RSSI (dBm)")
    for c, s in df.groupby("cluster"):
        s = s.sort_values("t")
        a_dis.plot(s.t, s.RSSI, marker=".", ms=15, label=f"Cluster {c + 1}")
    a_dis.set_title("Disentangled per device")
    a_dis.set_xlabel("Time (s)")
    h, l = a_dis.get_legend_handles_labels()
    fig.legend(h, l, loc="upper center", ncol=3, frameon=False, bbox_to_anchor=(0.5, 1.02))

    fig.tight_layout(rect=[0, 0, 1, 0.90])
    fig.savefig(args.out, bbox_inches="tight")
    print(f"[ok] wrote {args.out}")
