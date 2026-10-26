# Parlex v1.5.1-beta.11 (Build 15111)

S10 screen-reliability pass: session lock, Save safety, package-aware overlay
dismissal, timing logs and a finite capture animation.

## Important: signing-key rotation

beta.11 is signed with a new organizational `RandoTeam` release key. The old
key's certificate subject contained personal data and is no longer used.

- **Uninstall beta.10 before installing beta.11.** Android will reject an
  in-place update because the signing certificate changed.
- Uninstalling removes app-local history, settings and downloaded app data.
  Export anything you need first.
- The old key is retained locally outside the repository for reference only; it
  will not sign future builds.
- Certificate subject and fingerprint are intentionally not published. Verify
  the APK with the SHA-256 below.

## Screen translation

- The translation mutex is now session-owned. A cancelled job can no longer
  unlock the session that replaced it.
- The watchdog timeout (20 s) now outlives the longest pipeline timeout
  (15 s for LLM, 8 s for fast NMT), so LLM screen translation is not killed by
  the watchdog first.
- Save renders the composite on the main thread while the overlay is alive and
  exports from an immutable snapshot. Closing the overlay during Save no longer
  races the live view.
- The overlay is dismissed only when the foreground package actually changes.
  Parlex's own overlay and IME/keyboard windows are ignored; opening an in-app
  dialog or keyboard should no longer make the translation disappear.
- FAST_ONLY settings copy now says "accessibility first" and documents the OCR
  fallback when no accessibility nodes exist.
- Added `ScreenPerf` logcat timings for nodes, capture, OCR, translation,
  overlay add and first frame. No user text is logged.
- The capture animation is now two finite 900 ms sweeps followed by a quiet
  perimeter pulse while OCR/translation is still running. Reduced-motion devices
  skip the animation.

## Privacy and repository

- Removed the personal Kaspi phone number from `README.md`.
- Added a public-donation rule: project links/wallet addresses are allowed;
  personal names, phone numbers, emails and home addresses are not.
- Added privacy gates to `QUALITY_GATES.md` and `RELEASES.md`.
- Release notes and public docs no longer contain certificate subject or
  fingerprint data.

## Validation

- **466 tests / 0 failures**, 69 suites.
- Lint: **0 errors**, 391 warnings.
- Public GitHub Actions run `40342674210` is green for unit tests, lint, debug
  APK and native llama.cpp/OpenCL build (release code commit).
- Tag commit CI run `40344494945` is green for the same checks.
- `:app:assembleRelease` green.
- APK contains `lib/arm64-v8a/libtranslive.so`; no OpenCL stub packaged.
- Signed with APK Signature Scheme v2 using the new organizational key.
- Device QA: **not performed for this build**. All screen behavior below is
  device-pending.

## What to verify on a device

1. Uninstall beta.10, install beta.11, complete onboarding.
2. Run 10 consecutive screen-shortcut translations, closing the overlay between
   them. No no-op, stale lock or wrong-language result.
3. Open the keyboard and an in-app dialog while the overlay is visible. The
   overlay should stay; switch to another app and it should dismiss.
4. Hardware back and edge-swipe: record which one closes the overlay. The X
   button must always work.
5. Save immediately after opening the overlay and immediately before closing:
   no crash, and the saved image contains translations.
6. FAST_ONLY vs FULL_SCAN on grey/raster text; record `ScreenPerf` timings from
   logcat.
7. Scan animation: two sweeps, then a quiet pulse; it should stop on error or
   timeout and never appear in the saved image.
8. LLM screen translation: confirm it can exceed 10 s without the watchdog
   closing the overlay.

## Known limitations

- No physical-device acceptance was performed.
- Hardware back on a non-focusable overlay remains unproven; X/edge-swipe are
  the guaranteed close paths until device evidence says otherwise.
- Progressive first paint for FULL_SCAN is deferred until device timings show
  OCR/translation is the dominant cost.
- TalkBack virtual nodes, migration instrumentation tests and full model
  SHA-256 coverage remain pending.
- Legacy `LiveScreenTranslateService` and `ScreenCaptureService` paths are still
  retained pending the L10 cleanup decision.

## Integrity

- APK: `Parlex-v1.5.1-beta.11-release.apk`
- Size: 122,563,136 bytes (116.9 MB)
- SHA-256: `2094143447e16273b1841131ef3764abf5a2743a1d5492c577e386330d671082`
- Signature: APK Signature Scheme v2 (new organizational `RandoTeam` release
  key; certificate details intentionally not published)
- Package: `com.translive.app`
- `versionCode`: 15111
- `versionName`: `1.5.1-beta.11`
- `minSdk`: 26, `targetSdk`: 36, ABI: `arm64-v8a`
