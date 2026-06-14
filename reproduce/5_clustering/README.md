# Example 5 — Spatial–temporal clustering (Level 3, paper Figure 11)

Re-associates the rotating MAC addresses of co-located lost devices back into
per-device clusters, defeating MAC-address randomization.

## Setup (controlled, reproducible)

Two or three co-located mimicked AirTags — the provided ESP32 firmware
(`../../esp32_airtag_mac_rotation/`) at different distances — advertise Find My
packets, captured by `SnatchAPP`. To get a repeatable setup with exact ground
truth, `cluster.py` applies the MAC-address rotation **in software** to the
devices' logged readings (a fresh random MAC every `--period` seconds), each
device rotating **independently** on its own phase, as real devices powered on
at different times do; the firmware performs the same rotation on-device. The
clustering then runs **blind** — it sees only timestamps and RSSI, never the
device identity.

## Input

Two BLE scan logs exported by `SnatchAPP` are provided:

- `data/clustering_log.csv` — two controlled devices,
- `data/clustering_log_3dev.csv` — three controlled devices.

Each has the same columns:

| Column | Meaning |
| :--- | :--- |
| `Timestamp` | packet time |
| `MAC_Address` | advertised MAC (here, each device's stable base MAC) |
| `RSSI` | received signal strength (dBm) |
| `Payload_Hex`, `Payload_Length`, `Connectable` | Find My advertisement fields (unused by the clustering) |

The controlled devices are identified by their base MAC at the top of
`cluster.py` (`DEVICES`); a log that contains only the first two is clustered as
two devices, the three-device log adds the third. Pass any log with `--log`.

## Output

`clustering_trace.pdf` (`--out` to rename): the raw stream with every rotated MAC
a different colour (left, fragmented) and the recovered per-device RSSI
trajectories (right). The script also prints the clustering accuracy against the
known software-rotation ground truth.

## Method

- **Temporal:** a physical device cannot advertise under two MAC addresses at
  once, so two MAC addresses whose lifetimes overlap in time belong to different
  devices.
- **Spatial (RSSI):** a newly-appearing MAC address is stitched to the device
  whose recent RSSI is closest.

## Run

```bash
python cluster.py                                      # 2 devices, 30 s rotation
python cluster.py --log data/clustering_log_3dev.csv \
                  --out clustering_trace_3dev.pdf      # 3 devices
python cluster.py --period 10                          # faster rotation
```

## Expected result

On the two-device log the rotated MAC addresses are grouped back into the 2
devices (100% on the provided log): two clean, separated RSSI trajectories. The
three-device log is harder — the devices' RSSI ranges overlap and cross — but
the temporal-overlap constraint still recovers the three per-device trajectories.
A freshly collected log will differ in geometry; the example illustrates the
disentanglement, not a bit-exact trace.
