# Third-Party Notices

This application uses the following third-party components. Each component
is subject to its own license, listed below.

---

## llama.cpp

- **Repository:** https://github.com/ggml-org/llama.cpp
- **License:** MIT License
- **Usage:** LLM inference engine for translation (linked via JNI)
- **Note:** Not included in this repository. Cloned separately during build.

## Sherpa-ONNX

- **Repository:** https://github.com/k2-fsa/sherpa-onnx
- **License:** Apache License 2.0
- **Usage:** Offline STT runtime for Whisper Tiny + Silero VAD

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

## Tencent Hy-MT2 (Translation Models)

- **Repository:** https://huggingface.co/collections/tencent/hy-mt2
- **License:** Apache License 2.0
- **Usage:** Current Tencent translation models (GGUF quantized variants)
- **Note:** NOT included in this repository. Must be downloaded separately.

---

## Google TranslateGemma 4B (Translation Model)

- **Repository:** https://huggingface.co/google/translate-gemma-4b-it
- **License:** Gemma Terms of Use
- **Usage:** Google translation model via GGUF and LiteRT beta variants
- **Note:** NOT included in this repository. Must be downloaded separately.

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
| CameraX | Apache 2.0 |
| ML Kit Text Recognition | Apache 2.0 / Google ML Kit terms |
| ML Kit On-Device Translation | Apache 2.0 / Google ML Kit terms |
| ML Kit Language ID | Apache 2.0 / Google ML Kit terms |
| LiteRT-LM Android | Apache 2.0 / Google AI Edge terms |
| Tesseract4Android | Apache 2.0 |
| Tesseract OCR | Apache 2.0 |

All Android libraries are fetched via Gradle and are NOT included
in this repository source code.
