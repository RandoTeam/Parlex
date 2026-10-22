# Dead Code & Legacy Audit - 2026-10-22

Independent audits were run against the beta.8/beta.9 tree. This file records
what was verified dead, what was removed, and what is intentionally retained.

## Removed in beta.9

- `CameraBetaBadge` and the old all-lens `CameraHardwareControls` strip.
- Unused Lottie Compose dependency and its third-party notice.
- Dead `ScreenTranslateOverlayService.translateScreenshot()` and
  `translateNodes()` APIs.
- `ui/viewmodel/DictionaryViewModel.kt` (no screen instantiated it).
- `engine/camera/CameraTextInpainter.kt` (zero callers).
- `engine/download/DownloadMetricsComponents.kt` and
  `ui/components/download/UniversalDownloadComponents.kt` plus their
  test-only suites.
- Stale About version/build/target labels now read from `BuildConfig` and
  Android 16/API 36.
- Stale HANDOFF/README build and signing claims; MNN/JNI version markers.

## Verified dead, retained pending a product decision

These are not reachable from a supported production path, but their removal is
larger than a surgical cleanup:

- `service/LiveScreenTranslateService.kt`,
  `ui/overlay/LiveOverlayRenderer.kt`,
  `service/model/ScreenTranslateModels.kt` and the
  `ACTION_REQUEST_LIVE_TRANSLATE` branch in `MainActivity`.
- `service/ScreenCaptureService.kt`, its manifest/FileProvider declarations and
  the unused one-shot `ACTION_SHOW_TRANSLATION` path.
- Most helper types in `service/overlay/ScreenOverlayLifecycleComponents.kt`
  except the live `DragToDismissCalculator`; their three test suites are also
  test-only.
- `engine/stt/SttEngineComponents.kt` and
  `engine/vision/VisionLlmComponents.kt` are used only by tests. Keep if the
  multi-engine STT / vision roadmap is still active; otherwise remove them and
  their tests together.
- Small dead helpers: `PdfDocumentMetadata` rect helpers, unused DAO/repository
  methods, `CameraTravelModels`, and several unused strings/ProGuard keeps.

## Tests

- Shadow suites still exist: `CameraViewModelLifecycleTest`,
  `ScreenOverlayLifecycleTest`, `BackGestureInterceptionTest`,
  `BubbleAndFabMenuTest`, `ArOverlayRenderingTest`, `LensScanEffectTest`, and
  parts of the dialogue tests reimplement production behavior instead of
  constructing the production classes.
- Current real coverage is green: **460 tests / 0 failures**, **68 suites**,
  lint **0 errors**.
- Next test work should replace shadow suites with Robolectric/real
  `CameraViewModel`/service tests and bitmap-diff export tests.

## Next cleanup options

1. `go cleanup-live` - remove the unreachable continuous-live service/renderer
   and its tests.
2. `go cleanup-capture` - remove the legacy `ScreenCaptureService`, FileProvider
   and one-shot URI path.
3. `go cleanup-overlay-helpers` - keep only `DragToDismissCalculator` and delete
   the test-only overlay/FAB helper classes and tests.
4. `go cleanup-tests` - replace shadow harnesses with production-calling tests.
