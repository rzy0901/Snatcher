# Artifact Appendix (LaTeX source)

This folder contains the **Artifact Appendix** for the paper *"Snatcher: Apple
Find My Network Exposes Your Lost Devices to Strangers"* (ACM CCS 2026),
prepared for the Artifact Evaluation process.

## Files

| File | Description |
| :--- | :--- |
| `Snatcher_AE.tex` | The artifact appendix source (standalone, `acmart` class). |
| `Snatcher_AE.pdf` | Pre-built PDF for convenience. |
| `figs/` | Expected-result figures shown in the appendix. |

## Building

The document is self-contained and compiles with a standard TeX Live
installation (requires the `acmart` class). Run `pdflatex` twice to resolve
cross-references:

```bash
pdflatex Snatcher_AE.tex
pdflatex Snatcher_AE.tex
```

## Scope

The appendix documents how to obtain, build, deploy, and evaluate the artifact:

- **Available / Functional:** build and run the Android app (`SnatchAPP/`) and
  the two ESP32 firmwares (`esp32_airtag_mac_rotation/`, `esp32_sound_maker/`)
  to exercise the three-level attack primitives.
- **Partial reproduction (`reproduce/`):** representative data and scripts that
  reproduce the acoustic signal processing (Fig. 6, 7), a Level 1/2 navigation
  trace (Fig. 8), and the spatial–temporal clustering (Fig. 11).

## Before submission

Items requiring the authors' input are marked `[TODO: ...]` (rendered in red) in
`Snatcher_AE.tex` — code license, archival DOI, and confirmation of the final
`reproduce/` folder and script names. Remove all `\todo{...}` markers once
resolved.
