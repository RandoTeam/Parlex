# Project Stage Snapshot

Snapshot date: 2026-10-22

> **Recovery status:** the 2026-10-21 audit
> [`docs/AUDIT_AND_RECOVERY_PLAN_2026-10-21.md`](docs/AUDIT_AND_RECOVERY_PLAN_2026-10-21.md)
> supersedes the "COMPLETED" labels below where code and verification disagree.
> The first stabilization pass covers CI/native reproducibility, screen layout
> fitting, accessibility-shortcut diagnostics, camera crop/resolution, voice TTS
> timeout and ASR decoupling. Device acceptance is still required.

## Repository

- Remote: `https://github.com/RandoTeam/Parlex.git`
- Branch: `main`
- Last public release commit: `f82df69`
- Recovery work HEAD at audit time: `fe7d4b4`
- Latest public signed prerelease: `v1.5.1-beta.7`
- Latest public APK asset: `Parlex-v1.5.1-beta.7-release.apk`
- Public APK SHA-256:
  `9b2365e669df2f2fe05aac95118b81b2e9eb5c05460eb9417c34e958c5eb9cf3`

## App Version

- `versionCode`: 15107
- `versionName`: `1.5.1-beta.7`
- `compileSdk`: 36
- `targetSdk`: 36
- `minSdk`: 26
- ABI: `arm64-v8a`

## Current Functional Stage

- **Hybrid Vector + OCR Screen Translation & TQI Benchmark Suite (Phase Z & Z.1)**: Dual-stream perception engine combining 0ms OS vector node extraction with on-device MNN PP-OCRv6 raster text scanning:
  1. *Dual-Stream Perception & IoU Bounding Box Deduplication*: Added `OcrMode` preference (`FAST_ONLY` vs `FULL_SCAN`), upgraded `ScreenAccessibilityService` to capture silent screenshots in all modes, and implemented `CandidateMerger` running on `Dispatchers.Default` to detect and translate text embedded in raster images while discarding OCR blocks overlapping $> 50\%$ with authoritative vector accessibility nodes.
  2. *Layout Collision & Inlining Bug Fixes*: Removed destructive vertical squish in `ArTranslateOverlayView.resolveOverlaps()`, preventing colliding blobs on large screens, and calibrated inline fragment heuristics in `NativeScreenTextExtractor`.
  3. *Translation Quality Index (TQI) Automated Framework*: 100-point host evaluation suite (`TqiHostBenchmarkTest`, `TqiEvaluator`) testing Geometry, Inpainting, Typography, and Semantics across 4 real-world UI archetypes (Web Search, Messenger, Docs, App Store Grid).
  4. *Offline Test Isolation*: Fixed `CurrencySyncAndAttributionTest` socket timeout bloat by enforcing manual sync policy in tests.
- **In-Place AR Screen Translation & Visual Overhaul (Phase Z)**: Comprehensive redesign of Screen Translation overlay eliminating visual fragmentation, calques, dark halos, and mutual recursion:
  1. *State Resilience & Zero-Crash Dismissal*: Patched infinite mutual recursion between `ScreenTranslateOverlayService` and `ArTranslateOverlayView` (28,000+ frame `StackOverflowError`) via unconditional dismissal state locking and reference nulling before cascade invocation.
  2. *Semantic Node Hierarchy Reconstruction (Substring Coverage Inversion)*: Overhauled `NativeScreenTextExtractor` with coverage-ratio analysis. Purged partial child fragment leaf-nodes (`<b>Android</b>`, `<b>Police</b>`) when enclosed by a cohesive paragraph covering $< 70\%$ text length, preserving natural card sentence structure and full layout width ($1254\text{ px}$).
  3. *Brand & Proper Noun Entity Shield*: Implemented `BrandEntityShield` preserving high-tech brand names (`Android Police`, `Android`, `DuckDuckGo`, `YouTube`, `Google`, `Valnet`) with zero-latency token shielding and post-NMT restoration, eliminating literal sci-fi calques (*"Человекоподобный робот"*). Filtered search breadcrumbs and URL noise in `ScreenTextNoiseFilter`.
  4. *Zero-Drift Inpainting & Halo Elimination*: Decoupled `eraseRect` from collision downward offsets in `ArRenderBox` (anchoring erase mask strictly to original screenshot bounds), removed 45% alpha `eraseFringePaint` stroke framing halos, tuned 48-point perimeter sampling to 25% trimmed-mean outlier rejection with neutral `#F01E1E1E` surface fallback, disabled hyphenation (`HYPHENATION_FREQUENCY_NONE`) to prevent broken syllabic splits (*"По-лиция"*), and introduced dedicated per-box `TextPaint` instances with bold headline hierarchy (`Typeface.BOLD` + `#FFFFFF`) and readable body text (`#D1D1D6`).
  5. *Monotonic Column-Aware Collision Engine*: Replaced adjacent-only collision checks with a global monotonic scan across all preceding items sharing column overlap ($\ge 20\%$) and spacing-preserving upward headroom containment, preventing bottom-screen crushing and multi-column interleaving skips.
