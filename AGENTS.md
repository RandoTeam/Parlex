# Codex Rules For This Repository

These rules are part of the project state and should survive a clean clone.

## Working Contract

- Treat requests as product-engineering work. Make concrete repository changes when the user asks to build, fix, improve, release, or implement.
- Keep changes surgical. Do not rewrite unrelated code or reformat files without need.
- Prefer existing project patterns over new abstractions.
- Use `rg`/`rg --files` for search.
- Use `apply_patch` for manual source/document edits.
- Do not revert user changes unless explicitly asked.
- Always validate with the narrowest useful checks, then broader checks when release risk is high.

## Android Project Rules

- Keep secrets and generated artifacts out of git: `keystore.properties`, APK/AAB files, Gradle build folders, local models, diagnostics, and native engine checkouts are ignored.
- The local `llama.cpp` checkout is restored separately under `app/src/main/cpp/llama.cpp/`; keep `app/src/main/cpp/llama.cpp.version` as the tracked source of truth.
- Use official upstream sources for dependency, model, and native-engine audits.
- For release builds, verify the signed APK with `apksigner` and inspect `versionCode` / `versionName` before publishing.
- Release notes should describe user-visible functionality, not internal dependency details.

## Git And Release Rules

- Keep feature work in focused commits.
- Before a GitHub release: bump `versionCode`, bump `versionName`, update visible version docs/badges, build signed release APK, verify signature, push `main`, then create a prerelease/tag with the APK asset.
- Do not commit restore-only files that are reproducible locally.

## Documentation And Stage Lifecycle

- Maintain documentation as the living source of truth for the codebase:
  - `PROJECT_STAGE.md`: Update on every release or major phase completion (snapshot date, commit HEAD, `versionCode`/`versionName`, public APK SHA-256, feature statuses, and next maintainer action).
  - `README.md`: Keep latest public signed release, system requirements, and setup commands synchronized.
  - `HANDOFF.md`: Keep standalone and up to date so a new engineer or agent can onboard and build cleanly without relying on prior chat history.
  - `ROADMAP.md`: Enforce the single-phase-at-a-time rule. Keep phases granular (`R*`, `C*`, `L*`, `M*`, `P*`) and check off items only after verification.
  - `docs/*_PLAN_*.md`: Create or update detailed technical plans before executing complex architectural features (Camera, Screen Overlay, GPU/NMT).
  - `docs/DEPENDENCY_*.md` & R&D notes: Record dated dependency/runtime audits, benchmark results, and hold rationales.
  - `THIRD_PARTY_NOTICES.md`: Update when adding dependencies, fonts, models, or native engines. Strictly enforce GPL code isolation (ideas/protocols may be independently reproduced; GPL code must never be copied into the repository).
  - `app/src/main/cpp/*.version`: Keep exact 40-character upstream commit SHAs for native submodules (`llama.cpp.version`, `MNN.version`).

## Work Stages And Execution Contract

- Enforce one phase per commit. Complete, verify, and document each phase before moving forward.
- Keep changes surgical. Do not touch unrelated code or reformat arbitrarily.
- Always validate with the narrowest useful checks first (e.g. `:app:compileDebugKotlin`, unit tests), followed by full assemble and APK signature checks when relevant.

## Communication

- Final responses must remain dry, concise, and purely technical:
  - `Changed:`
  - `Files:`
  - `Validation:`
  - `Notes:`

## Skills And Plugins

- Codex skills/plugins are installed globally on the workstation, not vendored into this repo.
- Use available GitHub tooling or `gh` for issue/release work.
- Use official documentation/source links when checking unstable current versions.

