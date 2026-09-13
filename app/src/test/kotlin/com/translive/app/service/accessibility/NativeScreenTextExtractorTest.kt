package com.translive.app.service.accessibility

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeScreenTextExtractorTest {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    @Test
    fun filterAndDeduplicate_dropsContainerWhenChildrenCoverIt() {
        // Parent container covering children
        val parent = ExtractedTextNode(
            id = "parent",
            text = "Breaking News 10:45 AM",
            bounds = rect(0, 100, 500, 200)
        )
        // Child 1: Title
        val child1 = ExtractedTextNode(
            id = "child1",
            text = "Breaking News",
            bounds = rect(10, 110, 350, 190)
        )
        // Child 2: Timestamp
        val child2 = ExtractedTextNode(
            id = "child2",
            text = "10:45 AM",
            bounds = rect(360, 110, 490, 190)
        )

        val result = NativeScreenTextExtractor.filterAndDeduplicate(
            listOf(parent, child1, child2)
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "child1" })
        assertTrue(result.any { it.id == "child2" })
        assertTrue(result.none { it.id == "parent" })
    }

    @Test
    fun filterAndDeduplicate_dropsEmptyAndPunctuationOnly() {
        val valid = ExtractedTextNode("v", "Valid Text Here", rect(10, 10, 100, 50))
        val blank = ExtractedTextNode("b", "   ", rect(10, 60, 100, 100))
        val symbolsOnly = ExtractedTextNode("s", "---", rect(10, 110, 100, 150))

        val result = NativeScreenTextExtractor.filterAndDeduplicate(listOf(valid, blank, symbolsOnly))

        assertEquals(1, result.size)
        assertEquals("Valid Text Here", result[0].text)
    }

    @Test
    fun sortByReadingOrder_ordersTopToBottomLeftToRight() {
        // Line 1: left and right elements
        val line1Right = ExtractedTextNode("l1r", "Profile", rect(300, 50, 400, 90))
        val line1Left = ExtractedTextNode("l1l", "Home", rect(50, 52, 150, 92))

        // Line 2: body paragraph
        val line2 = ExtractedTextNode("l2", "Welcome to the application", rect(50, 150, 450, 200))

        // Line 3: button
        val line3 = ExtractedTextNode("l3", "Continue", rect(50, 300, 250, 350))

        val unsorted = listOf(line3, line1Right, line2, line1Left)
        val sorted = NativeScreenTextExtractor.sortByReadingOrder(unsorted, yBandPx = 20)

        assertEquals(4, sorted.size)
        assertEquals("l1l", sorted[0].id)
        assertEquals("l1r", sorted[1].id)
        assertEquals("l2", sorted[2].id)
        assertEquals("l3", sorted[3].id)
    }
}
