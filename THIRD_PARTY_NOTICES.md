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

## Vendored binary exception

`app/libs/sherpa-onnx-1.13.4.aar` is intentionally vendored because it is not
published in a checksum-locked Maven repository used by this project. It is
Sherpa-ONNX (Apache 2.0). Record its version and SHA-256 before replacing it.

`app/src/main/assets/tessdata/*.traineddata` contains Tesseract language data
(Apache 2.0). The bundled set is limited; additional scripts require the user to
provide matching traineddata.

## Optional MNN OCR build

The PP-OCR/MNN bridge is compiled only when an MNN checkout is restored by
`scripts/setup-native.ps1 -WithMnn`. MNN is Apache 2.0. A normal release without
that checkout contains only the capability stub; do not advertise PP-OCR as
active in that build.

## License scope

The MIT license in `LICENSE` applies to the application source code only. Model
weights, model outputs, upstream engines, fonts, trademarks and traineddata are
covered by their own terms. See `docs/LEGAL.md`.

