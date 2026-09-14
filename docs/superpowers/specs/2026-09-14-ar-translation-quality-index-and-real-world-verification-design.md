# AR Translation Quality Index (TQI) & Real-World Scenario Verification Framework

**Date:** 2026-09-14  
**Scope:** In-Place AR Screen Translation (Parlex Core, NativeScreenTextExtractor, ArTranslateOverlayView, BrandEntityShield, ScreenTextNoiseFilter)  
**Target Hardware:** OnePlus 13 (Android 16, Snapdragon 8 Elite / SM8750, Serial `a36d1946`)  

---

## 1. Problem Statement & Motivation

During live testing of In-Place AR Screen Translation on OnePlus 13, critical visual regressions were uncovered:
1. **Container Ingestion Bug:** Deeply nested WebView / GeckoView root nodes swallowed individual search result cards, causing full-screen black erasing and text aggregation.
2. **Leaked Brand Tokens:** NMT tokenizers dropped brackets (`[Entity_0]]:`), failing rigid regex unshielding and leaking internal tokens to users.
3. **Collision Floor Collapse:** The collision resolution loop prematurely clamped bottom cards to the screen floor, causing overlapping stacked blobs.

While the project has 452+ unit tests, a significant portion of them tested synthetic, idealized strings rather than the complex, messy layouts of real mobile applications. To guarantee commercial-grade reliability, Parlex requires an **automated 100-point AR Translation Quality Index (TQI)** and a **Dual-Tier Real-World Scenario Verification Suite**.

---

## 2. The 100-Point AR Translation Quality Index (TQI)

The TQI evaluates any screen translation output across four orthogonal dimensions, awarding up to 25 points each (Total 0–100):

$$\text{TQI} = S_{\text{geom}} + S_{\text{inpaint}} + S_{\text{typo}} + S_{\text{semantic}}$$

### 2.1. Dimension 1: Geometry & Anchoring ($S_{\text{geom}}$, 0–25 points)
- **Left-Edge Alignment (10 pts):** Each translated card / line pill must anchor to the original bounding box left edge ($\Delta X \le 4\text{dp}$). Deduct 2 pts per $4\text{dp}$ drift.
- **Inter-Box Collision Clearance (10 pts):** No two translated boxes may overlap ($IoU = 0$). Monotonic vertical gap $\ge 4\text{dp}$ strictly maintained. Deduct 5 pts per collision.
- **Screen Containment (5 pts):** Translated cards must respect top margin ($Y \ge \text{statusBar} + 44\text{dp}$) and bottom margin ($Y \le \text{screenHeight} - 56\text{dp}$). Deduct 5 pts for clipping.

### 2.2. Dimension 2: Inpainting & Erase Seamlessness ($S_{\text{inpaint}}$, 0–25 points)
- **Background Color Fidelity (10 pts):** The dominant edge color calculated by `InpaintingHelper` must match the actual surface background ($\Delta E_{94} < 2.5$). Deduct 3 pts if $\Delta E_{94} \ge 5.0$.
- **Bleed Containment (10 pts):** Erase rectangle bleed must not exceed $3\text{dp}$ horizontally and $2\text{dp}$ vertically. Giant erase rects ($Area > 25\%$ screen) are strictly forbidden (Score = 0).
- **Anti-Halo Transparency (5 pts):** Zero dark borders or framing halos around individual translated lines.

### 2.3. Dimension 3: Typography & Readability ($S_{\text{typo}}$, 0–25 points)
- **WCAG AA Contrast Ratio (10 pts):** Text color vs inpainting surface color must satisfy $Contrast \ge 4.5:1$ for body text and $\ge 3.0:1$ for headlines. Deduct 5 pts if below threshold.
- **Visual Hierarchy Preservation (8 pts):** Headings ($H1/H2$, line height $\ge 20\text{dp}$) must render in `Typeface.BOLD`. Body snippets must render in `Typeface.NORMAL` with proportional auto-fit font sizing ($11\text{dp} \le size \le 16\text{dp}$).
- **Hyphenation & Wrap Integrity (7 pts):** Zero broken syllables or mid-word clipping. High-quality word breaking enabled.

