# Design Specification: In-Place Per-Line AR Screen Translation & Crash Hardening

**Date:** 2026-09-14  
**Author:** Antigravity Team  
**Status:** Approved for Implementation (Approach 2)  
**Target Platform:** Android 16 (API 36), OnePlus 13 (Snapdragon 8 Elite / SM8750)  

---

## 1. Context & Motivation

### 1.1 The Crash Regression
In v1.5.1-beta.3, screen translation crashed with:
```text
Translation error: Cannot coerce value to an empty range: maximum -32.0 is less than minimum 32.0
```
**Root Cause:** In `ArTranslateOverlayView.kt:297`, `(centerX - finalWidth / 2f).coerceIn(8f * density, maxScreenW - finalWidth - 8f * density)` evaluates an inverted range when `finalWidth` approaches screen width (e.g. wide search results on DuckDuckGo). On display density `4.0`, `8 * 4.0 = 32.0`. When `finalWidth >= screenWidth - 64`, `max = -32.0 < min = 32.0`, triggering Kotlin's `IllegalArgumentException`.
Furthermore, upon crash or timeout, the overlay service failed to release state tokens and reset `FloatingBubbleView`, causing second attempts to play the scanning animation without rendering translation.

### 1.2 The Visual Experience Problem
The previous implementation used a **single centered card** paradigm:
- Text bounding boxes were wrapped into one large backplate pill.
- The pill and its internal text were centered horizontally (`centerX - finalWidth / 2f`), causing translated lines to drift away from the original text's left-aligned reading margin.
- The dark backplate and cyan border looked like opaque electrical tape pasted over the UI.

### 1.3 Target Architecture: Approach 2 (Zero-Card Direct Canvas Erase + Per-Line Text Replacement)
Mirroring Google Lens / Circle to Search:
1. **Zero Allocations & Zero 18MB Bitmap Copies:** The frozen screenshot `frozenBitmap` is kept as an immutable GPU texture.
2. **Clean Inpainting Erase:** Original text glyphs are masked directly on Canvas by drawing anti-aliased, edge-feathered patches filled with the sampled background color extracted via perimeter sampling.
3. **Per-Line Left-Edge Anchoring:** The text layout is anchored strictly to `srcRect.left` with zero horizontal drift.
4. **Per-Line Geometry:** Rather than a monolithic card, each line of translated text is backed by its own tight per-line pill matching the exact width of that line.
5. **Zero Wireframes:** No unselected border strokes or dimming scrims; translated text appears directly in the native UI.

---

## 2. Technical Architecture & Component Design

### 2.1 Defensive Geometry & Safe Coercion (`safeCoerceIn`)
Universal safe range extension functions to guarantee `max >= min`:
```kotlin
internal inline fun Float.safeCoerceIn(minVal: Float, maxVal: Float): Float =
    this.coerceIn(minVal, kotlin.math.max(minVal, maxVal))

internal inline fun Int.safeCoerceIn(minVal: Int, maxVal: Int): Int =
    this.coerceIn(minVal, kotlin.math.max(minVal, maxVal))
```
Applied across all 8 bounding and positioning calculations in `ArTranslateOverlayView.kt`:
1. Multi-line candidate text size.
2. Single-line candidate text size.
3. Horizontal left margin (`minLeft = 8f * density`, `maxLeft = max(minLeft, maxScreenW - boundedFinalWidth - minLeft)`).
4. Multi-line top anchor.
5. Single-line top anchor.
6. Language picker dropdown menu position.
7. Pill corner radius.
8. Floating micro-toolbar horizontal anchor.

### 2.2 Inpainting Engine (`InpaintingEngine`)
Samples a 48-point perimeter boundary ring ($3\text{dp}$ outside the text bounding box):
- 16 equidistant points along top & bottom edges.
- 8 equidistant points along left & right edges.
- Outlier rejection: Sort samples by Rec. 601 luminance ($Y = 0.299R + 0.587G + 0.114B$) and trim the top/bottom 15% to eliminate stray glyph descenders or punctuation.
- Computes the trimmed mean background color $C_{bg}$ and optimal high-contrast text color $C_{fg}$ ($Y_{bg} \ge 128 \implies \text{Black}$, $Y_{bg} < 128 \implies \text{White}$).

### 2.3 Per-Line Layout & Geometry
1. **Line-Height Proportional Sizing:**
   $$\text{origLineHeight} = \frac{\text{srcHeight}}{\max(1, \lfloor \text{srcHeight} / (18\text{dp}) \rfloor)}$$
   $$\text{candidateTextSize} = (\text{origLineHeight} \times 0.72).\text{safeCoerceIn}(11\text{dp}, 16\text{dp})$$
