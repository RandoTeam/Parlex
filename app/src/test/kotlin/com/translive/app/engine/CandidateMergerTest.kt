package com.translive.app.engine

import android.graphics.Rect
import com.translive.app.ui.overlay.CandidateBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateMergerTest {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    @Test
    fun `merge drops ocr node if covered by native node`() {
        val native = CandidateBlock("Native Text", rect(10, 10, 100, 50))
        val ocrInside = CandidateBlock("Ocr Text", rect(12, 12, 98, 48))
        val ocrOutside = CandidateBlock("Image Text", rect(200, 200, 300, 250))

        val merged = CandidateMerger.merge(listOf(native), listOf(ocrInside, ocrOutside))

        assertEquals(2, merged.size)
        assertTrue(merged.contains(native))
        assertTrue(merged.contains(ocrOutside))
        assertFalse(merged.contains(ocrInside))
    }

    @Test
    fun `merge retains ocr node if overlap is at or below 50 percent`() {
        val native = CandidateBlock("Native", rect(0, 0, 100, 100))
        // 50% overlap: intersection is 50x100 = 5000, ocr area is 100x100 = 10000
        val ocrHalf = CandidateBlock("Half Overlap OCR", rect(50, 0, 150, 100))

        val merged = CandidateMerger.merge(listOf(native), listOf(ocrHalf))

        assertEquals(2, merged.size)
        assertTrue(merged.contains(native))
        assertTrue(merged.contains(ocrHalf))
    }

    @Test
    fun `merge drops ocr node when overlap strictly exceeds 50 percent`() {
        val native = CandidateBlock("Native", rect(0, 0, 100, 100))
        // 51% overlap: intersection is 51x100 = 5100, ocr area is 100x100 = 10000
        val ocr51 = CandidateBlock("51% Overlap OCR", rect(49, 0, 149, 100))

        val merged = CandidateMerger.merge(listOf(native), listOf(ocr51))

        assertEquals(1, merged.size)
        assertTrue(merged.contains(native))
        assertFalse(merged.contains(ocr51))
    }

    @Test
    fun `merge preserves all native nodes and appends non-overlapping ocr nodes`() {
        val native1 = CandidateBlock("Native 1", rect(10, 10, 80, 40))
        val native2 = CandidateBlock("Native 2", rect(10, 60, 80, 90))
        val ocr1 = CandidateBlock("OCR 1", rect(200, 10, 250, 40))
        val ocr2 = CandidateBlock("OCR 2", rect(200, 60, 250, 90))

        val merged = CandidateMerger.merge(listOf(native1, native2), listOf(ocr1, ocr2))

        assertEquals(4, merged.size)
        assertEquals(listOf(native1, native2, ocr1, ocr2), merged)
    }

    @Test
    fun `merge with empty native returns all non-empty ocr nodes`() {
        val ocr1 = CandidateBlock("OCR 1", rect(10, 10, 100, 50))
        val ocrZero = CandidateBlock("Empty Area OCR", rect(10, 10, 10, 50))

        val merged = CandidateMerger.merge(emptyList(), listOf(ocr1, ocrZero))

        assertEquals(1, merged.size)
        assertEquals(ocr1, merged[0])
    }

    @Test
    fun `merge with empty ocr returns all native nodes`() {
        val native = CandidateBlock("Native Only", rect(10, 10, 100, 50))

        val merged = CandidateMerger.merge(listOf(native), emptyList())

        assertEquals(1, merged.size)
        assertEquals(native, merged[0])
    }
}
