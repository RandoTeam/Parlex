package com.translive.app.engine

import android.icu.text.Transliterator
import com.translive.app.data.model.Language
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline Romanization/Transliteration engine powered by Android ICU Transliterator (API 24+).
 * Converts non-Latin scripts (Cyrillic, Han/Pinyin, Kana/Romaji, Hangul, Devanagari, Arabic, etc.)
 * into Latin phonetics for reading aloud or pronunciation assistance.
 */
@Singleton
class TransliterationEngine @Inject constructor() {

    private val transliteratorCache = ConcurrentHashMap<String, Transliterator>()

    /**
     * Set of language codes that natively use the Latin alphabet.
     */
    private val latinLanguages = setOf(
        "en", "fr", "de", "es", "pt", "it", "nl", "pl", "cs", "tr", "vi", "id", "ms", "fil"
    )

    /**
     * Map language code to the most accurate ICU Transliterator ID.
     */
    private fun getTransliteratorId(langCode: String): String {
        return when (langCode) {
            "ru", "uk", "mn" -> "Cyrillic-Latin"
            "zh", "zh-Hant", "yue", "nan" -> "Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC"
            "ja" -> "Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC"
            "ko" -> "Hangul-Latin"
            "hi", "mr" -> "Devanagari-Latin"
            "bn" -> "Bengali-Latin"
            "gu" -> "Gujarati-Latin"
            "ta" -> "Tamil-Latin"
            "te" -> "Telugu-Latin"
            "ar", "fa", "ur", "ug" -> "Arabic-Latin"
            "he" -> "Hebrew-Latin"
            "th" -> "Thai-Latin"
            else -> "Any-Latin"
        }
    }

    private fun getTransliterator(id: String): Transliterator? {
        val cached = transliteratorCache[id]
        if (cached != null) return cached

        val created = runCatching { Transliterator.getInstance(id) }
            .getOrElse {
                runCatching { Transliterator.getInstance("Any-Latin") }.getOrNull()
            } ?: return null

        transliteratorCache[id] = created
        return created
    }

    /**
     * Transliterates [text] to Latin script.
     * Returns `null` if the language is natively Latin, if transliteration fails,
     * or if the result is identical to the original text.
     */
    fun transliterate(text: String, language: Language? = null): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val langCode = language?.code ?: ""
        if (language != null && isLatinLanguage(langCode) && !containsNonLatinChars(trimmed)) {
            return null
        }

        val id = getTransliteratorId(langCode)
        val transliterator = getTransliterator(id) ?: return null

        return runCatching {
            val result = transliterator.transliterate(trimmed).trim()
            if (result.isBlank() || result.equals(trimmed, ignoreCase = true)) {
                null
            } else {
                result
            }
        }.getOrNull()
    }

    fun isLatinLanguage(langCode: String): Boolean = langCode in latinLanguages

    private fun containsNonLatinChars(text: String): Boolean {
        for (cp in text.codePoints()) {
            val block = Character.UnicodeBlock.of(cp)
            if (block != Character.UnicodeBlock.BASIC_LATIN &&
                block != Character.UnicodeBlock.LATIN_1_SUPPLEMENT &&
                block != Character.UnicodeBlock.LATIN_EXTENDED_A &&
                block != Character.UnicodeBlock.LATIN_EXTENDED_B &&
                block != Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL &&
                Character.isLetter(cp)
            ) {
                return true
            }
        }
        return false
    }
}
