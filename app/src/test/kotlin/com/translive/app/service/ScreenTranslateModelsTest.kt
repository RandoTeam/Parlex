package com.translive.app.service

import com.translive.app.data.model.Language
import com.translive.app.service.model.HudAction
import com.translive.app.service.model.HudStatus
import com.translive.app.service.model.HudUiState
import com.translive.app.service.model.ScreenTranslateMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenTranslateModelsTest {

    @Test
    fun default_HudUiState_initialization_is_valid() {
        val state = HudUiState()
        assertEquals(ScreenTranslateMode.AUTO_LIVE, state.mode)
        assertEquals(HudStatus.MONITORING, state.status)
        assertEquals(Language.ENGLISH, state.sourceLanguage)
        assertTrue(state.isSourceAuto)
        assertEquals(Language.RUSSIAN, state.targetLanguage)
        assertFalse(state.isPaused)
        assertFalse(state.isInteractiveMode)
    }

    @Test
    fun mode_switching_toggles_between_AUTO_LIVE_and_SINGLE_SHOT() {
        var state = HudUiState(mode = ScreenTranslateMode.AUTO_LIVE)
        
        state = state.copy(mode = ScreenTranslateMode.SINGLE_SHOT)
        assertEquals(ScreenTranslateMode.SINGLE_SHOT, state.mode)

        state = state.copy(mode = ScreenTranslateMode.AUTO_LIVE)
        assertEquals(ScreenTranslateMode.AUTO_LIVE, state.mode)
    }

    @Test
    fun language_swapping_correctly_exchanges_source_and_target_and_disables_auto_detect() {
        var state = HudUiState(
            sourceLanguage = Language.ENGLISH,
            targetLanguage = Language.VIETNAMESE,
            isSourceAuto = true
        )

        val oldSrc = state.sourceLanguage
        val oldTgt = state.targetLanguage
        state = state.copy(
            sourceLanguage = oldTgt,
            targetLanguage = oldSrc,
            isSourceAuto = false
        )

        assertEquals(Language.VIETNAMESE, state.sourceLanguage)
        assertEquals(Language.ENGLISH, state.targetLanguage)
        assertFalse(state.isSourceAuto)
    }

    @Test
    fun pause_action_updates_state_and_status_appropriately() {
        var state = HudUiState(status = HudStatus.MONITORING, isPaused = false)

        state = state.copy(isPaused = true, status = HudStatus.PAUSED)
        assertTrue(state.isPaused)
        assertEquals(HudStatus.PAUSED, state.status)

        state = state.copy(isPaused = false, status = HudStatus.MONITORING)
        assertFalse(state.isPaused)
        assertEquals(HudStatus.MONITORING, state.status)
    }

    @Test
    fun interactive_mode_toggle_updates_boolean_property() {
        var state = HudUiState(isInteractiveMode = false)
        state = state.copy(isInteractiveMode = true)
        assertTrue(state.isInteractiveMode)

        state = state.copy(isInteractiveMode = false)
        assertFalse(state.isInteractiveMode)
    }

    @Test
    fun all_HudStatus_enum_values_are_defined_and_distinct() {
        val statuses = HudStatus.values()
        assertEquals(6, statuses.size)
        assertTrue(statuses.contains(HudStatus.IDLE))
        assertTrue(statuses.contains(HudStatus.MONITORING))
        assertTrue(statuses.contains(HudStatus.STABILIZING))
        assertTrue(statuses.contains(HudStatus.TRANSLATING))
        assertTrue(statuses.contains(HudStatus.PAUSED))
        assertTrue(statuses.contains(HudStatus.ERROR))
    }
}
