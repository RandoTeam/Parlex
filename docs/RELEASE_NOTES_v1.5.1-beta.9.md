# Parlex v1.5.1-beta.9 (Build 15109)

Camera correctness pass plus verified dead-code cleanup. Same release key as
beta.6/beta.7/beta.8: installs in place.

## Camera fixes

Code commit: `02cc97f` - camera live overlay, gallery language detection and dead-code cleanup.

- Live camera translation is rendered again. `TranslationOverlay` was defined
  but never composed in LIVE mode; the viewmodel was OCRing and translating
  frames with no visible result.
- Auto camera translation no longer checks the global `isReadyFor` pair. It uses
  the requested pair directly, so an auto-detected pair can activate its
  installed ML Kit package instead of producing an empty/LLM-only overlay.
- Auto gallery OCR candidates now come from bundled OCR capabilities
  (Latin, Cyrillic, Chinese, Japanese, Korean, Devanagari, Arabic), not from
  downloaded translation packs. A Japanese/Chinese image is no longer penalized
  just because only RU/EN translation packs are installed.
- Gallery imports force auto source detection; the detected source is shown in
  the chip. Manual source is no longer overwritten by a capture fallback.
- Target inversion only applies to auto mode, is persisted in camera settings,
  and re-prepares the pair. An English photo with target English becomes a
  useful native-language translation instead of a no-op.
- Rapid gallery picks: cancellation is rethrown instead of becoming an ERROR
  state; `ensureActive()` checks guard the capture transition and final update.
  Old paragraphs/selection are cleared when a new capture starts or when
  returning to live.
- Live failure status is visible; capture job cancellation no longer leaves a
  stale `PROCESSING` state.
- Zoom labels now use `0.6x` for the ultra-wide bucket; selector includes the
  intended 0.6/1/3 anchors.

## Camera UI

- Clean top row: translation mode, source, swap, target, separate flash.
- Bottom: animated 0.6/1/3 zoom pills, orange shutter, gallery thumbnail,
  subtitles.
- Added gallery thumbnail from the last imported photo.
- Shutter has a content description; main camera actions meet 48 dp targets.
- Removed dead `CameraBetaBadge` and the old all-lens chip strip.

## Cleanup

- Removed unused Lottie dependency.
- Removed dead `translateScreenshot()`/`translateNodes()` service APIs.
- Removed unused `DictionaryViewModel`, `CameraTextInpainter`, download-metrics
  UI/tracker modules and their test suites.
- `BuildConfig` now supplies the About/export version; About no longer claims
  `1.5.0-beta.1` / `Build 15000` or Android 15/API 36.
- Fixed stale camera/build docs (HANDOFF wrapper/build-tools, signing rotation
  wording, README Kotlin/transliteration/PP-OCR/Tesseract claims).
- MNN version marker expanded to the full 40-character SHA; JNI header updated
  to the current llama.cpp pin.

## Validation

- Unit tests: **460 tests / 0 failures** (dead shadow/download suites removed).
- Lint: **0 errors**.
- Release build: signed, v2 verified.
- Native: full `libtranslive.so`, pinned `llama.cpp`, OpenCL backend.

## What to verify

1. Live camera: does translation appear inline over the preview at all?
2. Gallery: English photo with RU source; Japanese/Chinese photo; source chip
   and target pair; no immediate ERROR.
3. Two gallery picks quickly; first result must not overwrite second.
4. Zoom 0.6/1/3 chips and orange shutter; top row does not overlap warnings.
5. Switch capture -> live with no old paragraph bottom sheet.
6. About screen shows beta.9 and Android 16 (API 36).

## Known limitations

- No physical-device acceptance was performed.
- Live camera translation is now wired but its overlay placement still needs
  real-device visual tuning.
- Continuous legacy screen pipeline, ScreenCaptureService legacy path and
  test-only STT/Vision modules remain in the tree pending a product decision;
  they are documented as legacy/dead in the audit notes.
- TalkBack virtual nodes for camera painted text, migration instrumentation
  tests, and full SHA-256 coverage remain pending.

## Integrity

- APK: `Parlex-v1.5.1-beta.9-release.apk`
- Size: 122,562,876 bytes (116.9 MB)
- SHA-256: `640ad42f1b91160a6870a859f26ef830eded60d78c86f0d4344ee594963cfee6`
- Signature: APK Signature Scheme v2
- Certificate: `CN=Ilia Vlasov, OU=Parlex, O=RandoTeam, L=Moscow, C=RU`
- Certificate SHA-256: `ffd61486fd4cd768b30aad0276ceb41d2c6b1cb9d92fe6ab23f550b8ef37200a`
- Package: `com.translive.app`
- `versionCode`: 15109
- `versionName`: `1.5.1-beta.9`
- `minSdk`: 26, `targetSdk`: 36, ABI: `arm64-v8a`
