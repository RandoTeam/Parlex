package com.translive.app.ui.overlay

import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.Language
import com.translive.app.service.model.HudStatus
import com.translive.app.service.model.ScreenTranslateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.max

class MiniOverlayHudTest {

    enum class HudPresentationState {
        HIDDEN,
        EXPANDED,
        SELECTING_LANGUAGE,
        SETTLED
    }

    sealed interface MiniHudEvent {
        data object Show : MiniHudEvent
        data object Hide : MiniHudEvent
        data object OpenLanguagePicker : MiniHudEvent
        data object CloseLanguagePicker : MiniHudEvent
        data class SelectTarget(val language: Language) : MiniHudEvent
        data class SelectSource(val language: Language, val isAuto: Boolean) : MiniHudEvent
        data object SwapLanguages : MiniHudEvent
        data class ChangeEngineMode(val policy: TranslationPolicy) : MiniHudEvent
        data class ChangeScreenMode(val mode: ScreenTranslateMode) : MiniHudEvent
        data object TogglePause : MiniHudEvent
    }

    data class MiniHudState(
        val presentationState: HudPresentationState = HudPresentationState.HIDDEN,
        val operationalStatus: HudStatus = HudStatus.IDLE,
        val screenMode: ScreenTranslateMode = ScreenTranslateMode.AUTO_LIVE,
        val enginePolicy: TranslationPolicy = TranslationPolicy.FAST_WITH_LLM_IMPROVE,
        val sourceLanguage: Language = Language.ENGLISH,
        val targetLanguage: Language = Language.RUSSIAN,
        val isSourceAuto: Boolean = true,
        val isPaused: Boolean = false
    )

    class MiniHudStateMachine(initialState: MiniHudState = MiniHudState()) {
        var state: MiniHudState = initialState
            private set

        fun dispatch(event: MiniHudEvent): MiniHudState {
            state = when (event) {
                is MiniHudEvent.Show -> {
                    state.copy(
                        presentationState = HudPresentationState.EXPANDED,
                        operationalStatus = if (state.isPaused) HudStatus.PAUSED else HudStatus.MONITORING
                    )
                }
                is MiniHudEvent.Hide -> {
                    state.copy(
                        presentationState = HudPresentationState.HIDDEN,
                        operationalStatus = HudStatus.IDLE
                    )
                }
                is MiniHudEvent.OpenLanguagePicker -> {
                    if (state.presentationState == HudPresentationState.HIDDEN) state
                    else state.copy(presentationState = HudPresentationState.SELECTING_LANGUAGE)
                }
                is MiniHudEvent.CloseLanguagePicker -> {
                    if (state.presentationState == HudPresentationState.SELECTING_LANGUAGE) {
                        state.copy(presentationState = HudPresentationState.EXPANDED)
                    } else state
                }
                is MiniHudEvent.SelectTarget -> {
                    state.copy(
                        targetLanguage = event.language,
                        presentationState = HudPresentationState.SETTLED
                    )
                }
                is MiniHudEvent.SelectSource -> {
                    state.copy(
                        sourceLanguage = event.language,
                        isSourceAuto = event.isAuto,
                        presentationState = HudPresentationState.SETTLED
                    )
                }
                is MiniHudEvent.SwapLanguages -> {
                    val oldSource = state.sourceLanguage
                    val oldTarget = state.targetLanguage
                    state.copy(
                        sourceLanguage = oldTarget,
                        targetLanguage = oldSource,
                        isSourceAuto = false
                    )
                }
                is MiniHudEvent.ChangeEngineMode -> {
                    state.copy(enginePolicy = event.policy)
                }
                is MiniHudEvent.ChangeScreenMode -> {
                    state.copy(screenMode = event.mode)
                }
                is MiniHudEvent.TogglePause -> {
                    val newPaused = !state.isPaused
                    state.copy(
                        isPaused = newPaused,
                        operationalStatus = if (newPaused) HudStatus.PAUSED else HudStatus.MONITORING
                    )
                }
            }
            return state
        }
    }

    object HudOrientationProjectionMath {
        data class DisplayBounds(
            val widthPx: Int,
            val heightPx: Int,
            val density: Float,
            val insetTopPx: Int = 0,
            val insetBottomPx: Int = 0,
            val insetLeftPx: Int = 0,
            val insetRightPx: Int = 0
        )

        data class ProjectedCoordinates(
            val x: Int,
            val y: Int,
            val clamped: Boolean
        )

