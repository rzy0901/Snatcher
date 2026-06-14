#!/usr/bin/env python3
"""Sample navigation traces from the end-to-end evaluation (paper Figure 17).

A few example walking trajectories drawn from the end-to-end navigation
evaluation -- the academic-building runs that Figure 17 averages into mean
navigation time and distance. Each shows the attacker's reconstructed position
(PDR, in metres) as it homes in on a lost device under one attack level.

  (a) Level 1 -- acoustic direction finding; path coloured by walking progress.
  (b) Level 2 -- RSSI-IMU navigation; points coloured by the target's RSSI.
  (c) Level 3 -- RSSI-IMU navigation under spatial-temporal clustering: two lost
      devices rotate their MAC every 30 s, so marker shape = distinct MAC and
      colour = RSSI.

Each panel is one run from data/level{1,2,3}_*.csv. Usage: python plot_traces.py
"""
import itertools
import os

import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.ticker import MaxNLocator

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))
# This 3-panel figure is shown single-column (scaled down hard on the page), so
# override the shared style: fonts sized for the small on-page width, and lighter
# spines/ticks than the shared 2.2pt (which looks heavy at this scale).
plt.rcParams.update({"font.size": 13, "axes.titlesize": 13, "axes.labelsize": 13,
                     "xtick.labelsize": 11, "ytick.labelsize": 11, "legend.fontsize": 12,
                     "axes.linewidth": 1.0, "xtick.major.width": 1.0,
                     "ytick.major.width": 1.0, "xtick.major.size": 3.0,
                     "ytick.major.size": 3.0, "grid.linewidth": 0.7})

MARKERS = ["o", "s", "^", "D", "v", "P", "*", "X", "<", ">", "h", "p"]

# file, x col, y col, colour col, colour label, cmap, marker-by-MAC col, title
PANELS = [
    ("level1_acoustic", "positionx", "positiony", "step_count", "Step", "viridis",
     None, "(a) Acoustic"),
    ("level2_rssi", "Position_X", "Position_Y", "RSSI", "RSSI (dBm)", "jet",
     "MAC_Address", "(b) RSSI-IMU"),
    ("level3_clustering", "Position_X", "Position_Y", "RSSI", "RSSI (dBm)", "jet",
     "MAC_Address", "(c) Clustering"),
]

# Equal subplot cells that each panel fills, so the three share one height and
# pack tightly with no whitespace. The paths are illustrative sample traces, so
# they are scaled to fill the cell rather than drawn at a fixed 1:1 aspect.
fig, axes = plt.subplots(1, 3, figsize=(6.6, 2.6), layout="constrained")
for ax, (f, xc, yc, cc, clab, cmap, mac, title) in zip(axes, PANELS):
    d = pd.read_csv(f"data/{f}.csv")
    d = d[(d[xc] != 0) | (d[yc] != 0)]            # drop pre-walk / clustering-init samples at origin
    ax.plot(d[xc], d[yc], "-", color="0.8", lw=1.8, zorder=1)
    vmin, vmax = d[cc].min(), d[cc].max()
    if mac:                                        # shape encodes the (rotating) MAC address
        for mk, (_, g) in zip(itertools.cycle(MARKERS), d.groupby(mac)):
            sc = ax.scatter(g[xc], g[yc], c=g[cc], cmap=cmap, marker=mk, s=48,
                            vmin=vmin, vmax=vmax, edgecolor="k", linewidth=.5, zorder=2)
    else:
        sc = ax.scatter(d[xc], d[yc], c=d[cc], cmap=cmap, s=48,
                        edgecolor="k", linewidth=.5, zorder=2)
    ax.scatter(d[xc].iloc[0], d[yc].iloc[0], marker="o", s=120,
               facecolor="lime", edgecolor="k", zorder=3)        # Start
    ax.scatter(d[xc].iloc[-1], d[yc].iloc[-1], marker="s", s=120,
               facecolor="red", edgecolor="k", zorder=3)         # End
    ax.set_title(title)
    ax.set_xlabel("X (m)")
    ax.set_ylabel("Y (m)")
    ax.xaxis.set_major_locator(MaxNLocator(3, integer=True))   # few integer ticks -> less clutter
    ax.yaxis.set_major_locator(MaxNLocator(5, integer=True))
    ax.margins(0.04)                                  # small padding so Start/End markers aren't clipped
    ax.grid(ls="--", alpha=.3)
    fig.colorbar(sc, ax=ax, fraction=.10, aspect=16, pad=.16).set_label(clab)
fig.get_layout_engine().set(w_pad=0.015, h_pad=0.02, wspace=0.03)
fig.savefig("navigation_traces.pdf", bbox_inches="tight")
print("[ok] wrote navigation_traces.pdf")
