# Example 6 — Maximum attack distance (paper Figures 14 & 15)

Characterises how far the attack reaches by sweeping the attacker-to-device
distance from 0 to 80 m, for both the BLE discovery channel and the non-owner
sound trigger.

## Setup (controlled field measurement)

A mimicked AirTag (the provided ESP32 firmware) is placed at fixed distances
(0, 10, …, 80 m) in an open outdoor area. At each distance the phone running
`SnatchAPP` records the device's RSSI and the advertising interval it can scan,
and — for the sound channel — connects to trigger the non-owner sound while
recording the resulting sound level and the connect-and-trigger time. The
per-distance summaries are provided here; the full per-distance raw logs and
audio recordings live in `../6_max_distance_raw/`.

## Input

Two summary CSVs in `data/`:

`rssi_distance.csv` — BLE discovery range:

| Column | Meaning |
| :--- | :--- |
| `distance_m` | attacker-to-device distance (m) |
| `rssi_dB` | received signal strength at that distance (dB) |
| `scanned_adv_interval_s` | advertising interval the scanner observes (s) |

`sound_distance.csv` — sound-trigger range (several trials per distance):

| Column | Meaning |
| :--- | :--- |
| `distance_m` | attacker-to-device distance (m) |
| `sound_level_dB` | recorded sound level of the triggered alert (dB) |
| `connection_duration_s` | time to connect and trigger the sound (s) |

## Output

- `rssi_adv_interval_vs_distance.pdf` (paper Fig. 14): RSSI (left axis) and the
  scanned advertising interval (right axis) vs distance.
- `sound_connection_vs_distance.pdf` (paper Fig. 15): mean sound level (left
  axis) and mean connection duration (right axis) vs distance.

## Run

```bash
python plot_max_distance.py
```

## Expected result

Two dual-axis plots. (a) RSSI decays smoothly with distance while the scanned
advertising interval stays short and then rises sharply beyond ~60 m — the
device remains discoverable out to tens of metres. (b) The triggered sound is
loud near the device (~92 dB at 0 m) and falls toward ambient by ~30 m, while
the connect-and-trigger time stays short at close range and grows at the far
end. A freshly collected sweep differs in absolute levels with the environment;
the example illustrates the distance trend, not bit-exact values.

## Raw traces

The two summary CSVs in `data/` are derived from the per-distance field
measurements in `raw/` — the RSSI logs in full, plus a representative audio
recording per distance (the complete ~260 MB recording set lives in
`../6_max_distance_raw/`):

- **BLE range** — `raw/rssi_dis/<dist>m_NavRSSI_*.csv`: one BLE scan log per
  distance (RSSI and timestamp over a short capture; same NavRSSI columns as
  Example 4). `raw/calc_rssi_interval.py` averages the RSSI and the inter-scan
  interval per distance and reproduces `data/rssi_distance.csv` exactly.
- **Sound range** — `raw/acoustic_dis/<dist>m/`: one stereo recording per
  distance (`audio_left/right_*.pcm`, 16-bit 44.1 kHz) with a `*_metadata.txt`
  giving the device MAC, recording angle, RSSI, and the connect-and-trigger
  duration. The non-owner sound was triggered while recording;
  `raw/calc_sound_distance.py` (with `test_new_power.py` and the `model2.wav`
  chirp template) measures the sound level on the top-mic (right) channel and
  reads the connection duration, producing `sound_distance.csv`.

```bash
cd raw
python calc_rssi_interval.py    # reproduces data/rssi_distance.csv
python calc_sound_distance.py   # sound level + connection time per recording
```

Only a sample of audio is shipped here to keep the repository small, so
`calc_sound_distance.py` reports just the sampled recordings; the per-distance
averages in `data/sound_distance.csv` were computed over the full set.

