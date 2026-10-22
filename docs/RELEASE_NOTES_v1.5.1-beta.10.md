# Parlex v1.5.1-beta.10 (Build 15110)

Public-repository release: camera live-overlay polish, legal/repository
packaging, and CI verification on the public repo.

## Camera / UI

Code commit: `bca3bf9` - live-overlay glass cards and beta.10 version.

- Live camera translation cards now use a darker glass gradient with a thin
  white rim instead of flat black boxes.
- Line spacing in live translation cards is slightly increased for readability.
- Camera top/bottom layout remains the beta.9 design: one compact
  source/target/mode row, separate flash, 0.6/1/3x zoom pills and orange
  shutter.
- No backend/model changes in this release.

## Repository & legal

- Added `PRIVACY.md`: local processing, permissions, network endpoints and data
  retention.
- Added `SECURITY.md`: private vulnerability reporting through GitHub Security
  Advisories.
- Added `CODE_OF_CONDUCT.md`.
- Added issue templates for reproducible bug reports and feature requests, with
  privacy checks.
- Added `docs/LEGAL.md` with the code/model/license matrix and disclaimer.
- Cleaned `LICENSE` to the standard MIT text; model terms now live in
  `THIRD_PARTY_NOTICES.md` and `docs/LEGAL.md`.
- Documented the vendored Sherpa-ONNX AAR, Tesseract traineddata and optional
  MNN/PP-OCR build.
- Added CI/license/security links to the README and GitHub topics/description.

## Validation

- **460 tests / 0 failures**, 68 suites.
- Lint: **0 errors**.
- Public GitHub Actions run `39817933416` is green for unit tests, lint, debug
  APK and native llama.cpp/OpenCL build.
- Release APK signed with the same key as beta.6-beta.9.

## What to verify

1. Live camera: are translated cards readable over the preview and correctly
   positioned?
2. Gallery: English/Japanese/Chinese image, source/target chips, no stale
   language pair.
3. Rapid gallery picks: no old result overwrites the new image.
4. Camera top/bottom controls: 0.6/1/3x, orange shutter, flash, warnings.
5. Repeated screen shortcut and floating button.
6. About: beta.10 and Android 16 (API 36).

## Known limitations

- No physical-device acceptance was performed.
- Live overlay positioning remains device-resolution dependent.
- Continuous legacy screen pipeline and the legacy ScreenCaptureService path
  are still retained pending product decisions.
- TalkBack virtual nodes, migration instrumentation tests and full model
  SHA-256 coverage remain pending.

## Integrity

- APK: `Parlex-v1.5.1-beta.10-release.apk`
- Size: 122,562,872 bytes (116.9 MB)
- SHA-256: `abe26eb4907079940d6680ec7686026f26a983e87062e247fb8528a76ea6e340`
- Signature: APK Signature Scheme v2
- Certificate: `CN=Ilia Vlasov, OU=Parlex, O=RandoTeam, L=Moscow, C=RU`
- Certificate SHA-256: `ffd61486fd4cd768b30aad0276ceb41d2c6b1cb9d92fe6ab23f550b8ef37200a`
- Package: `com.translive.app`
- `versionCode`: 15110
- `versionName`: `1.5.1-beta.10`
- `minSdk`: 26, `targetSdk`: 36, ABI: `arm64-v8a`
