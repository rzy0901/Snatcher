# Example 2 — Find My MAC-address rotation interval (paper Table 2)

Measures how often each Apple device type rotates the MAC address it
advertises in the Find My network. The rotation interval bounds how long a
single address stays linkable, and the wide spread across devices is exactly
what the Level-3 clustering attack (Example 5) has to defeat: a slow rotator
(AirTag/AirPods) can be followed under one address for ~a day, while a fast
rotator (Apple Watch/iPhone) changes every ~15–25 min.

## Setup

Each device is left broadcasting in the lost state while a passive BLE scanner
logs every Find My advertisement (address, timestamp, RSSI) over a long
capture. For one address, the span between its first and last sighting
approximates one rotation interval.

## Input

Per-device, per-address durations in `data/rotation_<device>.csv`:

| Column | Meaning |
| :--- | :--- |
| `Address` | the rotated Find My MAC address |
| `Start`, `End` | first / last minute the address was observed |
| `Duration` | `End − Start` (one rotation interval) |

`rotation_airpods.csv`, `rotation_airtag.csv`, `rotation_watch.csv` are taken
directly from the long captures; `rotation_iphone.csv` is regenerated from the
shipped raw sample (below).

## Run

```bash
python make_table.py        # -> per-device summary table + mac_rotation.pdf
python derive_durations.py  # re-derive data/rotation_iphone.csv from raw/
```

## Output

- `mac_rotation_table.md` — the per-device summary table.
- `mac_rotation.pdf` — boxplot of the per-address rotation intervals per
  device, on a log axis.

## Expected result

The derived table reproduces the paper's Table 2:

| Category | Device | MAC Address Rotation Period |
| --- | --- | --- |
| Apple Accessories | AirPods Pro | ~24 h (updated daily ~4 am) |
| Apple Accessories | AirTag | ~24 h (updated daily ~4 am) |
| Apple Devices | iPhone | fixed ~15 min |
| Apple Devices | Apple Watch | 19-36 min |

The accessory addresses all change near a fixed daily boundary (~04:00), so the
period prints as ~24 h; the iPhone rotates on a tight 15-min clock; the Apple
Watch spreads over ~19–36 min. `mac_rotation.pdf` shows the underlying
per-address spread (log axis), which is what these periods are read off.

## Raw traces

The full scan logs for AirTag/AirPods/Apple Watch (tens of MB each, hundreds of
thousands of advertisements) are kept locally in the git-ignored
`../table1_raw/`. The small iPhone capture ships here as `raw/iphone_ble.csv`
(`Time (us)`, `Address`, `RSSI (dBm)`, `Raw data`, …) so that
`derive_durations.py` runs out of the box and reproduces `data/rotation_iphone.csv`:
each consecutive 15-min address window is one MAC rotation.
