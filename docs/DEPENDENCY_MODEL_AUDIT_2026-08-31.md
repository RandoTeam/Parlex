# Dependency And Model Catalog Audit — 2026-08-31

## Scope

Checked the app baseline against official upstream sources on 2026-08-31:

- Tencent HuggingFace Official Collections (`HY-MT1.5-1.8B-GGUF`, `Hy-MT2-1.8B-GGUF`, `Hy-MT2-7B-GGUF`).
- Google AI Edge LiteRT Community (`gemma-4-E2B-it-litert-lm`, `gemma-4-E4B-it-litert-lm`).
- Google TranslateGemma GGUF repository (`mradermacher/translategemma-4b-it-GGUF`).
- llama.cpp native submodule pinned tag `b9464` (`5dcb71166686799f0d873eab7386234302d05ecf`).
- Google LiteRT-LM runtime: `com.google.ai.edge.litertlm:litertlm-android:0.12.0`.
- Fast NMT (ML Kit Translation) and On-Device Offline Language Packs (Phase P1).

## Model Catalog Audit & Checksum Registry

### Tencent HY-MT 1.5 1.8B (33 Languages + Dialects)
- **License**: Tencent HY Community License (requires in-app acceptance dialog).
- **Prompt Format**: `HY_MT` (Chinese prompt for Chinese pairs, English prompt for other language pairs).
- **Variants**:
  - `1.25bit`: 461,861,216 bytes, 900 MB RAM, `FAST_BUDGET`.
  - `2bit`: 600,534,880 bytes, 1,100 MB RAM, `FAST_BUDGET`.
  - `Q4_K_M`: 1,133,080,512 bytes, 1,800 MB RAM, `BALANCED` (Recommended).
  - `Q6_K`: 1,474,785,216 bytes, 2,300 MB RAM, `MAX_QUALITY`.
  - `Q8_0`: 1,908,528,288 bytes, 2,800 MB RAM, `MAX_QUALITY`.

### Tencent Hy-MT2 Mobile 1.8B (33 Languages + Dialects)
- **License**: Apache 2.0 (no license dialog required).
- **Prompt Format**: `HY_MT2` (Fast-thinking instruction).
- **Variants**:
  - `1.25Bit`: 461,860,736 bytes, 900 MB RAM, `FAST_BUDGET`.
  - `2Bit`: 600,534,880 bytes, 1,100 MB RAM, `BALANCED` (Recommended for mobile).
  - `Q4_K_M`: 1,133,080,448 bytes, 1,800 MB RAM, `BALANCED`.
  - `Q6_K`: 1,474,785,120 bytes, 2,300 MB RAM, `MAX_QUALITY`.
  - `Q8_0`: 1,908,528,192 bytes, 2,800 MB RAM, `MAX_QUALITY`.

### Tencent Hy-MT2 Quality 7B (33 Languages)
- **License**: Apache 2.0.
- **Prompt Format**: `HY_MT2`.
- **Variants**:
  - `Q4_K_M`: 4,624,648,896 bytes, 6,500 MB RAM, `BALANCED` (Recommended 7B).
  - `Q6_K`: 6,164,482,720 bytes, 8,500 MB RAM, `MAX_QUALITY`.
  - `Q8_0`: 7,981,928,896 bytes, 10,500 MB RAM, `MAX_QUALITY`.

### Google TranslateGemma 4B (55 Languages)
- **License**: Gemma Terms of Use.
- **Prompt Format**: `TRANSLATE_GEMMA`.
- **Variants**: Q2_K, Q3_K_S, Q3_K_M, Q3_K_L, IQ4_XS, Q4_K_S, Q4_K_M (Recommended), Q5_K_S, Q5_K_M, Q6_K, Q8_0, F16.

### Google Gemma 4 LiteRT-LM (E2B / E4B)
- **License**: Apache 2.0.
- **Runtime**: `ModelRuntime.LITERT_LM`.
- **Variants**:
  - `E2B GPU`: 2,008,432,640 bytes, 3,000 MB RAM, GPU only, SHA-256: `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5`.
  - `E4B QAT`: 3,659,530,240 bytes, 4,000 MB RAM, CPU + GPU, SHA-256: `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`.

## UI Categorization & Performance Tiers

- Models are categorized with `ModelPerformanceTier`:
  - `FAST_BUDGET` (`⚡ Fast / Budget`): Sub-2-bit or Q2/Q3 quantizations for low memory footprints (<1.2 GB RAM).
  - `BALANCED` (`⭐ Balanced`): Standard 4-bit k-quants or 2-bit AngelSlim for daily mobile translation.
  - `MAX_QUALITY` (`💎 Max Quality`): 6-bit / 8-bit or 7B parameter models for high fidelity.
  - `GPU_ACCELERATED` (`🚀 GPU-Accelerated`): Hardware OpenCL/Vulkan accelerated LiteRT-LM bundles.
- Model cards display runtime indicators (`llama.cpp CPU/GPU` vs `LiteRT-LM GPU`) and recommendation badges.
