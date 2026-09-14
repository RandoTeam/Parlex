package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * TDD Test Suite for Safe Range Coercion and Boundary Calculation in AR Screen Translation.
 * Reproduces the DuckDuckGo search result crash:
 * "Cannot coerce value to an empty range: maximum -32.0 is less than minimum 32.0"
 */
class ArOverlaySafeGeometryTest {

    // Mirror the production extension functions to test their mathematical contract
    private fun Float.safeCoerceIn(minVal: Float, maxVal: Float): Float =
        this.coerceIn(minVal, max(minVal, maxVal))

    private fun Int.safeCoerceIn(minVal: Int, maxVal: Int): Int =
        this.coerceIn(minVal, max(minVal, maxVal))

    @Test
    fun standardCoerceIn_whenMaxLessThanMin_throwsIllegalArgumentException() {
        val minVal = 32.0f
        val maxVal = -32.0f
        try {
            16.0f.coerceIn(minVal, maxVal)
            fail("Expected standard coerceIn to throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot coerce value to an empty range") == true)
        }
    }

    @Test
    fun safeCoerceIn_whenMaxLessThanMin_doesNotThrowAndClampsToMin() {
        val minVal = 32.0f
        val maxVal = -32.0f
        val value = 16.0f
        val result = value.safeCoerceIn(minVal, maxVal)
        assertEquals(32.0f, result, 0.001f)
    }

    @Test
    fun safeCoerceIn_whenValueWithinValidRange_returnsOriginalValue() {
        val result = 45.0f.safeCoerceIn(10.0f, 100.0f)
        assertEquals(45.0f, result, 0.001f)
    }

    @Test
    fun safeCoerceIn_integer_whenInverted_returnsMin() {
        val result = 150.safeCoerceIn(50, 10)
        assertEquals(50, result)
    }

    @Test
    fun horizontalMarginCalculation_withFullWidthText_doesNotProduceInvertedRange() {
        val density = 4.0f
        val screenWidth = 1080f
        val finalWidth = 1080f // Exactly screen width (e.g. wide search header)
        val boundedFinalWidth = min(finalWidth, screenWidth - (16f * density))
        val minLeft = 8f * density
        val maxLeft = max(minLeft, screenWidth - boundedFinalWidth - minLeft)
        val left = (0f).safeCoerceIn(minLeft, maxLeft)
        assertTrue("Left must be at least minLeft", left >= minLeft)
        assertTrue("Left must not exceed maxLeft", left <= maxLeft)
    }

    @Test
    fun verticalMarginCalculation_withMassiveParagraph_doesNotProduceInvertedRange() {
        val density = 4.0f
        val screenHeight = 2400f
        val statusBarHeight = 120
        val topMinY = statusBarHeight + (44f * density)
        val bottomMaxY = screenHeight - (56f * density)
        val finalHeight = 2500f // Taller than screen

        val maxTop = max(topMinY, bottomMaxY - finalHeight)
        val top = (500f).safeCoerceIn(topMinY, maxTop)
        assertTrue("Top must be at least topMinY", top >= topMinY)
    }
}