- **Qualcomm Snapdragon NPU Hardware Offload & In-Place AR Typography (Phase Y & X.9-12)**: Direct on-device NPU hardware execution using LiteRT Qualcomm NPU delegate (`Backend.NPU(context.applicationInfo.nativeLibraryDir)`), 3-tier fallback ladder (`NPU` -> `GPU` -> `CPU`), and dynamic Qualcomm Hexagon HTP hardware probe (`QualcommNpuProfile.kt`, `QualcommNpuRegistry.kt`) detecting Snapdragon 8 Elite (SM8750 / HTP v79, 45 TOPS) and Snapdragon 8 Gen 3 (SM8650 / HTP v75, 45 TOPS). Architecture documented in `docs/NPU_INTEGRATION_PLAN.md`. In-place AR screen typography overhaul in `ArTranslateOverlayView` (Approach 2): zero-card direct Skia Canvas inpainting erasing original text bounds with 48-point perimeter sampling and 15% trimmed-mean outlier rejection, strict per-line left-edge anchoring (`textOffsetX = srcRect.left`) without horizontal centering drift, line-level `ArLinePill` geometry, line-height proportional font scaling (`11sp–16sp` clamp, 0.72 line height ratio), column-aware downward elastic collision avoidance ($\ge 25\%$ horizontal overlap threshold) with gap compression ($4\text{dp} \to 2\text{dp}$), universal safe bounds coercion (`safeCoerceIn`) resolving inverted-range crashes, resilient session token isolation in `ScreenTranslateOverlayService`, and auto-resetting `FloatingBubbleView` state machine.
- **Screen Text Reconstruction, Interactive In-Place AR & Resilience (Phase X)**: Total architectural stabilization of on-device Screen Translation. Replaced OCR dependency and fragile heuristic clusterers with 100% native vector OS node parsing (`NativeScreenTextExtractor`) supporting `TYPE_APPLICATION` filtering, leaf-node priority (eliminating duplicate container text), semantic `contentDescription` icon filtering, and topological reading-order line banding (Y-band + left-to-right). Completely bypassed `ArBoundingBoxClusterer` for native accessibility nodes to preserve exact pixel bounds and eliminate multi-line text "каша". Added selective screen text noise filtering (`ScreenTextNoiseFilter`) automatically eliminating solitary timestamps (`12:45`), battery levels (`98%`), standalone numbers/dates, URLs, file paths, and stray symbols while preserving natural conversational text. Integrated Google Circle to Search / Apple Liquid Glass visual styling with full-frame silent screenshot freeze, edge glow halo frame (`LensScanEffectView`, `ArTranslateOverlayView`), and smooth tap-to-dismiss alpha fade. Implemented an interactive Liquid Glass top bar with Source (`Auto ▼` / language code) and Target (`RU ▼`) chips, quick-swap button `⇄` with 180° rotation animation, instant 0ms candidate re-translation (`retranslateCurrentScreen`) without re-capturing screenshots or A11y tree walking, global `[Translate ⇄ Original]` toggle, and individual pill `Hide` micro-action. Hardened fault tolerance with a lease-based self-healing mutex breaking stale locks (>10s), execution timeouts (8s Fast / 15s LLM), a 10s watchdog handler runnable, busy-bubble tap recovery (`recoverToIdle`), `ACTION_CANCEL` drag reset in `FloatingBubbleView`, and 2.5s auto-reset from `COMPLETE` to `IDLE`.
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

The original Parlex release key was lost after the workstation rebuild. The key
used for `v1.5.1-beta.5` is also unavailable on the current build machine.

`v1.5.1-beta.7` is signed with a newly generated 2026 Parlex release key:

- Subject: `CN=Ilia Vlasov, OU=Parlex, O=RandoTeam, L=Moscow, C=RU`
- Certificate SHA-256:
  `FF:D6:14:86:FD:4C:D7:68:B3:0A:AD:02:76:CE:B4:1D:2C:6B:1C:B9:D9:2F:E6:AB:23:F5:50:B8:EF:37:20:0A`
- Alias: `parlex`

Android treats this as another signing-key rotation without lineage. Users
with `v1.5.1-beta.5` or older builds must uninstall the old app before
installing beta.6; local data is lost unless exported first.

The matching keystore and `keystore.properties` are stored outside the
repository. Back them up before the next release; do not publish a build signed
with a debug key.

Do not upload `app-debug.apk` as a beta or release.

## Restore After Clean Clone

Tracked source does not include generated outputs, local model files, native
engine checkouts, or signing secrets.

Restore when needed:

```powershell
git clone https://github.com/RandoTeam/Parlex.git
cd Parlex

# OpenCL headers + link stub + exact llama.cpp revision from llama.cpp.version
powershell -ExecutionPolicy Bypass -File scripts/setup-native.ps1
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
