# Parlex v1.5.1-beta.7 (Build 15107)

Follow-up to beta.6. This build fixes the two screen-translation regressions
reported on-device: repeated shortcut capture and the floating button becoming
stuck after one use. It also starts the screen-overlay visual redesign.

## Compatibility

This build uses the same 2026 Parlex release key as beta.6. It can be installed
over beta.6 without uninstalling or losing app data.

## Changes

- `f82df69` - reliable repeated capture and floating-bubble lifecycle:
  - a visible AR overlay no longer owns the accessibility focus while a new
    shortcut capture is starting; the old overlay is dismissed first and the
    underlying window gets a short settle interval;
  - accessibility screenshots retry once when Android reports
    `Interval time too short`;
  - a stale translation lock is recovered when no job is active;
  - the floating bubble is hidden while the full-screen AR overlay is open and
    restored after dismissal, instead of being removed/re-added above the
    overlay. This fixes the bubble losing touch and becoming impossible to
    drag after the first translation;
  - touch, long-press and velocity state are reset when the overlay closes.
- `f82df69` - translation completeness and readability:
  - one-shot screen capture now always runs the OCR merge in addition to the
    accessibility tree, so text rendered as raster or low-contrast grey text
    has another detection path;
  - screen batches use one delimited NMT pass per language group instead of
    isolated per-fragment calls, improving context for short UI strings;
  - translated blocks use an opaque sampled surface, two drawing passes
    (backgrounds first, text second), and clip-out against earlier blocks so
    translated lines cannot print on top of each other;
  - duplicate/overlapping parent-child boxes are suppressed when their text is
    already contained in the parent;
  - line height is tightened and hard height truncation is applied when even
    the minimum font size would overflow the source block.
- `f82df69` - Liquid Glass / Material 3 Expressive pass:
  - floating bubble: translucent ink-glass body, Pixel-blue tonal rim,
    specular highlight, inner glow, spring press feedback and softer shadow;
  - capture animation: quieter glass vignette, thin white leading scan line,
    Pixel-blue perimeter halo, softer beam and reduced neon;
  - implementation follows Apple's Liquid Glass principles (translucency,
    specular response, content-first controls) and Material 3 Expressive
    guidance (tonal color, hierarchy, springy motion, accessible touch target).
  - design reasoning is recorded in
    `docs/SCREEN_OVERLAY_DESIGN_2026-10-22.md`.

## Validation

- Unit tests: **70 suites / 470 tests / 0 failures**.
- Lint: **0 errors**; 386 warnings.
- Release build: **BUILD SUCCESSFUL**.
- APK signature: v2 valid, same certificate as beta.6.
- Native: full `libtranslive.so` (about 8 MB) with pinned `llama.cpp`
  `b94041a98ec86e29e63b02decd29175d6820b2a1`; OpenCL backend enabled.

## What to verify

1. Press the accessibility shortcut several times in a row. Every press should
   capture the underlying app and replace the previous overlay.
2. Open the floating button, translate, close the overlay, then drag / long-press
   the bubble and translate again. It must remain movable and clickable.
3. Test grey, small and raster text (image captions, secondary labels). Confirm
   whether the OCR fallback picks up text that was previously skipped.
4. Check dense pages with many blocks: translated lines must not overlap each
   other or the untouched UI around the block.
5. Compare translation quality on short UI labels and paragraphs. The batch
   path should reduce disconnected word salad, but it does not make ML Kit a
   context-aware LLM.

## Known limitations

- No physical-device acceptance was performed for this build; screen capture,
  OEM shortcuts, camera and TalkBack remain device-only checks.
- `androidTest` migration tests are still missing.
- TalkBack virtual nodes for translated blocks and 48 dp touch targets remain
  pending.
- SHA-256 coverage is incomplete for most catalog/STT models.
- GitHub Actions is blocked by the account billing failure, not by workflow
  code.

## Integrity

- APK: `Parlex-v1.5.1-beta.7-release.apk`
- Size: 122,563,216 bytes (116.9 MB)
- SHA-256: `9b2365e669df2f2fe05aac95118b81b2e9eb5c05460eb9417c34e958c5eb9cf3`
- Signature: APK Signature Scheme v2 (Parlex release key; certificate details are intentionally not published)

- Package: `com.translive.app`
- `versionCode`: 15107
- `versionName`: `1.5.1-beta.7`
- `minSdk`: 26, `targetSdk`: 36, ABI: `arm64-v8a`
