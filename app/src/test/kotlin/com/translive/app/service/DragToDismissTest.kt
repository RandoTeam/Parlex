package com.translive.app.service

import com.translive.app.service.overlay.DragDismissAction
import com.translive.app.service.overlay.DragDismissState
import com.translive.app.service.overlay.DragToDismissCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Phase O1: Drag-to-Dismiss & Magnetic Trash Zone mechanics:
 * 1. Trash zone target position and boundary calculation based on screen dimensions.
 * 2. Magnetic attraction detection when bubble is dragged within threshold radius.
 * 3. Haptic feedback trigger state machine (triggers once upon entering magnetic zone).
 * 4. Dismiss action resolution upon release inside vs outside trash zone.
 * 5. Edge docking fallback coordinates when released outside trash zone.
 */
class DragToDismissTest {

    private val screenWidth = 1080
    private val screenHeight = 2160
    private val density = 2.75f // 440 dpi (Xiaomi Mi 8 / OnePlus)
    private val bubbleSizePx = (76 * density).toInt()

    private val calculator = DragToDismissCalculator(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        density = density,
        bubbleSizePx = bubbleSizePx,
        trashRadiusDp = 40f,
        bottomMarginDp = 48f,
        magneticThresholdDp = 90f
    )

    // =========================================================================
    // SECTION 1: Trash Target Geometry
    // =========================================================================

    @Test
    fun trashGeometry_calculatesCenterAtBottomMiddleOfScreen() {
        val target = calculator.trashCenter
        val expectedX = screenWidth / 2f
        val expectedY = screenHeight - (48f * density)

        assertEquals(expectedX, target.x, 0.5f)
        assertEquals(expectedY, target.y, 0.5f)
    }

    // =========================================================================
    // SECTION 2: Magnetic Hover & State Transitions
    // =========================================================================

    @Test
    fun evaluateDrag_farFromTrash_returnsNormalDraggingState() {
        // Dragging at the top center of the screen
        val state = calculator.evaluateDrag(bubbleCenterX = 540f, bubbleCenterY = 500f)

        assertEquals(DragDismissState.DRAGGING, state.state)
        assertFalse(state.isInsideMagneticZone)
        assertFalse(state.shouldTriggerHaptic)
        assertEquals(1.0f, state.trashScaleFactor, 0.01f)
    }

    @Test
    fun evaluateDrag_enteringMagneticZone_triggersHapticAndExpandsTrash() {
        val trash = calculator.trashCenter
        // Position within 50dp of trash center (well within 90dp threshold)
        val testX = trash.x + (20f * density)
        val testY = trash.y - (30f * density)

        val state = calculator.evaluateDrag(bubbleCenterX = testX, bubbleCenterY = testY)

        assertEquals(DragDismissState.MAGNETIC_HOVER, state.state)
        assertTrue(state.isInsideMagneticZone)
        assertTrue(state.shouldTriggerHaptic)
        assertTrue("Trash should expand in magnetic zone", state.trashScaleFactor > 1.1f)
        assertEquals(trash.x, state.snapTargetX, 0.1f)
        assertEquals(trash.y, state.snapTargetY, 0.1f)
    }

    @Test
    fun evaluateDrag_stayingInMagneticZone_doesNotRetriggerHapticMultipleTimes() {
        val trash = calculator.trashCenter

        // First move into magnetic zone
        val state1 = calculator.evaluateDrag(bubbleCenterX = trash.x + 10f, bubbleCenterY = trash.y - 10f)
        assertTrue(state1.shouldTriggerHaptic)

        // Micro-move within magnetic zone
        val state2 = calculator.evaluateDrag(bubbleCenterX = trash.x + 12f, bubbleCenterY = trash.y - 8f)
        assertTrue(state2.isInsideMagneticZone)
        assertFalse("Haptic should only trigger on entrance edge, not continuously", state2.shouldTriggerHaptic)
    }

    @Test
    fun evaluateDrag_exitingMagneticZone_resetsHoverState() {
        val trash = calculator.trashCenter

        // Enter magnetic zone
        calculator.evaluateDrag(bubbleCenterX = trash.x, bubbleCenterY = trash.y)
        
        // Drag far away
        val stateExit = calculator.evaluateDrag(bubbleCenterX = 200f, bubbleCenterY = 400f)
        assertEquals(DragDismissState.DRAGGING, stateExit.state)
        assertFalse(stateExit.isInsideMagneticZone)
        assertEquals(1.0f, stateExit.trashScaleFactor, 0.01f)
    }

    // =========================================================================
    // SECTION 3: Release Action Resolution
    // =========================================================================

    @Test
    fun onRelease_insideMagneticZone_returnsDismissServiceAction() {
        val trash = calculator.trashCenter
        // Enter magnetic zone
        calculator.evaluateDrag(bubbleCenterX = trash.x, bubbleCenterY = trash.y)

        val action = calculator.onRelease()
        assertEquals(DragDismissAction.DISMISS_SERVICE, action)
    }

    @Test
    fun onRelease_outsideMagneticZone_returnsDockToEdgeAction() {
        // Drag at middle-left of screen
        calculator.evaluateDrag(bubbleCenterX = 200f, bubbleCenterY = 1000f)

        val action = calculator.onRelease()
        assertTrue(action is DragDismissAction.DOCK_TO_EDGE)
        val dockAction = action as DragDismissAction.DOCK_TO_EDGE
        assertEquals(DragDismissAction.DockSide.LEFT, dockAction.side)
        assertTrue("X target should be near left margin", dockAction.targetX <= (16f * density))
    }

    @Test
    fun onRelease_rightSide_docksToRightEdge() {
        // Drag at middle-right of screen
        calculator.evaluateDrag(bubbleCenterX = 900f, bubbleCenterY = 1200f)

        val action = calculator.onRelease()
        assertTrue(action is DragDismissAction.DOCK_TO_EDGE)
        val dockAction = action as DragDismissAction.DOCK_TO_EDGE
        assertEquals(DragDismissAction.DockSide.RIGHT, dockAction.side)
        assertTrue("X target should be near right margin", dockAction.targetX >= screenWidth - bubbleSizePx - (16f * density))
    }
}
