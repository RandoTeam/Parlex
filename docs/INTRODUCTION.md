# Parlex Project Introduction

Parlex is a privacy-first, offline translator for Android. It is built for
people who need text, camera, screen and voice translation in places where
connectivity is poor, expensive or untrusted.

## What it does

- **Text**: paste/type text and translate with a local NMT or local LLM.
- **Camera**: point at a menu, sign or document, or import a photo; OCR and
  translation happen on the device.
- **Screen**: use the Android accessibility shortcut to translate another app in
  place, with a floating bubble as an alternative trigger.
- **Voice**: two-way dialogue with local speech recognition, translation and
  text-to-speech.
- **Offline data**: dictionary, history, language packs and optional currency
  baselines stay on the device.

No Parlex server receives user text, screenshots, camera frames or audio.
Network access is for model/package downloads and optional currency rates.

## Current state

- Package: `com.translive.app`
- Latest public beta: `v1.5.1-beta.10`
- `versionCode` 15110, `minSdk` 26, `targetSdk` 36, `arm64-v8a`
- Public repository, MIT application code, CI green for unit tests, lint, debug
  APK and native build.
- `main` is ahead of beta.10 with an unreleased screen-reliability pass; beta.11
  is not published yet.
- Device acceptance is still pending for the latest beta; see
  `docs/DEVICE_QA.md`. The active phase is S10, tracked in
  `docs/S10_SCREEN_RELIABILITY_2026-10-26.md`.

## Design direction

The current visual language is **Quiet Glass**: content first, one accent per
surface, translucent controls only where they help, readable solid surfaces for
translated text. Camera uses an orange shutter; screen focus uses blue; state
colors are reserved for success/warning/error. Details: `docs/DESIGN_SYSTEM.md`.

## How to read this repository

Start with `docs/INDEX.md`. The short version:

1. `docs/PRODUCT_VISION.md` - why and for whom.
2. `docs/ARCHITECTURE.md` - how the app is built.
3. `ROADMAP.md` - what is next and what "done" means.
4. `docs/PIPELINE.md` - how work moves.
5. `docs/QUALITY_GATES.md` - required checks.
6. `docs/PROJECT_RULES.md` - operating rules for maintainers and agents.

## Non-goals

Parlex is not a cloud translation service, an account/social product, a
general-purpose chatbot, or a model-weight redistribution site. It does not add
telemetry or hidden network calls.
