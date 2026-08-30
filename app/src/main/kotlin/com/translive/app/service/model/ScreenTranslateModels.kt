package com.translive.app.service.model

import com.translive.app.data.model.Language

/** Operating mode for screen translation. */
enum class ScreenTranslateMode {
    /** Single snapshot on-demand: captures high-res screenshot and displays translation card. */
    SINGLE_SHOT,
    /** Continuous Live AR: monitors screen motion and updates in-place overlays automatically. */
    AUTO_LIVE
}

/** Operational status of the floating HUD indicator. */
enum class HudStatus {
    IDLE,
    MONITORING,
    STABILIZING,
    TRANSLATING,
    PAUSED,
    ERROR
}

/** Comprehensive UI state for the floating screen translation HUD and overlay. */
data class HudUiState(
    val mode: ScreenTranslateMode = ScreenTranslateMode.AUTO_LIVE,
    val status: HudStatus = HudStatus.MONITORING,
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.RUSSIAN,
    val isSourceAuto: Boolean = true,
    val isInteractiveMode: Boolean = false,
    val isPaused: Boolean = false,
    val activeBlockCount: Int = 0,
    val lastProcessingTimeMs: Long = 0L,
    val isMiniLangPickerVisible: Boolean = false,
    val errorMessage: String? = null
)

/** Actions dispatched from the floating HUD controls. */
sealed interface HudAction {
    data class SetMode(val mode: ScreenTranslateMode) : HudAction
    data object TriggerSingleShot : HudAction
    data object TogglePause : HudAction
    data object ToggleInteractiveMode : HudAction
    data object ToggleMiniLangPicker : HudAction
    data class SelectSourceLanguage(val language: Language, val isAuto: Boolean) : HudAction
    data class SelectTargetLanguage(val language: Language) : HudAction
    data object SwapLanguages : HudAction
    data object CloseService : HudAction
}
