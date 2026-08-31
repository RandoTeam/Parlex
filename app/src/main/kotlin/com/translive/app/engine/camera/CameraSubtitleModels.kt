package com.translive.app.engine.camera

import android.graphics.Rect
import com.translive.app.data.model.Language

/**
 * A single tracked subtitle line with spatial coordinates and stability metrics.
 */
data class SubtitleLine(
    val id: String,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val boundingBox: Rect = Rect(),
    val confidence: Float = 1.0f,
    val lastSeenFrame: Int = 0,
    val hitsCount: Int = 1,
    val isStable: Boolean = true
)

/**
 * Visual styling preferences for the floating teleprompter / subtitle HUD.
 */
data class SubtitleStyle(
    val fontSizeSp: Int = 16,
    val backgroundOpacity: Float = 0.82f,
    val showOriginal: Boolean = true,
    val positionTop: Boolean = false
)

/**
 * Comprehensive UI state for Live Subtitle mode in camera viewfinder.
 */
data class LiveSubtitleUiState(
    val isSubtitleModeActive: Boolean = false,
    val subtitles: List<SubtitleLine> = emptyList(),
    val isPaused: Boolean = false,
    val isTtsSpeaking: Boolean = false,
    val style: SubtitleStyle = SubtitleStyle(),
    val activeTrackCount: Int = 0
)

/**
 * Actions dispatched from the live subtitle controls.
 */
sealed interface SubtitleAction {
    data object ToggleSubtitleMode : SubtitleAction
    data object TogglePause : SubtitleAction
    data object ToggleTts : SubtitleAction
    data object ToggleShowOriginal : SubtitleAction
    data object CycleFontSize : SubtitleAction
    data object TogglePosition : SubtitleAction
    data object ClearSubtitles : SubtitleAction
}
