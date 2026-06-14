# Example 3 — Acoustic direction finding (Level 1, paper Figure 6)

Demonstrates the Level 1 acoustic signal processing on one recording: detecting
the AirTag chirp arrivals by cross-correlation and measuring the received signal
strength in dB. Per-orientation, this dB is what Level 1 compares across the
four body orientations ("Scan & Spin") to estimate the direction to the source.

## Input

| File | Format | Meaning |
| :--- | :--- | :--- |
| `data/example_top.pcm` | 16-bit signed mono PCM, 44.1 kHz, little-endian, headerless | the channel of the phone's **top microphone** for one recording (the attacker triggers the AirTag's sound and records it) |
| `data/template.wav` | 16-bit mono WAV, 44.1 kHz | the model "di" chirp used as the matched template for cross-correlation |
| `data/direction_example.csv` | CSV: `orientation` in {F,R,B,L}, `db_top` (dB) | the four per-orientation signal strengths from one "Scan & Spin", used to draw the direction polar |

`SnatchAPP` records stereo and writes the two channels as separate PCM files;
the **top mic is the right channel**. `data/example_metadata.txt` carries the
recording metadata (sample rate, phone orientation, RSSI, timing).

## Output

- Printed: the number of detected chirp arrivals and the received signal
  strength in dB (relative to 16-bit full scale).
- `acoustic_example.pdf`: (i) the band-passed template, (ii) the recording with
  the detected arrivals marked, and (iii) the cross-correlation with the
  adaptive threshold and detected peaks.

## Method

1. Band-pass the template and the recording to the 2.6–2.8 kHz AirTag chirp band
   (Butterworth, order 5).
2. Cross-correlate the recording with the template; peaks above an adaptive
   threshold (`0.3 × max`) and at least 0.1 s apart are chirp arrivals (keep the
   strongest 45).
3. For each arrival, take a template-length window, normalise by full scale, and
   take its RMS; aggregate (RMS-of-RMS) and convert to dB.

## Run

```bash
python plot_waveform_spectrogram.py  # raw waveform + spectrogram of the clip
python acoustic_example.py           # arrival detection + dB on one recording
python plot_direction_polar.py       # direction polar from the four dB values
```

## Expected result

- `acoustic_example.py`: for the provided clip (outdoor, 5 m, Front orientation)
  it detects 45 chirp arrivals — clustered in the three sound-play events — and
  reports a signal strength of about **−42.8 dB**, matching the value produced by
  the full offline pipeline.
- `plot_waveform_spectrogram.py`: the raw waveform shows the three AirTag
  sound-play bursts; the spectrogram shows their energy concentrated around
  2.6–2.8 kHz (`waveform_spectrogram.pdf`).
- `plot_direction_polar.py`: from the four dB values it computes the
  contrast-enhanced score `S_k = 0.3·A_k + 0.7·(A_k − A_opp)` and marks the
  estimated heading — **Front** for this sample — on a polar plot
  (`direction_polar.pdf`).

The unprocessed multi-distance/orientation capture set is kept locally under
`3_acoustic_direction_raw/` (git-ignored).
