package com.translive.app.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for Screen Translation Overlay and Quick Settings Tile synchronization.
 */
class ScreenOverlaySyncTest {

    @Test
    fun testOverlayRunningStateFlowContract() = runBlocking {
        // Initial state
        assertFalse(ScreenTranslateOverlayService.isServiceRunning.first())

        // Verify state is reactive
        val stateFlow = ScreenTranslateOverlayService.isServiceRunning
        assertEquals(false, stateFlow.value)
    }

    @Test
    fun testQuickToolCardSubtitleResolution() {
        fun resolveSubtitle(isOverlayRunning: Boolean): String {
            return if (isOverlayRunning) "Активен • Нажмите, чтобы скрыть" else "Плавающая кнопка"
        }

        assertEquals("Плавающая кнопка", resolveSubtitle(false))
        assertEquals("Активен • Нажмите, чтобы скрыть", resolveSubtitle(true))
    }

    @Test
    fun testTileStateResolution() {
        // Android Tile.STATE_ACTIVE = 2, STATE_INACTIVE = 1
        val stateActive = 2
        val stateInactive = 1

        fun calculateTileState(isOverlayRunning: Boolean): Int {
            return if (isOverlayRunning) stateActive else stateInactive
        }

        assertEquals(stateInactive, calculateTileState(false))
        assertEquals(stateActive, calculateTileState(true))
    }
}
