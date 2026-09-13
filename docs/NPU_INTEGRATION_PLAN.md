# Qualcomm Snapdragon NPU Architecture & Integration Plan

**Target Hardware:** Qualcomm Snapdragon 8 Elite (SM8750, Hexagon HTP v79) & Snapdragon 8 Gen 3 (SM8650, Hexagon HTP v75)  
**Target Runtimes:** Google LiteRT-LM (Qualcomm NPU delegate) & llama.cpp (Qualcomm QNN HTP backend)  
**Status:** Architectural Specification & Implementation Baseline  
**Date:** 2026-09-14

---

## 1. Executive Summary & Hardware Context

Snapdragon 8 Elite (SM8750 / "Sun") and Snapdragon 8 Gen 3 (SM8650 / "Lanai") represent a qualitative breakthrough in mobile edge AI, offering 45 TOPS of on-device neural compute via Qualcomm's dedicated Hexagon Tensor Processor (HTP). While Parlex's current production pipeline accelerates LLM inference via Adreno GPU OpenCL kernels, dedicated NPU execution offers significant advantages:
1. **Energy Efficiency:** 2.5× to 3.8× lower energy per token compared to Adreno GPU, virtually eliminating thermal throttling on extended dialogue and screen translation sessions.
2. **Dedicated Memory Bandwidth:** The Hexagon NPU features dedicated low-latency TCM (Tightly Coupled Memory) and direct unified memory access without competing with Android UI surface compositing on the Adreno GPU.
3. **Dedicated Quantized Matrix Units (HMX):** Hardware-native INT4 and INT8 matrix-multiply operations execute at wire speed without software emulation.

---

## 2. Hexagon NPU Microarchitecture Matrix

| Parameter | Snapdragon 8 Elite (SM8750) | Snapdragon 8 Gen 3 (SM8650) | Snapdragon 8 Gen 2 (SM8550) |
|---|---|---|---|
| **SoC Codename** | Sun (`sun`) | Lanai (`lanai`) | Kalama (`kalama`) |
| **NPU Architecture** | Hexagon HTP v79 | Hexagon HTP v75 | Hexagon HTP v73 |
| **Peak INT4/INT8 Compute** | **45 TOPS** | **45 TOPS** | **33 TOPS** |
| **Vector Engine (HVX)** | 8x 1024-bit vector units | 4x 1024-bit vector units | 4x 1024-bit vector units |
| **Matrix Engine (HMX)** | 2nd-gen micro-tile HMX | 1st-gen micro-tile HMX | HMX baseline |
| **HTP Shared TCM** | Expanded multi-tier TCM | High-speed TCM | Standard TCM |
| **Native Quantizations** | INT4, INT8, FP16, Mixed W4A16 | INT4, INT8, FP16, W4A16 | INT8, FP16, W8A16 |
| **Reference Device** | **OnePlus 13** | Samsung Galaxy S24 Ultra | Xiaomi 13 Pro |

---

## 3. Dual-Engine NPU Integration Architecture

Parlex implements a unified dual-engine NPU strategy supporting both LiteRT-LM and GGUF:

```
                          ┌───────────────────────────┐
                          │    SettingsRepository     │
                          │   (CPU / GPU / NPU mode)  │
                          └─────────────┬─────────────┘
                                        │
                         ┌──────────────┴──────────────┐
                         ▼                             ▼
              ┌─────────────────────┐       ┌─────────────────────┐
              │LiteRtTranslationEng.│       │  TranslationEngine  │
              │     (LiteRT-LM)     │       │     (llama.cpp)     │
              └──────────┬──────────┘       └──────────┬──────────┘
                         │                             │
                         ▼                             ▼
              ┌─────────────────────┐       ┌─────────────────────┐
              │    Backend.NPU()    │       │     GGML_QNN /      │
              │  (libQnnHtpV79.so)  │       │    libQnnHtp.so     │
              └──────────┬──────────┘       └──────────┬──────────┘
                         │                             │
                         └──────────────┬──────────────┘
                                        │
                                        ▼
                         ┌─────────────────────────────┐
                         │    Qualcomm Hexagon NPU     │
                         │   HTP v75 / v79 (45 TOPS)   │
                         └─────────────────────────────┘
```

