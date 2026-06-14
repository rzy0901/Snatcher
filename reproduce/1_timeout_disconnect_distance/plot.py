#!/usr/bin/env python3
"""Lost-state onset of Apple Find My devices (paper Figure 3).

Two field measurements that bound when, and from how far, an Apple device
starts broadcasting in the Separated (Lost) state -- the precondition for the
Snatcher attack. Each bar is the mean over the trials, the error bar its
standard deviation:

  (a) Lost-state timeout -- the delay between the owner leaving (the BLE link
      to the owner's phone drops) and the device entering the lost state, per
      device type (data/timeout.csv, 10 trials each).

  (b) Disconnect distance -- the owner-to-device distance at which that BLE
      link breaks, across four everyday indoor scenes (4 scenes x 3 devices,
      5 trials each; data/disconnect_distance.csv).

Usage: python plot.py
"""
import os

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))

COLOR = {"AirTag": "#d62728", "AirPods": "#1f77b4", "AppleWatch": "#2ca02c"}
LABEL = {"AirTag": "AirTag", "AirPods": "AirPods", "AppleWatch": "Apple Watch"}
DEVICES = ["AirTag", "AirPods", "AppleWatch"]
SCENES = ["Office", "Subway", "Mall", "Cafeteria"]

fig, (ax_t, ax_d) = plt.subplots(
    1, 2, figsize=(11, 5.2), gridspec_kw={"width_ratios": [1, 2.4]})

# (a) lost-state timeout per device -- bar = mean, error bar = std (log axis,
#     ~13 s ... ~18 min). On a log axis the lower error bar is clipped to a
#     floor so it stays positive.
to = pd.read_csv("data/timeout.csv").dropna(subset=["Timeout_sec"])
t_mean = np.array([to.loc[to.Device == d, "Timeout_sec"].mean() / 60 for d in DEVICES])
t_std = np.array([to.loc[to.Device == d, "Timeout_sec"].std() / 60 for d in DEVICES])
FLOOR = 0.05                                            # 3 s -- log-axis bottom
t_lo = np.minimum(t_std, t_mean - FLOOR)               # keep error bars > 0
ax_t.bar(range(len(DEVICES)), t_mean, width=0.7, color=[COLOR[d] for d in DEVICES],
         alpha=0.75, edgecolor=[COLOR[d] for d in DEVICES],
         yerr=[t_lo, t_std], ecolor="black",
         error_kw={"elinewidth": 2.5, "capthick": 2.5, "capsize": 7})
ax_t.set_yscale("log")
ax_t.set_ylim(FLOOR, (t_mean + t_std).max() * 1.8)
ax_t.set_xticks(range(len(DEVICES)))
ax_t.set_xticklabels(["AirTag", "AirPods", "Watch"], rotation=35, ha="right")
ax_t.set_ylabel("Timeout (min)")
ax_t.set_title("(a)")
ax_t.grid(True, axis="y", which="both", ls="--", alpha=0.4)

# (b) disconnect distance per scene, grouped by device -- bar = mean, error bar = std
dd = pd.read_csv("data/disconnect_distance.csv")
w, top = 0.24, 0.0
for j, d in enumerate(DEVICES):
    sub = dd[dd.Device == d]
    mean = np.array([sub[sub.Scenario == s]["Disconnect_Distance_m"].mean() for s in SCENES])
    std = np.array([sub[sub.Scenario == s]["Disconnect_Distance_m"].std() for s in SCENES])
    pos = [i + (j - 1) * w for i in range(len(SCENES))]
    ax_d.bar(pos, mean, width=w * 0.9, color=COLOR[d], alpha=0.75, edgecolor=COLOR[d],
             yerr=std, ecolor="black",
             error_kw={"elinewidth": 2, "capthick": 2, "capsize": 4})
    top = max(top, np.nanmax(mean + std))
ax_d.set_xticks(range(len(SCENES)))
ax_d.set_xticklabels(SCENES, rotation=35, ha="right")
ax_d.set_xlim(-0.6, len(SCENES) - 0.4)
ax_d.set_ylim(0, top * 1.12)
ax_d.set_ylabel("Distance (m)")
ax_d.set_title("(b)")
ax_d.grid(True, axis="y", ls="--", alpha=0.4)

# one shared device legend, in a clear band above both panels (out of the plots)
handles = [plt.Rectangle((0, 0), 1, 1, fc=COLOR[d], alpha=0.75) for d in DEVICES]
fig.legend(handles, [LABEL[d] for d in DEVICES], ncol=3, frameon=False,
           loc="upper center", bbox_to_anchor=(0.5, 1.0))

fig.tight_layout(rect=[0, 0, 1, 0.86])
fig.savefig("fig3_timeout_disconnect.pdf", bbox_inches="tight")
print("[ok] wrote fig3_timeout_disconnect.pdf")
