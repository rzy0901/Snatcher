# Reproduction package

Example traces and analysis code that reproduce *Snatcher*'s three attack
levels and the field measurements characterising its attack surface. See
`../doc/Snatcher_AE.pdf` for the full artifact appendix.

Each example runs offline from a representative trace exported by `SnatchAPP`,
explains its input/output format, and requires no Apple hardware.

## Setup

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

## Examples

| Folder | Paper fig./table | Demonstrates |
| :--- | :--- | :--- |
| [`1_timeout_disconnect_distance/`](./1_timeout_disconnect_distance/) | Fig. 3 | lost-state timeout and owner-disconnect distance per device |
| [`2_mac_rotation/`](./2_mac_rotation/)             | Table 2 | per-device Find My MAC-address rotation interval |
| [`3_acoustic_direction/`](./3_acoustic_direction/) | Fig. 6 (Level 1) | acoustic chirp-arrival detection (cross-correlation with a template) and dB measurement |
| [`4_rssi_navigation/`](./4_rssi_navigation/)       | Fig. 8 (Level 2) | RSSI–IMU navigation-trace reconstruction |
| [`5_clustering/`](./5_clustering/)                 | Fig. 11 (Level 3) | spatial–temporal clustering under MAC-address rotation |
| [`6_max_distance/`](./6_max_distance/)             | Fig. 14–15 | maximum attack distance: BLE discovery range + non-owner sound trigger |
| [`7_navigation_traces/`](./7_navigation_traces/) | Fig. 17 | end-to-end evaluation sample traces: walking trajectory per attack level |

Each folder's `README.md` describes the input trace format, how to run it, and
the expected output. The full unprocessed capture sets are kept locally in the
git-ignored `*_raw/` folders.
