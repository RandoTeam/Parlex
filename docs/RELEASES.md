# Releases

## Channels

| Channel | Tag | APK | Meaning |
|---|---|---|---|
| Beta | `v1.5.1-beta.N` | prerelease | Public test build; device QA may be pending |
| Stable | `v1.5.1` | latest release | Signed, device-tested, release notes complete |

Only maintainers publish release assets. Agents may prepare a beta after the
user's explicit instruction.

## Checklist

1. Start from a clean `main` synchronized with `origin/main`.
2. Bump `versionCode` and `versionName`.
3. Update version strings, README badge, PROJECT_STAGE and HANDOFF.
4. Build `:app:assembleRelease` with the release keystore restored.
5. Verify:

   ```powershell
   & "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs `
     app/build/outputs/apk/release/app-release.apk
   & "$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe" dump badging `
     app/build/outputs/apk/release/app-release.apk
   Get-FileHash app/build/outputs/apk/release/app-release.apk -Algorithm SHA256
   ```

6. Confirm the APK contains the expected `libtranslive.so` and **not** the
   OpenCL stub.
7. Copy the asset as `Parlex-v<versionName>-release.apk`.
8. Commit code, then docs, push `main`.
9. Create an annotated tag and a GitHub prerelease with `--notes-file`.
10. Record size and SHA-256 in the release notes and `PROJECT_STAGE.md`.
11. Let public CI complete; attach the run URL to the report.

## Release notes format

- User-visible changes by surface (text, camera, screen, voice, privacy, build).
- Short commit grouping, not an internal changelog dump.
- Validation counts and APK integrity.
- **What to verify on a device** list.
- Known limitations and signing rotation notes.
- Never claim a device-only feature passed without evidence.

## Failure handling

- Do not move/delete a published tag.
- Do not replace an asset silently.
- Publish a higher `versionCode` with a fix.
- If a signing key is lost, document the rotation and required uninstall, back
  up the new key, and update `PROJECT_STAGE.md`/`HANDOFF.md`.
