# Parlex — Offline AI Translator for Android

<p align="center">
  <strong>33 languages • 1,056 translation directions • 100% offline • Voice dialogue</strong>
</p>

> On-device translator powered by [Tencent Hy-MT1.5-1.8B](https://huggingface.co/tencent/Hy-MT1.5-1.8B) (2-bit GGUF, 574 MB).  
> No internet required. Your data never leaves the device.

## Features

- 🌍 **33 languages** — all combinations (1,056 directions)
- ⚡ **Fast** — ~1 sec per sentence on Snapdragon 865+
- 🔒 **100% offline** — no network, no data collection
- 🎙️ **Voice dialogue** — two-person real-time translation with full conversation log
- 📱 **Lightweight** — 574 MB model, ~1 GB RAM

## Tech Stack

| Component | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose (Material 3) |
| Translation | [llama.cpp](https://github.com/ggerganov/llama.cpp) + GGUF via JNI |
| Speech-to-Text | [whisper.cpp](https://github.com/ggerganov/whisper.cpp) |
| Text-to-Speech | [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) + Piper |
| Storage | Room DB (history + dialogue logs) |

## Setup

### Prerequisites
- Android Studio Ladybug 2024.2+
- Android SDK 35, NDK 27.2
- CMake 3.22.1

### 1. Clone
```bash
git clone https://github.com/YOUR_USERNAME/Parlex.git
cd Parlex
```

### 2. Get llama.cpp
```bash
cd app/src/main/cpp
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git
```

### 3. Download model
Download `hy-mt1.5-1.8b-2bit.gguf` from [HuggingFace](https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF).

Push to device:
```bash
adb shell mkdir -p /data/data/com.translive.app/files/models
adb push hy-mt1.5-1.8b-2bit.gguf /data/data/com.translive.app/files/models/
```

### 4. Build
Open in Android Studio → Sync Gradle → Build (`arm64-v8a`).

## Supported Languages

English, Chinese (Simplified/Traditional), Japanese, Korean, French, German, Spanish, Portuguese, Italian, Dutch, Polish, Czech, Turkish, Ukrainian, Russian, Burmese, Hindi, Bengali, Gujarati, Marathi, Tamil, Telugu, Urdu, Persian, Hebrew, Arabic, Thai, Vietnamese, Indonesian, Malay, Filipino, Khmer + 5 dialects (Cantonese, Hokkien, Tibetan, Mongolian, Uyghur).

## License

**App code:** [MIT License](LICENSE)

**Translation model:** [Tencent HY Community License](https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF) — the model is NOT included in this repository. Users must download it separately and accept the model license. Note: the model license does not cover EU, UK, or South Korea.
