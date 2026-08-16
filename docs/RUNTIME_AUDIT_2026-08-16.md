# Runtime Audit — 2026-08-16

## Verified updates

| Component | Previous | Updated | Result |
|---|---:|---:|---|
| LiteRT-LM Android | 0.12.0 | 0.16.0 | Debug APK builds with the current Engine, Conversation and Backend APIs. |
| llama.cpp | b9464 | b10442 | JNI bridge adapted and arm64-v8a Debug APK builds successfully. |

Sources:

- LiteRT-LM Maven metadata: https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml
- llama.cpp releases and Android build documentation: https://github.com/ggml-org/llama.cpp/releases and https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md

## LiteRT-LM backend status

- `Backend.GPU()` is used only by `.litertlm` models.
- The app falls back to CPU only when GPU model initialisation fails and records that fallback in the runtime status.
- The previous TranslateGemma Android conversion is valid for CPU inference, but its publisher documents Android GPU execution as failing/experimental. It must not be treated as a verified GPU model: https://huggingface.co/barakplasma/translategemma-4b-it-android-task-quantized
- Gemma 4 E2B/E4B LiteRT entries are the GPU candidates to benchmark on a real phone. They are general instruction models, not specialised translation models: https://developers.google.com/edge/litert-lm/models/gemma-4

## GGUF / llama.cpp status

- The current Android native build links the CPU backend only. It uses arm64 CPU kernels, mmap, Flash Attention and the user-selected CPU thread limit.
- The application's GPU setting therefore does not accelerate GGUF models. The settings copy correctly states that it applies only to LiteRT-LM.
- Upstream llama.cpp supports an Android Qualcomm Adreno GPU route through its OpenCL backend, including Adreno-specific kernels: https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md
- Enabling it is a separate engineering task: package/load the Android OpenCL backend, select a backend device, offload a measured number of layers, expose real active-backend telemetry, and verify fallback on the phone. It must not be enabled speculatively.

## Device and drivers

- The connected OnePlus 13 reports `Adreno 830v2`.
- GPU drivers on ColorOS are supplied with system/OTA firmware. An APK cannot install or replace an Adreno driver safely.
- Application-side work is limited to using the system driver through a supported runtime (LiteRT GPU, or a verified llama.cpp OpenCL/Vulkan backend) and reporting its actual use.

## Required device benchmarks

1. Gemma 4 E2B LiteRT GPU: cold load, first-token latency, decode tokens/s, Russian↔English and Russian↔Chinese quality.
2. Gemma 4 E4B LiteRT GPU: the same measurements, memory and thermal stability.
3. TranslateGemma LiteRT CPU: retain only if its translation quality justifies its CPU latency.
4. GGUF CPU baseline with the same texts, before enabling the separate OpenCL prototype.
