# Parlex Developer Handoff

This document is the standalone transfer note for continuing Parlex without
depending on previous chat history.

> **2026-10-21 recovery:** read
> [`docs/AUDIT_AND_RECOVERY_PLAN_2026-10-21.md`](docs/AUDIT_AND_RECOVERY_PLAN_2026-10-21.md)
> first. It lists verified defects, the phased plan and acceptance criteria.
> CI is expected to be green after the first baseline pass; native GPU builds
> are restored by `scripts/setup-native.ps1`.

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
- Latest public signed beta: `v1.5.1-beta.7`
- App package: `com.translive.app`
- Current app version in Gradle: `1.5.1-beta.7`, `versionCode` 15107
- Supported ABI: `arm64-v8a`
- Native translation runtime: `llama.cpp` through JNI
- Stable model format: GGUF
- Experimental model format: LiteRT-LM beta

Current release signing state:

- The original release signing key was not available after the workstation
  rebuild.
- `v1.5.1-beta.5` was signed with the previous 2026 Parlex key; that key is no
  longer available on this build machine.
- `v1.5.1-beta.7` is signed with a new 2026 Parlex release key:
  `CN=Ilia Vlasov, OU=Parlex, O=RandoTeam, L=Moscow, C=RU`
  (certificate SHA-256 `FF:D6:14:86:FD:4C:D7:68:B3:0A:AD:02:76:CE:B4:1D:2C:6B:1C:B9:D9:2F:E6:AB:23:F5:50:B8:EF:37:20:0A`).
- Users with `v1.5.1-beta.5` or older APKs must uninstall the old app before
  installing beta.6. Android will reject the update because the signing key
  changed.
- The new keystore and `keystore.properties` are stored outside the repository.
  Back them up before the next release.
- Do not use debug APKs as releases.

## What Is Implemented

- **In-Place AR Screen Translation & Visual Overhaul (Phase Z)**: Elimination of visual fragmentation, calques, dark halos, and recursion:
  1. *State Resilience & Zero-Crash Dismissal*: Patched infinite mutual recursion between `ScreenTranslateOverlayService` and `ArTranslateOverlayView` (28,000+ frame `StackOverflowError`) via unconditional dismissal state locking and reference nulling before cascade invocation (`ArOverlayDismissalRecursionTest.kt`).
  2. *Semantic Node Hierarchy Reconstruction (Substring Coverage Inversion)*: Overhauled `NativeScreenTextExtractor` with coverage-ratio analysis. Purged partial child fragment leaf-nodes (`<b>Android</b>`, `<b>Police</b>`) when enclosed by a cohesive paragraph covering $< 70\%$ text length, preserving natural card sentence structure and full layout width ($1254\text{ px}$).
  3. *Brand & Proper Noun Entity Shield*: Implemented `BrandEntityShield` preserving high-tech brand names (`Android Police`, `Android`, `DuckDuckGo`, `YouTube`, `Google`, `Valnet`) with zero-latency token shielding and post-NMT restoration, eliminating literal sci-fi calques (*"Человекоподобный робот"*). Filtered search breadcrumbs and URL noise in `ScreenTextNoiseFilter`.
  4. *Zero-Drift Inpainting & Halo Elimination*: Decoupled `eraseRect` from collision downward offsets in `ArRenderBox` (anchoring erase mask strictly to original screenshot bounds), removed 45% alpha `eraseFringePaint` stroke framing halos, tuned 48-point perimeter sampling to 25% trimmed-mean outlier rejection with neutral `#F01E1E1E` surface fallback, disabled hyphenation (`HYPHENATION_FREQUENCY_NONE`) to prevent broken syllabic splits (*"По-лиция"*), and introduced dedicated per-box `TextPaint` instances with bold headline hierarchy (`Typeface.BOLD` + `#FFFFFF`) and readable body text (`#D1D1D6`).
  5. *Monotonic Column-Aware Collision Engine*: Replaced adjacent-only collision checks with a global monotonic scan across all preceding items sharing column overlap ($\ge 20\%$) and spacing-preserving upward headroom containment, preventing bottom-screen crushing and multi-column interleaving skips (`ArElasticRelaxationTest.kt`).
