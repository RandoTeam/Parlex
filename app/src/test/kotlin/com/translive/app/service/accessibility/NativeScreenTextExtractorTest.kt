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

    @Test
    fun filterAndDeduplicate_preservesParentParagraph_whenEnclosedChildrenAreInlineFormattedFragments() {
        // Full cohesive snippet from web DOM
        val snippetParent = ExtractedTextNode(
            id = "snippet_p",
            text = "Android Police is a digital tech publication founded in 2010. Focused on Android and Google, Android Police is one of the most authoritative and...",
            bounds = rect(64, 815, 1318, 1050)
        )

        // Chromium/Gecko leaf nodes from bold tags <b>Android</b>, <b>Google</b>
        val boldAndroid1 = ExtractedTextNode(
            id = "bold_android_1",
            text = "Android",
            bounds = rect(540, 900, 730, 965)
        )
        val boldGoogle = ExtractedTextNode(
            id = "bold_google",
            text = "Google",
            bounds = rect(830, 900, 990, 965)
        )
        val boldAndroidPolice = ExtractedTextNode(
            id = "bold_android_police",
            text = "Android Police",
            bounds = rect(1045, 900, 1275, 965)
        )

        val result = NativeScreenTextExtractor.filterAndDeduplicate(
            listOf(snippetParent, boldAndroid1, boldGoogle, boldAndroidPolice)
        )

        // Parent must be preserved, individual word fragments suppressed
        assertEquals(1, result.size)
        assertEquals("snippet_p", result[0].id)
        assertEquals(snippetParent.bounds.left, result[0].bounds.left)
        assertEquals(snippetParent.bounds.top, result[0].bounds.top)
        assertEquals(snippetParent.bounds.right, result[0].bounds.right)
        assertEquals(snippetParent.bounds.bottom, result[0].bounds.bottom)
    }

    @Test
    fun filterAndDeduplicate_preservesParentTitle_whenEnclosedChildrenAreBoldWords() {
        // Link header
        val titleParent = ExtractedTextNode(
            id = "title_a",
            text = "Android Police - YouTube",
            bounds = rect(64, 685, 948, 765)
        )
        // Child word fragment
        val boldWord = ExtractedTextNode(
            id = "title_word",
            text = "Android",
            bounds = rect(64, 685, 250, 765)
        )

        val result = NativeScreenTextExtractor.filterAndDeduplicate(
            listOf(titleParent, boldWord)
        )

        assertEquals(1, result.size)
        assertEquals("title_a", result[0].id)
        assertEquals("Android Police - YouTube", result[0].text)
    }

    @Test
    fun filterAndDeduplicate_dropsLargeDocumentContainer_preservingMultipleDistinctCards() {
        val screenBounds = rect(0, 0, 1440, 3168)

        // Card 1: Main search result
        val card1 = ExtractedTextNode(
            id = "card1",
            text = "Android Police: Android news, reviews, guides, editorials, and...",
            bounds = rect(48, 412, 1392, 750)
        )
        // Card 2: Sub-result
        val card2 = ExtractedTextNode(
            id = "card2",
            text = "Android | Android Police",
            bounds = rect(48, 810, 1392, 1150)
        )
        // Card 3: YouTube link
        val card3 = ExtractedTextNode(
            id = "card3",
            text = "Android Police - YouTube",
            bounds = rect(48, 1210, 1392, 1550)
        )
        // Footer: Settings
        val footer = ExtractedTextNode(
            id = "footer",
            text = "Nastavení vyhledávání",
            bounds = rect(48, 2600, 1392, 2800)
        )

        // Giant GeckoView / WebView document root container enclosing all cards
        val pageRoot = ExtractedTextNode(
            id = "page_root",
            text = "${card1.text} ${card2.text} ${card3.text} ${footer.text}",
            bounds = rect(0, 200, 1440, 2800),
            className = "org.mozilla.geckoview.GeckoView"
        )

        val result = NativeScreenTextExtractor.filterAndDeduplicate(
            listOf(card1, card2, card3, footer, pageRoot),
            screenBounds = screenBounds
        )

        // Page container MUST be dropped, and all 4 individual cards MUST be preserved
        assertEquals(4, result.size)
        assertTrue(result.any { it.id == "card1" })
        assertTrue(result.any { it.id == "card2" })
        assertTrue(result.any { it.id == "card3" })
        assertTrue(result.any { it.id == "footer" })
        assertTrue(result.none { it.id == "page_root" })
    }
}
