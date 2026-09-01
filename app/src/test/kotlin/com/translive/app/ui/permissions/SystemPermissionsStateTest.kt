package com.translive.app.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPermissionsStateTest {

    @Test
    fun defaultState_allFalse_isFullSilentCaptureReadyFalse() {
        val state = SystemPermissionsState()
        assertFalse(state.isAccessibilityConfigured)
        assertFalse(state.isAccessibilityConnected)
        assertFalse(state.isAssistantRoleHeld)
        assertFalse(state.isOverlayGranted)
        assertFalse(state.isNotificationGranted)
        assertFalse(state.isFullSilentCaptureReady)
    }

    @Test
    fun fullSilentCaptureReady_requiresConnectedAndOverlay() {
        val stateOnlyA11y = SystemPermissionsState(isAccessibilityConnected = true, isOverlayGranted = false)
        assertFalse(stateOnlyA11y.isFullSilentCaptureReady)

        val stateOnlyOverlay = SystemPermissionsState(isAccessibilityConnected = false, isOverlayGranted = true)
        assertFalse(stateOnlyOverlay.isFullSilentCaptureReady)

        val stateReady = SystemPermissionsState(isAccessibilityConnected = true, isOverlayGranted = true)
        assertTrue(stateReady.isFullSilentCaptureReady)
    }

    @Test
    fun accessibilityStatusFormatting_returnsExpectedKeys() {
        val stateActive = SystemPermissionsState(isAccessibilityConnected = true, isAccessibilityConfigured = true)
        assertEquals(AccessibilityUiStatus.ACTIVE, stateActive.accessibilityStatus)

        val stateConfiguredOnly = SystemPermissionsState(isAccessibilityConnected = false, isAccessibilityConfigured = true)
        assertEquals(AccessibilityUiStatus.CONFIGURED_NOT_BOUND, stateConfiguredOnly.accessibilityStatus)

        val stateDisabled = SystemPermissionsState(isAccessibilityConnected = false, isAccessibilityConfigured = false)
        assertEquals(AccessibilityUiStatus.DISABLED, stateDisabled.accessibilityStatus)
    }
}
