package com.translive.app.engine

import com.translive.app.AppLog
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.translive.app.data.model.Language
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LanguageDetectionEngine @Inject constructor() {

    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.35f)
            .build()
    )

    suspend fun detect(text: String, fallback: Language = Language.ENGLISH): Language {
        val normalized = text.trim()
        if (normalized.length < MIN_DETECTION_CHARS) return detectByScript(normalized, fallback)

        val scriptCandidate = detectByScript(normalized, fallback)
        if (scriptCandidate != fallback && expectedScript(scriptCandidate) != TextScript.LATIN) {
            return scriptCandidate
        }

        val mlKitLanguage = identifyWithMlKit(normalized)
        val mappedLanguage = mlKitLanguage?.let(::mapMlKitLanguage)

        return mappedLanguage
            ?: if (scriptCandidate != fallback) scriptCandidate else detectLatinFallback(normalized, fallback)
    }

    fun detectByScript(text: String, fallback: Language = Language.ENGLISH): Language {
        if (text.isBlank()) return fallback

        val counts = TextScript.entries.associateWithTo(mutableMapOf()) { 0 }
        for (char in text) {
            val script = scriptForChar(char)
            if (script != TextScript.OTHER) {
                counts[script] = counts.getValue(script) + 1
            }
        }

        val dominant = counts
            .filterKeys { it != TextScript.OTHER }
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= MIN_SCRIPT_LETTERS }
            ?.key
            ?: return fallback

        return when (dominant) {
            TextScript.CYRILLIC -> guessCyrillicLanguage(text)
            TextScript.CJK -> guessCjkLanguage(text)
            TextScript.DEVANAGARI -> Language.HINDI
            TextScript.ARABIC -> guessArabicScriptLanguage(text)
            TextScript.HEBREW -> Language.HEBREW
            TextScript.LATIN -> detectLatinFallback(text, fallback)
            TextScript.OTHER -> fallback
        }
    }

    fun detectLineFallback(text: String, fallback: Language): Language =
        detectByScript(text, fallback)

    private suspend fun identifyWithMlKit(text: String): String? =
        suspendCancellableCoroutine { cont ->
            identifier.identifyLanguage(text)
                .addOnSuccessListener { languageTag ->
                    if (cont.isActive) {
                        cont.resume(languageTag.takeUnless { it == "und" })
                    }
                }
                .addOnFailureListener { error ->
                    AppLog.w(TAG, "Language identification failed: ${error.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }

    private fun mapMlKitLanguage(languageTag: String): Language? {
        val normalized = languageTag.lowercase(Locale.ROOT)
        return when {
            normalized == "zh-hant" || normalized == "zh-tw" || normalized == "zh-hk" ->
                Language.CHINESE_TRADITIONAL
            normalized.startsWith("zh") -> Language.CHINESE_SIMPLIFIED
            normalized == "tl" || normalized == "fil" -> Language.FILIPINO
            normalized == "iw" || normalized == "he" -> Language.HEBREW
            normalized == "fa" || normalized == "ar" || normalized == "ur" -> {
                Language.fromCode(normalized) ?: Language.ARABIC
            }
            else -> Language.fromCode(normalized.substringBefore("-"))
        }
    }

    private fun detectLatinFallback(text: String, fallback: Language): Language {
        val lowerText = text.lowercase(Locale.ROOT)
        val scores = linkedMapOf(
            Language.ENGLISH to languageHeuristicScore(lowerText, ENGLISH_HINTS, ENGLISH_CHARS),
            Language.FRENCH to languageHeuristicScore(lowerText, FRENCH_HINTS, FRENCH_CHARS),
            Language.GERMAN to languageHeuristicScore(lowerText, GERMAN_HINTS, GERMAN_CHARS),
            Language.CZECH to languageHeuristicScore(lowerText, CZECH_HINTS, CZECH_CHARS),
            Language.SPANISH to languageHeuristicScore(lowerText, SPANISH_HINTS, SPANISH_CHARS),
            Language.PORTUGUESE to languageHeuristicScore(lowerText, PORTUGUESE_HINTS, PORTUGUESE_CHARS),
            Language.POLISH to languageHeuristicScore(lowerText, POLISH_HINTS, POLISH_CHARS),
            Language.TURKISH to languageHeuristicScore(lowerText, TURKISH_HINTS, TURKISH_CHARS),
            Language.VIETNAMESE to languageHeuristicScore(lowerText, VIETNAMESE_HINTS, VIETNAMESE_CHARS)
        )

        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: fallback
    }

    private fun languageHeuristicScore(
        lowerText: String,
        hints: Set<String>,
        distinctiveChars: Set<Char>
    ): Int {
        val words = lowerText.split(Regex("""[^a-z\u00c0-\u024f\u1e00-\u1eff]+"""))
            .filter { it.isNotBlank() }
        val hintScore = words.count { it in hints } * 3
        val charScore = lowerText.count { it in distinctiveChars } * 4
        return hintScore + charScore
    }

    private fun guessCjkLanguage(text: String): Language =
        when {
            text.any { it in '\uAC00'..'\uD7AF' } -> Language.KOREAN
            text.any { it in '\u3040'..'\u30FF' } -> Language.JAPANESE
            else -> Language.CHINESE_SIMPLIFIED
        }

    private fun guessCyrillicLanguage(text: String): Language =
        if (text.any { it in UKRAINIAN_CHARS }) Language.UKRAINIAN else Language.RUSSIAN

    private fun guessArabicScriptLanguage(text: String): Language =
        when {
            text.any { it in URDU_CHARS } -> Language.URDU
            text.any { it in PERSIAN_CHARS } -> Language.PERSIAN
            else -> Language.ARABIC
        }

    private fun expectedScript(language: Language): TextScript =
        when (language.code) {
            "ru", "uk", "mn" -> TextScript.CYRILLIC
            "zh", "zh-Hant", "ja", "ko", "yue", "nan" -> TextScript.CJK
            "hi", "mr" -> TextScript.DEVANAGARI
            "ar", "fa", "ur", "ug" -> TextScript.ARABIC
            "he" -> TextScript.HEBREW
            else -> TextScript.LATIN
        }

    private fun scriptForChar(char: Char): TextScript {
        return when {
            char in '\u0400'..'\u052F' -> TextScript.CYRILLIC
            char in 'A'..'Z' || char in 'a'..'z' ||
                char in '\u00C0'..'\u024F' ||
                char in '\u1E00'..'\u1EFF' -> TextScript.LATIN
            char in '\u3040'..'\u30FF' ||
                char in '\u3400'..'\u4DBF' ||
                char in '\u4E00'..'\u9FFF' ||
                char in '\uAC00'..'\uD7AF' -> TextScript.CJK
            char in '\u0900'..'\u097F' -> TextScript.DEVANAGARI
            char in '\u0600'..'\u06FF' ||
                char in '\u0750'..'\u077F' ||
                char in '\u08A0'..'\u08FF' ||
                char in '\uFB50'..'\uFDFF' ||
                char in '\uFE70'..'\uFEFF' -> TextScript.ARABIC
            char in '\u0590'..'\u05FF' -> TextScript.HEBREW
            else -> TextScript.OTHER
        }
    }

    private enum class TextScript {
        CYRILLIC, LATIN, CJK, DEVANAGARI, ARABIC, HEBREW, OTHER
    }

    companion object {
        private const val TAG = "LanguageDetectionEngine"
        private const val MIN_DETECTION_CHARS = 3
        private const val MIN_SCRIPT_LETTERS = 2

        private val ENGLISH_HINTS = setOf("the", "and", "of", "to", "in", "is", "for", "on", "with", "exit", "entry")
        private val FRENCH_HINTS = setOf("le", "la", "les", "des", "du", "et", "un", "une", "est", "pour", "avec", "sortie")
        private val GERMAN_HINTS = setOf("der", "die", "das", "und", "ist", "zu", "ein", "eine", "mit", "fur", "ausgang")
        private val CZECH_HINTS = setOf("je", "se", "na", "pro", "ze", "do", "nebo", "jako", "vstup")
        private val SPANISH_HINTS = setOf("el", "la", "los", "las", "de", "es", "para", "con", "una", "salida")
        private val PORTUGUESE_HINTS = setOf("os", "as", "de", "para", "com", "uma", "saida")
        private val POLISH_HINTS = setOf("na", "do", "jest", "dla", "oraz", "nie", "wejscie")
        private val TURKISH_HINTS = setOf("ve", "bir", "icin", "ile", "bu", "da", "de")
        private val VIETNAMESE_HINTS = setOf("va", "la", "cua", "cho", "mot", "voi", "khong")

        private val ENGLISH_CHARS = emptySet<Char>()
        private val FRENCH_CHARS = setOf('\u00e0', '\u00e2', '\u00e7', '\u00e8', '\u00e9', '\u00ea', '\u00eb', '\u00ee', '\u00ef', '\u00f4', '\u00f9', '\u00fb', '\u0153', '\u00e6')
        private val GERMAN_CHARS = setOf('\u00e4', '\u00f6', '\u00fc', '\u00df')
        private val CZECH_CHARS = setOf('\u00e1', '\u010d', '\u010f', '\u00e9', '\u011b', '\u00ed', '\u0148', '\u00f3', '\u0159', '\u0161', '\u0165', '\u00fa', '\u016f', '\u00fd', '\u017e')
        private val SPANISH_CHARS = setOf('\u00e1', '\u00e9', '\u00ed', '\u00f1', '\u00f3', '\u00fa', '\u00fc')
        private val PORTUGUESE_CHARS = setOf('\u00e1', '\u00e2', '\u00e3', '\u00e0', '\u00e7', '\u00e9', '\u00ea', '\u00ed', '\u00f3', '\u00f4', '\u00f5', '\u00fa')
        private val POLISH_CHARS = setOf('\u0105', '\u0107', '\u0119', '\u0142', '\u0144', '\u00f3', '\u015b', '\u017a', '\u017c')
        private val TURKISH_CHARS = setOf('\u00e7', '\u011f', '\u0131', '\u00f6', '\u015f', '\u00fc')
        private val VIETNAMESE_CHARS = setOf('\u0103', '\u00e2', '\u00ea', '\u00f4', '\u01a1', '\u01b0', '\u0111')
        private val UKRAINIAN_CHARS = setOf('\u0456', '\u0457', '\u0454', '\u0491', '\u0406', '\u0407', '\u0404', '\u0490')
        private val URDU_CHARS = setOf('\u0679', '\u0688', '\u0691', '\u06ba', '\u06be', '\u06c1', '\u06d2')
        private val PERSIAN_CHARS = setOf('\u067e', '\u0686', '\u0698', '\u06af', '\u06cc')
    }
}
