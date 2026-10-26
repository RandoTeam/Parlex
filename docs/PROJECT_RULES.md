# Project Rules

These are the operating rules for maintainers and AI agents. They replace
earlier agent-specific files. The repository owner may override a rule in a
direct request, but privacy and release-integrity rules are non-negotiable.

## Mission

Ship a privacy-first, offline Android translator whose text, camera, screen and
voice surfaces are predictable on real devices, with honest status reporting and
reproducible releases.

## Roles

An agent may act as one or more roles. State the role in the report.

- **Architect**: boundaries, data flow, ADRs, non-goals.
- **Android engineer**: Kotlin/Compose/CameraX/services/JNI integration.
- **QA**: reproduction, device matrix, regression coverage.
- **Security/privacy reviewer**: permissions, capture, intents, secrets, model
  integrity, personal data.
- **Release engineer**: versioning, signing, APK verification, prerelease.
- **Technical writer**: README, docs, release notes, legal/third-party notices.
- **Designer**: content hierarchy, motion, accessibility, Quiet Glass tokens.

## Privacy and personal data

- Never publish a maintainer's personal name, address, phone number, email,
  certificate subject, keystore path or other personal data in release notes,
  README, docs, issue templates or APK metadata.
- Public release identity is the project: **RandoTeam / Parlex**.
- A release certificate subject must be organizational and generic, never a
  person's legal name. Do not print certificate subject or fingerprint in public
  release notes; publish the APK SHA-256 instead.
- Do not publish keys, `keystore.properties`, `.jks`, passwords or private
  contact data.
- Existing signed artifacts cannot be retroactively anonymized. If a key subject
  contains personal data, plan a signing-key rotation explicitly and document
  the uninstall requirement.
- Do not upload user text, screenshots, audio, camera frames or model files to a
  server or issue tracker.

## Autonomy

Allowed without asking, when it directly serves the request:

- read/search the repository and run builds/tests/lint;
- edit code, tests and docs;
- create focused commits and push `main` when the user asked to continue or
  publish;
- build and verify a signed beta when a generic release key is present;
- update GitHub metadata/docs when the user asked for repository presentation;
- clean verified dead code when no production references exist.

Ask before:

- rotating/replacing the release signing key or changing signing behavior;
- destructive database migration or deleting user data paths;
- deleting a large subsystem/legacy feature rather than isolating it;
- force-push, history rewrite, tag/asset replacement;
- adding telemetry, accounts, cloud translation or broad new permissions;
- adding a new native toolchain (for example Rust) or a large dependency.

Never:

- commit secrets, keystores, tokens, APKs/AABs, model weights or personal data;
- publish a debug APK as a beta/release;
- fake device QA or claim unverified behavior passed;
- disable tests/lint/security checks to make CI green;
- introduce hidden network calls or user-text upload;
- copy GPL code into the repository; independently reproduce ideas/protocols only.

## Phase workflow

1. Pick one phase from `ROADMAP.md`.
2. For architectural work, write/update a design note or ADR.
3. Implement surgically, preserving user data and existing public behavior.
4. Run the narrowest check, then broader checks for release risk.
5. Update docs/release notes if behavior changes.
6. Commit code and docs separately when practical.
7. Report changed files, validation, remaining risks and next executable step.

One phase per commit group. Do not mix unrelated surfaces.

## Validation matrix

| Change | Minimum |
|---|---|
| Kotlin/doc-only | compile + affected unit tests |
| UI/camera/screen/voice | unit tests + lint + assembleDebug |
| JNI/CMake/model | native build + release APK inspection |
| Release | tests + lint + assembleRelease + apksigner + badging + SHA-256 |
| Data/schema | migration tests or explicit P0 warning until they exist |
| Dependency/model | license/source/SHA update in notices |

A green unit suite is not proof that overlay/camera/voice production paths work;
shadow tests must be named as a risk, not hidden.

## Git and release

- `main` must stay buildable.
- Use focused conventional commits, for example `fix(camera): ...`.
- Do not revert user changes without explicit instruction.
- Never rewrite published tags or silently replace release assets.
- A beta publication sequence is in `docs/RELEASES.md`.
- Release notes must include user-visible changes, validation, APK integrity,
  **what to verify on a device**, and known limitations.
- Do not include personal signing data in release notes.

## Documentation

- `docs/INDEX.md` is the canonical map.
- `docs/INTRODUCTION.md` is the short project introduction.
- `ROADMAP.md` decides priority.
- `docs/PIPELINE.md` decides process.
- `docs/QUALITY_GATES.md` decides done.
- `PROJECT_STAGE.md` is the latest verified snapshot.
- `HANDOFF.md` must let a new engineer build without chat history.
- Dated audits/old plans are historical; link them, do not treat them as current.
- Update README/PROJECT_STAGE/HANDOFF and release notes on every beta.
- New dependency/model/asset requires a license/source update in
  `THIRD_PARTY_NOTICES.md` and `docs/LEGAL.md`.

## Security and privacy

- Prefer local processing; no telemetry or user-text upload.
- Validate intents, URIs, archive extraction, file paths and model downloads.
- Keep `allowBackup=false` unless a migration/backup design is accepted.
- Accessibility/MediaProjection privileges must be disclosed and user-enabled.
- Report vulnerabilities privately via `SECURITY.md`.

## Reporting

Final report format:

```text
Changed:
Files:
Validation:
Notes:
What to verify:
Next:
```

`Next` should contain concrete options the user can accept with `go`.
Device-only work must be explicitly marked as device-pending.
