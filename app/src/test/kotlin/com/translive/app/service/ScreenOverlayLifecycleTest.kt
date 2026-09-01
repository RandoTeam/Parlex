package com.translive.app.service

import com.translive.app.service.overlay.CaptureDebounceMutex
import com.translive.app.service.overlay.FloatingBubbleStateMachine
import com.translive.app.service.overlay.OverlayEvent
import com.translive.app.service.overlay.OverlayLayoutCalculator
import com.translive.app.service.overlay.OverlayState
import com.translive.app.service.overlay.ProjectionToken
import com.translive.app.service.overlay.ScreenCaptureSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TestProjectionToken(
    override val id: String = "token_default",
    @Volatile override var isValid: Boolean = true
) : ProjectionToken {
    override fun invalidate() {
        isValid = false
    }
}

/**
 * Pure JVM unit test suite verifying screen translation overlay lifecycle contracts:
 * 1. MediaProjection token retention across consecutive triggers.
 * 2. Debounce and mutex re-entrancy under high-concurrency click spam.
 * 3. State machine transitions and automatic error recovery.
 * 4. Window LayoutParams flags and dimension geometry math.
 */
class ScreenOverlayLifecycleTest {

    // =========================================================================
    // SECTION 1: MediaProjection Token Retention & Session Persistence
    // =========================================================================

    @Test
    fun tokenRetention_consecutiveTriggers_reusesSameTokenWithoutInvalidation() {
        val manager = ScreenCaptureSessionManager()
        val token = TestProjectionToken(id = "system_token_alpha")
        manager.attachToken(token)

        assertTrue(manager.isSessionActive)
        assertEquals(0, manager.tokenReuseCount)

        // Execute 10 consecutive translation captures
        for (i in 1..10) {
            val acquiredToken = manager.requestCapture()
            assertEquals("system_token_alpha", acquiredToken.id)
            assertTrue(acquiredToken.isValid)
        }

        assertEquals(10, manager.tokenReuseCount)
        assertEquals(0, manager.permissionRequestCount)
        assertTrue(manager.isSessionActive)
    }

    @Test
    fun tokenRetention_unattachedSession_throwsExceptionAndTracksPermissionRequest() {
        val manager = ScreenCaptureSessionManager()
        assertFalse(manager.isSessionActive)

        assertThrows(IllegalStateException::class.java) {
            manager.requestCapture()
        }
        assertEquals(1, manager.permissionRequestCount)
        assertEquals(0, manager.tokenReuseCount)
    }

    @Test
    fun tokenRetention_explicitRelease_invalidatesTokenCleanly() {
        val manager = ScreenCaptureSessionManager()
        val token = TestProjectionToken(id = "token_beta")
        manager.attachToken(token)

        assertTrue(manager.isSessionActive)
        manager.releaseSession()

        assertFalse(manager.isSessionActive)
        assertFalse(token.isValid)
        assertNull(manager.activeToken)
    }

    @Test
    fun tokenRetention_systemRevocation_marksSessionInactive() {
        val manager = ScreenCaptureSessionManager()
        val token = TestProjectionToken(id = "token_gamma")
        manager.attachToken(token)

        // Simulate Android OS callback onStop()
        token.invalidate()

        assertFalse(manager.isSessionActive)
        assertThrows(IllegalStateException::class.java) {
            manager.requestCapture()
        }
        assertEquals(1, manager.permissionRequestCount)
    }

    @Test
    fun tokenRetention_reAttachNewToken_resetsCounterAndRestoresActiveState() {
        val manager = ScreenCaptureSessionManager()
        val token1 = TestProjectionToken(id = "token_1")
        manager.attachToken(token1)
        manager.requestCapture()
        manager.requestCapture()
        assertEquals(2, manager.tokenReuseCount)

        val token2 = TestProjectionToken(id = "token_2")
        manager.attachToken(token2)
        assertEquals(0, manager.tokenReuseCount)
        assertEquals("token_2", manager.requestCapture().id)
        assertEquals(1, manager.tokenReuseCount)
    }

    // =========================================================================
    // SECTION 2: Debounce & Mutex Re-Entrancy
    // =========================================================================

    @Test
    fun debounceMutex_concurrentSpam_executesExactlyOnceAndDropsOverlapping() = runBlocking {
        val guard = CaptureDebounceMutex(debounceWindowMs = 200L)
        val now = 1000L

        // Spawn 30 concurrent coroutines invoking capture simultaneously
        val jobs = (1..30).map {
            async(Dispatchers.Default) {
                guard.executeGuarded(now) {
                    delay(50L) // Simulate bitmap capture + OCR
                }
            }
        }

        val results = jobs.awaitAll()
        val successfulRuns = results.count { it }

        assertEquals(1, successfulRuns)
        assertEquals(1, guard.executedTriggers.get())
        assertEquals(29, guard.droppedTriggers.get())
    }

