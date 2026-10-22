# Contributing

Thanks for helping improve Parlex. External pull requests are welcome, but the project keeps maintainer review and release control.

## Pull Request Rules

- Keep one pull request focused on one topic.
- Separate i18n, UI, CI, build, dependency, model catalog, and release-process changes.
- Do not change the Gradle wrapper unless the PR is specifically about the wrapper and explains why.
- Do not add release automation unless it was discussed first. Signed beta and release APKs stay under manual maintainer control.
- Keep model IDs, filenames, URLs, sizes, and licenses stable unless the PR is explicitly about the model catalog.

## Checks

Before opening a PR, run the narrowest useful local check. For Android changes, prefer:

```bash
./gradlew assembleDebug
```

On Windows:

```bat
gradlew.bat assembleDebug
```

If native engine files are missing, restore `app/src/main/cpp/llama.cpp/` from the commit recorded in `app/src/main/cpp/llama.cpp.version`.

## Privacy, security and licensing

- Do not upload user text, screenshots, audio, keystores, tokens or model files in an issue or PR.
- New dependencies/models must include their upstream source and license in `THIRD_PARTY_NOTICES.md` / `docs/LEGAL.md`.
- Never commit `keystore.properties`, `.jks`, APK/AAB files or generated model packages.
- Security issues go through `SECURITY.md`, not public issues.

## CI

The public repository runs `.github/workflows/android-check.yml` on pushes and
pull requests: unit tests, lint, debug APK and a native llama.cpp/OpenCL build.

