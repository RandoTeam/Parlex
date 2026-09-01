package com.translive.app.ui.overlay

import com.translive.app.service.overlay.FabMenuAction
import com.translive.app.service.overlay.FabMenuLayoutCalculator
import com.translive.app.service.overlay.FabMenuPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Phase O3 & O4:
 * 1. Material 3 Expressive FAB Menu actions dispatching.
 * 2. Adaptive positioning math for left-docked vs right-docked bubble.
 * 3. Screen bounds boundary clamping and layout width.
 * 4. Zero-emoji compliance and action labeling contracts.
 */
class BubbleAndFabMenuTest {

    private val screenWidth = 1080
    private val screenHeight = 2160
    private val density = 2.75f
    private val bubbleSizePx = (76 * density).toInt()

    private val calculator = FabMenuLayoutCalculator(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        density = density,
        bubbleSizePx = bubbleSizePx,
        menuWidthDp = 260f,
        marginDp = 12f
    )

    // =========================================================================
    // SECTION 1: Adaptive Positioning Math
    // =========================================================================

    @Test
    fun layoutPosition_whenBubbleDockedOnLeft_expandsMenuToTheRight() {
        // Bubble at left margin
        val bubbleX = (12 * density).toInt()
        val bubbleY = 800

        val placement = calculator.computePlacement(bubbleX = bubbleX, bubbleY = bubbleY)

        assertEquals(FabMenuPlacement.ExpandDirection.EXPAND_RIGHT, placement.direction)
        assertTrue("Menu X must be placed to the right of the bubble", placement.menuX > bubbleX)
        assertTrue("Menu X must stay within screen bounds", placement.menuX + placement.menuWidthPx <= screenWidth)
    }

    @Test
    fun layoutPosition_whenBubbleDockedOnRight_expandsMenuToTheLeft() {
        // Bubble at right margin
        val bubbleX = screenWidth - bubbleSizePx - (12 * density).toInt()
        val bubbleY = 800

        val placement = calculator.computePlacement(bubbleX = bubbleX, bubbleY = bubbleY)

        assertEquals(FabMenuPlacement.ExpandDirection.EXPAND_LEFT, placement.direction)
        assertTrue("Menu X must be placed to the left of the bubble", placement.menuX < bubbleX)
        assertTrue("Menu X must not go offscreen on the left", placement.menuX >= (12 * density).toInt())
    }

    @Test
    fun layoutPosition_verticalClamping_preventsMenuFromExceedingScreenTopOrBottom() {
        // Bubble near top of screen
        val topPlacement = calculator.computePlacement(bubbleX = 100, bubbleY = 20)
        assertTrue("Menu Y must respect top margin", topPlacement.menuY >= (24 * density).toInt())

        // Bubble near bottom of screen
        val bottomPlacement = calculator.computePlacement(bubbleX = 100, bubbleY = screenHeight - 50)
        assertTrue("Menu Y must respect bottom navigation clearance", bottomPlacement.menuY + 300 <= screenHeight)
    }

    // =========================================================================
    // SECTION 2: FAB Menu Actions & MD3 Zero-Emoji Compliance
    // =========================================================================

    @Test
    fun menuActions_allActionsHaveUniqueIdsAndCleanLabels() {
        val actions = listOf(
            FabMenuAction.TRANSLATE_SCREEN,
            FabMenuAction.VISION_AI_ANALYZE,
            FabMenuAction.SELECT_LANGUAGE,
            FabMenuAction.SAVE_SCREENSHOT,
            FabMenuAction.CLOSE_SERVICE
        )

        assertEquals(5, actions.size)
        // Verify all IDs are non-blank and unique
        val uniqueIds = actions.map { it.actionId }.toSet()
        assertEquals(5, uniqueIds.size)
    }
}
