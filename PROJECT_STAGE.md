# Project Stage Snapshot

Snapshot date: 2026-08-31

## Repository

- Remote: `https://github.com/RandoTeam/Parlex.git`
- Branch: `main`
- Baseline head before this release pass: `c579621`
- Latest public signed prerelease: `v1.4.1-beta.1`
- Latest public APK asset: `Parlex-v1.4.1-beta.1-release.apk`
- Public APK SHA-256:
  `c7935b493db3b5b6a40f62249829b06cb8eb1de9134687a65c99c178ed7da45e`

## App Version

- `versionCode`: 9
- `versionName`: `1.4.1-beta.1`
- `compileSdk`: 36
- `targetSdk`: 35
- `minSdk`: 26
- ABI: `arm64-v8a`

## Current Functional Stage

- **Multi-Tier Translation Policy**: Fast NMT (ML Kit, ~30ms, 0MB LLM RAM), Fast + Improve, and LLM Direct across Text, Dialogue, Camera, and Screen Translation.
- **Screen Translation & Floating HUD**: Continuous Auto-Live AR translation and Single-Shot mode with animated pulsating HUD, mini language picker, clipboard copy, and TTS readout.
- **Transliteration Engine**: Offline ICU Transliterator for Cyrillic, CJK (Pinyin/Romaji), Arabic, Devanagari, Thai, and Hangul.
- **Built-in Offline Dictionary**: Room DB lexicon with fast word lookup, grammatical categorization, and TSV lexicon import.
- **PDF Document Translation**: High-DPI page rendering, semantic paragraph extraction, bilingual overlay/split mode, and TXT/PDF export.
- **Zero-Key Live Currency Converter**: Real-time multi-currency detection ($50, 100€, 10 000₫, 1 500₽) with baseline offline conversion into user's preferred home currency.
- **Camera Document Polish & Travel Mode**: 2-stage OCR box clustering, gradient inpainting, interactive inspect sheet, Luma SAD stability/lighting analyzer, and dietary allergen classification across 33 languages.
- **Live Subtitle Mode**: Spatio-temporal text tracking with IoU matching and Levenshtein jitter filtering for dynamic video/presentation subtitle teleprompter.
- **Offline Language Packs**: Unified multi-component travel packs (RU-EN, VI-EN, ZH-EN, ES-EN, JA-EN, DE-EN, FR-EN) coordinating NMT, Dictionary, STT, and OCR assets.
- **Audited Model Catalog & Performance Tiers**: Pinned SHA-256 checksums, memory bounds, and tier badges (`⚡ Fast / Budget`, `⭐ Balanced`, `💎 Max Quality`, `🚀 GPU-Accelerated`).
- **Multi-Generation Snapdragon Adreno GPU Acceleration**: Dynamic hardware profile registry (`AdrenoHardwareProfile.kt`, `AdrenoProfileRegistry.kt`) covering Snapdragon 845 (Adreno 630), Snapdragon 8 Gen 3 (Adreno 750) through Snapdragon 8 Elite (Adreno 830 / OnePlus 13 reference profile) with runtime layer scaling, dynamic batching (128/256/512), and OpenCL hardware probes in a single universal APK.
- **LiteRT-LM Beta Benchmark**: Comprehensive 38-language evaluation suite, TTFT/throughput profiler, and CPU/GPU fallback telemetry.

## Repository Work Recently Completed

- GitHub issue #2 was closed as completed by `v1.4.0-beta.1`.
- External PR #3 was reviewed, credited as an i18n contribution idea, and closed
  as implemented separately.
- Android CI was added and updated to current action versions.
- i18n was implemented in the project architecture instead of merging the broad
  external PR directly.

## Current Release Signing State

The original release signing key was not available after the workstation
rebuild, so `v1.4.1-beta.1` uses a newly generated 2026 Parlex release key.

Android treats this as a signing-key rotation without lineage. Users with older
Parlex APKs signed by the previous key must uninstall the old app before
installing `v1.4.1-beta.1`.

Do not upload `app-debug.apk` as a beta or release.

## Restore After Clean Clone

Tracked source does not include generated outputs, local model files, native
engine checkouts, or signing secrets.

Restore when needed:

```powershell
git clone https://github.com/RandoTeam/Parlex.git
cd Parlex

git clone https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout 5dcb71166686799f0d873eab7386234302d05ecf
```

For signed builds, also restore:

- `keystore.properties`
- the matching release keystore file referenced by `storeFile`

## Clean Folder Policy

These are intentionally ignored. Build outputs and restored dependencies are
safe to delete before reinstalling Windows or preparing a clean project
transfer. Signing secrets must be backed up first if they should remain usable.

- `.gradle/`
- `build/`
- `app/build/`
- `app/.cxx/`
- APK/AAB files
- `keystore.properties`
- local model files: `*.gguf`, `*.litertlm`, `*.tflite`, `*.task`,
  `*.safetensors`, `*.bin`
- local native engine checkout: `app/src/main/cpp/llama.cpp/`
- legacy ignored native checkout path, if present locally:
  `app/src/main/cpp/whisper.cpp/`
- `diagnostics/`

## Next Maintainer Action

1. Back up the new local release keystore outside the repository.
2. Run full manual smoke on a real phone.
3. Continue camera document polish.
4. Benchmark LiteRT on Snapdragon 8 Elite against GGUF.
