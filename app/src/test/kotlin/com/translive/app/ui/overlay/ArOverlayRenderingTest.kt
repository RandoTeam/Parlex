package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM Unit Test Suite for AR Screen Overlay Rendering Engine.
 */
class ArOverlayRenderingTest {

    data class SpatialRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)

        fun union(other: SpatialRect): SpatialRect {
            return SpatialRect(
                left = min(left, other.left),
                top = min(top, other.top),
                right = max(right, other.right),
                bottom = max(bottom, other.bottom)
            )
        }

        fun contains(x: Int, y: Int, padding: Int = 0): Boolean {
            return x >= left - padding && x <= right + padding &&
                y >= top - padding && y <= bottom + padding
        }

        fun calculateIoU(other: SpatialRect): Float {
            val interLeft = max(left, other.left)
            val interTop = max(top, other.top)
            val interRight = min(right, other.right)
            val interBottom = min(bottom, other.bottom)

            val interWidth = max(0, interRight - interLeft)
            val interHeight = max(0, interBottom - interTop)
            val interArea = interWidth * interHeight

            val areaA = width * height
            val areaB = other.width * other.height
            val unionArea = areaA + areaB - interArea

            return if (unionArea <= 0) 0f else interArea.toFloat() / unionArea.toFloat()
        }
    }

    data class ArOcrFragment(
        val id: String,
        val text: String,
        val box: SpatialRect,
        val frameTimestampMs: Long = 0L
    )

    data class ArRenderBox(
        val id: String,
        val rawText: String,
        val translatedText: String,
        val boundingBox: SpatialRect,
        val fragments: List<ArOcrFragment> = emptyList(),
        val frameTimestampMs: Long = 0L,
        val zIndex: Int = 0
    )

    class SpatioTemporalArClusterer(
        private val verticalGapRatio: Float = 1.35f,
        private val columnGutterFactor: Float = 1.4f,
        private val horizontalOverlapThreshold: Float = 0.30f
    ) {
        fun clusterLines(fragments: List<ArOcrFragment>): List<ArRenderBox> {
            if (fragments.isEmpty()) return emptyList()

            val sortedFragments = fragments.sortedWith(
                compareBy<ArOcrFragment> { it.box.top }
                    .thenBy { it.box.left }
            )

            val n = sortedFragments.size
            val parent = IntArray(n) { it }

            fun find(i: Int): Int {
                var root = i
                while (root != parent[root]) {
                    root = parent[root]
                }
                var curr = i
                while (curr != root) {
                    val nxt = parent[curr]
                    parent[curr] = root
                    curr = nxt
                }
                return root
            }

            fun union(i: Int, j: Int) {
                val rootI = find(i)
                val rootJ = find(j)
                if (rootI != rootJ) {
                    parent[rootI] = rootJ
                }
            }

            for (i in 0 until n) {
                val fA = sortedFragments[i]
                val hA = fA.box.height.coerceAtLeast(1).toFloat()
                for (j in i + 1 until n) {
                    val fB = sortedFragments[j]
                    val hB = fB.box.height.coerceAtLeast(1).toFloat()
                    val avgHeight = (hA + hB) / 2f

                    val verticalGap = fB.box.top - fA.box.bottom
                    val maxAllowedVerticalGap = avgHeight * verticalGapRatio

                    val isGutterSeparated = isColumnGutterSeparated(
                        fA.box,
                        fB.box,
                        avgHeight * columnGutterFactor
                    )

                    val overlap = calculateHorizontalOverlapRatio(fA.box, fB.box)
                    val sharesColumn = overlap >= horizontalOverlapThreshold ||
                        (!isGutterSeparated && abs(fA.box.left - fB.box.left) < avgHeight * columnGutterFactor)

                    val shouldMerge = verticalGap <= maxAllowedVerticalGap &&
                        verticalGap >= -(avgHeight * 0.5f) &&
                        !isGutterSeparated &&
                        sharesColumn

                    if (shouldMerge) {
                        union(i, j)
                    }
                }
            }

            val groupMap = mutableMapOf<Int, MutableList<ArOcrFragment>>()
            for (i in 0 until n) {
                val root = find(i)
                groupMap.getOrPut(root) { mutableListOf() }.add(sortedFragments[i])
            }

            return groupMap.values.mapIndexed { index, clusterItems ->
                val ordered = clusterItems.sortedWith(compareBy({ it.box.top }, { it.box.left }))
                val joinedText = ordered.joinToString(" ") { it.text.trim() }
                val unionBox = ordered.map { it.box }.reduce { acc, rect -> acc.union(rect) }

                ArRenderBox(
                    id = "cluster_$index",
                    rawText = joinedText,
                    translatedText = joinedText,
                    boundingBox = unionBox,
                    fragments = ordered,
                    frameTimestampMs = ordered.maxOfOrNull { it.frameTimestampMs } ?: 0L,
                    zIndex = index
                )
            }.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        }

        fun trackAcrossFrames(
            previousClusters: List<ArRenderBox>,
            newClusters: List<ArRenderBox>,
            iouThreshold: Float = 0.35f
        ): List<ArRenderBox> {
            return newClusters.map { newBox ->
                val matchedPrev = previousClusters.firstOrNull { prev ->
                    prev.boundingBox.calculateIoU(newBox.boundingBox) >= iouThreshold
                }
                if (matchedPrev != null) {
                    newBox.copy(id = matchedPrev.id)
                } else {
                    newBox
                }
            }
        }

        private fun isColumnGutterSeparated(a: SpatialRect, b: SpatialRect, minGutter: Float): Boolean {
            val noXOverlap = a.right < b.left || b.right < a.left
            val xDistance = if (a.right < b.left) (b.left - a.right) else (a.left - b.right)
            return noXOverlap && xDistance >= minGutter
        }

        private fun calculateHorizontalOverlapRatio(a: SpatialRect, b: SpatialRect): Float {
            val overlapLeft = max(a.left, b.left)
            val overlapRight = min(a.right, b.right)
            val overlapWidth = max(0, overlapRight - overlapLeft)
            val minWidth = min(a.width, b.width).coerceAtLeast(1)
            return overlapWidth.toFloat() / minWidth.toFloat()
        }
    }

    object ArTextFittingEngine {

        data class TextFittingResult(
            val fontSize: Float,
            val lineCount: Int,
            val lines: List<String>,
            val renderedWidth: Float,
            val renderedHeight: Float,
            val isOverflow: Boolean
        )

        fun fitTextToBounds(
            text: String,
            bounds: SpatialRect,
            minFontSize: Float = 10f,
            maxFontSize: Float = 36f,
            charWidthFactor: Float = 0.55f,
            lineHeightFactor: Float = 1.25f,
            paddingPx: Int = 4
        ): TextFittingResult {
            val targetWidth = max(1, bounds.width - 2 * paddingPx).toFloat()
            val targetHeight = max(1, bounds.height - 2 * paddingPx).toFloat()

            if (text.isBlank() || targetWidth <= 0f || targetHeight <= 0f) {
                return TextFittingResult(
                    fontSize = minFontSize,
                    lineCount = 0,
                    lines = emptyList(),
                    renderedWidth = 0f,
                    renderedHeight = 0f,
                    isOverflow = false
                )
            }

            var low = minFontSize
            var high = maxFontSize
            var bestResult: TextFittingResult? = null

            while (high - low >= 0.5f) {
                val midSize = (low + high) / 2f
                val layout = wrapText(text, midSize, targetWidth, charWidthFactor, lineHeightFactor)

                if (layout.renderedHeight <= targetHeight && layout.renderedWidth <= targetWidth) {
                    bestResult = layout
                    low = midSize
                } else {
                    high = midSize
                }
            }

            return bestResult ?: wrapText(
                text = text,
                fontSize = minFontSize,
                maxWidth = targetWidth,
                charWidthFactor = charWidthFactor,
                lineHeightFactor = lineHeightFactor
            ).let { minLayout ->
                minLayout.copy(
                    isOverflow = minLayout.renderedHeight > targetHeight || minLayout.renderedWidth > targetWidth
                )
            }
        }

        private fun wrapText(
            text: String,
            fontSize: Float,
            maxWidth: Float,
            charWidthFactor: Float,
            lineHeightFactor: Float
        ): TextFittingResult {
            val charWidth = fontSize * charWidthFactor
            val lineHeight = fontSize * lineHeightFactor
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

            val lines = mutableListOf<String>()
            var currentLine = StringBuilder()
            var currentLineWidth = 0f
            var maxLineWidthObserved = 0f

            for (word in words) {
                val wordWidth = word.length * charWidth
                val spaceWidth = if (currentLine.isEmpty()) 0f else charWidth

                if (currentLineWidth + spaceWidth + wordWidth <= maxWidth || currentLine.isEmpty()) {
                    if (currentLine.isNotEmpty()) {
                        currentLine.append(" ")
                    }
                    currentLine.append(word)
                    currentLineWidth += spaceWidth + wordWidth
                } else {
                    lines.add(currentLine.toString())
                    maxLineWidthObserved = max(maxLineWidthObserved, currentLineWidth)
                    currentLine = StringBuilder(word)
                    currentLineWidth = wordWidth
                }
            }

            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                maxLineWidthObserved = max(maxLineWidthObserved, currentLineWidth)
            }

            val totalHeight = lines.size * lineHeight

            return TextFittingResult(
                fontSize = fontSize,
                lineCount = lines.size,
                lines = lines,
                renderedWidth = maxLineWidthObserved,
                renderedHeight = totalHeight,
                isOverflow = false
            )
        }
    }

    object ArTouchHitTester {

        data class HitResult(
            val hitBox: ArRenderBox?,
            val isBackgroundHit: Boolean,
            val touchX: Int,
            val touchY: Int
        )

        fun hitTest(
            boxes: List<ArRenderBox>,
            touchX: Int,
            touchY: Int,
            touchSlopPadding: Int = 8
        ): HitResult {
            val sortedBoxes = boxes.sortedByDescending { it.zIndex }

            for (box in sortedBoxes) {
                if (box.boundingBox.contains(touchX, touchY, touchSlopPadding)) {
                    return HitResult(
                        hitBox = box,
                        isBackgroundHit = false,
                        touchX = touchX,
                        touchY = touchY
                    )
                }
            }

            return HitResult(
                hitBox = null,
                isBackgroundHit = true,
                touchX = touchX,
                touchY = touchY
            )
        }
    }

    private lateinit var clusterer: SpatioTemporalArClusterer

    @Before
    fun setUp() {
        clusterer = SpatioTemporalArClusterer()
    }

    @Test
    fun testEmptyFragmentsReturnsEmptyList() {
        val result = clusterer.clusterLines(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun testSingleFragmentProducesSingleCluster() {
        val fragment = ArOcrFragment(
            id = "f1",
            text = "Welcome",
            box = SpatialRect(left = 50, top = 100, right = 200, bottom = 130)
        )

        val clusters = clusterer.clusterLines(listOf(fragment))
        assertEquals(1, clusters.size)
        assertEquals("Welcome", clusters[0].rawText)
        assertEquals(50, clusters[0].boundingBox.left)
        assertEquals(100, clusters[0].boundingBox.top)
        assertEquals(200, clusters[0].boundingBox.right)
        assertEquals(130, clusters[0].boundingBox.bottom)
    }

    @Test
    fun testVerticallyAdjacentLinesInSameColumnMergeIntoSingleUnionBox() {
        val line1 = ArOcrFragment(
            id = "f1",
            text = "The quick brown fox",
            box = SpatialRect(left = 50, top = 100, right = 400, bottom = 130)
        )
        val line2 = ArOcrFragment(
            id = "f2",
            text = "jumps over lazy dog",
            box = SpatialRect(left = 50, top = 136, right = 380, bottom = 166)
        )
        val line3 = ArOcrFragment(
            id = "f3",
            text = "and runs away fast",
            box = SpatialRect(left = 50, top = 172, right = 410, bottom = 202)
        )

        val clusters = clusterer.clusterLines(listOf(line1, line2, line3))
        assertEquals(1, clusters.size)

        val cluster = clusters[0]
        assertEquals("The quick brown fox jumps over lazy dog and runs away fast", cluster.rawText)
        assertEquals(3, cluster.fragments.size)
        assertEquals(50, cluster.boundingBox.left)
        assertEquals(100, cluster.boundingBox.top)
        assertEquals(410, cluster.boundingBox.right)
        assertEquals(202, cluster.boundingBox.bottom)
    }

    @Test
    fun testLargeVerticalGapSplitsIntoSeparateClusters() {
        val p1Line = ArOcrFragment(
            id = "f1",
            text = "Paragraph One Header",
            box = SpatialRect(left = 50, top = 100, right = 300, bottom = 130)
        )
        val p2Line = ArOcrFragment(
            id = "f2",
            text = "Paragraph Two Body",
            box = SpatialRect(left = 50, top = 250, right = 320, bottom = 280)
        )

        val clusters = clusterer.clusterLines(listOf(p1Line, p2Line))
        assertEquals(2, clusters.size)
        assertEquals("Paragraph One Header", clusters[0].rawText)
        assertEquals("Paragraph Two Body", clusters[1].rawText)
        assertEquals(100, clusters[0].boundingBox.top)
        assertEquals(250, clusters[1].boundingBox.top)
    }

    @Test
    fun testMultiColumnLayoutWithGutterRemainsDistinctClusters() {
        val col1Line1 = ArOcrFragment(
            id = "c1_l1",
            text = "Left column line 1",
            box = SpatialRect(left = 50, top = 100, right = 250, bottom = 130)
        )
        val col2Line1 = ArOcrFragment(
            id = "c2_l1",
            text = "Right column line 1",
            box = SpatialRect(left = 450, top = 100, right = 650, bottom = 130)
        )
        val col1Line2 = ArOcrFragment(
            id = "c1_l2",
            text = "Left column line 2",
            box = SpatialRect(left = 50, top = 136, right = 240, bottom = 166)
        )
        val col2Line2 = ArOcrFragment(
            id = "c2_l2",
            text = "Right column line 2",
            box = SpatialRect(left = 450, top = 136, right = 660, bottom = 166)
        )

        val clusters = clusterer.clusterLines(listOf(col1Line1, col2Line1, col1Line2, col2Line2))
        assertEquals(2, clusters.size)

        val leftCol = clusters.first { it.boundingBox.left < 300 }
        val rightCol = clusters.first { it.boundingBox.left >= 300 }

        assertEquals("Left column line 1 Left column line 2", leftCol.rawText)
        assertEquals("Right column line 1 Right column line 2", rightCol.rawText)
        assertEquals(50, leftCol.boundingBox.left)
        assertEquals(450, rightCol.boundingBox.left)
    }

    @Test
    fun testMultiFrameTemporalStabilityPreservesClusterId() {
        val frame1Fragments = listOf(
            ArOcrFragment("f1", "Live Subtitle Text", SpatialRect(100, 500, 400, 540), frameTimestampMs = 1000L)
        )
        val frame1Clusters = clusterer.clusterLines(frame1Fragments)
        assertEquals(1, frame1Clusters.size)
        val originalClusterId = frame1Clusters[0].id

        val frame2Fragments = listOf(
            ArOcrFragment("f2", "Live Subtitle Text", SpatialRect(102, 502, 402, 542), frameTimestampMs = 1033L)
        )
        val frame2RawClusters = clusterer.clusterLines(frame2Fragments)
        val frame2Tracked = clusterer.trackAcrossFrames(frame1Clusters, frame2RawClusters)

        assertEquals(1, frame2Tracked.size)
        assertEquals(originalClusterId, frame2Tracked[0].id)
    }

    @Test
    fun testShortTranslationFitsAtMaxFontSize() {
        val bounds = SpatialRect(left = 0, top = 0, right = 400, bottom = 100)
        val text = "OK"

        val result = ArTextFittingEngine.fitTextToBounds(
            text = text,
            bounds = bounds,
            minFontSize = 12f,
            maxFontSize = 32f
        )

        assertEquals(32f, result.fontSize, 1.0f)
        assertEquals(1, result.lineCount)
        assertFalse(result.isOverflow)
        assertTrue(result.renderedWidth <= bounds.width)
        assertTrue(result.renderedHeight <= bounds.height)
    }

    @Test
    fun testModerateTranslationScalesDownWithoutOverflow() {
        val bounds = SpatialRect(left = 0, top = 0, right = 200, bottom = 40)
        val text = "Select Payment Method"

        val result = ArTextFittingEngine.fitTextToBounds(
            text = text,
            bounds = bounds,
            minFontSize = 10f,
            maxFontSize = 30f
        )

        assertTrue(result.fontSize < 30f)
        assertTrue(result.fontSize >= 10f)
        assertFalse(result.isOverflow)
        assertTrue(result.renderedWidth <= bounds.width)
        assertTrue(result.renderedHeight <= bounds.height)
    }

    @Test
    fun testLongTranslationWrapsIntoMultipleLinesWithinBounds() {
        val bounds = SpatialRect(left = 0, top = 0, right = 300, bottom = 150)
        val text = "This is a detailed description of the product features and technical specifications."

        val result = ArTextFittingEngine.fitTextToBounds(
            text = text,
            bounds = bounds,
            minFontSize = 10f,
            maxFontSize = 24f
        )

        assertTrue(result.lineCount > 1)
        assertFalse(result.isOverflow)
        assertTrue(result.renderedWidth <= bounds.width)
        assertTrue(result.renderedHeight <= bounds.height)
    }

    @Test
    fun testExtremeTranslationAtMinFontSizeReportsOverflowIfExceeded() {
        val tinyBounds = SpatialRect(left = 0, top = 0, right = 40, bottom = 15)
        val hugeText = "Supercalifragilisticexpialidocious extra long impossible text sentence"

        val result = ArTextFittingEngine.fitTextToBounds(
            text = hugeText,
            bounds = tinyBounds,
            minFontSize = 10f,
            maxFontSize = 24f
        )

        assertEquals(10f, result.fontSize, 0.1f)
        assertTrue(result.isOverflow)
    }

    @Test
    fun testWordWrappingPreservesWordBoundaries() {
        val bounds = SpatialRect(left = 0, top = 0, right = 150, bottom = 100)
        val text = "Alpha Beta Gamma Delta"

        val result = ArTextFittingEngine.fitTextToBounds(
            text = text,
            bounds = bounds,
            minFontSize = 14f,
            maxFontSize = 14f
        )

        for (line in result.lines) {
            val wordsInLine = line.split(" ")
            for (word in wordsInLine) {
                assertTrue(listOf("Alpha", "Beta", "Gamma", "Delta").contains(word))
            }
        }
    }

    @Test
    fun testDirectInteriorHitReturnsSpecificBox() {
        val boxA = ArRenderBox("box_A", "Raw A", "Trans A", SpatialRect(100, 100, 300, 200), zIndex = 0)
        val boxB = ArRenderBox("box_B", "Raw B", "Trans B", SpatialRect(100, 400, 300, 500), zIndex = 1)
        val boxes = listOf(boxA, boxB)

        val resultA = ArTouchHitTester.hitTest(boxes, touchX = 150, touchY = 150)
        assertFalse(resultA.isBackgroundHit)
        assertNotNull(resultA.hitBox)
        assertEquals("box_A", resultA.hitBox?.id)

        val resultB = ArTouchHitTester.hitTest(boxes, touchX = 200, touchY = 450)
        assertFalse(resultB.isBackgroundHit)
        assertNotNull(resultB.hitBox)
        assertEquals("box_B", resultB.hitBox?.id)
    }

    @Test
    fun testHitOutsideAllBoxesRegistersAsBackground() {
        val box = ArRenderBox("box_1", "Raw", "Trans", SpatialRect(100, 100, 300, 200))
        val boxes = listOf(box)

        val result = ArTouchHitTester.hitTest(boxes, touchX = 50, touchY = 50)
        assertTrue(result.isBackgroundHit)
        assertNull(result.hitBox)
    }

    @Test
    fun testHitWithinSlopPaddingRegistersAsBoxHit() {
        val box = ArRenderBox("box_1", "Raw", "Trans", SpatialRect(100, 100, 300, 200))
        val boxes = listOf(box)

        val slopHit = ArTouchHitTester.hitTest(boxes, touchX = 304, touchY = 150, touchSlopPadding = 8)
        assertFalse(slopHit.isBackgroundHit)
        assertEquals("box_1", slopHit.hitBox?.id)

        val outsideHit = ArTouchHitTester.hitTest(boxes, touchX = 312, touchY = 150, touchSlopPadding = 8)
        assertTrue(outsideHit.isBackgroundHit)
        assertNull(outsideHit.hitBox)
    }

    @Test
    fun testOverlappingBoxesResolvesToTopmostZOrder() {
        val bottomBox = ArRenderBox("bottom", "Bottom Layer", "Bottom Layer", SpatialRect(100, 100, 300, 300), zIndex = 0)
        val topBox = ArRenderBox("top", "Top Layer", "Top Layer", SpatialRect(150, 150, 250, 250), zIndex = 5)
        val boxes = listOf(bottomBox, topBox)

        val result = ArTouchHitTester.hitTest(boxes, touchX = 200, touchY = 200)
        assertFalse(result.isBackgroundHit)
        assertEquals("top", result.hitBox?.id)
    }

    @Test
    fun testExactBoundaryCoordinatesHitBox() {
        val box = ArRenderBox("box_edge", "Edge", "Edge", SpatialRect(100, 100, 200, 200))
        val boxes = listOf(box)

        val leftTop = ArTouchHitTester.hitTest(boxes, touchX = 100, touchY = 100, touchSlopPadding = 0)
        assertEquals("box_edge", leftTop.hitBox?.id)

        val rightBottom = ArTouchHitTester.hitTest(boxes, touchX = 200, touchY = 200, touchSlopPadding = 0)
        assertEquals("box_edge", rightBottom.hitBox?.id)
    }

    @Test
    fun testNegativeAndFarOffscreenCoordinatesRegisterAsBackground() {
        val box = ArRenderBox("box_1", "Text", "Text", SpatialRect(100, 100, 200, 200))
        val boxes = listOf(box)

        val negResult = ArTouchHitTester.hitTest(boxes, touchX = -10, touchY = -50)
        assertTrue(negResult.isBackgroundHit)
        assertNull(negResult.hitBox)

        val farResult = ArTouchHitTester.hitTest(boxes, touchX = 5000, touchY = 9000)
        assertTrue(farResult.isBackgroundHit)
        assertNull(farResult.hitBox)
    }
}
