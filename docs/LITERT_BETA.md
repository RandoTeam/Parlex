# TranslateGemma LiteRT Beta Spike

## Goal

Add TranslateGemma LiteRT as a third, clearly marked beta runtime.
HY-MT GGUF and TranslateGemma GGUF remain the stable production paths.

This spike is measurement-driven: LiteRT moves forward only if it beats the current GGUF path on real phones without unacceptable quality loss.

## Current Runtime Inventory

- Text/dialogue translation: `TranslationEngine` -> JNI -> `llama.cpp` -> GGUF.
- Stable model families: Tencent HY-MT 1.5 1.8B and Google TranslateGemma 4B.
- Camera translation: OCR plus ML Kit on-device translation for the fast camera path.
- Settings already expose CPU/GPU/NPU labels, but GPU/NPU are placeholders for the current GGUF runtime.

## Target Runtime

- Model family: TranslateGemma.
- Beta format: LiteRT-LM `.litertlm`, not a normal `.tflite` tensor model.
- Android dependency candidate: `com.google.ai.edge.litertlm:litertlm-android:0.11.0`.
- Non-LLM LiteRT dependency candidate for plain `.tflite` experiments: `com.google.ai.edge.litert:litert:2.1.5`.
- Backends to benchmark: CPU, GPU, NPU.

The important distinction: LiteRT is the low-level on-device runtime, while LiteRT-LM is the LLM path we need for text generation and streaming translation.

## Candidate Artifact

Initial smoke-test artifact:

- Repository: `barakplasma/translategemma-4b-it-android-task-quantized`.
- INT4 file: `artifacts/int4-generic/translategemma-4b-it-int4-generic.litertlm`, 2,011,201,536 bytes.
- Dynamic INT8 file: `artifacts/dynamic_int8-generic/translategemma-4b-it-dynamic_int8-generic.litertlm`, 3,920,576,512 bytes.
- Input format: `<src>en</src><dst>ru</dst><text>Hello world</text>`.
- License note: model weights remain under Google Gemma Terms of Use; conversion scripts are Apache 2.0.

Use `tools/litert-download-translategemma.ps1 -Quant int4` to download the first beta artifact into ignored local model storage.

## Why Snapdragon 8 Elite Matters

Snapdragon 8 Elite class hardware is the correct target for this test because it gives us enough RAM and modern Qualcomm acceleration. The current connected physical device reported by `adb` during this spike was still `MI 8 / SDM845`, so benchmark runs must explicitly select the new phone serial before any performance decision.

## Spike Phases

1. Device probe
   - Confirm the physical benchmark device serial.
   - Confirm SoC, Android version, ABI, RAM, installed app version, and thermal access.
   - Reject benchmark results from emulator or old Snapdragon 845 device.

2. Model artifact
   - Find or build a TranslateGemma `.litertlm` artifact.
   - Record source, license, quantization, size, tokenizer assets, and expected backend support.
   - Keep GGUF files and `.litertlm` files separate in model storage.

3. Outside-app smoke test
   - Run LiteRT-LM sample binary or minimal harness before touching app UI.
   - Test CPU first, then GPU/NPU.
   - Capture load time, first-token latency, generated tokens/sec, peak RSS, and errors.

4. App beta runtime
   - Add a `TranslateGemma LiteRT Beta` model entry only after the external smoke test works.
   - Route it through a separate engine instead of changing the GGUF `TranslationEngine`.
   - Keep the UI label explicit: `Beta`, backend shown, no silent replacement.

5. Quality gate
   - Compare against current TranslateGemma GGUF Q4_K_M and HY-MT Q4_K_M.
   - Test short text, long paragraphs, dialogue turns, OCR page text, and mixed-language snippets.
   - LiteRT beta only stays visible if speed improves without obvious translation regressions.

## Benchmark Metrics

- Model file size.
- Cold load time.
- First-token latency.
- Full translation latency.
- Generated tokens/sec.
- Peak app RSS.
- Backend actually used: CPU, GPU, or NPU.
- Device temperature/thermal throttling notes.
- Translation quality notes for fixed prompts.

## Pass Criteria

- CPU LiteRT must be competitive with GGUF before GPU/NPU work matters.
- GPU/NPU must produce stable output, not just faster startup.
- NPU support must be proven on the target phone, not inferred from the SoC name.
- App integration must not affect existing HY-MT/GGUF behavior.

## Sources To Recheck Before Integration

- LiteRT Android docs: https://ai.google.dev/edge/litert/android
- LiteRT-LM Android docs: https://ai.google.dev/edge/litert-lm/android
- LiteRT-LM NPU docs: https://ai.google.dev/edge/litert/next/litert_lm_npu
- Google Maven LiteRT-LM metadata: https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml
- Google Maven LiteRT metadata: https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml
