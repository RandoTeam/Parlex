# Camera OCR roadmap

Status date: 2026-08-16

## Product split

- **Live camera:** short signs and labels. Low latency, stable overlays, no frame queue.
- **Photo / poster / book:** high-quality OCR with a readable result screen. It must never shrink a full page into illegible translated text over the image.

## Current baseline

- [x] CameraX `ImageAnalysis` is bound to the live camera pipeline.
- [x] Live ML Kit OCR receives `ImageProxy` directly; the previous YUV -> JPEG -> Bitmap conversion was removed from the ML Kit path.
- [x] Back pressure is `STRATEGY_KEEP_ONLY_LATEST`.
- [x] OCR and translation timing is written to logcat under `CameraVM`.
- [x] Image-line translation has bounded parallelism (three active requests maximum).
- [x] CPU and GPU are the only accelerator choices. NPU is not part of the target design.

## Selected OCR architecture

1. ML Kit remains the immediate live OCR path for Latin, CJK and Devanagari.
2. PP-OCR v5 mobile is the high-quality offline path for photos, posters and books, including scripts where the current Tesseract path is weak.
3. PP-OCR uses LiteRT GPU OpenCL when the device supports the converted FP16 models; CPU XNNPACK is the explicit fallback.
4. The app records actual active backend and per-stage timing. A requested GPU is never displayed as GPU unless initialization and inference succeed.
5. OCR models are installed as a verified offline package with version, SHA-256, size and license metadata. They are not silently downloaded when the user opens the camera.

## Implementation sequence

- [x] A. Repair the live frame transport and remove unnecessary image conversion.
- [x] B. Add stage telemetry and bounded NMT parallelism.
- [ ] C. Add the PP-OCR package manager entry: detector, recognizer, dictionaries, checksum verification and import/export support.
- [ ] D. Add a LiteRT PP-OCR runtime on one dedicated worker thread. It must expose requested backend, actual backend, warm-up time, detector time, recognizer time and total time.
- [ ] E. Replace capture/photo OCR with PP-OCR: rotated quadrilateral boxes, confidence, script routing and correct reading order.
- [ ] F. Add document processing: document edges, perspective correction, sharp-frame selection, high-resolution tiled OCR and a separate reading result.
- [ ] G. Add live tracking: keyframe OCR plus lightweight luma/feature tracking between keyframes. Re-run OCR only after motion settles, focus changes or translation state changes.
- [ ] H. Run a fixed OnePlus 13 corpus: Latin/Russian/Chinese/Arabic/Thai posters, book pages and mixed-script signs. Keep the backend only if it improves both accuracy and measured latency.

## Research decisions

- Google ML Kit documents direct `ImageProxy` input with CameraX and `KEEP_ONLY_LATEST` for streaming OCR.
- Current PP-OCR v5 mobile projects demonstrate a detector + recognizer pipeline with static FP16 LiteRT models and OpenCL GPU execution. Their models/conversion must be independently versioned and verified before inclusion.
- A document scanner needs perspective correction and high-quality capture processing. Live overlay and book reading cannot share the same rendering policy.

## Sources

- https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- https://developers.google.com/edge/litert/android/gpu
- https://developers.google.com/edge/litert/android/gpu_native
- https://github.com/iFleey/PPOCRv5-Android
- https://github.com/egdels/makeacopy
- https://github.com/dominostars/playtranslate
