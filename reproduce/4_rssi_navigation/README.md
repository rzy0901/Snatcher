# Example 4 — RSSI–IMU navigation (Level 2)

Plots a Level 2 navigation trace: the attacker's walked path (reconstructed from
the IMU by Pedestrian Dead Reckoning, PDR) bound to the target's RSSI. As the
attacker approaches, the RSSI rises and the path converges on the device.

## Input

`SnatchAPP` writes two logs per navigation run.

**Navigation log** — `data/nav_trace_{1,2}.csv` (`NavRSSI_*` from the app): one
row per BLE reading of any nearby Find My device.

| Column | Meaning |
| :--- | :--- |
| `Timestamp` | wall-clock time of the reading |
| `MAC_Address` | advertised MAC of the device (the target plus co-located devices) |
| `RSSI` | received signal strength (dBm) |
| `Phone_Direction` | phone heading / azimuth (degrees), from the fused IMU orientation |
| `Position_X`, `Position_Y` | PDR-reconstructed attacker position (m), updated per step |
| `Step_Count` | cumulative step count (pedometer) |

**IMU log** — `data/imu_example.csv` (`imu_data_*` from the app): the raw sensor
stream that the PDR engine turns into the position / heading / step count above.

| Column(s) | Meaning |
| :--- | :--- |
| `Timestamp` | sample time |
| `Accelerometer_{X,Y,Z}` | raw accelerometer (m/s²) |
| `Gyroscope_{X,Y,Z}` | raw gyroscope (rad/s) |
| `Magnetometer_{X,Y,Z}` | raw magnetometer (µT) |
| `Gravity_{X,Y,Z}` | gravity vector (m/s²) |
| `Orientation_Azimuth` | fused heading (degrees) |
| `Linear_Acceleration_{X,Y,Z}` | gravity-removed acceleration (m/s²), used for step detection |
| `Position_X`, `Position_Y` | PDR position (m) |
| `Step_Count` | cumulative steps |

The navigation log is sufficient to draw the trace; the IMU log is included to
show what the PDR consumes. The target device is taken to be the frequently-seen
MAC the attacker gets closest to (highest peak RSSI); override with `--mac`.

## Output

`nav_trace.pdf`: (left) the walked trajectory coloured by RSSI with Start/End
markers — the "trajectory-bound radio map"; (right) RSSI over time.

## Run

```bash
python plot_trace.py                       # uses data/nav_trace_1.csv
python plot_trace.py --log data/nav_trace_2.csv
```

## Expected result

The trajectory starts at low RSSI (blue) and converges on the target at high RSSI
(red); RSSI climbs over the run (e.g. −81 → −39 dBm for `nav_trace_1.csv`),
through the multipath fluctuations the Level 2 fusion is designed to smooth.
A freshly collected trace will differ in path geometry.
