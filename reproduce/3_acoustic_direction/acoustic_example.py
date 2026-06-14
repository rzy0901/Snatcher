#!/usr/bin/env python3
"""Acoustic direction-finding example: chirp-arrival detection and dB measurement.

Demonstrates the Level 1 acoustic signal processing on one recording. The
attacker triggers the lost AirTag's sound and records it on the smartphone;
this script processes the channel of the phone's TOP microphone.

Input
  data/example_top.pcm   top-mic channel, 16-bit signed mono PCM @ 44.1 kHz
                         (raw little-endian int16; no header)
  data/template.wav      the model "di" chirp used as the matched template
                         (16-bit mono WAV @ 44.1 kHz)

Output (printed + figure acoustic_example.pdf)
  - the chirp-arrival times detected by cross-correlation
  - the received signal strength in dB (relative to 16-bit full scale)

Pipeline (as in the paper)
  1. Band-pass both the template and the recording to the 2.6-2.8 kHz AirTag
     chirp band (Butterworth, order 5).
  2. Cross-correlate the recording with the template; peaks above an adaptive
     threshold and at least 0.1 s apart mark chirp arrivals (keep the strongest
     MAX_ARRIVALS).
  3. For each arrival, take a template-length window, normalise by full scale,
     and measure its RMS; aggregate the per-arrival RMS values and convert to
     dB. This per-orientation dB is the quantity Level 1 compares across the
     four body orientations to find the direction to the source.

Usage: python acoustic_example.py
"""
import os
import wave

import numpy as np
import matplotlib.pyplot as plt
from scipy.signal import butter, sosfiltfilt, correlate, correlation_lags, find_peaks

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))
# This figure is shown in the widest column of the acoustic figure, so its
# fonts scale up more than the neighbours'. Use smaller in-canvas label/tick
# fonts than the shared style: the labels then match the neighbours' on-page
# size and, taking less room, leave the waveform/correlation plots taller.
plt.rcParams.update({"font.size": 18, "axes.titlesize": 18, "axes.labelsize": 18,
                     "xtick.labelsize": 15, "ytick.labelsize": 15, "legend.fontsize": 17})

SR = 44100                  # sample rate (Hz)
FULL_SCALE = float(2 ** 15)  # 16-bit signed full scale, for dB
BAND = (2600.0, 2800.0)     # AirTag chirp band (Hz)
THRESHOLD_RATIO = 0.3       # adaptive threshold = ratio * max(correlation)
MIN_GAP_S = 0.1             # minimum spacing between arrivals (s)
MAX_ARRIVALS = 45           # keep the strongest N arrivals
TOPMIC_PCM = "data/example_top.pcm"
TEMPLATE_WAV = "data/template.wav"


def bandpass(x, band=BAND, fs=SR, order=5):
    sos = butter(order, band, btype="bandpass", fs=fs, output="sos")
    return sosfiltfilt(sos, x - np.mean(x))


def read_pcm(path):
    return np.fromfile(path, dtype=np.int16).astype(np.float64)


def read_wav(path):
    with wave.open(path, "rb") as w:
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype=np.int16).astype(np.float64)


def detect_arrivals(rec, tmpl, fs=SR):
    """Cross-correlation arrival detection. Returns peak indices into `corr`."""
    corr = correlate(rec, tmpl, mode="full") / len(tmpl)
    lags = correlation_lags(len(rec), len(tmpl), mode="full")
    thr = THRESHOLD_RATIO * corr.max()
    peaks, _ = find_peaks(corr, height=thr, distance=int(MIN_GAP_S * fs))
    peaks = peaks[np.argsort(corr[peaks])[::-1][:MAX_ARRIVALS]]  # strongest N
    return peaks, corr, lags, thr


def measure_db(rec, arrival_idx, win_len):
    """RMS (vs full scale) over each template-length arrival window, in dB."""
    rms = []
    for a in arrival_idx:
        s = max(0, int(a))
        e = min(len(rec), s + win_len)
        if e > s:
            rms.append(np.sqrt(np.mean((rec[s:e] / FULL_SCALE) ** 2)))
    agg = np.sqrt(np.mean(np.square(rms))) if rms else 0.0
    return 20 * np.log10(agg + 1e-12)


if __name__ == "__main__":
    tmpl = bandpass(read_wav(TEMPLATE_WAV))
    rec = bandpass(read_pcm(TOPMIC_PCM))

    peaks, corr, lags, thr = detect_arrivals(rec, tmpl)
    arrival_idx = lags[peaks]                 # sample index of each arrival in rec
    db = measure_db(rec, arrival_idx, len(tmpl))
    print(f"[info] template {len(tmpl)/SR*1000:.0f} ms; "
          f"{len(peaks)} chirp arrivals; signal strength = {db:.1f} dB")

    t = np.arange(len(rec)) / SR
    t_arr = np.sort(arrival_idx) / SR
    # Two-panel view of the detection: the band-passed recording with the
    # detected chirp arrivals, and the cross-correlation they come from. (The
    # matched template is described in the text; plotting it adds little.)
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(8, 5.0))

    ylim = 1.5 * np.percentile(np.abs(rec), 99.99)
    a1.plot(t, rec, lw=3, color="C0", zorder=2)
    for k, ta in enumerate(t_arr):
        a1.axvline(ta, color="red", lw=3, alpha=0.7, zorder=3,
                   label="detected arrival" if k == 0 else None)
    a1.set_xlim(0, t[-1])
    a1.set_ylim(-ylim, ylim)
    a1.set_xlabel("Time (s)")
    a1.set_ylabel("Amplitude")
    a1.set_title("Recording (band-passed)")

    cmax = corr.max()                       # normalise for a clean 0-1 y-axis
    a2.plot(lags / SR, corr / cmax, lw=3, color="0.4")
    a2.axhline(THRESHOLD_RATIO, color="orange", ls="--", lw=3, label="threshold")
    a2.plot(lags[peaks] / SR, corr[peaks] / cmax, "r.", ms=15, label="arrivals")
    a2.set_xlim(0, t[-1])
    a2.set_xlabel("Lag (s)")
    a2.set_ylabel("Correlation")
    a2.set_title("Cross-correlation")

    hs, ls = [], []
    for a in (a1, a2):                      # the recording + cross-correlation axes
        h, l = a.get_legend_handles_labels()
        hs += h; ls += l
    fig.legend(hs, ls, loc="upper center", ncol=3, frameon=False, bbox_to_anchor=(0.5, 1.0))

    fig.tight_layout(rect=[0, 0, 1, 0.88])
    fig.savefig("acoustic_example.pdf", bbox_inches="tight")
    print("[ok] wrote acoustic_example.pdf")
