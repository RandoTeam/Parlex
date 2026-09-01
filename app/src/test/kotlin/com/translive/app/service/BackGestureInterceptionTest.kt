package com.translive.app.service

import com.translive.app.service.overlay.ArOverlayBackController
import com.translive.app.service.overlay.ArOverlayBackState
import com.translive.app.service.overlay.ArOverlayWindowFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Phase O2: Predictive Back Gesture & Overlay Dismissal Mechanics:
 * 1. WindowManager flag transitions between idle (non-focusable) and active overlay (back-interceptable).
 * 2. Back event interception while scanning/translating (cancels running job and recovers).
 * 3. Back event interception while displaying AR overlay (smoothly dismisses overlay without propagating to host app).
 * 4. Outside-touch dismissal handling.
 * 5. Re-entrancy and double-back key press robustness.
 */
class BackGestureInterceptionTest {

    private val controller = ArOverlayBackController()

    // =========================================================================
    // SECTION 1: WindowManager LayoutParams Flags Contracts
    // =========================================================================

    @Test
    fun windowFlags_idleBubble_hasNotFocusableFlag() {
        val flags = ArOverlayWindowFlags.computeFlagsForState(ArOverlayBackState.IDLE)
        assertTrue("Idle bubble must be NOT_FOCUSABLE so taps pass to system", flags.isNotFocusable)
        assertFalse("Idle bubble should not intercept back keys", flags.isBackInterceptionActive)
    }

    @Test
    fun windowFlags_activeArOverlay_enablesBackInterceptionWithoutBlockingTouches() {
        val flags = ArOverlayWindowFlags.computeFlagsForState(ArOverlayBackState.DISPLAYING_AR)
        assertFalse("Active AR overlay must NOT have FLAG_NOT_FOCUSABLE so it receives Back gesture", flags.isNotFocusable)
        assertTrue("Active AR overlay must have FLAG_NOT_TOUCH_MODAL so outside touches pass through", flags.isNotTouchModal)
        assertTrue("Active AR overlay must watch outside touches for dismiss", flags.isWatchOutsideTouch)
        assertTrue("Active AR overlay must have back interception active", flags.isBackInterceptionActive)
    }

    // =========================================================================
    // SECTION 2: Back Gesture Handling State Machine
    // =========================================================================

    @Test
    fun handleBack_whenIdle_isNotHandled() {
        controller.setState(ArOverlayBackState.IDLE)
        var cancelJobCalled = false
        var dismissOverlayCalled = false

        val handled = controller.onBackInvoked(
            onCancelJob = { cancelJobCalled = true },
            onDismissOverlay = { dismissOverlayCalled = true }
        )

        assertFalse("Back should not be consumed when overlay is idle", handled)
        assertFalse(cancelJobCalled)
        assertFalse(dismissOverlayCalled)
    }

    @Test
    fun handleBack_whenScanningOrTranslating_cancelsActiveJobAndRecovers() {
        controller.setState(ArOverlayBackState.SCANNING)
        var cancelJobCalled = false
        var dismissOverlayCalled = false

        val handled = controller.onBackInvoked(
            onCancelJob = { cancelJobCalled = true },
            onDismissOverlay = { dismissOverlayCalled = true }
        )

        assertTrue("Back should be consumed when scanning", handled)
        assertTrue("Active translation coroutine job must be cancelled", cancelJobCalled)
        assertFalse("Overlay is not displayed yet, dismiss should not be called", dismissOverlayCalled)
        assertEquals(ArOverlayBackState.IDLE, controller.currentState)
    }

    @Test
    fun handleBack_whenDisplayingAr_dismissesOverlayCleanly() {
        controller.setState(ArOverlayBackState.DISPLAYING_AR)
        var cancelJobCalled = false
        var dismissOverlayCalled = false

        val handled = controller.onBackInvoked(
            onCancelJob = { cancelJobCalled = true },
            onDismissOverlay = { dismissOverlayCalled = true }
        )

        assertTrue("Back should be consumed when AR is visible", handled)
        assertTrue("Dismiss callback must be invoked", dismissOverlayCalled)
        assertEquals(ArOverlayBackState.IDLE, controller.currentState)
    }

    @Test
    fun handleOutsideTouch_whenDisplayingAr_dismissesOverlay() {
        controller.setState(ArOverlayBackState.DISPLAYING_AR)
        var dismissOverlayCalled = false

        val handled = controller.onOutsideTouch(
            onDismissOverlay = { dismissOverlayCalled = true }
        )

        assertTrue("Outside touch should be handled", handled)
        assertTrue("Dismiss callback must be triggered on outside touch", dismissOverlayCalled)
        assertEquals(ArOverlayBackState.IDLE, controller.currentState)
    }

    @Test
    fun doubleBackPress_doesNotCrashOrDuplicateCallbacks() {
        controller.setState(ArOverlayBackState.DISPLAYING_AR)
        var dismissCount = 0

        val firstBackHandled = controller.onBackInvoked(
            onCancelJob = {},
            onDismissOverlay = { dismissCount++ }
        )
        val secondBackHandled = controller.onBackInvoked(
            onCancelJob = {},
            onDismissOverlay = { dismissCount++ }
        )

        assertTrue("First back press must be consumed", firstBackHandled)
        assertFalse("Second back press must pass through because state is already IDLE", secondBackHandled)
        assertEquals(1, dismissCount)
    }
}
