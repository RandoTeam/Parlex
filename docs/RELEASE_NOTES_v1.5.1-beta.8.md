# Parlex v1.5.1-beta.8 (Build 15108)

Camera workflow and camera UI pass. Same release key as beta.7: installs over
beta.7 without uninstalling or losing data.

## Camera language and gallery fixes

Code commit: `bc7294a` - camera UI redesign and gallery language detection.

- Gallery imports now run auto language detection instead of inheriting a stale
  manual source. An English photo no longer reports `Russian -> English`.
- If the detected source equals the selected target, the target is inverted to
  the user's native language (or English/Russian fallback), so an English photo
  does not become a no-op `English -> English`.
- The detected source is written back into the capture UI and shown in the
  source chip, even when the user previously had a manual source selected.
- Capture status is visible immediately: opening photo -> finding text ->
  translating page. A new gallery pick cancels the old translate job.
- Added structured logs `Capture language: requested=..., auto=..., detected=...,
  target=...` for future device diagnostics (no user text).

## Camera UI redesign

- Removed the top strip of every logical/physical camera lens.
- Top is now one compact glass row: translation mode, source, swap, target;
  flash is a separate circular control.
- Bottom is now camera-like:
  - zoom targets `0.6x / 1x / 3x` where the device exposes them;
  - a single orange shutter with a white rim;
  - gallery on the left and subtitle mode on the right.
- Quality/NMT warnings are contextual and no longer occupy the primary control
  row.
- Added a content description for the shutter and kept the existing 48 dp+
  interactive targets for the main camera actions.

## Engineering decision: Rust

CameraX, MediaProjection, Compose, ML Kit and the current native stack
(llama.cpp/ggml, OpenCL, sherpa-onnx/ONNX Runtime) are already split across JVM
and C/C++. Rewriting camera/OCR/UI in Rust would add a JNI boundary, a second
NDK toolchain and more release/CI risk without removing the dominant model
runtime cost. Rust is therefore **not** introduced in this pass. Future work
should profile a pure-CPU hot path first; if one exists, compare C++/NEON before
adding Rust. Details: `docs/CAMERA_REDESIGN_2026-10-22.md`.

## Validation

- Unit tests: **70 suites / 470 tests / 0 failures**.
- Lint: **0 errors**.
- Release build: signed, v2 verified.
- Native: full `libtranslive.so`, pinned `llama.cpp`, OpenCL backend.

## What to verify

1. Gallery: English photo while source is Russian. The source chip should show
   English/Auto, the target should be useful, and the result must translate
   immediately.
2. Gallery: Japanese, Chinese and Cyrillic photos; mixed-language image.
3. Pick two gallery images quickly; the first result must not overwrite the
   second.
4. Live camera: 0.6/1/3x zoom chips, flash, orange shutter, gallery, subtitles.
5. Capture a photo and confirm the painted translation/layout.
6. Confirm warnings do not cover the top language row.

## Known limitations

- No physical-device acceptance was performed for this build.
- Camera UI behavior on OEM camera HALs still needs Pixel/Samsung/OnePlus
  validation.
- Instrumentation migration tests and TalkBack virtual nodes remain pending.
- SHA-256 coverage is incomplete for most catalog/STT models.
- GitHub Actions is blocked by account billing, not by workflow code.

## Integrity

- APK: `Parlex-v1.5.1-beta.8-release.apk`
- Size: 122,563,384 bytes (116.9 MB)
- SHA-256: `11cfe0a39c856f463ce26a3c12c856cd831669bd8f45f7490b80acebeb49d3bf`
- Signature: APK Signature Scheme v2
- Certificate: `CN=Ilia Vlasov, OU=Parlex, O=RandoTeam, L=Moscow, C=RU`
- Certificate SHA-256: `ffd61486fd4cd768b30aad0276ceb41d2c6b1cb9d92fe6ab23f550b8ef37200a`
- Package: `com.translive.app`
- `versionCode`: 15108
- `versionName`: `1.5.1-beta.8`
- `minSdk`: 26, `targetSdk`: 36, ABI: `arm64-v8a`
