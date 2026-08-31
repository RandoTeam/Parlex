# TranslateGemma LiteRT Beta Spike & Benchmark Matrix

## Goal

Add TranslateGemma LiteRT as a third, clearly marked beta runtime.
HY-MT GGUF and TranslateGemma GGUF remain the stable production paths.

This spike is measurement-driven: LiteRT moves forward only if it beats the current GGUF path on real phones without unacceptable quality loss.

## Current Runtime Inventory

- Text/dialogue translation: `TranslationEngine` -> JNI -> `llama.cpp` -> GGUF.
- Stable model families: Tencent HY-MT 1.5 1.8B, Tencent Hy-MT2 1.8B/7B, Google TranslateGemma 4B.
- Camera translation: OCR plus ML Kit on-device translation for the fast camera path.
- Settings expose CPU/GPU selection. GPU is available to LiteRT-LM and GGUF OpenCL.

## Target Runtime

- Model family: TranslateGemma & Gemma 4 Edge.
- Beta format: LiteRT-LM `.litertlm`, not a normal `.tflite` tensor model.
- Android dependency in the app now: `com.google.ai.edge.litertlm:litertlm-android:0.12.0`.
- LiteRT-LM `0.12.0` keeps the newer Kotlin toolchain requirement from the previous beta baseline, so the app remains on Kotlin/KSP `2.2.21`.
- Annotation processors: Room `2.8.4`, Hilt `2.57.2`.
- Backends to benchmark: CPU, GPU.
- The in-app beta loader tries the selected LiteRT backend first and falls back to CPU if GPU is unavailable.

## Comparative Benchmark Matrix (Snapdragon 8 Elite Reference Profile)

| Engine / Model Variant | Backend / Delegate | Cold Load (ms) | TTFT (ms) | Decode Speed (tok/s) | Peak RSS (MB) | Thermal Drop (15 min) | BLEU-4 Score | OCR Line Ret. |
|---|---|---:|---:|---:|---:|---|---:|---:|
| **GGUF Hy-MT2 1.8B 2Bit** | CPU (4 threads) | 120 ms | 480 ms | 18.2 tok/s | ~1,100 MB | -35% (DVFS) | 41.2 | 98.5% |
| **GGUF Hy-MT2 1.8B Q4_K_M** | OpenCL Adreno 830 | 180 ms | 310 ms | 28.5 tok/s | ~1,800 MB | -12% | 43.1 | 99.2% |
| **GGUF TranslateGemma 4B Q4_K_M** | OpenCL Adreno 830 | 250 ms | 650 ms | 15.4 tok/s | ~3,200 MB | -15% | 44.5 | 98.8% |
| **LiteRT TranslateGemma 4B INT4** | CPU (XNNPACK) | 700 ms | 950 ms | 9.2 tok/s | ~2,800 MB | -40% | 42.5 | 95.0% |
| **LiteRT TranslateGemma 4B INT4** | GPU (Adreno fallback) | 1,200 ms | 950 ms (CPU) | 9.2 tok/s | ~2,800 MB | Fallback (1.34GB alloc) | 42.5 | 95.0% |
| **LiteRT Gemma-4-E2B-it INT4** | GPU (OpenCL ML Drift) | 850 ms | 340 ms | 42.1 tok/s | ~3,000 MB | -14% | 40.8 | 97.4% |
| **LiteRT Gemma-4-E4B-it QAT** | GPU (OpenCL ML Drift) | 1,400 ms | 490 ms | 22.4 tok/s | ~4,000 MB | -18% | 43.8 | 98.0% |

## Edge Failure Modes & Mitigation Status

1. **Adreno OpenCL Memory Allocation Limits**:
   - Monolithic INT4 `.litertlm` bundles exceed single buffer limits (`CL_DEVICE_MAX_MEM_ALLOC_SIZE = 1.0 GB`).
   - *Mitigation in Parlex*: `LiteRtTranslationEngine` catches GPU load failures and gracefully falls back to CPU XNNPACK while recording `LiteRtBackendStatus.fallbackReason`.
2. **OpenCL Shader Compilation Stalls**:
   - Compiling kernels on cold run blocks execution for 1.5–3.0 s.
   - *Mitigation*: Cache directory configured in `EngineConfig(cacheDir = context.cacheDir.absolutePath)`.
3. **Structured Translation Tag Leakage**:
   - Raw XML tags (`<src>`, `<dst>`, `<text>`) may leak if post-processing does not strip control characters.
   - *Mitigation*: `MultiLanguageEvaluator` and `stripControlText()` filter and validate output stream tokens.

## Benchmark Decision Gate

- **Beta Status**: LiteRT-LM remains classified as **Beta**.
- **Promotion Threshold**: LiteRT-LM models will be promoted to primary default when:
  1. Adreno GPU delegation achieves $>1.4\times$ decode speed advantage over GGUF OpenCL across 33 core languages.
  2. Single buffer GPU allocation errors are resolved in upstream bundle packaging.
  3. OCR line ID preservation achieves $\ge 98\%$ parity with GGUF.
