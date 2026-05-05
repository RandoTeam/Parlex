# Third-Party Notices

This application uses the following third-party components. Each component
is subject to its own license, listed below.

---

## llama.cpp

- **Repository:** https://github.com/ggerganov/llama.cpp
- **License:** MIT License
- **Usage:** LLM inference engine for translation (linked via JNI)
- **Note:** Not included in this repository. Cloned separately during build.

## whisper.cpp

- **Repository:** https://github.com/ggerganov/whisper.cpp
- **License:** MIT License
- **Usage:** Whisper speech recognition engine (via Sherpa-ONNX)
- **Note:** Not included in this repository.

## Sherpa-ONNX

- **Repository:** https://github.com/k2-fsa/sherpa-onnx
- **License:** Apache License 2.0
- **Usage:** Runtime for TTS (Kokoro) and STT (Whisper + VAD)

## Kokoro TTS (Model)

- **Repository:** https://huggingface.co/hexgrad/Kokoro-82M
- **License:** Apache License 2.0
- **Usage:** Text-to-speech voice synthesis model
- **Note:** Downloaded at runtime via in-app model manager. Not included in repository.

## Silero VAD

- **Repository:** https://github.com/snakers4/silero-vad
- **License:** MIT License
- **Usage:** Voice Activity Detection for speech segmentation
- **Note:** Model file downloaded at runtime. Not included in repository.

## Whisper Tiny (Model)

- **Repository:** https://github.com/openai/whisper
- **License:** MIT License
- **Usage:** Multilingual speech recognition (via Sherpa-ONNX ONNX export)
- **Note:** Downloaded at runtime. Not included in repository.

---

## Tencent Hy-MT 1.5 1.8B (Translation Model)

- **Repository:** https://huggingface.co/tencent/Hy-MT1.5-1.8B
- **License:** Tencent Hunyuan Community License Agreement
- **Usage:** Core translation model (GGUF quantized variants)
- **Note:** NOT included in this repository. Must be downloaded separately.

> ⚠️ **IMPORTANT:** The Tencent Hunyuan Community License Agreement
> grants usage rights ONLY outside of the European Union, the United
> Kingdom, and South Korea. Users in those regions are NOT licensed
> to use this model. See the full license at:
> https://huggingface.co/tencent/Hy-MT1.5-1.8B/blob/main/LICENSE

---

## Android Libraries

| Library | License |
|---------|---------|
| Jetpack Compose | Apache 2.0 |
| Room (AndroidX) | Apache 2.0 |
| Dagger Hilt | Apache 2.0 |
| Material 3 | Apache 2.0 |
| OkHttp | Apache 2.0 |
| Apache Commons Compress | Apache 2.0 |
| Navigation Compose | Apache 2.0 |
| Kotlin Coroutines | Apache 2.0 |

All Android libraries are fetched via Gradle and are NOT included
in this repository source code.
