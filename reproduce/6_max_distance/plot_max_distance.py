#!/usr/bin/env python3
"""Max-distance characterisation of the attack (paper Figures 14 & 15).

Two field sweeps over the attacker-to-device distance (0--80 m):

  (a) BLE discovery range -- the lost device's RSSI and the advertising
      interval the attacker actually scans, vs distance
      (data/rssi_distance.csv). RSSI decays and the scanned interval grows
      with distance, but the device stays discoverable to tens of metres.

  (b) Non-owner sound-trigger range -- the recorded sound level and the
      time to connect-and-trigger, vs distance (data/sound_distance.csv,
      several trials per distance, averaged here). The triggered sound is
      loud near the device and falls toward ambient by ~30 m.

Each is drawn as a dual-axis plot, reproducing the two figures of the paper.

Usage: python plot_max_distance.py
"""
import os

import pandas as pd
import matplotlib.pyplot as plt

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))

C_RED, C_BLK = "#e41a1c", "black"   # left axis (red), right axis (black, dashed)


def dual_axis(x, y_left, y_right, l_label, r_label, out):
    # taller box (portrait-ish) so the curves fill it; constrained layout keeps
    # the right-hand twin y-label from being clipped.
    fig, ax_l = plt.subplots(figsize=(7, 5.7), layout="constrained")
    ax_l.plot(x, y_left, color=C_RED, marker="o", ms=15, lw=4.5)
    ax_l.set_xlabel("Distance (m)")
    ax_l.set_ylabel(l_label, color=C_RED)
    ax_l.tick_params(axis="y", labelcolor=C_RED)
    ax_l.set_xticks(x)
    ax_l.grid(True, axis="y", ls="--", alpha=0.5)

    ax_r = ax_l.twinx()
    ax_r.plot(x, y_right, color=C_BLK, marker="s", ms=15, lw=4.5, ls="--")
    ax_r.set_ylabel(r_label, color=C_BLK)
    ax_r.tick_params(axis="y", labelcolor=C_BLK)

    fig.savefig(out, bbox_inches="tight")
    print(f"[ok] wrote {out}")


# (a) BLE discovery range: RSSI + scanned advertising interval vs distance
r = pd.read_csv("data/rssi_distance.csv")
dual_axis(r["distance_m"], r["rssi_dB"], r["scanned_adv_interval_s"],
          "RSSI (dB)", "Scanned interval (s)",
          "rssi_adv_interval_vs_distance.pdf")

# (b) sound-trigger range: mean sound level + connection duration vs distance
s = (pd.read_csv("data/sound_distance.csv")
       .groupby("distance_m", as_index=False).mean())
dual_axis(s["distance_m"], s["sound_level_dB"], s["connection_duration_s"],
          "Sound level (dB)", "Connection duration (s)",
          "sound_connection_vs_distance.pdf")
