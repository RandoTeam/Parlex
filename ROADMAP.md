# Parlex Roadmap

This is the priority and phase-order source of truth. Historical completed
phases are preserved in `docs/HISTORICAL_ROADMAP_2026.md`; they are not a promise
that the current build has every feature.

**Current baseline:** `v1.5.1-beta.10` public release; `main` is 4 commits ahead
with an unreleased screen-performance pass. CI green, 460 unit tests, no device
acceptance for beta.10.

## Rules

1. One active phase at a time; finish, verify, document, commit.
2. P0 (crash/data loss/security) interrupts any planned phase.
3. A beta may ship with device-pending items only if the release notes list
   them explicitly.
4. A stable release requires the device matrix in `docs/DEVICE_QA.md`.
5. No new phase starts while the prior phase has an open P0/P1 blocker.

## Horizon A — Stabilize the public beta (now, 0-2 weeks)

**Active phase: S10.** Camera acceptance (C10) starts only after S10 exit
criteria are met, because S10 still has open P1 items. Working checklist:
`docs/S10_SCREEN_RELIABILITY_2026-10-26.md`.

### S10. Screen translation reliability — ACTIVE

**Outcome:** repeated shortcut and floating-bubble usage are stable, Save is
safe, and first paint is measurably faster.

- [ ] 10 consecutive shortcut translations without a stale lock/no-op.
- [ ] Hardware back or explicit close always works; no focus trap.
- [ ] Save never crashes during overlay teardown.
- [ ] Verify foreground-service type does not downgrade with projection attached.
- [ ] Confirm scan/bubble are not captured into saved screenshots.
- [ ] Dismiss on a real foreground package change, not on every window event.
- [ ] FAST_ONLY and FULL_SCAN match the settings copy.
- [ ] Record trigger → first frame timings in `logcat`.

**Exit:** screen scenarios in `docs/DEVICE_QA.md` pass on at least one device.

### C10. Camera acceptance and live-overlay correctness — NEXT

**Outcome:** camera is predictable for a normal user, not just in unit tests.

- [ ] Verify live translation overlay positioning on 16:9 and 4:3 previews.
- [ ] Add tap-to-inspect behavior or remove the unused hit-target layer.
- [ ] Verify gallery English/Japanese/Chinese + rapid picks on a real device.
- [ ] Measure live OCR/translation frame budget.
- [ ] Fix any OEM-specific lens/zoom mapping found.

**Exit:** device QA for camera scenarios passes or each failure has an issue and
an explicit workaround.

### D10. Migration and data integrity

**Outcome:** upgrading users keep history and recordings.

- [ ] Add `androidTest` `MigrationTestHelper` tests for 1→2, 2→3, 3→4, 4→5.
- [ ] Add a fresh-install schema validation test.
- [ ] Verify audio paths/session statistics after upgrade.

**Exit:** migration tests run in CI/device and green; no destructive fallback.

### L10. Legacy code retirement

**Outcome:** remove unreachable pipelines and misleading test coverage.

- [ ] Remove or explicitly archive `LiveScreenTranslateService` +
  `LiveOverlayRenderer` + models if continuous mode is not planned.
- [ ] Remove/retire `ScreenCaptureService`, FileProvider and one-shot URI path.
- [ ] Keep only `DragToDismissCalculator` from `ScreenOverlayLifecycleComponents`.
- [ ] Delete or rewrite shadow tests.
- [ ] Remove unused strings/DAO methods/ProGuard keeps.

**Exit:** dead-code audit has no P0 entries; tests still green.

### A10. Accessibility baseline

**Outcome:** core controls are usable without sight and with reduced motion.

- [ ] TalkBack virtual nodes for translated screen/camera text.
- [ ] 48 dp minimum for overlay/camera actions.
- [ ] Respect animator scale / reduced motion.
- [ ] Verify no focus theft by overlay.

**Exit:** accessibility checklist passes on one device.

## Horizon B — Quality and speed (1-2 months)

### C11. Camera quality and benchmark

- Build a reproducible camera benchmark: menus, signs, documents, screenshots,
  mixed scripts, low light.
- Measure OCR accuracy, translation quality, latency and memory.
- Tune OCR preprocessing and paragraph grouping from data.
- Decide whether document reflow returns.

### D11. Quiet Glass design system

- Apply `docs/DESIGN_SYSTEM.md` tokens to camera controls, live cards and screen
  overlay chrome.
- Add reduced-motion and 48 dp acceptance tests.
- Capture before/after screenshots on the device matrix.
- Do not expand the theme to every screen until camera/screen pass device QA.

### S11. Screen quality and privacy

- Improve in-place typography and edge-aware masks.
- Add per-block TalkBack and selection.
- Boundary-aware overlay dismissal (package/window identity, not a timer).
- Verify no private banking/2FA surface is captured unexpectedly.

### V11. Voice continuity

- Streaming partials or visibly bounded latency.
- Stronger VAD/AEC/turn-taking on real devices.
- TTS stop/cancel and long-CJK acceptance.
- Recording timeline integrity across process death.

### M11. Model integrity and storage

- SHA-256 for every catalog/STT model or fail-closed import.
- Resumable download validation (Content-Range/ETag/size).
- Storage cleanup for screenshots and deleted dialogue audio.
- Model license metadata in the catalog UI.

### P11. Performance and packaging

- Cold-start budget, camera frame budget, overlay first-draw budget.
- Native/APK size audit.
- Baseline profiles and macrobenchmark where possible.
- Battery/thermal test for screen and dialogue sessions.

## Horizon C — Product depth (quarter)

### C12/G12. Semantic memory (EmbeddingGemma)

- On-device embedding index for history, dictionary and translation memory.
- Explicit opt-in; no cloud embedding.
- Evaluate retrieval quality against a deterministic benchmark.
- Keep storage bounded and deletable.

### H12. Hardware acceleration

- Safe NPU/GPU selection on Snapdragon.
- Fallback ladder measured, not guessed.
- Compare NPU vs GGUF vs LiteRT with thermal/battery data.

### E12. Export and continuity

- PDF/TXT/overlay export with stable file naming.
- Android 26-28 storage path.
- Import/export of settings and fast packs with validation.

### R12. Rust/C++ decision review

- Profile pure-CPU hot paths.
- Compare C++/NEON before Rust.
- Do not add a second toolchain without measured benefit.

## Stable-release gates

Before `1.6.0` / `2.0.0`:

- Camera/screen/voice/text device matrix passes.
- Migration tests green.
- No known P0/P1 crash, data loss or privacy issue.
- Model integrity policy documented and enforced.
- Legal/third-party notices reviewed.
- README, PROJECT_STAGE, HANDOFF, release notes and roadmap synchronized.

## Backlog (not scheduled)

- Tablet/foldable overlay layout.
- Additional OCR scripts and models.
- More languages/dialects.
- Cross-platform companion.
- Community translation packs.

Backlog items enter a horizon only after the current phase is verified.