- **Qualcomm Snapdragon NPU Hardware Offload & In-Place AR Typography (Phase Y & X.9-12)**: Direct on-device NPU hardware execution using LiteRT Qualcomm NPU delegate (`Backend.NPU(context.applicationInfo.nativeLibraryDir)`), 3-tier fallback ladder (`NPU` -> `GPU` -> `CPU`), and dynamic Qualcomm Hexagon HTP hardware probe (`QualcommNpuProfile.kt`, `QualcommNpuRegistry.kt`) detecting Snapdragon 8 Elite (SM8750 / HTP v79, 45 TOPS) and Snapdragon 8 Gen 3 (SM8650 / HTP v75, 45 TOPS). Architecture documented in `docs/NPU_INTEGRATION_PLAN.md`. In-place AR screen translation overhaul in `ArTranslateOverlayView` (Approach 2): zero-card direct Skia Canvas inpainting erasing original text bounds with 48-point perimeter sampling and 15% trimmed-mean outlier rejection, strict per-line left-edge anchoring (`textOffsetX = srcRect.left`) without horizontal centering drift, line-level `ArLinePill` geometry, line-height proportional font scaling (`11sp–16sp` clamp, 0.72 line height ratio), column-aware downward elastic collision avoidance ($\ge 25\%$ horizontal overlap threshold) with gap compression ($4\text{dp} \to 2\text{dp}$), universal safe bounds coercion (`safeCoerceIn`) resolving inverted-range crashes, resilient session token isolation in `ScreenTranslateOverlayService`, and auto-resetting `FloatingBubbleView` state machine.
- **Screen Text Reconstruction, Interactive In-Place AR & Resilience (Phase X)**: Direct vector parsing via `NativeScreenTextExtractor` (`TYPE_APPLICATION`, leaf node priority, semantic icon label filtering, topological line banding) eliminating multi-line "каша" without OCR clustering; selective noise filtering (`ScreenTextNoiseFilter`) removing isolated timestamps, battery levels, URLs, pure digits, and symbols; interactive Liquid Glass top bar with Source (`Auto ▼` / code) and Target (`RU ▼`) chips, quick-swap button `⇄` with 180° rotation, 0ms in-place candidate re-translation (`retranslateCurrentScreen`) without re-capturing screenshots or A11y tree walking, global `[Translate ⇄ Original]` toggle, individual pill `Hide`, lease-based self-healing mutex (>10s lock breaker), 8s/15s coroutine timeouts, and 10s watchdog handler runnable.
- **Zero-Latency Screen Translation & Volume Key Decoupling (Phase W)**: High-speed zero-dialogue screen translation pipeline with direct `AccessibilityNodeInfo` BFS vector text extraction (`extractVisibleTextNodesFast`), 1-pass delimited batch translation (`FastBatchTranslator`), volume shortcut decoupling (`ScreenA11yShortcutBehavior`: `SINGLE_SHOT_NO_BUBBLE` vs `TOGGLE_FLOATING_BUBBLE`), and offline travel pack download hardening with automatic Whisper/VAD STT bundling.
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

# Restores the OpenCL headers/link stub and the exact llama.cpp revision
# recorded in app/src/main/cpp/llama.cpp.version.
powershell -ExecutionPolicy Bypass -File scripts/setup-native.ps1
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
   & "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
   ```

6. Inspect APK version with Android build tools.
7. Verify the SHA-256 and attach the APK as `Parlex-v<version>-release.apk`.
8. Commit and push `main`.
9. Create GitHub prerelease and upload the signed APK asset.
10. Release notes must describe user-facing functionality and device checks; keep
    the internal command log in `docs/`.

Recommended next beta version: `v1.5.1-beta.8` after beta.7.

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
