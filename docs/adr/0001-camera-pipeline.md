# ADR-0001: Camera capture and translation pipeline

- Status: accepted
- Date: 2026-10-22

## Context

Camera translation had inconsistent language pairs, gallery imports that did
not translate immediately and live OCR that paid a JPEG round-trip. The UI also
exposed every logical/physical lens as a chip strip.

## Decision

1. One capture pipeline for live, shutter and gallery.
2. Gallery and one-shot captures use auto language detection; the detected
   source is written back to the UI.
3. Target inversions apply only in auto mode and are persisted.
4. Live QR/OCR uses the zero-copy ML Kit path with viewport coordinate remapping.
5. Camera UI exposes 0.6/1/3x zoom anchors, one orange shutter, gallery and
   subtitles; flash is separate.
6. Camera translation uses pair-specific ML Kit APIs and a local LLM fallback
   only when that model is actually loaded.

## Consequences

- The visible source/target pair must match the OCR/translation pair.
- Gallery import may perform several OCR script probes; correctness wins over
  first-frame latency for still images.
- Live translation is rendered inline; it is not a hidden OCR-only pipeline.
- OEM camera behavior still needs device validation.

## Alternatives

- Keep one global translator: rejected because it mixes concurrent pairs.
- Use only the manual source language: rejected for arbitrary imported photos.
- Keep the all-lens chip strip: rejected as a diagnostics UI, not a camera UI.
