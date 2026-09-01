package com.translive.app.engine

import com.translive.app.data.model.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM Unit Test Suite for Language Swapping, Selection, and Input Text Preservation.
 */
class LanguageSwapAndPreservationTest {

    data class TranslationUiState(
        val sourceLanguage: Language = Language.RUSSIAN,
        val isSourceAuto: Boolean = false,
        val detectedSourceLanguage: Language? = null,
        val isDetectingSourceLanguage: Boolean = false,
        val targetLanguage: Language = Language.ENGLISH,
        val sourceText: String = "",
        val translatedText: String = "",
        val isTranslating: Boolean = false,
        val isFastResult: Boolean = false,
        val canImproveWithLlm: Boolean = false,
        val isImprovingWithLlm: Boolean = false,
        val fastTranslationText: String = "",
        val error: String? = null
    )

    class TranslationStateController {
        private val _uiState = MutableStateFlow(TranslationUiState())
        val uiState = _uiState.asStateFlow()

        fun setSourceText(text: String) {
            _uiState.update { it.copy(sourceText = text) }
        }

        fun setTranslationResult(translated: String, isFast: Boolean = true) {
            _uiState.update {
                it.copy(
                    translatedText = translated,
                    isFastResult = isFast,
                    fastTranslationText = if (isFast) translated else it.fastTranslationText
                )
            }
        }

        fun swapLanguages() {
            val oldState = _uiState.value

            val effectiveSource = if (oldState.isSourceAuto) {
                oldState.detectedSourceLanguage ?: return
            } else {
                oldState.sourceLanguage
            }

            val newSourceLang = oldState.targetLanguage
            val newTargetLang = effectiveSource

            val hasTranslation = oldState.translatedText.isNotBlank()

            // Key Rule:
            // If translation exists: swap sourceText <-> translatedText
            // If NO translation yet: PRESERVE sourceText, keep translatedText empty!
            val newSourceText = if (hasTranslation) oldState.translatedText else oldState.sourceText
            val newTranslatedText = if (hasTranslation) oldState.sourceText else ""

            _uiState.update {
                it.copy(
                    sourceLanguage = newSourceLang,
                    targetLanguage = newTargetLang,
                    isSourceAuto = false,
                    detectedSourceLanguage = null,
                    isDetectingSourceLanguage = false,
                    sourceText = newSourceText,
                    translatedText = newTranslatedText,
                    isFastResult = false,
                    canImproveWithLlm = false,
                    isImprovingWithLlm = false,
                    fastTranslationText = "",
                    error = null
                )
            }
        }

        fun setSourceLanguage(lang: Language) {
            _uiState.update {
                it.copy(
                    sourceLanguage = lang,
                    isSourceAuto = false,
                    detectedSourceLanguage = null,
                    isDetectingSourceLanguage = false,
                    // Preserve sourceText; clear stale translation result
                    translatedText = "",
                    isFastResult = false,
                    canImproveWithLlm = false,
                    isImprovingWithLlm = false,
                    fastTranslationText = "",
                    error = null
                )
            }
        }

        fun setTargetLanguage(lang: Language) {
            _uiState.update {
                it.copy(
                    targetLanguage = lang,
                    // Preserve sourceText; clear stale translation result
                    translatedText = "",
                    isFastResult = false,
                    canImproveWithLlm = false,
                    isImprovingWithLlm = false,
                    fastTranslationText = "",
                    error = null
                )
            }
        }

        fun setSourceAuto(detectedLang: Language? = null) {
            _uiState.update {
                it.copy(
                    isSourceAuto = true,
                    detectedSourceLanguage = detectedLang,
                    isDetectingSourceLanguage = detectedLang == null
                )
            }
        }
    }

    private lateinit var controller: TranslationStateController

    @Before
    fun setUp() {
        controller = TranslationStateController()
    }

    @Test
    fun testSwapLanguages_whenNoTranslation_preservesTypedSourceText() {
        controller.setSourceText("Привет, как дела?")
        assertEquals("", controller.uiState.value.translatedText)

        // Perform swap
        controller.swapLanguages()

        // Source and target languages swapped
        assertEquals(Language.ENGLISH, controller.uiState.value.sourceLanguage)
        assertEquals(Language.RUSSIAN, controller.uiState.value.targetLanguage)

        // CRITICAL CHECK: Typed sourceText must NOT be wiped out
        assertEquals("Привет, как дела?", controller.uiState.value.sourceText)
        assertEquals("", controller.uiState.value.translatedText)
    }

    @Test
    fun testSwapLanguages_whenTranslationExists_swapsSourceAndTranslated() {
        controller.setSourceText("Привет")
        controller.setTranslationResult("Hello")

        // Perform swap
        controller.swapLanguages()

        assertEquals(Language.ENGLISH, controller.uiState.value.sourceLanguage)
        assertEquals(Language.RUSSIAN, controller.uiState.value.targetLanguage)

        // In reverse translation mode, the result becomes the new input
        assertEquals("Hello", controller.uiState.value.sourceText)
        assertEquals("Привет", controller.uiState.value.translatedText)
    }

    @Test
    fun testSetSourceLanguage_preservesTypedSourceTextAndClearsStaleTranslation() {
        controller.setSourceText("Привет, мир")
        controller.setTranslationResult("Hello, world")

        // User changes source language to Spanish
        controller.setSourceLanguage(Language.SPANISH)

        // Source language changed
        assertEquals(Language.SPANISH, controller.uiState.value.sourceLanguage)

        // Typed source text preserved
        assertEquals("Привет, мир", controller.uiState.value.sourceText)

        // Stale English translation cleared
        assertEquals("", controller.uiState.value.translatedText)
    }

    @Test
    fun testSetTargetLanguage_preservesTypedSourceTextAndClearsStaleTranslation() {
        controller.setSourceText("Привет, мир")
        controller.setTranslationResult("Hello, world")

        // User changes target language to German
        controller.setTargetLanguage(Language.GERMAN)

        // Target language changed
        assertEquals(Language.GERMAN, controller.uiState.value.targetLanguage)

        // Typed source text preserved
        assertEquals("Привет, мир", controller.uiState.value.sourceText)

        // Stale English translation cleared
        assertEquals("", controller.uiState.value.translatedText)
    }

    @Test
    fun testAutoDetection_swapResolvesDetectedSourceLanguage() {
        controller.setSourceText("Xin chào")
        controller.setSourceAuto(detectedLang = Language.VIETNAMESE)

        // Swap when auto detected Vietnamese -> Target was English
        controller.swapLanguages()

        assertFalse(controller.uiState.value.isSourceAuto)
        assertEquals(Language.ENGLISH, controller.uiState.value.sourceLanguage)
        assertEquals(Language.VIETNAMESE, controller.uiState.value.targetLanguage)
        assertEquals("Xin chào", controller.uiState.value.sourceText)
    }
}
