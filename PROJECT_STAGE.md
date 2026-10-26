# Project Stage Snapshot

Snapshot date: 2026-10-26

> Current-state document. Historical audits and old feature claims are in
> `docs/AUDIT_AND_RECOVERY_PLAN_2026-10-21.md`,
> `docs/VERIFICATION_2026-10-22.md` and `docs/HISTORICAL_ROADMAP_2026.md`.
> Use `docs/INDEX.md` for the canonical map.

## Repository

- Remote: `https://github.com/RandoTeam/Parlex.git`
- Visibility: **public**
- Branch: `main`
- Latest public beta: `v1.5.1-beta.11`
- Tag: `v1.5.1-beta.11` (release code commit `b3726aa`; the tag includes the
  final release-notes/docs commit `ad26d0f`)
- Current `main` HEAD: `bbfed27` (post-release docs/plan updates)
- APK asset: `Parlex-v1.5.1-beta.11-release.apk`
- APK SHA-256:
  `2094143447e16273b1841131ef3764abf5a2743a1d5492c577e386330d671082`

## App version

- `versionCode`: 15111
- `versionName`: `1.5.1-beta.11`
- `compileSdk` / `targetSdk`: 36 / 36
- `minSdk`: 26
- ABI: `arm64-v8a`

## Verified state

- Unit tests: **466 / 0 failures** (69 suites).
- Lint: **0 errors**, 391 warnings.
- Public CI: run `40342674210` green for unit tests, lint, debug APK and native
  llama.cpp/OpenCL build; tag commit run `40344494945` green.
- Release APK: v2 signature verified; full `libtranslive.so`; OpenCL stub not
  packaged.
- Device QA: **not performed for beta.11**.
- License: MIT application code; third-party/model terms in
  `THIRD_PARTY_NOTICES.md` and `docs/LEGAL.md`.
- Signing: beta.11 uses a new generic organizational `RandoTeam` key.
  beta.6–beta.10 use the old key whose certificate subject contained personal
  data. beta.10 and older require uninstall before installing beta.11. Do not
  publish the old subject or fingerprint.
- Unreleased on `main`: none after the beta.11 release commit. The beta.11 APK
  is the current public prerelease.

## Product surfaces

| Surface | State |
|---|---|
| Text translation | Stable; local NMT/LLM + history |
| Camera | Beta; live overlay restored, gallery language detection fixed, device QA pending |
| Screen translation | Beta; S10 lock/Save/window fixes landed, device QA pending |
| Voice dialogue | Beta; TTS timeout/AEC/ASR decoupling landed, acoustic tuning pending |
| Models | Stable UI; integrity coverage partial |
| Legal/repo | Public, MIT, privacy/security/issue templates in place |

## Immediate risks

1. Camera live-overlay placement and OEM zoom/lens behavior are unverified on a
   device.
2. Screen overlay hardware back/focus behavior plus Save teardown race remain P1.
3. No migration instrumentation tests.
4. Shadow tests still give false confidence.
5. TalkBack virtual nodes and 48 dp targets are incomplete.
6. Model SHA-256 coverage is incomplete.
7. Legacy `LiveScreenTranslateService`/`ScreenCaptureService` paths remain.

## Next action

Follow `ROADMAP.md` Horizon A. **S10 screen reliability is the active phase**;
C10 camera acceptance follows S10. The working checklist is
`docs/S10_SCREEN_RELIABILITY_2026-10-26.md`. beta.11 is built; publish it after
CI, then collect device QA. Do not start Horizon B until Horizon A exit criteria
are met.

## Canonical links

- `docs/INDEX.md`
- `docs/PRODUCT_VISION.md`
- `docs/ARCHITECTURE.md`
- `docs/PIPELINE.md`
- `docs/QUALITY_GATES.md`
- `docs/DEVICE_QA.md`
- `docs/RELEASES.md`
- `ROADMAP.md`
- `HANDOFF.md`
