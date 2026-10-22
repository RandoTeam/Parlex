# Development & Release Pipeline

## Operating model

Trunk-based development with short-lived work branches is acceptable; `main`
must stay buildable. One roadmap phase per commit group. A release is a signed
snapshot of `main`, not a separate development line.

## Stages

### 0. Triage

- Reproduce the issue or define the user outcome.
- Classify: P0 crash/data loss/security, P1 broken user flow, P2 polish.
- Link the roadmap phase and affected surface.
- For a new architectural feature, write a short design note first.

### 1. Design

- Update or add a design note in `docs/` for camera, screen overlay, voice,
  model runtime or storage changes.
- Record alternatives and the decision in `docs/adr/` if the choice is long-lived.
- Define what is explicitly out of scope.

### 2. Implementation

- Keep the change surgical; no unrelated rewrites.
- Preserve user data and existing public behavior unless the phase says otherwise.
- Handle cancellation, empty state, errors and permission denial.
- Do not add telemetry, hidden network calls or secrets.

### 3. Local gates

Run the narrowest useful check first:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Then, for release-risk changes:

```bash
./gradlew :app:assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk
```

### 4. Beta

- Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
- Update `README.md`, `PROJECT_STAGE.md`, `HANDOFF.md`, and the release notes.
- Build the signed release APK; verify the certificate and version.
- Commit code first, then docs/release notes.
- Push `main`, create an annotated tag and a GitHub **prerelease** with the APK.
- Record SHA-256 and size in the release notes.

### 5. Public CI

The public workflow (`android-check.yml`) must pass unit tests, lint, debug APK
and the native llama.cpp/OpenCL build. A beta may be published before device QA,
but the release notes must state that device acceptance is pending.

### 6. Device QA

Use `docs/DEVICE_QA.md`. A phase is not fully done until the relevant scenarios
pass on at least one real device. Capture logs with no user text when reporting.

### 7. Promotion

Promote a prerelease to a normal release only after the device matrix and
rollback plan are satisfied. Never reuse a tag or overwrite a release asset.

## Commit contract

Use focused conventional commits, for example:

```
feat(camera): restore live overlay
fix(screen): reset floating bubble touch state
docs: publish beta.10 notes
chore(ci): pin action versions
```

One phase per commit group. If a commit mixes unrelated surfaces, split it.

## Versioning

- `versionCode` increments for every published beta/release.
- `versionName` follows `1.5.1-beta.N` during beta, then `1.5.1` for a stable.
- Tags: `v<versionName>`.
- APK asset: `Parlex-v<versionName>-release.apk`.

## Release key policy

The release key is stored outside the repository. Never commit
`keystore.properties`, `.jks`, passwords or signed artifacts. If the key is
lost, treat the next build as a signing-key rotation, document the required
uninstall and back up the new key before publishing.

## Rollback

- Keep the previous release asset and tag intact.
- If a bad beta is found, publish a new higher `versionCode`; do not delete the
  old tag or silently replace the APK.
- For data migrations, ship a forward fix or an explicit export path; never
  reintroduce destructive migration without a documented decision.

## Agent roles

Agents may act as architect, Android engineer, QA, security reviewer, release
engineer and technical writer. The role must be stated in the work report. A
release requires a separate final validation pass even if implementation and
review were done by the same agent.
