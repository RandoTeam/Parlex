# PocketPal AI research — 2026-08-16

Repository inspected: `a-ghorbani/pocketpal-ai`, release `v1.16.1`, commit
`505ac4b717b015c7f909120f99ee6fdb082b6793` (2026-08-14).

## Direct conclusion

PocketPal AI is a strong reference for GGUF/OpenCL GPU management, benchmark
discipline, model downloading and neural text-to-speech. It is **not** a
reference implementation for speech-to-text or two-person voice translation:
the current source has no ASR model, Whisper/Qwen ASR runtime, VAD capture
pipeline, microphone stream, or spoken-language routing.

## LLM and GPU design

| Area | PocketPal implementation | Applicability to Parlex |
|---|---|---|
| GGUF runtime | `llama.rn 0.12.7` / llama.cpp | Same class of native GGUF runtime; its GPU method is relevant. |
| Adreno GPU | Optional `libOpenCL.so`; concrete GPU device list and `n_gpu_layers=99`. | Validate actual backend, not a blind GPU UI label. |
| Large buffers | Sets `LM_GGML_OPENCL_ADRENO_USE_LARGE_BUFFER=1` before native initialization. Backend self-gates on supported Adreno GPUs and Qualcomm extension. | Test on OnePlus 13 behind capability checks. |
| Effective backend | Parses native logs for `using device GPUOpenCL`, device name and `offloaded N/M layers`; requested GPU that stays CPU fails. | Must be copied into Parlex diagnostic/benchmark logic. |
| Benchmark | Matrix over model, quantization, backend and settings captures pp/tg, wall time, RAM, effective backend and logs. | Strong model for Parlex benchmark. |
| NPU | Experimental Hexagon branch. | Do not add; Parlex stays CPU/GPU only. |

PocketPal pins both the device set and `n_gpu_layers` per benchmark cell. This
prevents a previous accelerated run from contaminating a later CPU baseline.
Parlex should also make CPU `0` layers and GPU `full` layers explicit, then
prove the result from native logs.

## Speech recognition: absent

Source search covered `src/`, Android code, dependencies and native
configuration. There is no microphone capture implementation, microphone
permission, STT dependency, VAD pipeline, Whisper, Qwen ASR or ASR model
catalog. `@pocketpalai/react-native-speech` is TTS despite its generic name.

Therefore PocketPal cannot be used as a design source for Qwen3-ASR, Whisper,
language locking, barge-in or wrong-direction translation prevention. Parlex's
explicit-side buttons and selected-language guard remain the correct approach.

## Neural TTS design

PocketPal registers four engines:

| Engine | Runtime | Scope |
|---|---|---|
| System | Android/iOS system TTS | Fallback |
| Kitten | ONNX, CPU forced | English, ~57 MB |
| Kokoro | ONNX FP32, CPU forced | Higher quality, ~330 MB |
| Supertonic v3 | ONNX pipeline, CPU forced | 31 languages, 10 voices, ~398 MB |

All three neural engines use `executionProviders: ['cpu']`. Its Adreno OpenCL
work accelerates GGUF LLMs only, not TTS.

### Good decisions worth adopting

1. A serialized single-slot TTS runtime: switching neural voices releases the
   previous native model first, avoiding peak-RAM collisions.
2. Streaming TTS: LLM deltas are normalized, reasoning markup is excluded,
   and audio starts before the full reply; later text is batched at ~300 chars.
3. Stop, engine switch and cancellation share one queue, preventing an old
   stop signal from cancelling a new utterance.
4. On backgrounding or disabling auto-speak it stops and releases the active
   neural model; documented active footprint is 200–450 MB.
5. Installation status is filesystem-derived; Supertonic also requires a
   version sentinel written only after the complete bundle succeeds.
6. Per-engine progress/error state, free-space check and voice persistence.

### Limits: do not copy blindly

- On an interrupted neural-TTS download PocketPal removes the partial folder
  and restarts later. Parlex resumable downloads are better and should stay.
- The TTS files are checked by presence/version sentinel, not SHA-256. Parlex
  SHA-256 validation for Qwen downloads is stronger.
- The Supertonic catalog has an old comment about an initial stub; current
  code invokes the native Supertonic engine, but device quality must be tested
  before adopting it.

## Download design

GGUF downloads use Android WorkManager plus a persistent Room database. The
worker stores task state durably, resumes with HTTP Range from actual file size,
restarts cleanly if a server ignores Range, retries transient server errors
with exponential backoff, and persists progress about once per second.

This is the future reliability target for Parlex large downloads. The current
Parlex resume logic works, but WorkManager + Room is more crash-resistant.

## Benchmark contract to use in Parlex

1. Resolve GPU availability before a run; fail fast when unavailable.
2. Pin every context parameter, especially backend devices and layer count.
3. Load via one native entry point only.
4. Validate effective backend from native logs and offloaded layer count.
5. Record prompt speed, generation speed, wall time, peak RAM and logs.
6. Release between cells, wait for backend teardown, save JSON after each cell.

For Parlex add translation measurements: time to first translated character,
full completion time, source/target length and output-validation status.

## Recommended next work in Parlex

1. Port verified-GPU benchmark logic and OpenCL log proof.
2. Benchmark `LM_GGML_OPENCL_ADRENO_USE_LARGE_BUFFER=1` on OnePlus 13 before
   enabling it by default.
3. Keep ASR independent: complete Qwen3/Whisper tests, VAD tuning and
   selected-language rejection. PocketPal provides no ASR code to copy.
4. Add neural TTS only after STT and camera stabilize; start with system TTS
   fallback plus one downloadable multilingual neural engine.
5. Reuse serialized TTS ownership, streaming queue, background release and
   filesystem readiness; retain Parlex SHA-256 and resumable downloads.

## Sources

- https://github.com/a-ghorbani/pocketpal-ai
- https://github.com/a-ghorbani/pocketpal-ai/releases/tag/v1.16.1
- https://github.com/ggml-org/llama.cpp/pull/20997
- https://github.com/ggml-org/llama.cpp
