package com.translive.app.engine

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Fast on-device NMT for camera translation (~20ms per sentence).
 * Uses Google ML Kit Translation API — small models (~30MB per language pair).
 *
 * This is separate from the main TranslationEngine (HY-MT LLM) because:
 * - Camera needs realtime speed (~100ms total pipeline)
 * - HY-MT is too slow for camera (2-5 seconds per translation)
 * - ML Kit quality is lower but sufficient for visual context
 */
@Singleton
class CameraTranslateEngine @Inject constructor() {

    companion object {
        private const val TAG = "CameraTranslateEngine"
    }

    private var currentTranslator: Translator? = null
    private var currentSourceLang: String = ""
    private var currentTargetLang: String = ""

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    /**
     * Map our Language codes to ML Kit TranslateLanguage codes.
     */
    private fun toMlKitLang(code: String): String? {
        return when (code) {
            "ru" -> TranslateLanguage.RUSSIAN
            "en" -> TranslateLanguage.ENGLISH
            "de" -> TranslateLanguage.GERMAN
            "fr" -> TranslateLanguage.FRENCH
            "es" -> TranslateLanguage.SPANISH
            "it" -> TranslateLanguage.ITALIAN
            "pt" -> TranslateLanguage.PORTUGUESE
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "ar" -> TranslateLanguage.ARABIC
            "hi" -> TranslateLanguage.HINDI
            "tr" -> TranslateLanguage.TURKISH
            "pl" -> TranslateLanguage.POLISH
            "uk" -> TranslateLanguage.UKRAINIAN
            "nl" -> TranslateLanguage.DUTCH
            "cs" -> TranslateLanguage.CZECH
            "sv" -> TranslateLanguage.SWEDISH
            "vi" -> TranslateLanguage.VIETNAMESE
            "th" -> TranslateLanguage.THAI
            else -> null
        }
    }

    /**
     * Prepare translator for given language pair.
     * Downloads model if needed (~30MB, WiFi only by default).
     */
    suspend fun prepare(sourceCode: String, targetCode: String): Boolean {
        // Already prepared for this pair
        if (sourceCode == currentSourceLang && targetCode == currentTargetLang && _isReady.value) {
            return true
        }

        val srcLang = toMlKitLang(sourceCode)
        val tgtLang = toMlKitLang(targetCode)

        if (srcLang == null || tgtLang == null) {
            Log.w(TAG, "Unsupported language pair: $sourceCode -> $targetCode")
            _isReady.value = false
            return false
        }

        // Close previous translator
        currentTranslator?.close()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang)
            .setTargetLanguage(tgtLang)
            .build()

        val translator = Translation.getClient(options)
        currentTranslator = translator
        currentSourceLang = sourceCode
        currentTargetLang = targetCode

        // Download model if needed
        _isDownloading.value = true
        _isReady.value = false

        return suspendCancellableCoroutine { cont ->
            val conditions = DownloadConditions.Builder()
                .build()  // Allow any network (not just WiFi)

            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    Log.i(TAG, "ML Kit model ready: $sourceCode -> $targetCode")
                    _isReady.value = true
                    _isDownloading.value = false
                    if (cont.isActive) cont.resume(true)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Model download failed: ${e.message}", e)
                    _isReady.value = false
                    _isDownloading.value = false
                    if (cont.isActive) cont.resume(false)
                }
        }
    }

    /**
     * Translate text. Fast — ~20ms for a sentence.
     * Returns original text if translation fails.
     */
    suspend fun translate(text: String): String {
        val translator = currentTranslator ?: return text
        if (!_isReady.value) return text
        if (text.isBlank()) return text

        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { translated ->
                    if (cont.isActive) cont.resume(translated)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed: ${e.message}")
                    if (cont.isActive) cont.resume(text)
                }
        }
    }

    /**
     * Translate multiple lines in batch — still fast since ML Kit NMT is lightweight.
     */
    suspend fun translateLines(lines: List<String>): List<String> {
        if (!_isReady.value || currentTranslator == null) return lines
        return lines.map { translate(it) }
    }

    fun release() {
        currentTranslator?.close()
        currentTranslator = null
        _isReady.value = false
        currentSourceLang = ""
        currentTargetLang = ""
    }
}
