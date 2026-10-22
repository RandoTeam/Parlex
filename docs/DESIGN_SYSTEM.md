# Parlex Design System — "Quiet Glass"

This is the design direction for the next UI passes. It is deliberately
restrained: glass is a functional layer for controls, not a decoration applied to
every surface. The goal is a camera/translation tool that feels calm, fast and
native on Android 16, not a sci-fi dashboard.

## Principles

1. **Content first.** The translated content owns the screen. Controls sit above
   it as a quiet functional layer.
2. **One accent per surface.** Camera uses orange for the shutter; screen uses
   blue for focus; success/error use green/red only for state. Do not fill the UI
   with multiple neon accents.
3. **Glass only where it helps.** Translucent surfaces are for controls,
   floating actions and scan feedback. Text blocks use a solid or near-solid
   surface for readability.
4. **No template aesthetics.** No black-and-gold luxury cards, no generic
   glassmorphism panels, no decorative badges, no icon rows used as filler.
5. **Motion explains state.** Press, capture, translate and complete each get a
   short, distinct motion. No infinite decorative animation.

## Tokens

### Color

| Token | Value | Use |
|---|---|---|
| `ink` | `#0B1220` | dark glass base |
| `inkElevated` | `#111A29` | raised controls |
| `paper` | `#F7F9FC` | light surfaces |
| `textOnInk` | `#F3F6FB` | primary text on dark |
| `textMutedOnInk` | `#B8C2D1` | secondary text |
| `accentCamera` | `#FF6B2C` | camera shutter only |
| `accentFocus` | `#8AB4F8` | focus, links, selection |
| `success` | `#7DD3A8` | complete |
| `warning` | `#FFD08A` | low light/model warning |
| `error` | `#FFB4AB` | failed capture/translation |
| `rim` | `rgba(255,255,255,0.14)` | 1 dp glass rim |

Contrast target: WCAG AA for body text (4.5:1) where possible. Do not rely on
color alone for state; pair it with a label/icon.

### Shape and spacing

- 4 dp base grid.
- Controls: 12–22 dp rounded; full-circle only for shutter/bubble/zoom chips.
- Minimum touch target: 48 dp. Visual core may be smaller.
- 12 dp safe inset from screen edges; 8 dp between compact chips.

### Typography

Use the system sans (Roboto) unless a bundled font is explicitly licensed.

| Role | Size / line |
|---|---|
| Screen title | 20/26, medium |
| Section title | 16/22, medium |
| Body | 14/20 |
| Caption | 12/16 |
| Overlay body | 9–22 sp, fit to source block |
| Overlay headline | source-relative, bold, max 22 sp |

No all-caps labels. No fake-bold everywhere. Letter spacing 0 by default.

### Motion

| Interaction | Duration | Curve |
|---|---|---|
| Press scale | 90 ms down / 160 ms up | spring/overshoot |
| Control reveal | 180 ms | decelerate |
| Screen scan | 1.2 s | decelerate; skip when animators disabled |
| Overlay block reveal | 240–360 ms, stagger ≤ 350 ms | decelerate |
| State color | 220–260 ms | standard |

Respect `ValueAnimator.areAnimatorsEnabled()` / system reduced motion. Do not
animate opacity of large text blocks indefinitely.

## Surfaces

### Camera

- Full-bleed preview.
- Top: one compact glass row — mode, source, swap, target. Flash is a separate
  48 dp circle.
- Bottom: animated `0.6x / 1x / 3x` zoom chips, orange shutter, gallery
  thumbnail, subtitles.
- Live translation: dark glass cards with a thin rim, clipped to the source
  rectangle; never a flat black box over the preview if a glass surface is
  readable.
- Capture animation: quiet vignette, thin white scan line, blue perimeter halo.
- Warnings: contextual, below the top row; never cover the language controls.

### Screen overlay

- Translation replaces source text inside the original block.
- Solid/near-solid erase surface; no blurred ghost of source glyphs.
- Backgrounds drawn before all text; later text is clipped out of earlier
  blocks.
- Top bar: compact glass, Save/Close, source/target chips; no extra cards.
- Floating bubble: ink glass, blue rim, one specular highlight; hidden while the
  overlay is open; restored and touch-reset after close.

### Voice

- One central push-to-talk/start control; no ring of competing buttons.
- Live waveform is information, not decoration; stop it when silent.
- Show ASR state, translation state and TTS state separately. A green "complete"
  must not hide a failed TTS.

### Text

- Reading width, generous line height, clear hierarchy.
- Language chips are secondary; the translation is primary.
- History/dictionary use the same paper/ink surfaces, not a separate theme.

## Accessibility

- Every icon-only control has a content description.
- TalkBack virtual nodes for translated camera/screen blocks are a stable-release
  requirement.
- 48 dp minimum targets; 4.5:1 contrast.
- Reduced motion disables scan/reveal; no focus theft by overlays.
- Test with large font scale and RTL.

## What not to do

- Do not use glassmorphism on every card.
- Do not add a second orange/blue/green accent to the same screen.
- Do not center everything by default.
- Do not use decorative icon rows, fake dashboards or floating UI cards.
- Do not let translated text overflow, overlap or print over neighboring text.
- Do not use a blurred sample of the source text as the only mask.

## Rollout

1. Apply tokens to camera top/bottom controls and live translation cards.
2. Apply the same glass/rim tokens to screen overlay chrome and floating bubble.
3. Add a reduced-motion test.
4. Capture before/after screenshots on the device matrix.
5. Only then expand the system to history/models/settings.
