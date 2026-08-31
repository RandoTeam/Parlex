package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

object FloatingBubbleDockingMath {

    data class DockingResult(
        val targetX: Int,
        val targetY: Int,
        val isDockedLeft: Boolean
    )

    data class ScreenBounds(
        val widthPixels: Int,
        val heightPixels: Int,
        val density: Float,
        val statusBarInsetPx: Int = 0,
        val navBarInsetPx: Int = 0
    )

    fun calculateDockingPosition(
        currentX: Int,
        currentY: Int,
        buttonSizePx: Int,
        screen: ScreenBounds,
        marginDp: Int = 16
    ): DockingResult {
        val marginPx = (marginDp * screen.density).toInt()
        val bubbleCenterX = currentX + buttonSizePx / 2
        val screenCenterX = screen.widthPixels / 2

        val isDockedLeft = bubbleCenterX < screenCenterX
        val targetX = if (isDockedLeft) {
            marginPx
        } else {
            screen.widthPixels - buttonSizePx - marginPx
        }

        val minY = screen.statusBarInsetPx + marginPx
        val maxY = screen.heightPixels - screen.navBarInsetPx - buttonSizePx - marginPx
        val targetY = currentY.coerceIn(minY, maxY)

        return DockingResult(
            targetX = targetX,
            targetY = targetY,
            isDockedLeft = isDockedLeft
        )
    }

    fun isDragGesture(
        deltaX: Float,
        deltaY: Float,
        touchSlopPx: Float
    ): Boolean {
        return hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlopPx
    }

    fun projectPositionOnOrientationChange(
        previousY: Int,
        oldScreenHeight: Int,
        newScreenHeight: Int,
        buttonSizePx: Int,
        newMarginPx: Int
    ): Int {
        if (oldScreenHeight <= buttonSizePx) return newMarginPx
        val ratio = previousY.toFloat() / (oldScreenHeight - buttonSizePx).toFloat()
        val projectedY = (ratio * (newScreenHeight - buttonSizePx)).toInt()
        val maxY = newScreenHeight - buttonSizePx - newMarginPx
        return projectedY.coerceIn(newMarginPx, maxY)
    }
}

class FloatingBubbleTest {

    private val standardScreen = FloatingBubbleDockingMath.ScreenBounds(
        widthPixels = 1080,
        heightPixels = 2400,
        density = 2.625f,
        statusBarInsetPx = 63,
        navBarInsetPx = 126
    )

    private val buttonSizePx = (56 * standardScreen.density).toInt()
    private val marginPx = (16 * standardScreen.density).toInt()

    @Test
    fun testDockingToLeftEdgeWhenOnLeftSide() {
        val currentX = 200
        val currentY = 1000

        val result = FloatingBubbleDockingMath.calculateDockingPosition(
            currentX = currentX,
            currentY = currentY,
            buttonSizePx = buttonSizePx,
            screen = standardScreen,
            marginDp = 16
        )

        assertTrue(result.isDockedLeft)
        assertEquals(marginPx, result.targetX)
        assertEquals(1000, result.targetY)
    }

    @Test
    fun testDockingToRightEdgeWhenOnRightSide() {
        val currentX = 700
        val currentY = 1000

        val result = FloatingBubbleDockingMath.calculateDockingPosition(
            currentX = currentX,
            currentY = currentY,
            buttonSizePx = buttonSizePx,
            screen = standardScreen,
            marginDp = 16
        )

        assertFalse(result.isDockedLeft)
        val expectedRightX = standardScreen.widthPixels - buttonSizePx - marginPx
        assertEquals(expectedRightX, result.targetX)
        assertEquals(1000, result.targetY)
    }

    @Test
    fun testTouchSlopTapClassification() {
        val touchSlopPx = 10.0f * standardScreen.density
        assertFalse(FloatingBubbleDockingMath.isDragGesture(3.0f, 4.0f, touchSlopPx))
        assertFalse(FloatingBubbleDockingMath.isDragGesture(0.0f, 0.0f, touchSlopPx))
    }

    @Test
    fun testTouchSlopDragClassification() {
        val touchSlopPx = 10.0f * standardScreen.density
        assertTrue(FloatingBubbleDockingMath.isDragGesture(25.0f, 25.0f, touchSlopPx))
        assertTrue(FloatingBubbleDockingMath.isDragGesture(30.0f, 0.0f, touchSlopPx))
    }
}