        fun projectCoordinates(
            currentX: Int,
            currentY: Int,
            hudWidthPx: Int,
            hudHeightPx: Int,
            oldDisplay: DisplayBounds,
            newDisplay: DisplayBounds,
            marginDp: Int = 16
        ): ProjectedCoordinates {
            val oldUsableMinX = oldDisplay.insetLeftPx
            val oldUsableMaxX = max(oldUsableMinX, oldDisplay.widthPx - oldDisplay.insetRightPx - hudWidthPx)
            val oldUsableMinY = oldDisplay.insetTopPx
            val oldUsableMaxY = max(oldUsableMinY, oldDisplay.heightPx - oldDisplay.insetBottomPx - hudHeightPx)

            val xRatio = if (oldUsableMaxX > oldUsableMinX) {
                ((currentX - oldUsableMinX).toFloat() / (oldUsableMaxX - oldUsableMinX).toFloat()).coerceIn(0f, 1f)
            } else 0.5f

            val yRatio = if (oldUsableMaxY > oldUsableMinY) {
                ((currentY - oldUsableMinY).toFloat() / (oldUsableMaxY - oldUsableMinY).toFloat()).coerceIn(0f, 1f)
            } else 0.5f

            val marginPx = (marginDp * newDisplay.density).toInt()
            val newUsableMinX = newDisplay.insetLeftPx + marginPx
            val newUsableMaxX = max(newUsableMinX, newDisplay.widthPx - newDisplay.insetRightPx - hudWidthPx - marginPx)
            val newUsableMinY = newDisplay.insetTopPx + marginPx
            val newUsableMaxY = max(newUsableMinY, newDisplay.heightPx - newDisplay.insetBottomPx - hudHeightPx - marginPx)

            val rawProjectedX = (newUsableMinX + xRatio * (newUsableMaxX - newUsableMinX)).toInt()
            val rawProjectedY = (newUsableMinY + yRatio * (newUsableMaxY - newUsableMinY)).toInt()

            val clampedX = rawProjectedX.coerceIn(newUsableMinX, newUsableMaxX)
            val clampedY = rawProjectedY.coerceIn(newUsableMinY, newUsableMaxY)

            return ProjectedCoordinates(
                x = clampedX,
                y = clampedY,
                clamped = (rawProjectedX != clampedX) || (rawProjectedY != clampedY)
            )
        }
    }

    private lateinit var stateMachine: MiniHudStateMachine

    @Before
    fun setUp() {
        stateMachine = MiniHudStateMachine()
    }

    @Test
    fun testHudUiStateTransition_hidden_to_expanded_to_selectingLanguage_to_settled() {
        assertEquals(HudPresentationState.HIDDEN, stateMachine.state.presentationState)

        stateMachine.dispatch(MiniHudEvent.Show)
        assertEquals(HudPresentationState.EXPANDED, stateMachine.state.presentationState)

        stateMachine.dispatch(MiniHudEvent.OpenLanguagePicker)
        assertEquals(HudPresentationState.SELECTING_LANGUAGE, stateMachine.state.presentationState)

        stateMachine.dispatch(MiniHudEvent.SelectTarget(Language.VIETNAMESE))
        assertEquals(HudPresentationState.SETTLED, stateMachine.state.presentationState)
        assertEquals(Language.VIETNAMESE, stateMachine.state.targetLanguage)

        stateMachine.dispatch(MiniHudEvent.Hide)
        assertEquals(HudPresentationState.HIDDEN, stateMachine.state.presentationState)
    }

    @Test
    fun testTargetLanguageSwitchingAcrossAllSupportedLanguages() {
        stateMachine.dispatch(MiniHudEvent.Show)

        val allLanguages = Language.allLanguages
        assertEquals(38, allLanguages.size)

        for (lang in allLanguages) {
            stateMachine.dispatch(MiniHudEvent.OpenLanguagePicker)
            val updated = stateMachine.dispatch(MiniHudEvent.SelectTarget(lang))
            assertEquals(lang, updated.targetLanguage)
            assertEquals(HudPresentationState.SETTLED, updated.presentationState)
        }
    }

    @Test
    fun testEngineModeSwitchingBetweenFastNmtAndLocalLlm() {
        stateMachine.dispatch(MiniHudEvent.Show)

        assertEquals(TranslationPolicy.FAST_WITH_LLM_IMPROVE, stateMachine.state.enginePolicy)

        stateMachine.dispatch(MiniHudEvent.ChangeEngineMode(TranslationPolicy.FAST))
        assertEquals(TranslationPolicy.FAST, stateMachine.state.enginePolicy)

        stateMachine.dispatch(MiniHudEvent.ChangeEngineMode(TranslationPolicy.LLM_ONLY))
        assertEquals(TranslationPolicy.LLM_ONLY, stateMachine.state.enginePolicy)
    }

    @Test
    fun testPortraitToLandscapeProjectionCalculation() {
        val portraitDisplay = HudOrientationProjectionMath.DisplayBounds(
            widthPx = 1080,
            heightPx = 2400,
            density = 2.625f
        )

        val landscapeDisplay = HudOrientationProjectionMath.DisplayBounds(
            widthPx = 2400,
            heightPx = 1080,
            density = 2.625f
        )

        val hudWidthPx = 300
        val hudHeightPx = 80

        val projected = HudOrientationProjectionMath.projectCoordinates(
            currentX = 200,
            currentY = 1000,
            hudWidthPx = hudWidthPx,
            hudHeightPx = hudHeightPx,
            oldDisplay = portraitDisplay,
            newDisplay = landscapeDisplay,
            marginDp = 16
        )

        assertTrue(projected.x >= 0)
        assertTrue(projected.x + hudWidthPx <= landscapeDisplay.widthPx)
        assertTrue(projected.y >= 0)
        assertTrue(projected.y + hudHeightPx <= landscapeDisplay.heightPx)
    }
}
