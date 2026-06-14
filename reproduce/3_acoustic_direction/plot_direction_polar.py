#!/usr/bin/env python3
"""Direction-finding polar plot from one "Scan & Spin" measurement (Level 1).

The signal strength in dB is measured at the four body orientations -- Front,
Right, Back, Left -- (each via acoustic_example.py on that orientation's
recording). Level 1 then picks the heading to the source. This script takes
those four dB values, plots them on a polar axis, computes the contrast-enhanced
score

    S_k = alpha * A_k + beta * (A_k - A_opp(k)),    alpha = 0.3, beta = 0.7,

and marks the estimated direction D_init = argmax_k S_k (body shadowing makes the
Front, i.e. the heading toward the source, the strongest).

Input : data/direction_example.csv   columns: orientation in {F,R,B,L}, db_top (dB)
Output: direction_polar.pdf  (+ printed estimated direction)

Usage: python plot_direction_polar.py
"""
import csv
import os

import numpy as np
import matplotlib.pyplot as plt

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))

DB_OFFSET = 100.0                                # dBFS -> positive display scale
ORDER = ["F", "R", "B", "L"]
ANGLE = {"F": 0, "R": 90, "B": 180, "L": 270}    # degrees, clockwise from Front
NAME = {"F": "Front", "R": "Right", "B": "Back", "L": "Left"}
OPP = {"F": "B", "B": "F", "R": "L", "L": "R"}
ALPHA, BETA = 0.3, 0.7
CSV_PATH = "data/direction_example.csv"


def load():
    a = {}
    with open(CSV_PATH) as f:
        for row in csv.DictReader(f):
            a[row["orientation"].strip()] = float(row["db_top"]) + DB_OFFSET
    return a


if __name__ == "__main__":
    A = load()
    S = {k: ALPHA * A[k] + BETA * (A[k] - A[OPP[k]]) for k in ORDER}
    d_init = max(S, key=S.get)
    print("[info] dB: " + ", ".join(f"{NAME[k]}={A[k]:.1f}" for k in ORDER))
    print(f"[info] estimated direction: {NAME[d_init]} (argmax S_k)")

    theta = np.deg2rad([ANGLE[k] for k in ORDER] + [ANGLE[ORDER[0]]])
    r = [A[k] for k in ORDER] + [A[ORDER[0]]]

    fig, ax = plt.subplots(figsize=(5, 5), subplot_kw=dict(projection="polar"))
    ax.set_theta_zero_location("N")
    ax.set_theta_direction(-1)
    ax.plot(theta, r, "o-", color="C0", lw=4.5, ms=15)
    ax.fill(theta, r, alpha=0.15, color="C0")
    ax.plot(np.deg2rad(ANGLE[d_init]), A[d_init], "*", color="red", ms=22,
            zorder=5, label=f"estimated direction: {NAME[d_init]}")

    ax.set_xticks(np.deg2rad([0, 90, 180, 270]))
    ax.set_xticklabels(["Front", "Right", "Back", "Left"])
    lo = (np.floor(min(A.values()) / 10) - 1) * 10
    hi = (np.floor(max(A.values()) / 10) + 1) * 10
    ax.set_ylim(lo, hi)
    ticks = np.arange(lo, hi + 1, 10)
    ax.set_yticks(ticks)
    ax.set_yticklabels([f"{int(t)} dB" for t in ticks])
    ax.yaxis.set_tick_params(colors="gray")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.08), frameon=False)

    fig.tight_layout()
    fig.savefig("direction_polar.pdf", bbox_inches="tight")
    print("[ok] wrote direction_polar.pdf")
