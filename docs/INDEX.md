# Documentation Index

This is the canonical map for Parlex. Read the current-state documents first;
dated audits and design notes are historical context unless a current document
links to them.

## Start here

| Document | Purpose |
|---|---|
| [INTRODUCTION.md](INTRODUCTION.md) | Short project introduction and current state |
| [PRODUCT_VISION.md](PRODUCT_VISION.md) | Why Parlex exists, target users, product principles and non-goals |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Code map, data flows, native boundaries and threading model |
| [../ROADMAP.md](../ROADMAP.md) | Near/mid/long-term phases with exit criteria |
| [PIPELINE.md](PIPELINE.md) | Issue -> design -> implementation -> beta -> device QA -> release |
| [QUALITY_GATES.md](QUALITY_GATES.md) | Definition of Done, checks and known test gaps |
| [DEVICE_QA.md](DEVICE_QA.md) | Device matrix and manual acceptance scenarios |
| [RELEASES.md](RELEASES.md) | Versioning, signing, artifacts and release checklist |
| [OPEN_QUESTIONS.md](OPEN_QUESTIONS.md) | Decisions not yet frozen |
| [LEGAL.md](LEGAL.md) | Code/model/license boundaries |
| [../PRIVACY.md](../PRIVACY.md) | Data handling and network endpoints |
| [../SECURITY.md](../SECURITY.md) | Private vulnerability reporting |
| [PROJECT_RULES.md](PROJECT_RULES.md) | Operating rules for maintainers and AI agents |

## Current snapshot

- [../PROJECT_STAGE.md](../PROJECT_STAGE.md) - last verified build, APK, SHA,
  service state and immediate risks.
- [../HANDOFF.md](../HANDOFF.md) - clean-clone onboarding for a new engineer or
  agent.
- [../README.md](../README.md) - public project page and installation.

## Architecture decision records

- [adr/README.md](adr/README.md)
- [adr/0001-camera-pipeline.md](adr/0001-camera-pipeline.md)
- [adr/0002-rust-not-now.md](adr/0002-rust-not-now.md)
- [adr/0003-public-repository-and-mit.md](adr/0003-public-repository-and-mit.md)

## Active design notes

- [S10_SCREEN_RELIABILITY_2026-10-26.md](S10_SCREEN_RELIABILITY_2026-10-26.md) — active screen reliability/speed plan
- [CAMERA_REDESIGN_2026-10-22.md](CAMERA_REDESIGN_2026-10-22.md)
- [SCREEN_OVERLAY_DESIGN_2026-10-22.md](SCREEN_OVERLAY_DESIGN_2026-10-22.md)
- [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
- [SEMANTIC_MEMORY_PLAN_2026-10-21.md](SEMANTIC_MEMORY_PLAN_2026-10-21.md)
- [DEAD_CODE_AUDIT_2026-10-22.md](DEAD_CODE_AUDIT_2026-10-22.md)

## Historical documents

These are kept for traceability, not as current truth:

- `AUDIT_AND_RECOVERY_PLAN_2026-10-21.md`
- `VERIFICATION_2026-10-22.md`
- `DEPENDENCY_*`, `RUNTIME_*`, `PERFORMANCE_PROFILE_RESET.md`
- old screen/camera plans and phase specs
- `RELEASE_NOTES_v1.5.1-beta.*.md`
- `docs/superpowers/**`

## Source-of-truth rules

1. `ROADMAP.md` decides priority and phase order.
2. `docs/PIPELINE.md` decides how work moves and when it may ship.
3. `docs/QUALITY_GATES.md` decides whether a phase is done.
4. `PROJECT_STAGE.md` is the latest verified snapshot, not a wish list.
5. `docs/PROJECT_RULES.md` defines agent autonomy and mandatory behavior.
6. If two documents disagree, fix the older one or mark it historical; do not
   silently follow stale claims.
