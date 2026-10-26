# Quality Gates & Definition of Done

## Required gates

| Gate | When | Evidence |
|---|---|---|
| Compile | every code change | `:app:compileDebugKotlin` |
| Unit tests | every code change | `:app:testDebugUnitTest` |
| Lint | every code change | `:app:lintDebug`, 0 errors |
| Debug APK | UI/camera/voice/screen changes | `:app:assembleDebug` |
| Native build | JNI/CMake/dependency changes | native CI job or local `assembleRelease` |
| Signed release | beta/release | `apksigner verify`, version badging, SHA-256 |
| Device QA | feature completion | `docs/DEVICE_QA.md` checklist |
| Docs sync | every beta | README, PROJECT_STAGE, HANDOFF, release notes |
| Privacy | every beta/release | no personal name, phone, email, certificate subject or fingerprint in public docs, release notes, tags or APK metadata; APK SHA-256 only |
| License check | new dependency/model/asset | `THIRD_PARTY_NOTICES.md`, `docs/LEGAL.md` |

## Definition of Done

A task is done only when:

1. The user-visible behavior is specified and implemented.
2. Cancellation, empty state, permission denial and error state are handled.
3. The narrowest relevant checks pass; release-risk changes get broader checks.
4. No secret, model weight, APK/AAB or generated artifact is committed.
5. New dependencies/models have a license and source.
6. User text/audio is not uploaded.
7. Docs and release notes are updated when the public behavior changes.
8. The relevant device scenario is either verified or explicitly marked
   **device-pending** in the report.

## Current verified baseline (2026-10-26)

- 69 suites / **466 tests / 0 failures**.
- Lint: **0 errors**, 391 warnings.
- Public CI: unit tests, lint, debug APK and native build green.
- Release APK: v2 signature verified; full `libtranslive.so`.
- Device QA: not yet performed for beta.10.
- `main` is ahead of the beta.10 tag with the S10 reliability pass; no beta.11
  APK yet.

## Known quality gaps

- `CameraViewModelLifecycleTest` and several overlay tests are shadow harnesses;
  they do not construct production classes.
- No `androidTest` migration coverage.
- No bitmap-diff test for rendered overlay export.
- TalkBack virtual nodes and 48 dp targets are incomplete.
- Model SHA-256 coverage is partial.
- Screen/camera behavior on OEM skins remains device-only.

## Release blockers

- Build failure, lint error, or a failing unit test.
- Unsigned/incorrectly signed APK or version mismatch.
- Known P0 crash/data-loss/security regression.
- Release notes missing user-visible changes or device checks.
- Personal name, phone, email, certificate subject or fingerprint in public
  release notes/docs/tags.
- Missing license notice for a new dependency/model.

Device-only gaps do not block a beta if they are explicitly listed, but they do
block promotion to a stable release.
