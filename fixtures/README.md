# `fixtures/`

This directory holds **personal ride data** (NDJSON sample files, summary JSON, Samsung Health exports, Strava archives). **Everything in here is gitignored except this README and `.gitkeep`** so the directory structure stays in the repo but rider data never leaks.

## Layout (when populated locally)

```
fixtures/
├── *.ndjson              # raw per-second URUJ ride samples
├── *.summary.json        # ride summary metadata
├── strava/               # bulk-exported Strava archive (for cross-validation)
└── *.kmz                 # OpenTracks exports (legacy / pre-URUJ baseline rides)
```

## What we use these for

- **Unit testing** the power model, auto-pause detector, and other pure-logic classes against real ride data
- **Cross-validation** of URUJ's distance / power / elevation against Strava + Samsung Health
- **Tuning** Crr / CdA defaults from real-world rides
- **Regression detection** — when we change the physics model, re-run the analysis tools and compare

## How to populate locally

From your Android device:

1. Connect phone via USB with developer mode enabled
2. Android Studio → View → Tool Windows → **Device Explorer**
3. Navigate to `/storage/emulated/0/Android/data/com.uruj/files/rides/`
4. Right-click each `.ndjson` / `.summary.json` → Save As → into this folder

Or via ADB:

```bash
adb pull /storage/emulated/0/Android/data/com.uruj/files/rides/ ./fixtures/
```

## Privacy note

Per the `.gitignore`:
```
fixtures/*
!fixtures/.gitkeep
!fixtures/README.md
```

Personal ride data **never** leaves your machine via this repo. If you fork or contribute, your local rides stay local.
