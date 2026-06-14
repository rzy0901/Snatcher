# Example 1 — Lost-state onset: timeout & disconnect distance (paper Figure 3)

Characterises *when* and *from how far* an Apple Find My device starts
broadcasting in the Separated (Lost) state — the precondition that makes a
device discoverable to the Snatcher attacker in the first place.

## Setup (controlled field measurement)

Three Apple device types (AirTag, AirPods, Apple Watch) are separated from
their owner:

- **Timeout** — the owner disables the phone's Bluetooth next to the device,
  and we record the delay until the device begins lost-state broadcasting
  (10 trials per device).
- **Disconnect distance** — the owner walks away in a straight line until the
  BLE link to their phone drops, in four everyday indoor scenes (Office,
  Subway, Mall, Cafeteria; 5 trials per scene × device).

## Input

Two summary CSVs in `data/`:

`timeout.csv` — one row per timeout trial:

| Column | Meaning |
| :--- | :--- |
| `Device` | AirTag / AirPods / AppleWatch (`iPhone_PowerOff` rows are empty — see note) |
| `Trial_Num` | trial index (1–10) |
| `Start_Time`, `End_Time` | owner-leaves / lost-state-begins timestamps |
| `Timeout_sec` | `End_Time − Start_Time`, seconds |

`disconnect_distance.csv` — one row per disconnect trial:

| Column | Meaning |
| :--- | :--- |
| `Scenario` | Office / Subway / Mall / Cafeteria |
| `Device` | AirTag / AirPods / AppleWatch |
| `Trial_Num` | trial index (1–5) |
| `Disconnect_Distance_m` | distance at which the owner link dropped (m) |
| `Disconnect_Steps` | the same distance counted in walking steps |

## Run

```bash
python plot.py
```

## Output

`fig3_timeout_disconnect.pdf` — a two-panel figure:

- **(a)** lost-state timeout per device, on a log axis;
- **(b)** disconnect distance per scene, grouped by device.

## Expected result

(a) AirTag enters the lost state after ~12 min and AirPods after a similarly
long but more variable delay (sensitive to the charging-case state), whereas an
Apple Watch transitions almost immediately (~13 s). (b) Across all four scenes
the owner link consistently breaks at ~10–20 m, so the device starts
advertising its lost state well within the attacker's reach. A freshly
collected sweep differs in absolute values with the environment; the example
illustrates the trend, not bit-exact numbers.

> **Note** — `iPhone_PowerOff` rows are present but empty: a powered-off iPhone
> does not expose a measurable owner-disconnect timeout in this protocol, so it
> is omitted from panel (a).

## Raw traces

`data/*.csv` are the per-trial measurements used by the plot. The underlying
ESP32 BLE scan logs that pin the lost-state onset are in [`raw/`](raw/): a
representative trial per device — `ble_all_*.csv` (every advertisement the
scanner saw) and `ble_lost_*.csv` (the device's own advertisements through the
nearby → Separated/Lost transition). See [`raw/README.md`](raw/README.md) for
how a capture maps to `Timeout_sec`. The full 10-trials-per-device set
(~190 MB) is kept locally in the git-ignored
`../timeout_disconnect_distance_raw/{airpods,airtag,watch}/<trial>/`.