    @Test
    fun debounceMutex_timeSeparatedClicks_allowsSequentialExecution() = runBlocking {
        val guard = CaptureDebounceMutex(debounceWindowMs = 100L)

        val firstSuccess = guard.executeGuarded(1000L) {}
        val rapidClickSuppressed = guard.executeGuarded(1050L) {}
        val secondSuccess = guard.executeGuarded(1150L) {}

        assertTrue(firstSuccess)
        assertFalse(rapidClickSuppressed)
        assertTrue(secondSuccess)
        assertEquals(2, guard.executedTriggers.get())
        assertEquals(1, guard.droppedTriggers.get())
    }

    @Test
    fun debounceMutex_exceptionInPipeline_releasesLockForSubsequentTriggers() = runBlocking {
        val guard = CaptureDebounceMutex(debounceWindowMs = 50L)

        var thrown = false
        try {
            guard.executeGuarded(1000L) {
                throw RuntimeException("Capture buffer allocation failure")
            }
        } catch (e: RuntimeException) {
            thrown = true
        }

        assertTrue(thrown)

        // Ensure mutex was cleanly unlocked and next trigger succeeds
        val nextSuccess = guard.executeGuarded(1100L) {}
        assertTrue(nextSuccess)
        assertEquals(2, guard.executedTriggers.get())
    }

    // =========================================================================
    // SECTION 3: Floating Button State Machine Transitions
    // =========================================================================

    @Test
    fun stateMachine_happyPath_transitionsThroughAllStatesBackToIdle() {
        val recordedStates = mutableListOf<OverlayState>()
        val sm = FloatingBubbleStateMachine { recordedStates.add(it) }

        assertEquals(OverlayState.IDLE, sm.currentState)

        assertTrue(sm.transition(OverlayEvent.TriggerClick))
        assertEquals(OverlayState.SCANNING, sm.currentState)

        assertTrue(sm.transition(OverlayEvent.FrameCaptured))
        assertEquals(OverlayState.TRANSLATING, sm.currentState)

        assertTrue(sm.transition(OverlayEvent.TranslationReady))
        assertEquals(OverlayState.DISPLAYING, sm.currentState)

        assertTrue(sm.transition(OverlayEvent.DismissOverlay))
        assertEquals(OverlayState.IDLE, sm.currentState)

        assertEquals(
            listOf(
                OverlayState.SCANNING,
                OverlayState.TRANSLATING,
                OverlayState.DISPLAYING,
                OverlayState.IDLE
            ),
            recordedStates
        )
    }

    @Test
    fun stateMachine_errorInScanning_transitionsToErrorAndAutoRecovers() {
        val sm = FloatingBubbleStateMachine()

        sm.transition(OverlayEvent.TriggerClick)
        assertEquals(OverlayState.SCANNING, sm.currentState)

        sm.transition(OverlayEvent.ErrorOccurred("VirtualDisplay capture timeout"))
        assertEquals(OverlayState.ERROR, sm.currentState)
        assertEquals("VirtualDisplay capture timeout", sm.lastErrorMessage)

        // Automatic recovery on timer expiry
        assertTrue(sm.transition(OverlayEvent.AutoRecoverTimerExpired))
        assertEquals(OverlayState.IDLE, sm.currentState)
        assertNull(sm.lastErrorMessage)
    }

    @Test
    fun stateMachine_errorInTranslating_recoversOnUserClick() {
        val sm = FloatingBubbleStateMachine()

        sm.transition(OverlayEvent.TriggerClick)
        sm.transition(OverlayEvent.FrameCaptured)
        assertEquals(OverlayState.TRANSLATING, sm.currentState)

        sm.transition(OverlayEvent.ErrorOccurred("NMT offline model not loaded"))
        assertEquals(OverlayState.ERROR, sm.currentState)

        // Tap to dismiss error immediately
        assertTrue(sm.transition(OverlayEvent.TriggerClick))
        assertEquals(OverlayState.IDLE, sm.currentState)
        assertNull(sm.lastErrorMessage)
    }

    @Test
    fun stateMachine_prematureDismiss_cancelsPipelineCleanly() {
        val sm = FloatingBubbleStateMachine()

        sm.transition(OverlayEvent.TriggerClick)
        assertEquals(OverlayState.SCANNING, sm.currentState)

        assertTrue(sm.transition(OverlayEvent.DismissOverlay))
        assertEquals(OverlayState.IDLE, sm.currentState)
    }

    @Test
    fun stateMachine_invalidTransitions_areRejectedWithoutStateChange() {
        val sm = FloatingBubbleStateMachine()
        assertEquals(OverlayState.IDLE, sm.currentState)

        // Cannot jump directly from IDLE to TRANSLATING or DISPLAYING
        assertFalse(sm.transition(OverlayEvent.TranslationReady))
        assertEquals(OverlayState.IDLE, sm.currentState)

        assertFalse(sm.transition(OverlayEvent.FrameCaptured))
        assertEquals(OverlayState.IDLE, sm.currentState)
    }

    // =========================================================================
    // SECTION 4: Window LayoutParams Flags & Geometry Calculations
    // =========================================================================

