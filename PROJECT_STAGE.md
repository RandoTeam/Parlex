# Project Stage Snapshot

Snapshot date: 2026-09-01

## Repository

- Remote: `https://github.com/RandoTeam/Parlex.git`
- Branch: `main`
- Baseline head before this release pass: `c579621`
- Latest public signed prerelease: `v1.5.1-beta.1`
- Latest public APK asset: `Parlex-v1.5.1-beta.1-release.apk`
- Public APK SHA-256:
  `63b9bcfb22b1a8964fe512c5a6592b641f82cb4fa7548436b2827ab7519a853d`

## App Version

- `versionCode`: 15100
- `versionName`: `1.5.1-beta.1`
- `compileSdk`: 36
- `targetSdk`: 36
- `minSdk`: 26
- ABI: `arm64-v8a`

## Current Functional Stage

- **Zero-Latency Screen Translation & Volume Key Decoupling (Phase W)**: High-speed zero-dialogue screen translation pipeline with direct `AccessibilityNodeInfo` BFS vector text extraction (`extractVisibleTextNodesFast`), 1-pass delimited batch translation (`FastBatchTranslator`), fine-tuned AR bounding box clusterer (`ArBoundingBoxClusterer`), volume shortcut decoupling (`ScreenA11yShortcutBehavior`: `SINGLE_SHOT_NO_BUBBLE` vs `TOGGLE_FLOATING_BUBBLE`), independent screen target language synchronization/selection, and offline travel pack download hardening with automatic Whisper/VAD STT bundling.
- **Model Manager 5-Step Overhaul & Fast NMT Offline Sync (Phase M)**: 5-step numbered hierarchy structuring all offline assets: Step 1 Primary AI Translator (Hy-MT2 1.8B/7B, Gemma 4 LiteRT, TranslateGemma 4B, HY-MT 1.5, RAM management), Step 2 Fast NMT (expanded 59-language catalog, search filter, batch download, and one-file `.parlex-fast` archive import/export with `ParlexFastManifest`, `FastModelSyncPacker`, `FastModelArchiveValidator`), Step 3 Speech STT (Zipformer, SenseVoice, Whisper Tiny, Qwen3-ASR), Step 4 Vision & OCR (PP-OCRv6 tiny MNN), Step 5 Dictionaries & Data (Travel Packs Hub, built-in RU ↔ EN dictionary). Includes universal Exponential Moving Average (EMA) download speed smoothing, live progress formatting, and strict Material Design 3 zero-emoji compliance.
- **Floating Screen Translation Overhaul & AR Back Interception (Phase O)**: Complete UX overhaul of the floating overlay button: magnetic Drag-to-Dismiss trash zone (`DismissTrashView`, `DragToDismissCalculator`) with spring physics and haptic feedback, predictive back gesture interception in AR overlays (`ArOverlayBackController`, `ArOverlayWindowFlags`) without blocking background apps, Liquid Glass specular reflection styling and vector translation arrows `A ⇄ 文` replacing raw ASCII "T", and adaptive Material 3 Expressive FAB Menu (`FabMenuLayoutCalculator`).
- **Multi-Engine STT Architecture (Phase V)**: Full support and dynamic selection between 2026 on-device speech-to-text engines (`SttEngineRegistry`, `SttEngineSelector`, `TwoPassSttPipeline`): Zipformer true real-time streaming transducer (RU, EN, ZH, VI packs ~50-95 MB), SenseVoice-Small INT8 ultra-fast offline recognition (ZH/EN/JA/KO), Whisper Tiny (99 languages), and Qwen3-ASR 0.6B INT8.
- **On-Device Vision LLM & Screen Analysis (Phase AI)**: Multimodal AI screen perception pipeline (`VisionLlmCatalog`, `ImageDimensionScaler`, `VisionPromptBuilder`, `StreamingTextAccumulator`) supporting MiniCPM-V 4.6 (1.3B, ~1 GB INT4), SmolVLM-2 (500M, ~300 MB INT4), and Gemma 4 Edge E2B (2.0B, ~1.5 GB INT4) for on-device OCR, document explanation, and visual summarization.
- **Dialogue Voice Recording & Chronological Timeline (Phase H)**: Background session audio recording in user-configurable formats (AAC 48 kbps mono `.m4a` / 16-bit PCM `.wav`) via zero-contention PCM streaming from `SpeechEngine`, Room DB v5 persistence with per-turn audio timestamps and statistics, full chronological timeline in History with synchronized scrub/seek audio player bar (1.0x to 2.0x speeds), multilingual/CJK word and character analytics (`DialogueSessionStatsCard`), and local on-device LiteRT-LM (Gemma 2 2B) structured dialogue summarizer (`DialogueLlmSummaryCard`).
- **Multi-Tier Translation Policy**: Fast NMT (ML Kit, ~30ms, 0MB LLM RAM), Fast + Improve, and LLM Direct across Text, Dialogue, Camera, and Screen Translation.
- **Floating HUD & Screenshot Export (Sub-Phase S1.3)**: Expandable MD3 floating HUD docked beside the translate bubble with Fast NMT vs Smart LLM segmented switch, 1-tap quick target language carousel (`RU`, `EN`, `ZH`, `DE`, `FR`, `ES`, `VI`, `JA`, `KO`), one-tap "Save Screenshot" (`ScreenTranslationExporter`) compositing translated pill cards onto the raw screen capture and persisting to Android Scoped Storage (`Pictures/Parlex/`) with gallery indexing.
- **In-Place AR Text Overlays (Sub-Phase S1.2)**: Google Circle to Search / Apple Live Text style in-place rendering with Union-Find spatio-temporal line clustering (`ArBoundingBoxClusterer`), dynamic `StaticLayout` auto-fitting pill backplates (`#EE12121E` with cyan border `#5580D8FF`), micro-action floating bar on text card tap (`[Copy]`, `[Speak]`, `[Original]`, `[Show Original]`), top-bar `[Save]` export, resilient non-dismissing ambient touch handling, and explicit top "✕" / bottom "Dismiss" pill dismissal.
- **Screen Translation Lifecycle & Debounce (Sub-Phase S1.1)**: Persistent `VirtualDisplay` & `MediaProjection` token management preventing repeated permission requests / app bouncing, non-blocking `translationMutex` debounce with micro-haptic feedback, OpenCL 2.0 (`GGML_OPENCL_TARGET_VERSION=200`) universal Adreno GPU driver compatibility, dynamic floating button state machine (`IDLE` Teal $\to$ `SCANNING` Amber $\to$ `TRANSLATING` Indigo $\to$ `COMPLETE` Emerald $\to$ `ERROR` Red auto-recovering in 2s), and long-press HUD settings modal.
- **Continuous Hands-Free Voice Dialogue (Phases D2 & D3.1)**: Apple Translate-style hands-free conversational loop with a single pulsating central FAB, live audio waveform visualizer, bilingual status capsule, automatic multi-script language arbitration, Whisper empty-language fix, Fast NMT instant start without LLM dependency, hardware AEC echo guard, 300ms acoustic reverb cooldown, user-configurable Auto-TTS toggle in Settings (`dialogueAutoSpeak`), and an in-dialogue quick mute/unmute control dock button for silent teleprompter mode.
- **Fast NMT Package Manager & Transparent Downloader (Phase P2)**: Complete storage breakdown (~30 MB/pack, 900 MB total catalog), official source transparency (`Google ML Kit CDN • On-Device NMT`), active package download isolation (single spinner invariant, queued badges, per-item cancellation), batch download cancellation, and strict Material 3 Zero-Emoji ISO badges.
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
5. R&D AngelSlim (Phase R&D-M): investigate custom ARM NEON dequant kernels for Tencent 1.25-bit / 2-bit models vs standard Q4_K_M GGUF.
