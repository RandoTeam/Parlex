# Parlex

Parlex is an offline AI translator for Android. The project targets text
translation, two-way voice dialogue, and beta camera/photo translation without
sending user content to the network.

Latest public signed release: `v1.4.2`.

This beta is signed with a new 2026 release key. Devices that already have an
older Parlex APK signed with the previous key must uninstall the old build before
installing this one.

## Product Status

- Text translation: offline translation, source auto-detection, persisted
  direction, copy, speech output, history, and favorites.
- Dialogue mode: two-way speech flow with separate persisted direction.
- Camera beta: live camera, full-resolution capture, gallery image translation,
  OCR fallback, quality hints, torch, camera selection when Android exposes
  extra lenses, mixed-language capture path, and debug capture packs in debug
  builds.
- History: searchable translation history with favorites.
- Model manager: download, import, export, delete, and select translation models.
- Settings: interface language, CPU threads, backend choice, and model idle
  unload timeout.
- Interface localization: system language, English, Russian, Simplified Chinese,
  and Traditional Chinese.

## Translation Models

Models are not stored in this repository. They are downloaded or imported by the
app and remain subject to their own licenses.

Stable GGUF families:

- Tencent HY-MT 1.5 1.8B GGUF variants.
- Tencent Hy-MT2 GGUF variants.
- Google TranslateGemma 4B GGUF variants.

Experimental runtime:

- Google TranslateGemma LiteRT-LM is available as a separate beta path for
  device benchmarks. GGUF remains the stable route until LiteRT proves a real
  quality/speed advantage on target phones.

## Architecture

```text
Android Compose UI
  -> ViewModels
  -> repositories, settings, history database
  -> translation, OCR, speech, TTS, model download engines
  -> JNI bridge for llama.cpp GGUF inference
```

Important code areas:

- `app/src/main/kotlin/com/translive/app/ui/` - Compose screens and navigation.
- `app/src/main/kotlin/com/translive/app/ui/viewmodel/` - screen state and flows.
- `app/src/main/kotlin/com/translive/app/engine/` - translation, OCR, STT, TTS,
  downloads, and LiteRT beta engine.
- `app/src/main/kotlin/com/translive/app/data/` - settings, model repository, and
  Room database.
- `app/src/main/kotlin/com/translive/app/data/model/ModelCatalog.kt` - tracked
  model catalog metadata.
- `app/src/main/kotlin/com/translive/app/i18n/` - app locale support and
  localized text provider.
- `app/src/main/cpp/` - JNI and native build glue.

## Build Requirements

- Windows or Linux shell with Git.
- Android Studio / Android SDK.
- Android SDK platform 36.
- Android build tools 37.0.0.
- Android NDK `27.3.13750724`.
- CMake `3.22.1`.
- JDK 21 for CI parity. Local Gradle targets Java 17 bytecode.
- Gradle wrapper `8.11.1`.
- Android device or emulator with `arm64-v8a`, Android 8.0+ (`minSdk` 26).

App build configuration:

- `applicationId`: `com.translive.app`
- `versionCode`: `11`
- `versionName`: `1.4.2`
- `compileSdk`: `36`
- `targetSdk`: `35`
- ABI: `arm64-v8a`

## Restore After Clean Clone

```powershell
git clone https://github.com/RandoTeam/Parlex.git
cd Parlex

git clone https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout 5dcb71166686799f0d873eab7386234302d05ecf
```

The exact native engine source of truth is tracked in:

```text
app/src/main/cpp/llama.cpp.version
```

Generated files, APKs, model files, native engine checkouts, diagnostics, and
release signing secrets are intentionally ignored by Git.

## Debug Build

```powershell
.\gradlew.bat assembleDebug --no-daemon --stacktrace
```

Install on a connected device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APKs are for local testing only and must not be uploaded as beta releases.

## Signed Beta / Release Build

Signed release builds require `keystore.properties` at the repository root and a
matching release keystore. These files are local secrets and are not committed.

Example `keystore.properties` shape:

```properties
storeFile=C:/secure/parlex-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Build and verify after the key is restored:

```powershell
.\gradlew.bat assembleRelease --no-daemon --stacktrace
& "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Before publishing a beta:

1. Bump `versionCode` and `versionName`.
2. Update visible version references in docs.
3. Build the signed release APK.
4. Verify the APK signature and package version.
5. Push `main`.
6. Create a GitHub prerelease and upload the signed APK asset.

Release notes should describe user-visible behavior, not internal dependency
updates.

## CI And Contribution Flow

GitHub Actions runs `Android Check` for pull requests and pushes to `main`. It
restores the tracked `llama.cpp` commit and builds a debug APK as a quality
check only.

External pull requests are welcome, but release control stays with the
maintainer. See `CONTRIBUTING.md` and `.github/pull_request_template.md`.

## Documentation

- `PROJECT_STAGE.md` - current project snapshot.
- `HANDOFF.md` - standalone handoff guide for a new developer.
- `ROADMAP.md` - next development phases.
- `docs/LITERT_BETA.md` - LiteRT beta investigation.
- `docs/HY_MT2.md` - Tencent Hy-MT2 notes.
- `docs/DEPENDENCY_MODEL_AUDIT_2026-06-01.md` - last dependency/model audit.
- `THIRD_PARTY_NOTICES.md` - third-party licenses and runtime notices.

## License

Application code: MIT License.

Translation, OCR, speech, and runtime components use their own licenses. Model
files are not included in the repository. Review `THIRD_PARTY_NOTICES.md` before
redistributing builds or model packs.
