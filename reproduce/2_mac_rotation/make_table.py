#!/usr/bin/env python3
"""Per-device Find My MAC-address rotation period (paper Table 2).

Reproduces Table 2 -- "Measured MAC address rotation periods for different Apple
devices in Separated (Lost) state". From the per-address active spans in
``data/rotation_<device>.csv`` (one row per observed MAC), it derives, per
device, how long one MAC stays in use. Apple *accessories* (AirTag, AirPods Pro)
rotate roughly once a day; Apple *devices* (iPhone, Apple Watch) rotate every
~15-35 min. The long static window on accessories is Vulnerability III -- it
lets the attacker follow one address all the way to the target.

Usage: python make_table.py
"""
import pandas as pd
import matplotlib.pyplot as plt

# file key, display device, paper category, colour
DEVS = [("airpods", "AirPods Pro", "Apple Accessories", "#1f77b4"),
        ("airtag",  "AirTag",      "Apple Accessories", "#d62728"),
        ("iphone",  "iPhone",      "Apple Devices",     "#9467bd"),
        ("watch",   "Apple Watch", "Apple Devices",     "#2ca02c")]


def load(key):
    d = pd.read_csv(f"data/rotation_{key}.csv")
    return pd.to_timedelta(d["Duration"]).dt.total_seconds() / 60, d["Start"]


def period(mins, starts):
    """Format the rotation period the way Table 2 reports it."""
    if mins.median() >= 6 * 60:                       # daily rotator (accessory)
        hr = (pd.to_datetime(starts, errors="coerce") +
              pd.Timedelta("30min")).dt.hour.mode()   # round update time to the hour
        when = f" (updated daily ~{int(hr.iloc[0])} am)" if len(hr) else ""
        return f"~24 h{when}"
    q1, q3 = mins.quantile(.25), mins.quantile(.75)
    if q3 - q1 <= 3:                                   # tight spread -> fixed
        return f"fixed ~{mins.median():.0f} min"
    hi = mins[mins <= q3 + 1.5 * (q3 - q1)].max()      # upper whisker
    return f"{q1:.0f}-{hi:.0f} min"


def md_table(rows, cols):
    head = "| " + " | ".join(cols) + " |\n"
    sep = "| " + " | ".join("---" for _ in cols) + " |\n"
    return head + sep + "".join("| " + " | ".join(map(str, r)) + " |\n" for r in rows)


series, rows = {}, []
for key, name, cat, _ in DEVS:
    mins, starts = load(key)
    series[name] = mins
    rows.append([cat, name, period(mins, starts)])

table = md_table(rows, ["Category", "Device", "MAC Address Rotation Period"])
print(table)
with open("mac_rotation_table.md", "w") as fh:
    fh.write(table)

# supporting plot: per-address rotation interval per device (log minutes)
fig, ax = plt.subplots(figsize=(7, 4.2))
names = [n for _, n, _, _ in DEVS]
bp = ax.boxplot([series[n] for n in names], widths=0.6, patch_artist=True,
                showfliers=True, flierprops=dict(marker=".", markersize=4, alpha=0.4))
for patch, (_, _, _, colour) in zip(bp["boxes"], DEVS):
    patch.set(facecolor=colour, alpha=0.65, edgecolor=colour)
for med in bp["medians"]:
    med.set(color="black", linewidth=1.6)
ax.set_yscale("log")
ax.set_xticklabels(names)
ax.set_ylabel("MAC-rotation interval (min)")
ax.set_title("Find My MAC-address rotation interval per device")
ax.grid(True, axis="y", which="both", ls="--", alpha=0.4)
fig.tight_layout()
fig.savefig("mac_rotation.pdf", bbox_inches="tight")
print("[ok] wrote mac_rotation.pdf and mac_rotation_table.md")
