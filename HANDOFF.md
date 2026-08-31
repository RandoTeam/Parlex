# Parlex Developer Handoff

This document is the standalone transfer note for continuing Parlex without
depending on previous chat history.

## Current Goal

Parlex is an offline Android translator with three main product flows:

- Text translation.
- Two-way voice dialogue.
- Camera/photo translation beta.

The project should stay privacy-first: user text, camera frames, and speech are
processed locally whenever the selected feature is available offline.

## Current Repository State

- Repository: `https://github.com/RandoTeam/Parlex.git`
- Main branch: `main`
- Latest public signed beta: `v1.4.1-beta.1`
- App package: `com.translive.app`
- Current app version in Gradle: `1.4.1-beta.1`, `versionCode` 9
- Supported ABI: `arm64-v8a`
- Native translation runtime: `llama.cpp` through JNI
- Stable model format: GGUF
- Experimental model format: LiteRT-LM beta

Current release signing state:

- The original release signing key was not available after the workstation
  rebuild.
- `v1.4.1-beta.1` is signed with a newly generated 2026 Parlex release key.
- Users with older Parlex APKs signed by the previous key must uninstall the old
  app before installing this beta.
- Do not use debug APKs as releases.

## What Is Implemented

- **Text & Dialogue Multi-Tier Translation**: Fast NMT (ML Kit, zero LLM RAM), Fast + LLM Improve, and LLM Direct across all 33 primary languages and 5 dialects with ICU Transliteration subtitles.
- **Screen Translation & Floating HUD**: Draggable overlay button, in-place text replacement card, continuous Auto-Live AR stream, animated pulsating HUD, on-screen language switcher, and zero-key currency augmentation.
- **Live Zero-Key Currency Converter**: Lexical/symbol parser ($50, 100€, 10 000₫, 1 500₽) with baseline offline conversion table for 35+ fiat currencies and customizable user home currency.
- **Camera Document Polish & Travel Mode**: 2-stage OCR box grouping, background inpainting, interactive bilingual inspect bottom sheet, Luma SAD camera stability analyzer, and 7-category dietary allergen classification.
- **Live Subtitle Camera Mode**: Spatio-temporal OCR tracker (IoU matching + Levenshtein jitter filter) for live teleprompter captions over videos and presentations.
- **PDF Document Translation**: High-DPI page renderer, semantic paragraph segmentation, overlay/split viewer, and multi-format export.
- **Built-in Offline Dictionary**: Local Room DB lexicon with fast multi-word lookup, parts of speech, and TSV import.
- **Offline Language Packs Hub**: Unified travel packs (RU-EN, VI-EN, ZH-EN, ES-EN, JA-EN, DE-EN, FR-EN) coordinating atomic NMT, dictionary, STT, and OCR downloads.
- **Audited Model Catalog & Performance Tiers**: Pinned SHA-256 checksums and performance tier badges (`⚡ Fast / Budget`, `⭐ Balanced`, `💎 Max Quality`, `🚀 GPU-Accelerated`).
- **LiteRT-LM Beta Benchmark**: End-to-end profiler (TTFT, tok/s, RSS memory, delegate fallback) and 38-language quality evaluation suite.

Governance:

- GitHub issue #2 was closed as completed by `v1.4.0-beta.1`.
- External PR #3 was reviewed and closed as implemented separately, with credit
  for the i18n contribution idea.
- `CONTRIBUTING.md` and a PR template exist.
- GitHub Actions debug build runs on PRs and `main`.

## Clean Clone Setup

```powershell
git clone https://github.com/RandoTeam/Parlex.git
cd Parlex

git clone https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout 5dcb71166686799f0d873eab7386234302d05ecf
```

The tracked source of truth is:

```text
app/src/main/cpp/llama.cpp.version
```

Required local tools:

- JDK 21 for CI parity.
- Android SDK platform 36.
- Android build tools 37.0.0.
- Android NDK `27.3.13750724`.
- CMake `3.22.1`.
- Gradle wrapper `8.11.1`.

## Local Validation

Basic wrapper check:

```powershell
.\gradlew.bat --version
```

Debug build:

```powershell
.\gradlew.bat assembleDebug --no-daemon --stacktrace
```

Install debug build:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Recommended smoke test:

1. Text translation with source auto-detect.
2. Text direction persists after app restart.
3. Dialogue starts and stops cleanly.
4. Camera live mode opens.
5. Camera capture translates a printed page.
6. Gallery import translates an existing photo.
7. History stores text/camera/dialogue items.
8. Model manager opens and shows model variants.
9. Settings language switch updates UI.

## Signed Beta Release Process

Prerequisite: restore `keystore.properties` and the current release keystore.

Example local secret file:

```properties
storeFile=C:/secure/parlex-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Release checklist:

1. Confirm `git status -sb` is clean before release edits.
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Update visible version references in `README.md` and `PROJECT_STAGE.md`.
4. Build release:

   ```powershell
   .\gradlew.bat assembleRelease --no-daemon --stacktrace
   ```

5. Verify signature:

   ```powershell
   & "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
   ```

6. Inspect APK version with Android build tools.
7. Commit and push `main`.
8. Create GitHub prerelease and upload the signed APK asset.
9. Release notes must describe user-facing functionality only.

Recommended next beta version: use the next beta after `v1.4.1-beta.1`.

## Key Files

- `README.md` - public project overview and setup.
- `PROJECT_STAGE.md` - current state snapshot.
- `ROADMAP.md` - next phases.
- `AGENTS.md` - Codex/project operating rules.
- `CONTRIBUTING.md` - contributor rules.
- `.github/workflows/android-check.yml` - CI debug build.
- `.github/pull_request_template.md` - PR review checklist.
- `app/build.gradle.kts` - Android config, versions, dependencies, signing.
- `app/src/main/cpp/llama.cpp.version` - native engine commit marker.
- `app/src/main/kotlin/com/translive/app/ui/TransLiveNavHost.kt` - navigation.
- `app/src/main/kotlin/com/translive/app/ui/screens/` - Compose screens.
- `app/src/main/kotlin/com/translive/app/ui/viewmodel/` - ViewModels.
- `app/src/main/kotlin/com/translive/app/engine/` - translation/OCR/STT/TTS.
- `app/src/main/kotlin/com/translive/app/data/model/ModelCatalog.kt` - models.
- `app/src/main/kotlin/com/translive/app/i18n/` - localization support.

## Development Rules

- Keep one phase per commit.
- Keep changes surgical.
- Prefer existing architecture.
- Do not change signing/release automation casually.
- Do not commit model files, APKs, local native checkouts, diagnostics, or
  secrets.
- Use official upstream sources for dependency/model/runtime audits.

## Next Recommended Work

1. Back up the new local release keystore outside the repository.
2. Run full manual smoke on a real phone.
3. Continue camera document polish.
4. Benchmark LiteRT on Snapdragon 8 Elite against GGUF.
5. Keep external contributions welcome, but review and merge through maintainer
   control.
