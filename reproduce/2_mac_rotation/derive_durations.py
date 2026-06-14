#!/usr/bin/env python3
"""Derive per-MAC-address active durations from a raw Find My BLE capture.

A Find My device periodically rotates the MAC address it advertises. In a raw
scan log, the time span over which a single address is observed therefore
approximates one rotation interval. Grouping the log by address and taking
(last - first) sighting per address yields the per-address durations in
``data/rotation_<device>.csv``.

The large AirTag/AirPods/Apple-Watch captures live in the git-ignored
``../table1_raw/``; only the small iPhone capture ships here under ``raw/``, so
this script regenerates ``data/rotation_iphone.csv`` out of the box and
documents how the other three were produced.

Usage:
    python derive_durations.py [raw_log.csv] [out.csv]
    # default: raw/iphone_ble.csv -> data/rotation_iphone.csv
"""
import sys
import pandas as pd


def derive(src, dst):
    df = pd.read_csv(src)
    raw = next(c for c in df.columns if "aw" in c.lower() and "ata" in c.lower())
    # keep only Apple Find My advertisements (manufacturer 0x004C, type 0x12)
    df = df[df[raw].astype(str).str.contains("4C0012", case=False, na=False)].copy()

    if "PC_Beijing_Time" in df.columns:        # absolute wall-clock capture
        t = pd.to_datetime(df["PC_Beijing_Time"])
    else:                                      # microseconds-since-boot capture
        tcol = next(c for c in df.columns if "Time" in c)
        us = pd.to_numeric(df[tcol], errors="coerce")
        t = pd.to_timedelta(us - us.min(), unit="us")

    g = (df.assign(_t=t.dt.floor("min"))
           .groupby("Address")["_t"].agg(Start="min", End="max")
           .reset_index().sort_values("Start").reset_index(drop=True))
    g["Duration"] = g["End"] - g["Start"]
    g.to_csv(dst, index=False)
    print(g.to_string(index=False))
    print(f"\n[ok] {len(g)} addresses -> {dst}")


if __name__ == "__main__":
    args = sys.argv[1:] or ["raw/iphone_ble.csv", "data/rotation_iphone.csv"]
    derive(*args)
