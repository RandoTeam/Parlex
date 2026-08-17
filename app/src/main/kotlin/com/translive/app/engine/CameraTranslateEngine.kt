package com.translive.app.engine

import android.util.Log
import com.translive.app.data.model.Language
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    data class PackageStatus(
        val supported: Boolean,
        /** ML Kit language codes required for the current camera pair. */
        val requiredLanguageCodes: Set<String> = emptySet(),
        val downloadedLanguageCodes: Set<String> = emptySet()
    ) {
        val isReady: Boolean get() = supported &&
            requiredLanguageCodes.all { it in downloadedLanguageCodes }
        val missingLanguageCodes: Set<String> get() =
            requiredLanguageCodes - downloadedLanguageCodes
    }

    /**
     * One downloadable ML Kit model. Some catalog choices deliberately share a
     * model: simplified/traditional Chinese and the two Chinese dialect entries
     * all use the same on-device Chinese package.
     */
    data class LanguagePackage(
        val modelLanguageCode: String,
        val languages: List<Language>
    )

    /**
     * Map our Language codes to ML Kit TranslateLanguage codes.
     * ML Kit supports 59 languages — most of ours are covered.
     */
    fun toMlKitLang(code: String): String? {
        return when (code) {
            "en" -> TranslateLanguage.ENGLISH
            "zh", "zh-Hant" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "es" -> TranslateLanguage.SPANISH
            "pt" -> TranslateLanguage.PORTUGUESE
            "it" -> TranslateLanguage.ITALIAN
            "nl" -> TranslateLanguage.DUTCH
            "pl" -> TranslateLanguage.POLISH
            "cs" -> TranslateLanguage.CZECH
            "tr" -> TranslateLanguage.TURKISH
            "uk" -> TranslateLanguage.UKRAINIAN
            "ru" -> TranslateLanguage.RUSSIAN
            "hi" -> TranslateLanguage.HINDI
            "bn" -> TranslateLanguage.BENGALI
            "gu" -> TranslateLanguage.GUJARATI
            "mr" -> TranslateLanguage.MARATHI
            "ta" -> TranslateLanguage.TAMIL
            "te" -> TranslateLanguage.TELUGU
            "ur" -> TranslateLanguage.URDU
            "fa" -> TranslateLanguage.PERSIAN
            "he" -> TranslateLanguage.HEBREW
            "ar" -> TranslateLanguage.ARABIC
            "th" -> TranslateLanguage.THAI
            "vi" -> TranslateLanguage.VIETNAMESE
            "id" -> TranslateLanguage.INDONESIAN
            "ms" -> TranslateLanguage.MALAY
            "fil" -> TranslateLanguage.TAGALOG
            // Dialects — map to closest supported
            "yue", "nan" -> TranslateLanguage.CHINESE
            // Not in ML Kit Translation
            "my", "km", "mn", "bo", "ug" -> null
            else -> null
        }
    }

    /**
     * Lists the actual downloadable language models, not every source-target
     * pair. Once two language packages are installed, ML Kit translates in
     * either direction without another download.
     */
    fun availableLanguagePackages(): List<LanguagePackage> =
        Language.allLanguages
            .mapNotNull { language ->
                toMlKitLang(language.code)?.let { modelCode -> modelCode to language }
            }
            .groupBy({ it.first }, { it.second })
            .map { (modelCode, languages) -> LanguagePackage(modelCode, languages) }
            .sortedBy { it.languages.first().displayName }

    suspend fun downloadedLanguageCodes(): Set<String> {
        val manager = RemoteModelManager.getInstance()
        return suspendCancellableCoroutine { cont ->
            manager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    if (cont.isActive) cont.resume(models.mapTo(mutableSetOf()) { it.language })
                }
                .addOnFailureListener {
                    Log.w(TAG, "Cannot read installed ML Kit language packs", it)
                    if (cont.isActive) cont.resume(emptySet())
                }
        }
    }

    /**
     * Returns the exact on-device packages needed for a camera language pair.
     * This is intentionally separate from activation: opening the camera must
     * never make an implicit network request.
     */
    suspend fun getPackageStatus(sourceCode: String, targetCode: String): PackageStatus {
        val source = toMlKitLang(sourceCode) ?: return PackageStatus(supported = false)
        val target = toMlKitLang(targetCode) ?: return PackageStatus(supported = false)
        if (source == target) {
            return PackageStatus(supported = true)
        }

        val required = linkedSetOf(source, target)
        val downloaded = downloadedLanguageCodes()

        return PackageStatus(
            supported = true,
            requiredLanguageCodes = required,
            downloadedLanguageCodes = downloaded
        )
    }

    /**
     * Explicitly downloads the language packages selected by the user in the
     * Model Manager, then activates their translator. ML Kit owns these files,
     * so they cannot be exported as ordinary app model files.
     */
    suspend fun downloadAndActivate(sourceCode: String, targetCode: String): Boolean {
        val status = getPackageStatus(sourceCode, targetCode)
        if (!status.supported) return false
        if (status.isReady) return activateDownloadedPair(sourceCode, targetCode)

        _isDownloading.value = true
        _isReady.value = false
        return try {
            val manager = RemoteModelManager.getInstance()
            val conditions = DownloadConditions.Builder().build()
            status.missingLanguageCodes.forEach { language ->
                val model = TranslateRemoteModel.Builder(language).build()
                suspendCancellableCoroutine<Unit> { cont ->
                    manager.download(model, conditions)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                        .addOnFailureListener { error ->
                            if (cont.isActive) cont.resumeWith(Result.failure(error))
                        }
                }
            }
            activateDownloadedPair(sourceCode, targetCode)
        } catch (error: Throwable) {
            Log.e(TAG, "ML Kit language pack download failed", error)
            false
        } finally {
            _isDownloading.value = false
        }
    }

    /** Download one or more language packages selected in the Models screen. */
    suspend fun downloadLanguagePackages(modelLanguageCodes: Collection<String>): Boolean {
        val supportedCodes = TranslateLanguage.getAllLanguages().toSet()
        val requested = modelLanguageCodes.filter { it in supportedCodes }.toSet()
        if (requested.isEmpty()) return false

        val missing = requested - downloadedLanguageCodes()
        if (missing.isEmpty()) return true

        _isDownloading.value = true
        return try {
            val manager = RemoteModelManager.getInstance()
            val conditions = DownloadConditions.Builder().build()
            missing.forEach { language ->
                val model = TranslateRemoteModel.Builder(language).build()
                suspendCancellableCoroutine<Unit> { cont ->
                    manager.download(model, conditions)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                        .addOnFailureListener { error ->
                            if (cont.isActive) cont.resumeWith(Result.failure(error))
                        }
                }
            }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "ML Kit language package download failed", error)
            false
        } finally {
            _isDownloading.value = false
        }
    }

    /** Downloads only the missing reusable packages for a pair without changing the active camera translator. */
    suspend fun downloadPairPackages(sourceCode: String, targetCode: String): Boolean {
        val status = getPackageStatus(sourceCode, targetCode)
        return status.supported && downloadLanguagePackages(status.missingLanguageCodes)
    }

    /**
     * Activates only an already installed pair. It never starts a download.
     */
    suspend fun activateDownloadedPair(sourceCode: String, targetCode: String): Boolean {
        // Already prepared for this pair
        if (sourceCode == currentSourceLang && targetCode == currentTargetLang && _isReady.value) {
            return true
        }
        if (sourceCode == currentSourceLang && targetCode == currentTargetLang && _isDownloading.value) {
            return false
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

        _isReady.value = false
        val status = getPackageStatus(sourceCode, targetCode)
        if (!status.isReady) {
            Log.i(TAG, "ML Kit language packs are not installed: $sourceCode -> $targetCode")
            return false
        }

        Log.i(TAG, "ML Kit model activated: $sourceCode -> $targetCode")
        _isReady.value = true
        return true
    }

    fun isReadyFor(sourceCode: String, targetCode: String): Boolean =
        sourceCode == currentSourceLang &&
            targetCode == currentTargetLang &&
            _isReady.value &&
            currentTranslator != null

    fun isPreparingFor(sourceCode: String, targetCode: String): Boolean =
        sourceCode == currentSourceLang &&
            targetCode == currentTargetLang &&
            _isDownloading.value

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
        val concurrency = Semaphore(3)
        return coroutineScope {
            lines.map { line ->
                async { concurrency.withPermit { translate(line) } }
            }.awaitAll()
        }
    }

    fun release() {
        currentTranslator?.close()
        currentTranslator = null
        _isReady.value = false
        currentSourceLang = ""
        currentTargetLang = ""
    }
}
