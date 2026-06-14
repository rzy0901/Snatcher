#!/usr/bin/env python3
"""Plot a Level 2 RSSI-IMU navigation trace.

SnatchAPP logs, for the target device, each BLE reading's RSSI together with the
phone's heading and the IMU-derived Pedestrian-Dead-Reckoning (PDR) position and
step count (see README for the column format). This script draws the navigation
trace: the walked trajectory bound to RSSI (the "trajectory-bound radio map"),
plus RSSI over time. As the attacker approaches the device the RSSI rises
(blue -> red) and the path converges on the target.

Input : a NavRSSI log exported by SnatchAPP (data/nav_trace_1.csv by default)
Output: nav_trace.pdf  (+ printed summary)

Usage: python plot_trace.py [--log data/nav_trace_1.csv] [--mac AA:BB:CC:DD:EE:FF]
"""
import argparse
import os

import pandas as pd
import matplotlib.pyplot as plt

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))


def pick_target(df, min_readings=10):
    """The navigated target is the frequently-seen device the attacker gets
    closest to, i.e. the highest peak RSSI among MACs with enough readings."""
    counts = df["MAC_Address"].value_counts()
    cand = counts[counts >= min(min_readings, counts.max())].index
    return df[df["MAC_Address"].isin(cand)].groupby("MAC_Address")["RSSI"].max().idxmax()


def load(path, mac=None):
    df = pd.read_csv(path)
    df["t"] = (pd.to_datetime(df["Timestamp"])
               - pd.to_datetime(df["Timestamp"]).iloc[0]).dt.total_seconds()
    if mac is None:
        mac = pick_target(df)
    return df[df["MAC_Address"] == mac].reset_index(drop=True), mac


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--log", default="data/nav_trace_1.csv")
    p.add_argument("--mac", default=None, help="target MAC (default: most frequent)")
    p.add_argument("--out", default="nav_trace.pdf")
    args = p.parse_args()

    d, mac = load(args.log, args.mac)
    print(f"[info] target {mac}: {len(d)} readings, "
          f"RSSI {d.RSSI.min()}..{d.RSSI.max()} dBm, {int(d.Step_Count.max())} steps")

    fig, (ax_xy, ax_t) = plt.subplots(1, 2, figsize=(10, 4.5))

    # (a) walked trajectory (PDR) coloured by RSSI
    ax_xy.plot(d.Position_X, d.Position_Y, "-", color="0.7", lw=3, zorder=1)
    sc = ax_xy.scatter(d.Position_X, d.Position_Y, c=d.RSSI, cmap="jet", s=100,
                       edgecolors="k", linewidths=0.8, zorder=2)
    ax_xy.scatter(d.Position_X.iloc[0], d.Position_Y.iloc[0], marker="^", s=425,
                  facecolor="lime", edgecolor="k", zorder=3, label="Start")
    ax_xy.scatter(d.Position_X.iloc[-1], d.Position_Y.iloc[-1], marker="s", s=425,
                  facecolor="red", edgecolor="k", zorder=3, label="End")
    fig.colorbar(sc, ax=ax_xy, label="RSSI (dBm)")
    ax_xy.set_xlabel("x (m)")
    ax_xy.set_ylabel("y (m)")
    ax_xy.set_aspect("equal", adjustable="datalim")
    h, l = ax_xy.get_legend_handles_labels()
    fig.legend(h, l, loc="upper center", ncol=2, frameon=False, bbox_to_anchor=(0.5, 1.02))
    ax_xy.set_title("Trajectory (RSSI)")

    # (b) RSSI over time -- the trend the navigation follows
    ax_t.plot(d.t, d.RSSI, "-", color="gray", lw=3, zorder=1)
    ax_t.scatter(d.t, d.RSSI, c=d.RSSI, cmap="jet", s=62, zorder=2)
    ax_t.set_xlabel("Time (s)")
    ax_t.set_ylabel("RSSI (dBm)")
    ax_t.set_title("RSSI over time")

    fig.tight_layout(rect=[0, 0, 1, 0.90])
    fig.savefig(args.out, bbox_inches="tight")
    print(f"[ok] wrote {args.out}")
