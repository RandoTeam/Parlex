# Camera & Screen Capture Redesign - 2026-10-22

This note records the camera UX and pipeline decisions for beta.7+. It is
code-level design, not device acceptance.

## Problems observed

1. Gallery import could show a stale manual source (`Russian`) while OCR
   internally fell back to English. The UI then claimed `Russian -> English`
   and the result looked broken.
2. A gallery image did not feel immediate: the photo was decoded, then OCR,
   language detection and model activation happened without a strong capture
   state.
3. The old camera screen exposed every logical/physical lens as a horizontal
   chip strip plus flash, badges, language bar and translation-mode segmented
   control. It read like a diagnostics panel, not a camera.
4. Still capture and live OCR had different language assumptions; auto mode was
   off by default for camera settings.

## Camera UX direction

The reference is the restraint of OnePlus Camera / Pixel Camera, not a copy of
their visuals:

- full-bleed preview;
- top: one compact glass row with source language, swap, target language, and
  a single translation-mode chip; flash is a separate circular control;
- bottom: three large zoom targets (`0.6x`, `1x`, `3x`), a single orange shutter,
  gallery on the left, subtitles on the right;
- no horizontal strip of every camera module;
- no beta/diagnostics badges in the normal capture path;
- warnings appear contextually and disappear when resolved.

The orange shutter is intentionally the only saturated control in the camera
surface. It is the primary action; the rest of the controls use neutral glass.

## Language and capture pipeline

- Gallery imports always run auto language detection for the capture, even if
  the user has a manual source preference for live mode. Arbitrary photos are
  not guaranteed to be in the remembered language.
- If detected source equals the selected target, the target is inverted to the
  user's native language (or English/Russian fallback). This prevents an
  English photo from becoming a no-op `English -> English` while the UI says
  `Russian -> English`.
- The detected source is written back to the capture state and displayed in the
  source chip; the previous manual label can no longer lie about the OCR pass.
- Capture status is set immediately (`Opening photo` -> `Looking for text` ->
  `Translating page`) so the user gets feedback before OCR/model work.
- One capture job owns the pipeline; a new gallery choice cancels the old job
  before the new bitmap is decoded.

## Live camera

- Live OCR remains frame-budgeted and viewport-aware (zero-copy ML Kit path).
- Auto mode uses the last detected script as a hint but still probes
  periodically; a manual source is respected for live mode until the user
  changes it.
- Zoom selection is restricted to the three useful optical anchors rather than
  every exposed camera ID. Additional lenses remain available internally for
  fallback, but are not a wall of chips.

## Rust evaluation

Decision: **do not rewrite the camera, OCR, or UI layer in Rust now.**

Reasons:

- CameraX, MediaProjection, Compose and ML Kit are Android/JVM APIs; Rust would
  only add a JNI boundary and duplicate lifecycle code.
- The heavy native work is already in C/C++: llama.cpp, ggml/OpenCL,
  sherpa-onnx/ONNX Runtime and ML Kit native pipelines. Replacing Kotlin with
  Rust would not remove the dominant model/runtime cost.
- A Rust toolchain would add NDK/ABI/build reproducibility work across arm64,
  release signing and CI for no measured gain.
- The current camera bugs are state/language/UI bugs, not JVM compute bugs.

Revisit Rust only if a profiled hot path is pure CPU and isolated from Android
APIs, for example perspective correction/inpainting or a cross-platform
benchmark library. Even then, compare a C++/NEON implementation first.

## Device checks for this phase

1. Gallery: English photo with Russian source selected; source chip must become
   English/Auto and the result must translate into a useful target.
2. Gallery: Japanese/Chinese/Cyrillic photos; detection and target inversion.
3. Rapid gallery picks: old job must not overwrite the new image.
4. Live zoom 0.6/1/3x, flash availability, shutter, gallery and subtitle
   buttons in one-hand reach.
5. Still capture and live subtitle mode after the UI changes.
6. Warning/error chips must not cover the top language row.
