# Raw scan logs — lost-state timeout (E1, paper Figure 3)

Representative ESP32 BLE-scanner captures behind [`../data/timeout.csv`](../data/timeout.csv),
the lost-state-onset measurement. The complete set is 10 trials per device
(~190 MB of full-scan logs); a representative subset is included here and the
rest is retained locally (available on request).

## Contents

- `airtag/`, `watch/` — one trial each with the **full scan**
  (`ble_all_<ts>.csv`, every advertisement the scanner logged) and the device's
  own advertisements (`ble_lost_<ts>.csv`). AirTag (~12 min to enter the lost
  state) and Apple Watch (~13 s) bracket the range.
- `airpods/` — the device advertisements (`ble_lost_<ts>.csv`) for one trial.

## How a capture maps to the timeout

`data/timeout.csv` records, per trial, `Start_Time`, `End_Time`, and
`Timeout_sec = End_Time - Start_Time`. Reading the `ble_lost` log:

- **Start_Time** — the first advertisement after the owner's BLE link drops. The
  device is still paired/nearby, sending the short Find My payload
  `0x...4C0012 02 ...`.
- **End_Time** — when the device switches to the **Separated/Lost** broadcast:
  the 31-byte advertisement `0x...4C0012 19 ...` carrying the rotating public
  key that Find My finders pick up and relay.

For example, Apple Watch trial 1 (`TO-21`): nearby ads from `05:19:50.199`, the
first separated broadcast at `05:20:06.025` → `15.826` s. That difference is the
lost-state timeout plotted in Figure 3(a).

## CSV columns

`PC_Beijing_Time, Time (us), Address, RSSI (dBm), Raw Data, adv_data_len,
scan_rsp_len, Connectable, Addr Type, Adv Event Type`

The capture was logged with the ESP32-WROVER BLE scanner; `Raw Data` is the
hex advertising payload, `Address` the (rotating) advertised BLE address.
