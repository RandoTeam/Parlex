# Screen Overlay Design Notes - 2026-10-22

Scope: floating translation button, screen-capture feedback, and in-place text
overlay. This is a product/design note, not a claim of device acceptance.

## References studied

- Apple, Liquid Glass announcement (WWDC 2025): translucency, content-first
  controls, specular highlights, material that adapts to light/dark context.
- Google Material 3 Expressive guidance: tonal hierarchy, expressive motion,
  rounded shapes, accessible touch targets, reduced visual noise.

The goal is not to copy iOS or Pixel literally. The goal is the same discipline:
one clear action, a control that floats above content without competing with it,
and text that replaces the source text instead of decorating it.

## Why the previous screen overlay felt wrong

1. **Focus theft.** The full-screen overlay requested focus, so the accessibility
   tree belonged to Parlex on the next shortcut press. The next capture saw the
   overlay, not the app underneath.
2. **Z-order/touch conflict.** The floating bubble was removed and re-added above
   the overlay. On some OEM window managers this left the bubble in a cancelled
   drag state; it could not be moved afterwards.
3. **Ghost text.** The inpainting patch was sampled from the original block,
   including the original glyphs, and drawn opaque. The translation sat on a
   blurred copy of the source text.
4. **Overlapping blocks.** Backgrounds and text were drawn in one pass. A later
   block could paint over an earlier translation, especially when accessibility
   produced parent/child boxes with overlapping bounds.
5. **Fragmented translation.** Each accessibility node was translated alone. Short
   menu labels and card fragments lost sentence context and turned into word
   salad.

## Design decisions

### Floating control

- 76 dp window, 56 dp visual core, so the touch target is comfortable without a
  huge visual footprint.
- Ink-glass body with a low-alpha tonal fill, Pixel-blue rim, one specular
  highlight and one subtle inner glow. No neon, no animated rainbow, no blur
  layer.
- Press feedback is a spring scale (0.92 -> 1.0), not a ripple or a colour flash.
- State is expressed by tonal colour and a small glyph, not by adding more
  chrome: idle (translate glyph), scanning (arc), translating (pulse), complete
  (check), error (warning).
- The bubble is hidden while the AR overlay is open. The overlay is the only
  interactive layer; when it closes, the bubble is restored and its touch state
  is reset.

### Capture feedback

- Capture is a short glass scan, not a full-screen dark modal:
  - a quiet vignette (max ~25% black at the edges),
  - a thin white leading line,
  - a wide, low-opacity beam,
  - a Pixel-blue perimeter halo.
- Duration 900 ms per sweep; a finite number of sweeps is followed by a quiet
  processing pulse while OCR/translation is still running. It should read as
  "the screen is being read", not as a loading screen.
- The effect is non-touchable and disappears before the translation overlay.

### In-place typography

- Every translation is anchored to its source rectangle. It never pushes
  neighbouring UI.
- Type hierarchy comes from the source height and role:
  - headline: bold, white/near-black depending on surface,
  - body: regular, slightly muted,
  - minimum 9 sp, maximum 22 sp, line spacing ~1.08.
- If the translation does not fit, shrink first, then ellipsize. It must never
  grow outside the source block.
- The erase surface is an opaque tonal sample from the block edge; a 10% texture
  patch may be layered on top, never an opaque ghost of the source text.
- Backgrounds are drawn for all blocks first, then text. Text is clipped out of
  earlier blocks so two translated regions cannot print over each other.
- Parent/child boxes whose text is already contained in a larger box are
  suppressed.

### Translation context

- Screen text is translated in one delimited batch per language group instead of
  many isolated calls. This improves context for short labels and reduces
  latency.
- FAST_ONLY uses the accessibility tree first. OCR is a fallback only when the
  accessibility tree is empty; FULL_SCAN explicitly adds raster/low-contrast
  text. This keeps one-shot shortcut captures responsive by default.
- This does not turn ML Kit into a context-aware LLM; long-page quality still
  depends on the selected translation policy and model.

## Known trade-offs

- Solid backgrounds are more legible but less photographic than a perfect
  inpainting result. A future version can add a proper edge-aware fill instead of
  a blurred sample.
- Hiding the bubble during the overlay removes a quick dismiss affordance; the X
  button and edge-swipe remain, and the bubble returns immediately after close.
- OCR on every one-shot capture adds latency. FAST_ONLY keeps the shortcut
  responsive; FULL_SCAN is the deliberate quality/latency trade-off for
  raster/low-contrast text.

## Next design work

1. Add TalkBack virtual nodes for each translated block and 48 dp targets for
   overlay actions.
2. Respect system "remove animations" / animator duration scale for the scan.
3. Test adaptive tint on light, dark and photo wallpapers.
4. Add a real edge-aware inpainting pass, measured against the solid-surface
   fallback with screenshot diffs.
5. Validate OEM window layering on Pixel, Samsung and ColorOS before changing the
   bubble z-order again.
