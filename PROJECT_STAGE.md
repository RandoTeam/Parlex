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
- Latest public beta: `v1.5.1-beta.10`
- Last public release commit: `bca3bf9` (tag `v1.5.1-beta.10` includes the
  follow-up docs commit `53488b3`)
- Current `main` HEAD: `63695a8` (4 commits ahead of the beta.10 tag)
- APK asset: `Parlex-v1.5.1-beta.10-release.apk`
- APK SHA-256:
  `abe26eb4907079940d6680ec7686026f26a983e87062e247fb8528a76ea6e340`

## App version

- `versionCode`: 15110
- `versionName`: `1.5.1-beta.10`
- `compileSdk` / `targetSdk`: 36 / 36
- `minSdk`: 26
- ABI: `arm64-v8a`

## Verified state

- Unit tests: **460 / 0 failures** (68 suites).
- Lint: **0 errors**, 391 warnings.
- Public CI: unit tests, lint, debug APK and native llama.cpp/OpenCL build green.
- Release APK: v2 signature verified; full `libtranslive.so`; OpenCL stub not
  packaged.
- Device QA: **not performed for beta.10**.
- License: MIT application code; third-party/model terms in
  `THIRD_PARTY_NOTICES.md` and `docs/LEGAL.md`.
- Signing: beta.6–beta.10 use the same key. Its certificate subject still
  contains personal data; a rotation decision is pending. Do not publish the
  subject or fingerprint.
- Unreleased on `main`: screen performance changes (FAST_ONLY default, 900 ms
  scan loop) and documentation/privacy cleanup. No beta.11 APK exists yet.

## Product surfaces

| Surface | State |
|---|---|
| Text translation | Stable; local NMT/LLM + history |
| Camera | Beta; live overlay restored, gallery language detection fixed, device QA pending |
| Screen translation | Beta; repeated shortcut/bubble fixes landed, device QA pending |
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
`docs/S10_SCREEN_RELIABILITY_2026-10-26.md`. Do not start Horizon B until
Horizon A exit criteria are met.

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
