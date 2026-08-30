package com.translive.app.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiveTranslationPipelineTest {

    @Test
    fun `bounding box calculateIoU computes correct overlap`() {
        val b1 = LiveBoundingBox(0, 0, 100, 100) // Area 10000
        val b2 = LiveBoundingBox(50, 0, 150, 100) // Overlap (50..100) = 50x100 = 5000. Union = 15000. IoU = 1/3.
        val iou = b1.calculateIoU(b2)
        assertEquals(0.33333334f, iou)

        val b3 = LiveBoundingBox(200, 200, 300, 300) // Disjoint
        assertEquals(0f, b1.calculateIoU(b3))
    }

    @Test
    fun `diffFrames retains unchanged translated blocks and detects shifted coordinates`() {
        val oldBlocks = listOf(
            LiveTextBlock(
                id = "block-1",
                rawText = "Hello world",
                bounds = LiveBoundingBox(10, 100, 200, 140),
                translatedText = "Привет мир"
            ),
            LiveTextBlock(
                id = "block-2",
                rawText = "Paragraph two",
                bounds = LiveBoundingBox(10, 150, 200, 190),
                translatedText = "Второй абзац"
            )
        )

        // Simulate user scrolling up by 50px (Y decreases by 50) and a new line appearing at bottom
        val newBlocks = listOf(
            LiveTextBlock(
                rawText = "Hello world",
                bounds = LiveBoundingBox(10, 50, 200, 90)
            ),
            LiveTextBlock(
                rawText = "Paragraph two",
                bounds = LiveBoundingBox(10, 100, 200, 140)
            ),
            LiveTextBlock(
                rawText = "New third paragraph",
                bounds = LiveBoundingBox(10, 150, 250, 190)
            )
        )

        val (retained, toTranslate) = LiveTranslationPipeline.diffFrames(oldBlocks, newBlocks)

        // 2 blocks should be retained with their existing translations and stable IDs!
        assertEquals(2, retained.size)
        assertEquals("Привет мир", retained[0].translatedText)
        assertEquals("block-1", retained[0].id)
        assertEquals("Второй абзац", retained[1].translatedText)
        assertEquals("block-2", retained[1].id)

        // 1 block is new and needs translation
        assertEquals(1, toTranslate.size)
        assertEquals("New third paragraph", toTranslate[0].rawText)
    }

    @Test
    fun `LiveTranslationCache stores and returns cached results`() {
        val cache = LiveTranslationCache(10)
        assertNull(cache.get("en", "ru", "apple"))

        cache.put("en", "ru", "apple", "яблоко", "yabloko")
        val cached = cache.get("en", "ru", "apple")
        assertNotNull(cached)
        assertEquals("яблоко", cached.translatedText)
        assertEquals("yabloko", cached.transliteration)
    }
}