2. **StaticLayout Fit:**
   Configured with `.setAlignment(Layout.Alignment.ALIGN_NORMAL)`, `.setLineSpacing(0f, 1.15f)`, `.setIncludePad(false)`, `.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)`, and `.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)`.
3. **Per-Line Pill Decomposition:**
   For each line $i \in [0, \text{lineCount}-1]$:
   $$\text{lineLeft} = \text{textLeft} + \text{staticLayout.getLineLeft}(i)$$
   $$\text{lineWidth} = \text{staticLayout.getLineWidth}(i)$$
   $$\text{lineTop} = \text{textTop} + \text{staticLayout.getLineTop}(i)$$
   $$\text{lineBottom} = \text{textTop} + \text{staticLayout.getLineBottom}(i)$$
   Pill rectangle:
   $$\text{pillRect}_i = [\text{lineLeft} - pad_H, \ \text{lineTop} - pad_V/2, \ \text{lineLeft} + \text{lineWidth} + pad_H, \ \text{lineBottom} + pad_V/2]$$

### 2.4 Multi-Pass Elastic Relaxation Engine
When Russian text expands by 20–40%, lines wrap and increase layout height:
1. Boxes expand strictly **downward** from `srcRect.top`.
2. **Column-Aware Collision Detection:** Only blocks sharing $\ge 25\%$ horizontal overlap are evaluated for vertical pushdown.
3. **Forward Pushdown:** If block $B$ overlaps block $A$, $B$ is pushed down:
   $$\Delta Y = A.\text{bottom} + \text{gap} - B.\text{top}$$
4. **Backward Elastic Gap Compression:** If the bottom-most block overflows screen height:
   - Pass 1: Compress inter-block gaps from $4\text{dp}$ down to $2\text{dp}$.
   - Pass 2: Elastically shift blocks upward using available top slack (up to 50% of clearance below the status bar).

### 2.5 Resilient Service State Machine
1. **Session Token Isolation:** `currentSessionToken` incremented on every lock acquisition and cancellation; late screenshot callbacks with stale tokens are instantly discarded and recycled.
2. **Auto-Reset Floating Bubble:** `autoResetRunnable` in `FloatingBubbleView` resets both `BubbleState.ERROR` (after 2000ms) and `BubbleState.COMPLETE` (after 2500ms) back to `IDLE`.
3. **Instant Taps:** Tapping the bubble while in `COMPLETE` or `ERROR` clears timers and resets to `IDLE` immediately.
4. **Immediate Idempotent Dismissal:** `dismissOverlay(immediate = true)` halts animations and cleanly unbinds views from WindowManager.

---

## 3. Rendering Pipeline Sequence (`onDraw`)

1. **Layer 1: Frozen Screenshot**  
   Draws raw full-frame immutable `frozenBitmap` directly onto GPU canvas.
2. **Layer 2: Ambient Background**  
   100% transparent in inpainting mode (no dimming scrim).
3. **Layer 3: Batched Inpainting Erase Pass**  
   Iterates through all `renderBoxes` and draws anti-aliased rounded rectangles over `eraseRect` using $C_{bg}$ with a $2.5\text{dp}$ $45\%$ alpha fringe stroke for soft edge blending.
4. **Layer 4: Batched Text Layout Pass**  
   Translates canvas to $(textOffsetX, textOffsetY)$ and renders `staticLayout` using $C_{fg}$.
5. **Layer 5: Interactive Selection Highlight (Tap Only)**  
   If a block is selected, draws a subtle Material 3 cyan outline and spawns the floating micro-toolbar (`Copy`, `Speak`, `Translate ⇄ Original`, `Hide`).

---

## 4. Verification Plan

1. **Unit Tests (`ArOverlayRenderingTest.kt`):**
   - `testSafeCoerceIn_neverThrowsOnInvertedRange`
   - `testPerLineDecomposition_alignsToLeftEdge`
   - `testInpaintingEngine_trimmedMeanRejectsOutliers`
   - `testElasticRelaxation_pushesOverlappingBoxesDownward`
   - `testFloatingBubbleState_resetsFromCompleteToIdle`
2. **Build Verification:**
   - `:app:compileDebugKotlin`
   - `:app:testDebugUnitTest`
   - `:app:assembleRelease`
3. **On-Device Physical Audit (OnePlus 13):**
   - Re-test DuckDuckGo search screen (`media_1789369932303.png`): Verify crash is 100% resolved and text is left-aligned.
   - Re-test GitHub README screen: Verify multi-line paragraphs wrap and align with zero overlapping cards.
   - Verify second translation attempt works smoothly without stall.
