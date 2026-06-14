# Example 7 — End-to-end navigation sample traces (paper Figure 17)

Sample walking trajectories from the **end-to-end navigation evaluation** — the
academic-building runs that paper Figure 17 averages into mean navigation time
and distance. One example trace per attack level shows the attacker's
reconstructed walking trajectory (PDR position, in metres) as it homes in on a
lost device. Where Examples 3–5 demonstrate each level's *signal processing*,
these traces show the resulting *walk* behind the evaluation numbers.

## Input

One representative navigation trace per level in `data/` (one BLE/step sample
per row):

| File | Level | Key columns |
| :--- | :--- | :--- |
| `level1_acoustic.csv`   | L1 acoustic | `step_count`, `positionx`, `positiony` |
| `level2_rssi.csv`       | L2 RSSI–IMU | `Position_X`, `Position_Y`, `RSSI`, `MAC_Address` |
| `level3_clustering.csv` | L3 clustering | `Position_X`, `Position_Y`, `RSSI`, `MAC_Address`, `Cluster`, `Phase` |

`positionx/positiony` (and `Position_X/Position_Y`) are the PDR-reconstructed
attacker coordinates; `RSSI` is the lost device's signal; `MAC_Address` is the
(rotating) Find My address each packet was heard under.

> The academic-building **Level-2** runs were captured only as on-screen
> recordings (`../example_trace_evaluation_raw/.../rssi_nav/*.jpg`), so the L2
> panel uses a representative RSSI–IMU trace — the same one shown in
> [Example 4](../4_rssi_navigation/). Levels 1 and 3 are traces from this
> evaluation set.

## Run

```bash
python plot_traces.py
```

## Output

`navigation_traces.pdf` — three trajectory panels:

- **(a) Level 1, acoustic** — the walked path, coloured by walking progress.
- **(b) Level 2, RSSI–IMU** — points coloured by the target's RSSI; the signal
  strengthens as the attacker closes in.
- **(c) Level 3, clustering** — two lost devices rotating their MAC every 30 s;
  colour = RSSI, marker shape = distinct MAC, so the path stays continuous even
  as the address keeps changing.

## Expected result

Each panel shows a Start (green) → End (red) walk that converges on the target.
In (b) and (c) the RSSI rises along the way; in (c) the many marker shapes are
the rotating MAC addresses that clustering stitches back into a single
trajectory. Absolute coordinates depend on the run; the convergence is the
point.

## Raw traces

The full per-run captures (Level-1 acoustic runs with PCM audio and
`NavRSSI`/`navigation_realtime` logs, Level-2 screenshots, and the six Level-3
`20251230_Logs*_30s/` BLE+IMU+`NavRSSI` folders) are kept locally in the
git-ignored `../example_trace_evaluation_raw/`.
