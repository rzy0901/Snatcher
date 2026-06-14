#!/usr/bin/env python3
"""Re-derive the BLE-range summary (rssi_distance.csv) from the raw per-distance
NavRSSI logs in rssi_dis/.

For each <dist>m_NavRSSI_*.csv the script takes the mean RSSI and the mean
inter-scan interval (the time between consecutive Find My advertisements the
phone actually scans) -- one row per distance, matching data/rssi_distance.csv.

Usage:  cd raw && python calc_rssi_interval.py
"""
import os
import re
import glob
import pandas as pd

rows = []
for f in sorted(glob.glob("rssi_dis/*_NavRSSI_*.csv")):
    dist = int(re.match(r"(\d+)m_", os.path.basename(f)).group(1))
    df = pd.read_csv(f)
    t = pd.to_datetime(df["Timestamp"])
    rows.append({
        "distance_m": dist,
        "rssi_dB": round(df["RSSI"].mean(), 2),
        "scanned_adv_interval_s": round(t.diff().dt.total_seconds().mean(), 4),
    })

out = (pd.DataFrame(rows)
         .groupby("distance_m", as_index=False).mean()   # in case of repeats
         .sort_values("distance_m").round(4))
print(out.to_string(index=False))
out.to_csv("rssi_distance_recomputed.csv", index=False)
print("\n[ok] wrote rssi_distance_recomputed.csv "
      "(compare with ../data/rssi_distance.csv)")
