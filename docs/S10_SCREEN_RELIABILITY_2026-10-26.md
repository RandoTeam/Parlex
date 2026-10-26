# S10 — Screen Reliability & Speed Plan

Status: **active**  
Started: 2026-10-26  
Owner role: Android engineer / QA / technical writer

This is the living plan for the screen-translation stabilization pass. It is
the working checklist for `ROADMAP.md` phase S10. Update it after every
meaningful step; do not leave completed work undocumented.

## Goal

Make repeated screen translation predictable on a real device, reduce
time-to-first-translation, and finish the capture animation without hiding
known limitations.

## Scope

In scope:

- session-owned translation lock and watchdog correctness;
- Save/teardown safety;
- overlay dismissal when the underlying app/window changes;
- back/close behavior and honest documentation of what is guaranteed;
- FAST_ONLY vs FULL_SCAN semantics and settings copy;
- local pipeline timing logs (no telemetry, no user text);
- progressive first paint where it is safe;
- finite scan + processing pulse animation;
- device QA checklist and release notes.

Out of scope for this pass:

- continuous Auto-Live screen mode;
- edge-aware inpainting;
- TalkBack virtual nodes for every translated block (A10);
- Room migration instrumentation tests (D10);
- legacy service removal (L10);
- signing-key rotation decision (release step; see `PROJECT_STAGE.md`).

## Verified starting point

- `main` = `63695a8`; beta.10 is the latest published APK.
- `main` already contains unreleased screen performance changes.
- 460 unit tests / 0 failures, lint 0 errors, no device acceptance.
- beta.10 APK is still signed with the old personal-subject certificate.

## P1/P2 findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| S10-1 | P1 | Old job can unlock a new session's mutex in `finally` | planned |
| S10-2 | P1 | 10 s watchdog cancels an LLM path that allows 15 s | planned |
| S10-3 | P1 | Any `TYPE_WINDOW_STATE_CHANGED` dismisses the overlay | planned |
| S10-4 | P1 | Save can race overlay teardown and export an untranslated frame | planned |
| S10-5 | P1 | Hardware back is unproven on a non-focusable overlay | device-pending |
| S10-6 | P2 | FAST_ONLY copy says "accessibility only" but OCR fallback exists | planned |
| S10-7 | P2 | No pipeline timing, so "faster" cannot be proven | planned |
| S10-8 | P2 | No progressive first paint for FULL_SCAN/large screens | planned |
| S10-9 | P2 | Infinite scan sweep conflicts with the design-system motion rule | planned |

## Steps

### Step 0 — Documentation and privacy sync

- [x] Create this living plan.
- [x] Remove the personal phone number from `README.md` or document a narrow
  public-donation exception in `PROJECT_RULES.md`.
- [x] Update `PROJECT_STAGE.md` to the current HEAD and unreleased state.
- [x] Make S10 the active phase in `ROADMAP.md`; C10 follows S10.
- [x] Refresh stale OCR/scan statements in `OPEN_QUESTIONS.md` and
  `SCREEN_OVERLAY_DESIGN_2026-10-22.md`.
- [x] Add privacy/release gates to `QUALITY_GATES.md` and `RELEASES.md`.
- [x] Add hardware-back/window-dialog scenarios to `DEVICE_QA.md`.
- [x] Add this plan to `docs/INDEX.md`.

### Step 1 — Session lock and watchdog

- [ ] Make the mutex owned by a session token; `finally` releases only its own
  token.
- [ ] Add a forced recovery path that cannot be undone by an older job.
- [ ] Align watchdog with the longest pipeline timeout.
- [ ] Add pure unit tests for lock ownership/recovery.
- [ ] Document the lock model in `ARCHITECTURE.md` if behavior changes.

### Step 2 — Save and teardown

- [ ] Render the composite on the main thread while the overlay is alive.
- [ ] Export only from an immutable bitmap/boxes snapshot.
- [ ] Recycle only owned copies; never recycle the frozen source twice.
- [ ] Add a teardown/save regression test where practical.

### Step 3 — Window dismissal and back

- [ ] Pass source package/class from the accessibility event.
- [ ] Dismiss only when the foreground package actually changes; ignore our own
  package and IME windows.
- [ ] Keep a short guard after showing the overlay.
- [ ] Add pure policy tests.
- [ ] Verify back/edge-swipe on a device; document the guaranteed close paths.

### Step 4 — Speed and progressive paint

- [ ] Add `screen_perf` logs for trigger, capture, nodes, OCR, translation and
  first frame.
- [ ] Add a fast first paint from accessibility candidates when FULL_SCAN will
  also run.
- [ ] Update the overlay after OCR refinement without a second full-screen flash.
- [ ] Measure on a device; record numbers in this file.

### Step 5 — Animation

- [ ] Replace the infinite sweep with a finite sweep followed by a quiet
  processing pulse.
- [ ] Stop all animators on reset/error/timeout.
- [ ] Respect reduced motion.
- [ ] Confirm scan/bubble are never captured into Save output.

### Step 6 — Validation and beta.11

- [ ] `compileDebugKotlin`, unit tests, lint, debug APK.
- [ ] Device QA for screen scenarios.
- [ ] Decide signing key (rotate vs keep) before beta.11.
- [ ] Bump version, build signed APK, verify, publish notes.
- [ ] Update `PROJECT_STAGE.md`, `HANDOFF.md`, `README.md` and release notes.

## Validation log

| Date | Check | Result |
|---|---|---|
| 2026-10-26 | Documentation audit | complete; 5 stale docs and one privacy issue found |
| 2026-10-26 | Baseline unit tests | 460 / 0 failures |
| 2026-10-26 | Baseline lint | 0 errors |

## Device QA pending

No device is attached to the workstation. All screen timing, back, Save,
MediaProjection and OEM window behavior remains device-pending until the user
runs the checklist in `docs/DEVICE_QA.md` and returns `logcat`.

## Decisions

- Keep `FLAG_NOT_FOCUSABLE` until a device proves back is broken; do not
  reintroduce focus theft without evidence.
- Prefer package-change dismissal over a timer. IME and our own package must
  not dismiss the overlay.
- Save must never read the live overlay from a background thread.
- FAST_ONLY means "accessibility first"; OCR is a fallback when no nodes exist.
  FULL_SCAN explicitly adds raster OCR.
