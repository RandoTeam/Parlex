# Parlex Developer Handoff

Standalone onboarding for a new engineer or AI agent. Current state is also in
`PROJECT_STAGE.md`; priorities are in `ROADMAP.md`.

## What this is

Parlex is a privacy-first, offline Android translator with four surfaces:

- text translation;
- camera/photo OCR + translation;
- screen translation via accessibility shortcut + overlay;
- two-way voice dialogue with local ASR/TTS.

No user text, camera frames or audio is uploaded to a Parlex server. Network is
for model/package acquisition and optional currency rates.

## Repository

- Remote: https://github.com/RandoTeam/Parlex.git
- Public, MIT application code (`LICENSE`), third-party terms in
  `THIRD_PARTY_NOTICES.md` / `docs/LEGAL.md`.
- Package: `com.translive.app`
- ABI: `arm64-v8a`, minSdk 26, targetSdk 36.

## Read first

1. `docs/INDEX.md` - documentation map and source-of-truth rules.
2. `docs/PRODUCT_VISION.md` - what we are building and not building.
3. `docs/ARCHITECTURE.md` - modules and data flows.
4. `ROADMAP.md` - current/next phases.
5. `docs/PIPELINE.md` - development/release process.
6. `docs/QUALITY_GATES.md` - Definition of Done.
7. `docs/PROJECT_RULES.md` - operating rules for maintainers and agents.

## Clean clone

```powershell
git clone https://github.com/RandoTeam/Parlex.git
cd Parlex
powershell -ExecutionPolicy Bypass -File scripts/setup-native.ps1
```

The native setup restores the pinned llama.cpp checkout, OpenCL headers/link
stub and clears a stale `.cxx`. A clean clone still builds a CPU-only stub APK
without it; a release should contain a full `libtranslive.so`.

## Build and checks

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

Release:

```powershell
.\gradlew.bat :app:assembleRelease
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs `
  app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` and the `.jks` are not in Git. Restore them separately;
never commit them. Current signing state is in `PROJECT_STAGE.md`.

## CI

`.github/workflows/android-check.yml` runs unit tests, lint, debug APK and a
native llama.cpp/OpenCL job on public pushes/PRs. A release still requires the
manual checklist in `docs/RELEASES.md`.

## Current known state

- beta.10 is public and CI-green; no device acceptance yet.
- `main` is 4 commits ahead of beta.10 with an unreleased screen-performance
  pass; beta.11 is not published.
- Camera live translation is wired; placement/OEM behavior needs a device.
- Screen S10 is the active phase: session lock, Save teardown, window-change
  dismissal and first-paint latency. See
  `docs/S10_SCREEN_RELIABILITY_2026-10-26.md`.
- Migration instrumentation tests and full TalkBack coverage are missing.
- Model SHA-256 coverage is partial.
- Legacy `LiveScreenTranslateService`/`ScreenCaptureService` paths are retained
  pending a cleanup decision.

## Troubleshooting

- **Gradle wrapper**: use the tracked wrapper 9.4.1.
- **Build tools**: 36.0.0; JDK 21.
- **Stub native lib**: run `scripts/setup-native.ps1`, then clean
  `app/.cxx`, then rebuild.
- **Release key missing**: do not invent a silent rotation; follow
  `docs/RELEASES.md`.
- **CI billing**: public repo should use free standard Actions; if a run fails
  before jobs, check repository Actions settings/billing, not code.

## Communication

Work reports should state changed files, validation, remaining risks and the
next executable step. Mark device-only verification explicitly.
