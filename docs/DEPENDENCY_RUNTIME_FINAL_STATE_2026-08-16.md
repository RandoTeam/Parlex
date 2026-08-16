# Dependency and runtime final state — 2026-08-16

## Updated

| Component | Before | Final | Reason |
|---|---:|---:|---|
| Android Gradle Plugin | 8.9.1 | 9.2.0 | Current stable Android build stack |
| Gradle wrapper | 8.11.1 | 9.4.1 | Required by AGP 9.2 |
| Kotlin Android plugin | 2.2.21 | removed | AGP 9 built-in Kotlin migration |
| Compose compiler plugin | 2.2.21 | 2.3.21 | Matches current Kotlin build tooling |
| KSP | 2.2.21-2.0.5 | 2.3.10 (KSP2) | AGP 9-compatible annotation processing |
| Hilt | 2.57.2 | 2.60.1 | AGP 9/KSP2 compatible |
| OkHttp | 5.3.2 | 5.4.0 | Download stack fixes |
| Sherpa-ONNX Android AAR | 1.13.1 | 1.13.4 | Adds Qwen3-ASR Kotlin API |
| llama.cpp source | b10442 | b10452 | Current checked upstream revision, OpenCL retained |
| targetSdk | 35 | 36 | Android 16 target device support |

## Added

| Component | Final state |
|---|---|
| Qwen3-ASR | Explicit-download Qwen3-ASR 0.6B INT8, SHA-256 verified archive |
| Qwen ASR runtime | Sherpa-ONNX CPU provider only; no GPU/NPU claim |
| Dialogue guard | A Qwen result whose detected language differs from the pressed speaking-side button is discarded before translation |
| Paused downloads | Translation, VAD, Whisper and Qwen downloads retain destination and resume bytes after app process restart |
| Archive extraction | Canonical-path validation blocks archive path traversal |

## Already current / intentionally not changed

| Component | Version | Decision |
|---|---:|---|
| LiteRT-LM | 0.16.0 | Already current; independent from Qwen ASR |
| CameraX | 1.6.1 | Current stable; 1.7 is alpha |
| ML Kit Text Recognition | 16.0.1 | Current stable |
| ML Kit Translate | 17.0.3 | Current stable |
| ML Kit Language ID | 17.0.6 | Current stable |
| Room | 2.8.4 | Current stable |
| Activity Compose | 1.13.0 | Current stable |
| Navigation Compose | 2.9.8 | Current stable branch |
| Coroutines | 1.11.0 | Current stable |
| Lottie Compose | 6.7.1 | Current stable |
| Tesseract4Android | 4.9.0 | Current library release |
| NDK | 27.3.13750724 | Installed and working; NDK 28 is not installed |
| Compose BOM | 2026.05.01 | Newer 2026.06.00 requires compileSdk 37 |
| AndroidX Core KTX | 1.18.0 | 1.19.0 requires compileSdk 37 |
| Hilt Navigation Compose | 1.3.0 | 1.4.0 requires compileSdk 37 |

The local Android SDK has API 34, 35 and 36 only. Its SDK manager does not offer
API 37, therefore using API-37-only libraries would leave the project unbuildable.
The compatibility holds do not affect LiteRT GPU, llama.cpp OpenCL, camera OCR,
or the Qwen/Whisper offline ASR runtimes.

## Verification

- `:app:compileDebugKotlin` — passed.
- `:app:assembleDebug` — passed.
- `libtranslive.so` in the resulting APK needs `libOpenCL.so` and does not need
  `libc++_shared.so`, preventing the previous ColorOS launch crash.
- Debug APK SHA-256:
  `2E0D8602DB11664254457A16B3256F61BFD5F637B8DAC93AADA1C12632ECAD9A`.
