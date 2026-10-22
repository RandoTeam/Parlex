# ADR-0002: Do not rewrite the camera/OCR/UI stack in Rust now

- Status: accepted
- Date: 2026-10-22

## Context

The project uses Kotlin/Compose for UI, CameraX/MediaProjection for capture,
ML Kit for NMT/OCR/language ID, and C/C++ for llama.cpp, ggml/OpenCL,
sherpa-onnx and ONNX Runtime. A Rust rewrite was considered.

## Decision

Do not introduce Rust in the current camera/OCR/UI phase.

## Reasons

- Android camera, projection, accessibility and Compose are JVM/Android APIs;
  Rust would add a JNI boundary and duplicate lifecycle/state code.
- The dominant compute cost is already native (models/inference), so replacing
  Kotlin does not remove it.
- A second NDK/Rust toolchain increases clean-clone, CI and release-key surface.
- Current camera bugs were state/language/UI bugs, not JVM performance bugs.

## Revisit when

A profiled, isolated CPU hot path exists (for example edge-aware inpainting or
perspective correction). Compare a C++/NEON implementation before adding Rust.

## Consequences

- Kotlin remains the orchestration/UI language.
- Heavy native work stays in C/C++.
- Any future Rust component must be optional at build time and documented in
  `THIRD_PARTY_NOTICES.md`.