### 2.4. Dimension 4: Semantic Integrity & Noise Rejection ($S_{\text{semantic}}$, 0–25 points)
- **Zero Token Leakage (10 pts):** Zero occurrences of `[[ENTITY_N]]`, `[Entity_N]`, or bare `ENTITY_N` in final rendered output. Deduct 10 pts for any leak.
- **Protected Entity Preservation (8 pts):** 100% preservation of tech brands and proper nouns (e.g. "Android Police", "Google Play", "DuckDuckGo") without machine translation calques.
- **Noise Rejection Parity (7 pts):** Standalone timestamps (`14:30`, `5m ago`), battery levels (`85%`), prices (`$19.99`), URLs, and lone symbols must be completely filtered out and never translated.

---

## 3. The 4 Target Screen Archetypes

The verification suite defines 4 concrete real-world screen archetypes with serialized accessibility dumps and screenshot rasters:

| Archetype ID | Real App Context | Key Elements & Challenges | Pass Criteria (TQI) |
| :--- | :--- | :--- | :--- |
| **`ARCHETYPE_WEB_SEARCH`** | Firefox / DuckDuckGo | Top search bar, 3 search cards (Title + Snippet + URL), settings footer. Challenge: GeckoView page root container must not swallow cards. | $\ge 90$ |
| **`ARCHETYPE_MESSENGER_CHAT`** | Telegram / WhatsApp | Alternating left/right speech bubbles, reply quote preview, timestamps, status ticks. Challenge: Timestamps filtered; quotes preserved. | $\ge 90$ |
| **`ARCHETYPE_DOCUMENTATION`** | Obsidian / Wikipedia | Bold H1/H2 headings, multi-paragraph markdown body, code keywords, lists. Challenge: Strict left anchoring, vertical column packing. | $\ge 88$ |
| **`ARCHETYPE_APP_STORE_GRID`** | Google Play / Settings | Multi-column grid cards, app icons, price buttons (`$4.99`), toggle switches. Challenge: Prices and switches rejected as noise; titles translated. | $\ge 85$ |

---

## 4. Dual-Tier Testing Architecture

```
                          ┌──────────────────────────┐
                          │   Test Execution Matrix  │
                          └─────────────┬────────────┘
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
     ┌───────────────────────┐                     ┌───────────────────────┐
     │  Tier 1: Host Runner  │                     │ Tier 2: Device Runner │
     │  (Pure JVM / Gradle)  │                     │ (ADB on OnePlus 13)   │
     ├───────────────────────┤                     ├───────────────────────┤
     │ - 4 Screen Fixtures   │                     │ - Live Screen Capture │
     │ - Sub-second runtime  │                     │ - A11y tree dump      │
     │ - Zero emulator/device│                     │ - Real ML Kit NMT run │
     │ - Detailed TQI Report │                     │ - Skia overlay audit  │
     └───────────────────────┘                     └───────────────────────┘
```

### 4.1. Tier 1: Host-based Fixture Runner (`TqiHostBenchmarkTest`)
- Located in `app/src/test/kotlin/com/translive/app/engine/tqi/`.
- Loads JSON accessibility trees and PNG bitmaps from `app/src/test/resources/fixtures/`.
- Executes `NativeScreenTextExtractor.filterAndDeduplicate()`, `ScreenTextNoiseFilter.isTranslatable()`, `BrandEntityShield`, and layout positioning.
- Computes the numerical TQI score per archetype and produces a structured test report.
- Target execution time: $< 2.0\text{ seconds}$ for all archetypes combined.

### 4.2. Tier 2: On-Device Smoke Runner (`scripts/verify_on_device_tqi.ps1`)
- Targets strictly OnePlus 13 (`-s a36d1946`).
- Captures current active screen via `adb exec-out screencap -p`.
- Dumps window hierarchy via `adb exec-out uiautomator dump`.
- Injects one-shot translation via `ScreenTranslateOverlayService`.
- Verifies overlay bounds, measures rendering latency, and captures post-translation screenshot for human/agent review.

---

## 5. Test Suite Audit & Pruning Plan

1. **Keep & Fortify:**
   - Real-world extraction tests (`NativeScreenTextExtractorTest`): large container rejection, paragraph preservation.
   - Resilient unshielding tests (`BrandEntityShieldTest`): dropped bracket handling, case insensitivity.
   - Collision engine relaxation tests (`ArElasticRelaxationTest`): column-aware separation, upward rebalancing.
   - Comprehensive Noise filter tests (`ScreenTextNoiseFilterTest`).
2. **Consolidate / Prune:**
   - Remove duplicate repetitive single-character tests that bloat JVM test time without adding coverage.
   - Replace synthetic hardcoded bounding box tests with the 4 archetype fixtures.
