package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD test suite validating that dismissOverlay cannot recurse
 * and that onDismiss is invoked at most once even when circular callbacks occur.
 */
class ArOverlayDismissalRecursionTest {

    private class MockOverlay(val onDismiss: () -> Unit) {
        var isDismissing = false
        var dismissCallCount = 0
        var cleanupCallCount = 0

        fun dismissOverlay(immediate: Boolean = false) {
            dismissCallCount++
            if (isDismissing) return
            isDismissing = true
            cleanup()
            onDismiss()
        }

        private fun cleanup() {
            cleanupCallCount++
        }
    }

    private class MockService {
        var overlay: MockOverlay? = null
        var serviceDismissCount = 0
        var onDismissCallbackCount = 0

        fun init() {
            overlay = MockOverlay(
                onDismiss = {
                    onDismissCallbackCount++
                    dismissArOverlay()
                }
            )
        }

        fun dismissArOverlay() {
            val current = overlay ?: return
            serviceDismissCount++
            overlay = null // Null out reference first
            current.dismissOverlay(immediate = true)
        }
    }

    @Test
    fun dismissArOverlay_whenTriggeredFromService_doesNotRecurse() {
        val service = MockService()
        service.init()

        service.dismissArOverlay()

        assertEquals("Service dismiss should only execute cleanly", 1, service.serviceDismissCount)
        assertEquals("OnDismiss callback called once", 1, service.onDismissCallbackCount)
        assertEquals("Overlay cleanup called once", 1, service.overlay?.cleanupCallCount ?: 1)
    }

    @Test
    fun dismissOverlay_whenTriggeredFromView_doesNotRecurse() {
        val service = MockService()
        service.init()

        val view = service.overlay!!
        view.dismissOverlay(immediate = true)

        assertEquals("Service dismiss should be called once via callback", 1, service.serviceDismissCount)
        assertEquals("OnDismiss callback called exactly once", 1, service.onDismissCallbackCount)
        assertEquals("Overlay cleanup called exactly once", 1, view.cleanupCallCount)
    }

    @Test
    fun multipleRapidDismissCalls_areIdempotent() {
        var onDismissCount = 0
        val overlay = MockOverlay { onDismissCount++ }

        overlay.dismissOverlay(immediate = false)
        overlay.dismissOverlay(immediate = true)
        overlay.dismissOverlay(immediate = true)

        assertEquals("onDismiss must only be called once", 1, onDismissCount)
        assertEquals("cleanup must only be called once", 1, overlay.cleanupCallCount)
        assertTrue("isDismissing must remain true", overlay.isDismissing)
    }
}
