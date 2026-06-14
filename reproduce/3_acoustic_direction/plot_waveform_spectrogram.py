#!/usr/bin/env python3
"""Raw waveform and spectrogram of the example recording (top mic).

A complementary view of the same top-mic recording used by acoustic_example.py:
the raw waveform (with the AirTag sound-play intervals marked) and its
spectrogram, which shows the chirp energy concentrated around 2.6-2.8 kHz -- the
band that the matched-filter band-pass isolates.

Input : data/example_top.pcm   (16-bit signed mono PCM @ 44.1 kHz)
Output: waveform_spectrogram.pdf

Usage: python plot_waveform_spectrogram.py
"""
import os

import numpy as np
import matplotlib.pyplot as plt
from scipy.signal import butter, sosfiltfilt

plt.style.use(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "snatcher.mplstyle"))

SR = 44100
BAND = (2600.0, 2800.0)       # AirTag chirp band (Hz)
EXAMPLE = "data/example_top.pcm"


def bandpass(x, band=BAND, fs=SR, order=5):
    sos = butter(order, band, btype="bandpass", fs=fs, output="sos")
    return sosfiltfilt(sos, x - np.mean(x))


def detect_sound_bursts(xb, fs=SR, win_s=0.1, min_dur=0.4, merge_gap=0.6):
    """Approximate the AirTag sound-play intervals from the band-passed envelope."""
    win = max(1, int(win_s * fs))
    env = np.sqrt(np.convolve(xb ** 2, np.ones(win) / win, mode="same"))
    thr = np.median(env) + 0.3 * (np.percentile(env, 95) - np.median(env))
    edges = np.flatnonzero(np.diff(np.r_[0, (env > thr).astype(int), 0]))
    spans, merged = [[s, e] for s, e in zip(edges[0::2], edges[1::2])], []
    for s, e in spans:
        if merged and s - merged[-1][1] < merge_gap * fs:
            merged[-1][1] = e
        else:
            merged.append([s, e])
    return [(s / fs, e / fs) for s, e in merged if e - s >= min_dur * fs]


if __name__ == "__main__":
    x = np.fromfile(EXAMPLE, dtype=np.int16).astype(np.float64)
    t = np.arange(len(x)) / SR
    bursts = detect_sound_bursts(bandpass(x))

    fig, (ax_w, ax_s) = plt.subplots(2, 1, figsize=(8, 6))
    ax_w.plot(t, x, lw=3, color="C0", zorder=2)
    # zoom the y-axis to reveal the sound-play bursts, clipping the brief
    # connection transient that otherwise dominates the scale
    ylim = 1.5 * np.percentile(np.abs(x), 99.99)
    ax_w.set_ylim(-ylim, ylim)
    ax_w.set_xlim(0, t[-1])
    ax_w.set_xlabel("Time (s)")
    ax_w.set_ylabel("Amplitude")
    ax_w.set_title("Raw waveform")

    with np.errstate(divide="ignore"):    # silence log10(0) on fully silent bins
        _, _, _, im = ax_s.specgram(x, NFFT=2048, Fs=SR, noverlap=1024, cmap="viridis")
    ax_s.set_ylim(0, 6000)
    ax_s.set_xlim(0, t[-1])
    ax_s.set_xlabel("Time (s)")
    ax_s.set_ylabel("Frequency (Hz)")
    ax_s.set_title("Spectrogram")
    fig.colorbar(im, ax=ax_s, label="Power (dB)")

    # mark the approximate AirTag sound-play intervals on both panels
    for k, (s, e) in enumerate(bursts):
        lbl = "AirTag sound play" if k == 0 else None
        ax_w.axvspan(s, e, facecolor="none", edgecolor="red", lw=3, zorder=3, label=lbl)
        ax_s.axvspan(s, e, facecolor="none", edgecolor="red", lw=3, zorder=3)
    if bursts:
        fig.legend(*ax_w.get_legend_handles_labels(), loc="upper center",
                   frameon=False, bbox_to_anchor=(0.5, 1.02))

    fig.tight_layout(rect=[0, 0, 1, 0.92])
    fig.savefig("waveform_spectrogram.pdf", bbox_inches="tight")
    print(f"[ok] wrote waveform_spectrogram.pdf ({len(bursts)} sound-play intervals)")
