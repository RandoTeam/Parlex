package com.translive.app.engine.camera

import android.graphics.Rect
import com.translive.app.data.model.DictionaryEntry
import com.translive.app.data.model.Language

/**
 * Types of quick contextual actions available for travel items.
 */
enum class TravelActionType {
    COPY,
    SPEAK,
    DICTIONARY_LOOKUP,
    SAVE_FAVORITE,
    CONVERT_CURRENCY
}

/**
 * Interactive travel card state displayed over recognized/translated camera elements.
 */
data class TravelCardUiState(
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val boundingBox: Rect,
    val currencyConversion: String? = null,
    val dictionaryEntries: List<DictionaryEntry> = emptyList(),
    val isFavorite: Boolean = false,
    val isSpeaking: Boolean = false
)

/**
 * Action item for quick travel interactions.
 */
sealed interface TravelCardAction {
    data object Copy : TravelCardAction
    data object CopyBoth : TravelCardAction
    data object SpeakTranslation : TravelCardAction
    data object SpeakOriginal : TravelCardAction
    data class LookupWord(val word: String) : TravelCardAction
    data object ToggleFavorite : TravelCardAction
    data object Dismiss : TravelCardAction
}
