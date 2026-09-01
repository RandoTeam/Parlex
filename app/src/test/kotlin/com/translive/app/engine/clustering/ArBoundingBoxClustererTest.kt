package com.translive.app.engine.clustering

import android.graphics.Rect
import com.translive.app.engine.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArBoundingBoxClustererTest {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    @Test
    fun clusterLines_empty_returnsEmptyList() {
        val result = ArBoundingBoxClusterer.clusterLines(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun clusterLines_adjacentParagraphLines_clustersIntoSingleParagraph() {
        // Line 1: height 40px, top 100, bottom 140, left 50, right 450
        val line1 = OcrLine("This is the first line of a", rect(50, 100, 450, 140))
        // Line 2: height 40px, top 150 (gap of 10px = 0.25x height), bottom 190, left 50, right 420
        val line2 = OcrLine("natural reading paragraph.", rect(50, 150, 420, 190))

        val clusters = ArBoundingBoxClusterer.clusterLines(listOf(line1, line2))

        assertEquals(1, clusters.size)
        assertEquals("This is the first line of a natural reading paragraph.", clusters[0].consolidatedText)
        assertEquals(2, clusters[0].lineCount)
        assertEquals(50, clusters[0].boundingBox.left)
        assertEquals(100, clusters[0].boundingBox.top)
        assertEquals(450, clusters[0].boundingBox.right)
        assertEquals(190, clusters[0].boundingBox.bottom)
    }

    @Test
    fun clusterLines_distantElements_remainSeparateClusters() {
        // Title at top: top 50, bottom 90
        val header = OcrLine("Header Title", rect(50, 50, 300, 90))
        // Distant button at bottom: top 400, bottom 450
        val button = OcrLine("Submit Action", rect(50, 400, 250, 450))

        val clusters = ArBoundingBoxClusterer.clusterLines(listOf(header, button))

        assertEquals(2, clusters.size)
        assertEquals("Header Title", clusters[0].consolidatedText)
        assertEquals("Submit Action", clusters[1].consolidatedText)
    }

    @Test
    fun clusterLines_hyphenatedLineWrap_dehyphenatesCorrectly() {
        val line1 = OcrLine("We are implemen-", rect(50, 100, 350, 140))
        val line2 = OcrLine("ting fast translation.", rect(50, 150, 380, 190))

        val clusters = ArBoundingBoxClusterer.clusterLines(listOf(line1, line2))

        assertEquals(1, clusters.size)
        assertEquals("We are implementing fast translation.", clusters[0].consolidatedText)
    }
}