    @Test
    fun windowConfig_floatingBubble_hasRequiredOverlayFlags() {
        val config = OverlayLayoutCalculator.getBubbleWindowConfig()

        assertEquals(OverlayLayoutCalculator.TYPE_APPLICATION_OVERLAY, config.type)
        assertEquals(OverlayLayoutCalculator.PIXEL_FORMAT_TRANSLUCENT, config.format)

        val hasNotFocusable = (config.flags and OverlayLayoutCalculator.FLAG_NOT_FOCUSABLE) != 0
        val hasNoLimits = (config.flags and OverlayLayoutCalculator.FLAG_LAYOUT_NO_LIMITS) != 0

        assertTrue("Bubble must have FLAG_NOT_FOCUSABLE", hasNotFocusable)
        assertTrue("Bubble must have FLAG_LAYOUT_NO_LIMITS", hasNoLimits)
    }

    @Test
    fun windowConfig_hudToolbar_hasNonModalAndOutsideTouchFlags() {
        val config = OverlayLayoutCalculator.getHudWindowConfig()

        assertEquals(OverlayLayoutCalculator.TYPE_APPLICATION_OVERLAY, config.type)

        val hasNotTouchModal = (config.flags and OverlayLayoutCalculator.FLAG_NOT_TOUCH_MODAL) != 0
        val hasWatchOutside = (config.flags and OverlayLayoutCalculator.FLAG_WATCH_OUTSIDE_TOUCH) != 0

        assertTrue("HUD must have FLAG_NOT_TOUCH_MODAL", hasNotTouchModal)
        assertTrue("HUD must have FLAG_WATCH_OUTSIDE_TOUCH", hasWatchOutside)
    }

    @Test
    fun layoutGeometry_computeDockedPosition_snapsToCorrectEdge() {
        val screenWidth = 1080
        val screenHeight = 2400
        val buttonSize = 150
        val margin = 32

        // Dragged on left side (X = 200, center = 275 < 540)
        val leftDock = OverlayLayoutCalculator.computeDockedPosition(
            currentX = 200,
            currentY = 500,
            buttonSize = buttonSize,
            margin = margin,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(margin, leftDock.x)
        assertEquals(500, leftDock.y)

        // Dragged on right side (X = 800, center = 875 >= 540)
        val rightDock = OverlayLayoutCalculator.computeDockedPosition(
            currentX = 800,
            currentY = 600,
            buttonSize = buttonSize,
            margin = margin,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(screenWidth - buttonSize - margin, rightDock.x)
        assertEquals(600, rightDock.y)
    }

    @Test
    fun layoutGeometry_orientationChange_preservesRelativeVerticalRatio() {
        val portrait = OverlayLayoutCalculator.Dimensions(1080, 2400)
        val landscape = OverlayLayoutCalculator.Dimensions(2400, 1080)
        val buttonSize = 150
        val margin = 32

        // In portrait, button is docked on right at Y = 1200
        val portraitPos = OverlayLayoutCalculator.Point(1080 - 150 - 32, 1200)

        val scaledPos = OverlayLayoutCalculator.scalePositionOnOrientationChange(
            oldPos = portraitPos,
            oldScreen = portrait,
            newScreen = landscape,
            buttonSize = buttonSize,
            margin = margin
        )

        // In landscape, button should remain docked on right edge
        assertEquals(landscape.width - buttonSize - margin, scaledPos.x)

        val oldSpan = (portrait.height - buttonSize - 2 * margin).coerceAtLeast(1)
        val ratioY = ((portraitPos.y - margin).toFloat() / oldSpan).coerceIn(0f, 1f)
        val newSpan = (landscape.height - buttonSize - 2 * margin).coerceAtLeast(1)
        val expectedY = (margin + ratioY * newSpan).toInt()
        assertEquals(expectedY, scaledPos.y)
    }

    @Test
    fun layoutGeometry_hudPopupPlacement_avoidsScreenClipping() {
        val screenWidth = 1080
        val screenHeight = 2400
        val buttonSize = 150
        val hudWidth = 400
        val hudHeight = 300
        val margin = 32

        // When button docked left: HUD placed to right of button
        val leftButton = OverlayLayoutCalculator.Point(margin, 500)
        val leftHud = OverlayLayoutCalculator.computeHudPosition(
            buttonPos = leftButton,
            buttonSize = buttonSize,
            hudWidth = hudWidth,
            hudHeight = hudHeight,
            margin = margin,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(margin + buttonSize + margin, leftHud.x)
        assertEquals(500, leftHud.y)

        // When button docked right: HUD placed to left of button
        val rightButton = OverlayLayoutCalculator.Point(screenWidth - buttonSize - margin, 500)
        val rightHud = OverlayLayoutCalculator.computeHudPosition(
            buttonPos = rightButton,
            buttonSize = buttonSize,
            hudWidth = hudWidth,
            hudHeight = hudHeight,
            margin = margin,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(rightButton.x - hudWidth - margin, rightHud.x)
        assertEquals(500, rightHud.y)
    }
}
