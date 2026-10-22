# Parlex v1.5.1-beta.6 (Build 15106)

Recovery/beta stabilization release. This build contains the 2026-10-21 audit
fixes, the follow-up reliability pass, and the independent verification report.

## Important: signing key changed

This APK is signed with a new 2026 Parlex release key. The key used for
`v1.5.1-beta.5` is no longer available on the build machine.

Android will reject this APK as an update over `v1.5.1-beta.5` or older builds.
**Uninstall the previous Parlex app before installing beta.6.** Local history,
dialogues and recordings will be lost unless they were exported first.

The new key is stored outside the repository. Back it up before the next
release.

## What changed since beta.5

### Build, tests and release

- `d421a30`, `d3dbdd2` - reproducible Gradle/native setup, OpenCL link stub,
  fixed wrapper and CI workflow with unit tests and lint.
- `2101c79`, `b9dd1b1`, `e6cdcf2` - audit, recovery plan, handoff/status docs
  and independent role-based verification.
- `d473217` - concurrency-safe engine fallback test.

### Screen translation

- `85819dd` - translations fit inside the original text blocks instead of
  shifting over neighbouring UI; erase masks stay anchored; mixed-language
  blocks use explicit translator pairs; accessibility-shortcut diagnostics.
- `62b8abc`, `fe7d4b4`, `88b8d2c` - bounded translation calls, stale-session
  cancellation, rotation/window-change dismissal, rendered-overlay export and
  lint-clean quality pass.
- `44d42ab` - removed direct accessibility key filtering (privacy/Play risk);
  the system accessibility shortcut remains the supported trigger. Also fixed
  zip-slip in fast-model import.

### Camera

- `3668cb9` - crop-aware OCR, 1280x720 analysis request, no `runBlocking` in
  the live path and per-region document painting.
- `3a6be5a` - zero-copy ML Kit path with viewport coordinate remapping for
  rotation 90/180/270; removed the per-frame JPEG round-trip.

### Voice

- `ea775c3` - ASR decode decoupled from microphone reads, TTS timeout/watchdog,
  AEC/noise suppression/audio focus and measured VAD durations.
- `3a6be5a` - bounded segment queue, recording finalization, deferred native
  recognizer release and per-utterance callbacks.

### Data and security

- `74bf0b7` - explicit Room migrations 1 -> 5 instead of destructive migration;
  bounded per-pair Fast NMT translator pool.
- `44d42ab` - projection foreground-service type starts before
  `getMediaProjection()` on Android 14+, `allowBackup=false`, Assist trigger
  action whitelist, accessibility service no longer declared as an
  accessibility tool.
- `app/schemas/.../5.json` - exported Room schema for future migration tests.

## Validation

- Unit tests: **70 suites / 470 tests / 0 failures**.
- Lint: **0 errors** (`abortOnError=true`); 386 warnings.
- Debug APK: builds successfully.
- Release APK: signed with the new 2026 key; `apksigner` verified v2.
- Native engine: full `llama.cpp` pinned at
  `b94041a98ec86e29e63b02decd29175d6820b2a1`, linked into
  `libtranslive.so` (about 8 MB in the APK); OpenCL backend enabled with the
  device-supplied `libOpenCL.so`, and the link stub is not packaged.

## What to verify on a device

1. Install after uninstalling beta.5; confirm the app starts and onboarding works.
2. Screen translation: accessibility shortcut, overlay layout, rotation, window
   change dismissal, mixed-language blocks and "Save".
3. Android 14+/16 MediaProjection consent and foreground-service notification.
4. Camera: live crop, 4:3/16:9 preview, rotation, document capture and LiteRT
   fallback with an ML-Kit-unsupported pair.
5. Voice: microphone permission, AEC during TTS, long CJK utterances, recording
   finalization and playback.
6. Privacy: accessibility disclosure, no key filtering, backup disabled.
7. Database: clean install, then upgrade from a future beta to exercise the
   exported schema and migrations.

## Known limitations

- No physical-device acceptance was performed for this build. Screen capture,
  camera rotation, TalkBack, TTS CJK and OEM accessibility shortcuts remain
  device-only checks.
- `androidTest` migration tests are not implemented yet; migration SQL is
  statically reviewed and schema v5 is exported, but not exercised by
  `MigrationTestHelper`.
- TalkBack virtual nodes for translated overlay blocks and 48 dp touch targets
  are still pending.
- SHA-256 coverage is incomplete for most catalog/STT models.
- GitHub Actions is currently blocked by the account billing failure, not by
  workflow code.

## Integrity

- APK: `Parlex-v1.5.1-beta.6-release.apk`
- Size: 122,563,212 bytes (116.9 MB)
- SHA-256: `baf615c3f468368ea236cfb02318ee39db05df76df5c43deeaa969b1c22ec95a`
- Signature: APK Signature Scheme v2, new 2026 Parlex release key
- Certificate: `CN=Ilia Vlasov, OU=Parlex, O=RandoTeam, L=Moscow, C=RU`
- Certificate SHA-256: `ffd61486fd4cd768b30aad0276ceb41d2c6b1cb9d92fe6ab23f550b8ef37200a`
- Package: `com.translive.app`
- `versionCode`: 15106
- `versionName`: `1.5.1-beta.6`
- `minSdk`: 26, `targetSdk`: 36, ABI: `arm64-v8a`
