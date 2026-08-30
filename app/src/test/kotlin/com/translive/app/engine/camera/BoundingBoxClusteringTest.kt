package com.translive.app.engine.camera

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundingBoxClusteringTest {

    @Test
    fun testEmptyFragmentsReturnsEmpty() {
        val lines = BoundingBoxClustering.mergeInlineFragments(emptyList())
        val blocks = BoundingBoxClustering.clusterParagraphBlocks(lines)
        assertTrue(lines.isEmpty())
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun testSingleFragmentClustersIntoOneParagraph() {
        val r = Rect().apply { left = 10; top = 10; right = 100; bottom = 30 }
        val fragment = TextFragment(
            id = "f1",
            text = "Hello world",
            box = r,
            confidence = 0.95f
        )
        val lines = BoundingBoxClustering.mergeInlineFragments(listOf(fragment))
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)

        val blocks = BoundingBoxClustering.clusterParagraphBlocks(lines)
        assertEquals(1, blocks.size)
        assertEquals("Hello world", blocks[0].text)
        assertEquals(10, blocks[0].boundingBox.left)
        assertEquals(100, blocks[0].boundingBox.right)
    }

    @Test
    fun testHorizontalMergeOnSameBaseline() {
        val r1 = Rect().apply { left = 10; top = 10; right = 50; bottom = 30 }
        val r2 = Rect().apply { left = 60; top = 10; right = 110; bottom = 30 }
        val f1 = TextFragment(id = "f1", text = "Hello", box = r1)
        val f2 = TextFragment(id = "f2", text = "World", box = r2)

        val lines = BoundingBoxClustering.mergeInlineFragments(listOf(f1, f2))
        assertEquals(1, lines.size)
        assertEquals("Hello World", lines[0].text)
        assertEquals(10, lines[0].box.left)
        assertEquals(110, lines[0].box.right)
    }

    @Test
    fun testVerticalParagraphClusteringWithinLineGap() {
        val r1 = Rect().apply { left = 10; top = 10; right = 200; bottom = 30 }
        val r2 = Rect().apply { left = 10; top = 35; right = 190; bottom = 55 }
        val line1 = ClusteredLine(id = "l1", text = "First paragraph line 1", box = r1)
        val line2 = ClusteredLine(id = "l2", text = "First paragraph line 2", box = r2)

        val blocks = BoundingBoxClustering.clusterParagraphBlocks(listOf(line1, line2))
        assertEquals(1, blocks.size)
        assertEquals("First paragraph line 1\nFirst paragraph line 2", blocks[0].text)
        assertEquals(2, blocks[0].lines.size)
        assertEquals(10, blocks[0].boundingBox.left)
        assertEquals(200, blocks[0].boundingBox.right)
        assertEquals(55, blocks[0].boundingBox.bottom)
    }

    @Test
    fun testSplitOnLargeVerticalGap() {
        val r1 = Rect().apply { left = 10; top = 10; right = 150; bottom = 30 }
        val r2 = Rect().apply { left = 10; top = 100; right = 160; bottom = 120 }
        val line1 = ClusteredLine(id = "l1", text = "Paragraph One", box = r1)
        val line2 = ClusteredLine(id = "l2", text = "Paragraph Two", box = r2)

        val blocks = BoundingBoxClustering.clusterParagraphBlocks(listOf(line1, line2))
        assertEquals(2, blocks.size)
        assertEquals("Paragraph One", blocks[0].text)
        assertEquals("Paragraph Two", blocks[1].text)
    }

    @Test
    fun testLuminanceCalculationAndContrastColor() {
        val white = -1
        val black = -16777216
        val whiteLuminance = ColorSamplingAndLuminance.calculateLuminance(white)
        assertTrue(whiteLuminance > 250f)

        val blackLuminance = ColorSamplingAndLuminance.calculateLuminance(black)
        assertTrue(blackLuminance < 5f)

        val darkBg = SampledBackground(
            topColor = -12303292,
            bottomColor = black,
            primaryTextColor = white,
            strokeColor = black,
            isDarkBackground = true
        )
        assertTrue(darkBg.isDarkBackground)

        val lightBg = SampledBackground(
            topColor = white,
            bottomColor = -3355444,
            primaryTextColor = black,
            strokeColor = white,
            isDarkBackground = false
        )
        assertFalse(lightBg.isDarkBackground)
    }

    @Test
    fun testUnionRects() {
        val r1 = Rect().apply { left = 10; top = 20; right = 50; bottom = 60 }
        val r2 = Rect().apply { left = 30; top = 10; right = 80; bottom = 70 }
        val union = BoundingBoxClustering.unionRects(listOf(r1, r2))
        assertEquals(10, union.left)
        assertEquals(10, union.top)
        assertEquals(80, union.right)
        assertEquals(70, union.bottom)
    }
}
