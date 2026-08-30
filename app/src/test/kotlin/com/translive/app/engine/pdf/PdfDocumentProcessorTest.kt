package com.translive.app.engine.pdf

import android.graphics.Rect
import com.translive.app.engine.OcrBlock
import com.translive.app.engine.OcrLine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfDocumentProcessorTest {

    private val processor = PdfDocumentProcessor()

    @Test
    fun `buildParagraphs merges consecutive lines and removes hyphenation`() {
        val r1 = Rect().apply { left = 10; top = 10; right = 200; bottom = 30 }
        val r2 = Rect().apply { left = 10; top = 35; right = 220; bottom = 55 }
        val rBlock = Rect().apply { left = 10; top = 10; right = 220; bottom = 55 }

        val lines = listOf(
            OcrLine(text = "This is an inter-", boundingBox = r1),
            OcrLine(text = "national agreement.", boundingBox = r2)
        )
        val block = OcrBlock(text = "This is an inter-\nnational agreement.", boundingBox = rBlock, lines = lines)

        val paragraphs = processor.buildParagraphs(listOf(block), pageIndex = 0)

        assertEquals(1, paragraphs.size)
        assertEquals("This is an international agreement.", paragraphs[0].sourceText)
        assertEquals(10, paragraphs[0].boundingBox.left)
        assertEquals(10, paragraphs[0].boundingBox.top)
        assertEquals(220, paragraphs[0].boundingBox.right)
        assertEquals(55, paragraphs[0].boundingBox.bottom)
    }

    @Test
    fun `buildParagraphs separates paragraphs on large vertical gaps`() {
        val r1 = Rect().apply { left = 10; top = 10; right = 250; bottom = 30 }
        val r2 = Rect().apply { left = 10; top = 100; right = 300; bottom = 120 }
        val rBlock = Rect().apply { left = 10; top = 10; right = 300; bottom = 120 }

        val lines = listOf(
            OcrLine(text = "First paragraph ends here.", boundingBox = r1),
            OcrLine(text = "Second paragraph starts far below.", boundingBox = r2)
        )
        val block = OcrBlock(text = "First paragraph ends here.\nSecond paragraph starts far below.", boundingBox = rBlock, lines = lines)

        val paragraphs = processor.buildParagraphs(listOf(block), pageIndex = 0)

        assertEquals(2, paragraphs.size)
        assertEquals("First paragraph ends here.", paragraphs[0].sourceText)
        assertEquals("Second paragraph starts far below.", paragraphs[1].sourceText)
    }
}
