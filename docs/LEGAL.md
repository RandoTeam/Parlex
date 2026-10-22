# Legal & Licensing Notes

> This is an engineering summary, not legal advice. Upstream license texts are
> authoritative and must be reviewed before redistribution or commercial use.

## Repository code

The Android/Kotlin/Compose application source in this repository is released
under the **MIT License** (`LICENSE`). The license does not grant rights to
third-party models, engines, fonts, traineddata or trademarks.

## Models and binaries

Models are generally **not** vendored in this repository. They are downloaded by
the user or linked from the model catalog. Each model keeps its upstream
license; examples:

- Tencent Hy-MT 1.5: Tencent Hunyuan Community License (regional restrictions
  may apply; verify the upstream text before use);
- Tencent Hy-MT2: Apache 2.0 (verify the specific repository);
- Google TranslateGemma / Gemma: Gemma Terms of Use;
- Whisper: MIT;
- Silero VAD: MIT;
- LiteRT-LM models: the license attached to the specific model repository.

A catalog entry or download button is not a redistribution license. Do not
commit model weights, converted weights or extracted assets unless the upstream
license explicitly allows it.

## Engines and libraries

- llama.cpp / ggml: MIT;
- Sherpa-ONNX: Apache 2.0 (the vendored AAR is documented in
  `THIRD_PARTY_NOTICES.md`);
- ML Kit: Google ML Kit terms / Apache 2.0 components;
- Tesseract4Android and Tesseract: Apache 2.0;
- AndroidX / Compose / Room / Hilt / OkHttp: Apache 2.0.

The full list and notes are in `THIRD_PARTY_NOTICES.md`.

## Privacy and security

- `PRIVACY.md` describes local processing, permissions and network endpoints.
- `SECURITY.md` describes private vulnerability reporting.
- The app does not include telemetry. Model/CDN and optional exchange-rate
  requests are the only expected network paths.

## Trademarks

Android, Google, ML Kit, Gemma, LiteRT, Qualcomm, Snapdragon, OnePlus and other
names are trademarks of their respective owners. Parlex is not affiliated with
or endorsed by those companies.

## Release artifacts

Release APKs are built by the maintainer and signed with the Parlex release key.
Do not redistribute a build under the Parlex name if it was modified in a way
that could mislead users. Include the MIT notice and the relevant third-party
notices when redistributing source or binaries.

## Export / regional restrictions

Some models or jurisdictions may impose additional restrictions. Users are
responsible for complying with local law and the upstream model license.
