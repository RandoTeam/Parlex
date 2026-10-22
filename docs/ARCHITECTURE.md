# Architecture

## High-level shape

Parlex is a single-activity Android app with a Compose UI, Hilt DI, Room
persistence, CameraX/MediaProjection capture and a JNI boundary to local
inference engines.

```
Compose screens / overlay views
        |
ViewModels (state, orchestration, cancellation)
        |
Repositories / engines (Room, Settings, model catalog, translation, OCR, STT, TTS)
        |
+----------------------+----------------------+---------------------+
| ML Kit (NMT/OCR/ID)  | llama.cpp / ggml     | Sherpa-ONNX / TTS   |
| on-device packages   | GGUF + OpenCL        | Whisper / VAD       |
+----------------------+----------------------+---------------------+
```

## Main packages

| Package | Responsibility |
|---|---|
| `ui/screens` | Text, camera, dialogue, history, models, settings |
| `ui/viewmodel` | State machines, capture/translation orchestration, cancellation |
| `ui/overlay` | Screen AR overlay, floating bubble, scan effect, trash/docking |
| `service` | Accessibility, MediaProjection capture, foreground overlay service |
| `engine` | Translation, OCR, language ID, speech, TTS, model download, export |
| `engine/camera` | OCR clustering, document regions, tracking and prompts |
| `data` | Settings, Room entities/DAOs, model repository, dictionaries |
| `di` | Hilt modules and database construction |
| `cpp` | JNI bridge, llama.cpp/ggml, optional MNN/OCR bridge and OpenCL stub |

## Critical flows

### Text

`TranslationScreen` -> `TranslationViewModel` -> `TranslationEngine` /
`FastTranslateEngine` -> Room history. Selected policy decides Fast NMT,
Fast+LLM or LLM-only.

### Camera

`CameraScreen` -> `CameraViewModel` -> CameraX `ImageAnalysis`/`ImageCapture`
-> `OcrEngine` -> language detection -> translation policy -> painted bitmap or
live `TranslationOverlay`. Gallery uses the same capture pipeline with forced
auto language detection.

### Screen translation

`ScreenAccessibilityService` receives the shortcut -> optional silent
screenshot + accessibility nodes -> `ScreenTranslateOverlayService` ->
`CandidateMerger` -> translation -> `ArTranslateOverlayView`; the floating
bubble toggles/dismisses the overlay. MediaProjection is a fallback capture path.

### Voice

`DialogueScreen` -> `DialogueViewModel` -> `SpeechEngine` (AudioRecord + VAD +
sherpa/Qwen recognition) -> translation -> `SystemTtsEngine`; echo suppression,
audio focus and turn timing are coordinated by the ViewModel.

## Threading and lifecycle

- UI and overlay mutation on the main thread.
- OCR, decoding, model work and file IO on `Dispatchers.IO`/`Default`.
- One active capture job per surface; cancellation must be rethrown, not turned
  into an error state.
- The screen translation mutex is owned by the job that acquired it; watchdog
  recovery releases a stale lock only when no job is active.
- MediaProjection requires a foreground service of type `mediaProjection` to be
  running before `getMediaProjection()` on Android 14+.
- The floating bubble must not be re-added above the full-screen overlay; hide it
  while the overlay is open and reset its touch state on restore.

## Native boundary

- `app/src/main/cpp/llama.cpp.version` and `MNN.version` are tracked pins.
- `scripts/setup-native.ps1/.sh` restores the checkouts, OpenCL headers/stub and
  clears stale `.cxx`; the release APK must contain a full `libtranslive.so`.
- `libOpenCL.so` is a link-time stub, never packaged; the device driver supplies
  it through `<uses-native-library>`.
- Optional MNN/PP-OCR is compiled only with `-WithMnn`; do not claim it is active
  in a build that contains the capability stub.

## Architectural rules

1. No direct UI calls from engines; expose state/flows.
2. No blocking calls on the main thread in capture, OCR or translation paths.
3. Pair-specific ML Kit translation should use explicit source/target APIs, not
   the global current translator.
4. Per-box text layout must be clipped to the source rectangle; a later box may
   not paint over an earlier translation.
5. New native engines must be optional at build time and documented in
   `THIRD_PARTY_NOTICES.md`.
6. New model downloads need a source URL, size, license and SHA-256 (or an
   explicit fail-closed policy).
