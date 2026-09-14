package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * TDD Test Suite for Column-Aware Downward Elastic Collision Engine (Task 4).
 * Verifies column-aware collision detection (>= 25% horizontal overlap)
 * and elastic pushdown / gap compression logic.
 */
class ArElasticRelaxationTest {

    data class SimRectF(
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)

        fun offset(dx: Float, dy: Float) {
            left += dx
            top += dy
            right += dx
            bottom += dy
        }

        fun intersects(other: SimRectF): Boolean {
            return left < other.right && other.left < right && top < other.bottom && other.top < bottom
        }
    }

    data class SimBox(
        val id: String,
        val rect: SimRectF,
        var textOffsetY: Float = rect.top
    )

    companion object {
        fun calculateHorizontalOverlapRatio(a: SimRectF, b: SimRectF): Float {
            val overlapLeft = max(a.left, b.left)
            val overlapRight = min(a.right, b.right)
            val overlapWidth = max(0f, overlapRight - overlapLeft)
            val minWidth = min(a.width, b.width)
            if (minWidth <= 0f) return 0f
            return overlapWidth / minWidth
        }

        fun resolveSimOverlaps(
            boxes: List<SimBox>,
            maxScreenH: Float,
            topMargin: Float,
            bottomMargin: Float,
            density: Float = 3.0f,
            minColOverlap: Float = 0.20f
        ) {
            if (boxes.size < 2) return
            var gapPx = 4f * density

            for (pass in 0 until 4) {
                val sorted = boxes.sortedBy { it.rect.top }
                var anyShifted = false

                for (i in 0 until sorted.size) {
                    val current = sorted[i]
                    var highestRequiredTop = current.rect.top
                    for (j in 0 until i) {
                        val prev = sorted[j]
                        val colOverlap = calculateHorizontalOverlapRatio(prev.rect, current.rect)
                        if (colOverlap >= minColOverlap) {
                            val neededTop = prev.rect.bottom + gapPx
                            if (neededTop > highestRequiredTop) {
                                highestRequiredTop = neededTop
                            }
                        }
                    }

                    val pushDown = highestRequiredTop - current.rect.top
                    if (pushDown > 0.5f) {
                        current.rect.offset(0f, pushDown)
                        current.textOffsetY += pushDown
                        anyShifted = true
                    }
                }

                // Upward screen containment preserving relative spacing
                val lowestBottom = sorted.maxOfOrNull { it.rect.bottom } ?: 0f
                val screenLimit = maxScreenH - bottomMargin
                if (lowestBottom > screenLimit) {
                    val overflow = lowestBottom - screenLimit
                    val minTop = sorted.minOfOrNull { it.rect.top } ?: 0f
                    val availableHeadroom = max(0f, minTop - topMargin)
                    val upwardShift = min(overflow, availableHeadroom)
                    if (upwardShift > 0.5f) {
                        for (box in sorted) {
                            box.rect.offset(0f, -upwardShift)
                            box.textOffsetY -= upwardShift
                        }
                        anyShifted = true
                    }

                    if (gapPx > 2f * density) {
                        gapPx = 2f * density
                        anyShifted = true
                    }

                    // If still overflowing after upward shift and gap compression:
                    val remainingOverflow = (sorted.maxOfOrNull { it.rect.bottom } ?: 0f) - screenLimit
                    if (remainingOverflow > 0.5f) {
                        var nextTop = screenLimit
                        for (k in sorted.indices.reversed()) {
                            val item = sorted[k]
                            if (item.rect.bottom > nextTop) {
                                val pullUp = item.rect.bottom - nextTop
                                item.rect.offset(0f, -pullUp)
                                item.textOffsetY -= pullUp
                                anyShifted = true
                            }
                            nextTop = item.rect.top - gapPx
                        }
                    }
                }

                if (!anyShifted) break
            }
        }
    }

    @Test
    fun multiColumnLayout_doesNotPushDistinctColumns() {
        // Left column: x = 50..400, top = 200..300
        val leftCol = SimBox("left", SimRectF(50f, 200f, 400f, 300f))
        // Right column: x = 500..850, top = 220..320
        val rightCol = SimBox("right", SimRectF(500f, 220f, 850f, 320f))

        val overlapRatio = calculateHorizontalOverlapRatio(leftCol.rect, rightCol.rect)
        assertEquals("Different columns have zero horizontal overlap", 0f, overlapRatio, 0.001f)

        resolveSimOverlaps(
            boxes = listOf(leftCol, rightCol),
            maxScreenH = 2000f,
            topMargin = 100f,
            bottomMargin = 100f
        )

        assertEquals("Left column top unchanged", 200f, leftCol.rect.top, 0.001f)
        assertEquals("Right column top unchanged (NOT pushed down)", 220f, rightCol.rect.top, 0.001f)
    }

    @Test
    fun sameColumnCollision_pushesLowerBoxDownward() {
        // Upper headline: x = 100..700, top = 200..260
        val upper = SimBox("upper", SimRectF(100f, 200f, 700f, 260f))
        // Lower paragraph overlapping: x = 100..700, top = 240..340 (overlapping by 20px)
        val lower = SimBox("lower", SimRectF(100f, 240f, 700f, 340f))

        val overlapRatio = calculateHorizontalOverlapRatio(upper.rect, lower.rect)
        assertTrue("Sharing same column has >= 25% overlap", overlapRatio >= 0.25f)

        resolveSimOverlaps(
            boxes = listOf(upper, lower),
            maxScreenH = 2000f,
            topMargin = 100f,
            bottomMargin = 100f,
            density = 3f
        )

        val expectedGap = 4f * 3f // 12px
        assertEquals("Upper box remains at 200", 200f, upper.rect.top, 0.001f)
        assertEquals("Lower box pushed to upper.bottom + gap", 260f + expectedGap, lower.rect.top, 0.001f)
        assertEquals("TextOffsetY synchronized", 260f + expectedGap, lower.textOffsetY, 0.001f)
    }

    @Test
    fun screenBottomOverflow_compressesGapsElastic() {
        // Screen height 500, bottomMargin 50 -> maxBottom = 450
        val b1 = SimBox("b1", SimRectF(100f, 200f, 700f, 300f))
        val b2 = SimBox("b2", SimRectF(100f, 300f, 700f, 445f)) // would push past 450 with 12px gap

        resolveSimOverlaps(
            boxes = listOf(b1, b2),
            maxScreenH = 500f,
            topMargin = 50f,
            bottomMargin = 50f,
            density = 3f
        )

        assertTrue("Lower box bottom must not exceed maxBottom", b2.rect.bottom <= 450f)
    }

    @Test
    fun interleavedColumns_resolvesOverlapsAcrossDifferentInterleavedBoxes() {
        // Col 1 Box A: x = 100..400, top = 200..300
        val col1A = SimBox("col1A", SimRectF(100f, 200f, 400f, 300f))
        // Col 2 Box B: x = 500..800, top = 220..320 (interleaved by Y)
        val col2B = SimBox("col2B", SimRectF(500f, 220f, 800f, 320f))
        // Col 1 Box C: x = 100..400, top = 280..380 (overlaps Box A by 20px)
        val col1C = SimBox("col1C", SimRectF(100f, 280f, 400f, 380f))

        resolveSimOverlaps(
            boxes = listOf(col1A, col2B, col1C),
            maxScreenH = 2000f,
            topMargin = 100f,
            bottomMargin = 100f,
            density = 3f
        )

        val expectedGap = 4f * 3f // 12px
        assertEquals("Col 1 Box A remains at 200", 200f, col1A.rect.top, 0.001f)
        assertEquals("Col 2 Box B remains at 220 (not in same column)", 220f, col2B.rect.top, 0.001f)
        // Col 1 Box C must be pushed below Col 1 Box A, even though Col 2 Box B was interleaved between them!
        assertEquals("Col 1 Box C must be pushed below Col 1 Box A", 300f + expectedGap, col1C.rect.top, 0.001f)
        assertFalse("Col 1 Box A and Col 1 Box C must not intersect", col1A.rect.intersects(col1C.rect))
    }
}