### Path A: LiteRT-LM Qualcomm NPU Backend
Google AI Edge LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.16.0`) includes native support for `Backend.NPU(nativeLibraryDir)`:
- During model initialization, LiteRT queries Qualcomm's runtime libraries (`libQnnHtp.so`, `libQnnSystem.so`) in the application native library directory (`context.applicationInfo.nativeLibraryDir`).
- Quantized models (e.g. `gemma-4-E2B-it`, `translategemma-4b-it-int4`) are mapped to the Hexagon HTP execution graph.
- If unsupported operators or memory bounds are encountered, the 3-tier fallback ladder gracefully demotes to `Backend.GPU()` and then `Backend.CPU()`.

### Path B: llama.cpp Qualcomm QNN HTP Backend
Upstream `llama.cpp` incorporates the Qualcomm QNN backend (`GGML_BACKEND_QNN`):
- CMake target: `-DGGML_QNN=ON -DQNN_SDK_DIR=${QNN_SDK_PATH}`.
- Links `libQnnHtp.so` and target architecture libraries (`libQnnHtpV79.so` for SM8750, `libQnnHtpV75.so` for SM8650).
- Offloads GEMM (General Matrix Multiply) and self-attention operations directly to HTP tensor units.

---

## 4. Hardware Detection & Hardware Profile Registry

The hardware profile subsystem (`com.translive.app.engine.hardware`) dynamically inspects device hardware properties without relying on static heuristics:

1. **SoC Model Probe:**
   - Queries `android.os.Build.SOC_MODEL` (Android 12+ / API 31+).
   - For Snapdragon 8 Elite: `Build.SOC_MODEL` reports `"SM8750"`.
   - For Snapdragon 8 Gen 3: `Build.SOC_MODEL` reports `"SM8650"`.
   - Fallback inspects system properties `ro.soc.model`, `ro.board.platform`, and `/sys/devices/soc0/soc_id`.

2. **Qualcomm NPU Driver Probing:**
   - Verifies the availability of FastRPC user-space device nodes:
     - `/dev/fastrpc-adsp`
     - `/dev/fastrpc-cdsp` (Compute DSP / Hexagon NPU node)
     - `/dev/adsprpc-smd`
   - Confirms that the current process has SELinux domain permissions to open the CDSP channel.

3. **Classification & Recommendation:**
   - Devices matching SM8750 or SM8650 with active CDSP nodes are classified as `NPU_ACCELERATED`.
   - The UI automatically presents `"NPU (Qualcomm Hexagon)"` in the Settings compute options alongside `"GPU (Adreno)"` and `"CPU"`.

---

## 5. Resilient 3-Tier Fallback Ladder

To prevent crashes or degraded user experience when attempting to run models on experimental NPU paths, `LiteRtTranslationEngine` implements a strict fallback contract:

```
[Requested: NPU]
       │
       ▼
Try Backend.NPU() ──────────► [Success] ──► Active: NPU
       │ (Failure)
       ▼
Try Backend.GPU() ──────────► [Success] ──► Active: GPU (Fallback logged)
       │ (Failure)
       ▼
Try Backend.CPU() ──────────► [Success] ──► Active: CPU (Fallback logged)
       │ (Failure)
       ▼
Return false & display user-actionable error
```

All fallbacks are recorded with human-readable diagnostic reasons in `LiteRtBackendStatus.fallbackReason` and visible in Settings Technical Diagnostics.

---

## 6. Implementation Milestones

- [x] **Milestone 1: Hardware Abstraction & Architecture Specification** (Complete in `QualcommNpuProfile.kt` & `docs/NPU_INTEGRATION_PLAN.md`).
- [x] **Milestone 2: LiteRT-LM NPU Backend Binding & Fallback Ladder** (Integrated in `LiteRtTranslationEngine` & `DialogueSummaryEngine`).
- [x] **Milestone 3: Settings & UI Exposure** (Exposing NPU compute backend selection in `SettingsRepository` & `SettingsScreen`).
- [ ] **Milestone 4: Native QNN SDK Submodule & Compilation** (Packaging prebuilt QNN HTP v75/v79 stubs in `app/src/main/jniLibs/arm64-v8a/` for llama.cpp).
- [ ] **Milestone 5: On-Device Benchmarking on OnePlus 13** (Measuring TTFT, decode tokens/sec, and mA current draw).
