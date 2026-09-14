# Hybrid OCR Translation Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modify the screen translation engine to support full, unconditional OCR scanning that merges safely with native Accessibility nodes, removing the strict mutual exclusion.

**Architecture:** Introduce `OcrMode` in settings. In `ScreenTranslateOverlayService`, if `FULL_SCAN` is enabled, run MNN OCR on the background thread *alongside* native A11y nodes. Merge the results by computing Intersection-over-Union (IoU). If an OCR bounding box significantly overlaps a native text node (e.g., IoU > 0.40 or > 60% of OCR area is covered by native bounds), discard the OCR node in favor of the 100% accurate native node.

**Tech Stack:** Kotlin, Coroutines, MNN OCR (existing)

**Spec:** N/A (Self-contained bounded feature)

## Global Constraints
- Target Android 11+ for silent screenshot API (`takeScreenshot`).
- Must not block the Main UI Thread during OCR recognition (must use `Dispatchers.Default`).
- No regression in zero-node fallback.
- `SettingsRepository` additions must be backward compatible.

---

### Task 1: Add OCR Mode Settings

**Files:**
- Modify: `app/src/main/kotlin/com/translive/app/data/SettingsRepository.kt`

**Interfaces:**
- Produces: `enum class OcrMode { FAST_ONLY, FULL_SCAN }`
- Produces: `val ocrMode: OcrMode` in `SettingsRepository` (defaulting to `FULL_SCAN` for now as requested, with SharedPreferences backing).

- [ ] **Step 1: Add the Enum and Setting**
Add the `OcrMode` enum and the preference property to `SettingsRepository.kt`.

```kotlin
enum class OcrMode { FAST_ONLY, FULL_SCAN }

// In SettingsRepository:
var ocrMode: OcrMode
    get() = OcrMode.valueOf(prefs.getString("ocr_mode", OcrMode.FULL_SCAN.name) ?: OcrMode.FULL_SCAN.name)
    set(value) = prefs.edit().putString("ocr_mode", value.name).apply()
```

- [ ] **Step 2: Commit**
```bash
git add app/src/main/kotlin/com/translive/app/data/SettingsRepository.kt
git commit -m "feat: add OcrMode to SettingsRepository"
```

---

### Task 2: Implement Bounding Box Merger

**Files:**
- Create: `app/src/main/kotlin/com/translive/app/engine/CandidateMerger.kt`
- Test: `app/src/test/kotlin/com/translive/app/engine/CandidateMergerTest.kt`

**Interfaces:**
- Consumes: `CandidateBlock` (from `ScreenTranslateOverlayService.kt`)
- Produces: `fun merge(nativeNodes: List<CandidateBlock>, ocrNodes: List<CandidateBlock>): List<CandidateBlock>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.translive.app.engine

import android.graphics.Rect
import com.translive.app.ui.overlay.CandidateBlock
import org.junit.Assert.*
import org.junit.Test

class CandidateMergerTest {
    @Test
    fun `merge drops ocr node if covered by native node`() {
        val native = CandidateBlock("Native Text", Rect(10, 10, 100, 50))
        val ocrInside = CandidateBlock("Ocr Text", Rect(12, 12, 98, 48))
        val ocrOutside = CandidateBlock("Image Text", Rect(200, 200, 300, 250))
        
        val merged = CandidateMerger.merge(listOf(native), listOf(ocrInside, ocrOutside))
        
        assertEquals(2, merged.size)
        assertTrue(merged.contains(native))
        assertTrue(merged.contains(ocrOutside))
        assertFalse(merged.contains(ocrInside))
    }
}
```

- [ ] **Step 2: Write minimal implementation**

```kotlin
package com.translive.app.engine

import android.graphics.Rect
import com.translive.app.ui.overlay.CandidateBlock

object CandidateMerger {
    fun merge(nativeNodes: List<CandidateBlock>, ocrNodes: List<CandidateBlock>): List<CandidateBlock> {
        val merged = nativeNodes.toMutableList()
        for (ocr in ocrNodes) {
            val ocrArea = (ocr.boundingBox.width() * ocr.boundingBox.height()).toFloat()
            if (ocrArea <= 0) continue

            val isCovered = nativeNodes.any { native ->
                val intersect = Rect(ocr.boundingBox)
                if (intersect.intersect(native.boundingBox)) {
                    val intersectArea = (intersect.width() * intersect.height()).toFloat()
                    (intersectArea / ocrArea) > 0.50f
                } else {
                    false
                }
            }
            if (!isCovered) {
                merged.add(ocr)
            }
        }
        return merged
    }
}
```

- [ ] **Step 3: Run test to verify it passes**
Run: `./gradlew :app:testDebugUnitTest --tests "com.translive.app.engine.CandidateMergerTest"`
Expected: PASS

- [ ] **Step 4: Commit**
```bash
git add app/src/main/kotlin/com/translive/app/engine/CandidateMerger.kt app/src/test/kotlin/com/translive/app/engine/CandidateMergerTest.kt
git commit -m "feat: add CandidateMerger for native and OCR bounding boxes"
```

---

### Task 3: Integrate Hybrid OCR into Translation Pipeline

**Files:**
- Modify: `app/src/main/kotlin/com/translive/app/service/ScreenTranslateOverlayService.kt`
- Modify: `app/src/main/kotlin/com/translive/app/service/accessibility/ScreenAccessibilityService.kt`

**Interfaces:**
- Consumes: `CandidateMerger.merge`, `settings.ocrMode`

- [ ] **Step 1: Fix Accessibility Service Trigger**
In `ScreenAccessibilityService.kt`, update `accessibilityButtonCallback`.

```kotlin
// Remove the strict nodes.isNotEmpty() if-else blocks.
// Replace with:
val nodes = extractVisibleTextNodesFast()
captureSilentScreenshot(
    onSuccess = { bitmap ->
        ScreenTranslateOverlayService.translateNodesOneShot(this@ScreenAccessibilityService, nodes, bitmap)
    },
    onError = { err ->
        Log.e(TAG, "Screenshot fallback failed: $err")
        ScreenTranslateOverlayService.translateNodesOneShot(this@ScreenAccessibilityService, nodes, null)
    }
)
```

- [ ] **Step 2: Update `processAndShowArTranslation` logic**
In `ScreenTranslateOverlayService.kt`, update `rawCandidates` generation.

```kotlin
                  val rawCandidates: List<CandidateBlock> = withContext(Dispatchers.Default) {
                      val nativeBlocks = if (!directNodes.isNullOrEmpty()) {
                          directNodes.map { CandidateBlock(it.text, it.bounds) }
                      } else emptyList()

                      val doOcr = bitmap != null && (settings.ocrMode == OcrMode.FULL_SCAN || nativeBlocks.isEmpty())
                      val ocrBlocks = if (doOcr) {
                          val ocrResult = ocrEngine.recognize(bitmap!!, "auto")
                          val clusters = ArBoundingBoxClusterer.cluster(ocrResult)
                          clusters.map { CandidateBlock(it.consolidatedText, it.boundingBox) }
                      } else emptyList()

                      CandidateMerger.merge(nativeBlocks, ocrBlocks)
                  }
```

- [ ] **Step 3: Commit**
```bash
git add app/src/main/kotlin/com/translive/app/service/ScreenTranslateOverlayService.kt app/src/main/kotlin/com/translive/app/service/accessibility/ScreenAccessibilityService.kt
git commit -m "feat: integrate hybrid OCR merge into translation overlay pipeline"
```
