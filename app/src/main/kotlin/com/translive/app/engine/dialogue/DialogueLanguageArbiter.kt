package com.translive.app.engine.dialogue

import com.translive.app.data.model.Language
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolution strategy tagged by the arbiter for telemetry and debugging.
 */
enum class ResolutionMethod {
    /** Resolved via distinct Unicode scripts (e.g., Cyrillic vs Latin, Hanzi vs Latin, Arabic vs Latin). */
    SCRIPT_DISPARITY,

    /** Resolved via language-specific diacritics or tonal marks (e.g., Vietnamese tones, German umlauts, Spanish inverted punctuation/ñ). */
    DIACRITIC_FEATURE,

    /** Resolved via common conversational vocabulary/lexicon heuristics. */
    LEXICAL_HEURISTIC,

    /** Resolved via conversational alternation turn bias (previous turn was Lang A -> expect Lang B). */
    ALTERNATION_PRIOR,

    /** Resolved via same-speaker continuation context. */
    SAME_SPEAKER_PRIOR,

    /** Default fallback when text is completely neutral or unresolvable. */
    DEFAULT_FALLBACK
}

/**
 * Active two-way dialogue session language pair.
 */
data class DialogueSessionPair(
    val primaryLanguage: Language,
    val secondaryLanguage: Language
) {
    fun otherLanguage(language: Language): Language =
        if (language == primaryLanguage) secondaryLanguage else primaryLanguage

    fun contains(language: Language): Boolean =
        language == primaryLanguage || language == secondaryLanguage
}

/**
 * Conversational context passed into the arbiter.
 */
data class DialogueTurnContext(
    val previousLanguage: Language? = null,
    val isSameSpeaker: Boolean = false,
    val turnIndex: Int = 0
) {
    companion object {
        val EMPTY = DialogueTurnContext()
    }
}

/**
 * Result of the arbitration decision.
 */
data class DialogueArbitrationResult(
    val resolvedLanguage: Language,
    val targetLanguage: Language,
    val confidence: Float,
    val resolutionMethod: ResolutionMethod,
    val rawText: String
)

/**
 * Deterministic offline arbiter for two-party dialogue translation.
 * Resolves spoken utterances between Language A (Speaker A) and Language B (Speaker B).
 */
@Singleton
class DialogueLanguageArbiter @Inject constructor() {

    fun arbitrate(
        spokenText: String,
        pair: DialogueSessionPair,
        context: DialogueTurnContext = DialogueTurnContext.EMPTY
    ): DialogueArbitrationResult {
        val cleanText = spokenText.trim()
        if (cleanText.isBlank() || isPunctuationOnly(cleanText)) {
            val fallbackLang = context.previousLanguage?.let { prev ->
                if (context.isSameSpeaker) prev else pair.otherLanguage(prev)
            } ?: pair.primaryLanguage
            return buildResult(fallbackLang, pair, 0.0f, ResolutionMethod.DEFAULT_FALLBACK, cleanText)
        }

        // Check if text is purely ambiguous (universal words like OK, numbers, international brand names)
        val isAmbiguous = isAmbiguousInput(cleanText)
        if (isAmbiguous) {
            if (context.previousLanguage != null) {
                return if (context.isSameSpeaker) {
                    buildResult(
                        context.previousLanguage,
                        pair,
                        0.65f,
                        ResolutionMethod.SAME_SPEAKER_PRIOR,
                        cleanText
                    )
                } else {
                    val alternateLang = pair.otherLanguage(context.previousLanguage)
                    buildResult(
                        alternateLang,
                        pair,
                        0.65f,
                        ResolutionMethod.ALTERNATION_PRIOR,
                        cleanText
                    )
                }
            }
            return buildResult(pair.primaryLanguage, pair, 0.50f, ResolutionMethod.DEFAULT_FALLBACK, cleanText)
        }

        val langA = pair.primaryLanguage
        val langB = pair.secondaryLanguage
        val scriptA = scriptCategoryFor(langA)
        val scriptB = scriptCategoryFor(langB)

        // 1. Script Disparity Check (for distinct scripts, e.g. Cyrillic vs Latin, Hanzi vs Latin, Arabic vs Latin)
        if (scriptA != scriptB) {
            val scriptResult = resolveDistinctScripts(cleanText, pair, scriptA, scriptB)
            if (scriptResult != null) return scriptResult
        }

        // 2. Intra-Script CJK Handling (Japanese Kana vs Chinese Hanzi / Korean Hangul)
        if (isCjkScript(scriptA) || isCjkScript(scriptB)) {
            val cjkResult = resolveCjkPair(cleanText, pair)
            if (cjkResult != null) return cjkResult
        }

        // 3. Diacritics & Unique Orthography Feature Match (for shared scripts like Latin)
        val diacriticResult = resolveByDiacritics(cleanText, pair)
        if (diacriticResult != null) return diacriticResult

        // 4. Lexical & Conversational Stopword Matching
        val lexicalResult = resolveByLexicon(cleanText, pair)
        if (lexicalResult != null) return lexicalResult

        // 5. Conversational Turn Bias (Alternation vs Same Speaker Continuation)
        if (context.previousLanguage != null) {
            return if (context.isSameSpeaker) {
                buildResult(
                    context.previousLanguage,
                    pair,
                    0.65f,
                    ResolutionMethod.SAME_SPEAKER_PRIOR,
                    cleanText
                )
            } else {
                val alternateLang = pair.otherLanguage(context.previousLanguage)
                buildResult(
                    alternateLang,
                    pair,
                    0.65f,
                    ResolutionMethod.ALTERNATION_PRIOR,
                    cleanText
                )
            }
        }

        // 6. Default Fallback
        return buildResult(pair.primaryLanguage, pair, 0.45f, ResolutionMethod.DEFAULT_FALLBACK, cleanText)
    }

    private fun isPunctuationOnly(text: String): Boolean {
        return text.none { it.isLetterOrDigit() }
    }

    private fun isAmbiguousInput(text: String): Boolean {
        val words = text.lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{Nd}]+"""))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return true
        if (words.all { w -> w.all { it.isDigit() } }) return true
        if (words.all { it in AMBIGUOUS_TOKENS }) return true

        return false
    }

    private fun buildResult(
        resolved: Language,
        pair: DialogueSessionPair,
        confidence: Float,
        method: ResolutionMethod,
        rawText: String
    ): DialogueArbitrationResult {
        val target = pair.otherLanguage(resolved)
        return DialogueArbitrationResult(
            resolvedLanguage = resolved,
            targetLanguage = target,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            resolutionMethod = method,
            rawText = rawText
        )
    }

    private fun resolveDistinctScripts(
        text: String,
        pair: DialogueSessionPair,
        scriptA: ScriptCategory,
        scriptB: ScriptCategory
    ): DialogueArbitrationResult? {
        var countA = 0
        var countB = 0

        for (ch in text) {
            if (belongsToScript(ch, scriptA)) countA++
            if (belongsToScript(ch, scriptB)) countB++
        }

        if (countA == 0 && countB == 0) return null

        val total = countA + countB
        return if (countA > countB) {
            val conf = 0.85f + (countA.toFloat() / total.toFloat() * 0.15f)
            buildResult(pair.primaryLanguage, pair, conf, ResolutionMethod.SCRIPT_DISPARITY, text)
        } else if (countB > countA) {
            val conf = 0.85f + (countB.toFloat() / total.toFloat() * 0.15f)
            buildResult(pair.secondaryLanguage, pair, conf, ResolutionMethod.SCRIPT_DISPARITY, text)
        } else {
            null
        }
    }

    private fun resolveCjkPair(text: String, pair: DialogueSessionPair): DialogueArbitrationResult? {
        val hasKana = text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        val hasHangul = text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }

        if (hasHangul) {
            if (pair.primaryLanguage == Language.KOREAN) return buildResult(pair.primaryLanguage, pair, 0.99f, ResolutionMethod.SCRIPT_DISPARITY, text)
            if (pair.secondaryLanguage == Language.KOREAN) return buildResult(pair.secondaryLanguage, pair, 0.99f, ResolutionMethod.SCRIPT_DISPARITY, text)
        }

        if (hasKana) {
            if (pair.primaryLanguage == Language.JAPANESE) return buildResult(pair.primaryLanguage, pair, 0.99f, ResolutionMethod.SCRIPT_DISPARITY, text)
            if (pair.secondaryLanguage == Language.JAPANESE) return buildResult(pair.secondaryLanguage, pair, 0.99f, ResolutionMethod.SCRIPT_DISPARITY, text)
        }

        return null
    }

    private fun resolveByDiacritics(text: String, pair: DialogueSessionPair): DialogueArbitrationResult? {
        val lower = text.lowercase(Locale.ROOT)
        val scoreA = scoreDiacritics(lower, pair.primaryLanguage)
        val scoreB = scoreDiacritics(lower, pair.secondaryLanguage)

        if (scoreA == 0 && scoreB == 0) return null

        return when {
            scoreA > scoreB -> {
                val conf = (0.80f + (scoreA.toFloat() / (scoreA + scoreB + 1) * 0.18f)).coerceAtMost(0.98f)
                buildResult(pair.primaryLanguage, pair, conf, ResolutionMethod.DIACRITIC_FEATURE, text)
            }
            scoreB > scoreA -> {
                val conf = (0.80f + (scoreB.toFloat() / (scoreA + scoreB + 1) * 0.18f)).coerceAtMost(0.98f)
                buildResult(pair.secondaryLanguage, pair, conf, ResolutionMethod.DIACRITIC_FEATURE, text)
            }
            else -> null
        }
    }

    private fun scoreDiacritics(lower: String, lang: Language): Int {
        var score = 0
        when (lang.code) {
            "vi" -> {
                for (ch in lower) {
                    if (ch in '\u1EA0'..'\u1EF9') score += 6
                    if (ch in VIETNAMESE_UNIQUE_CHARS) score += 4
                }
            }
            "de" -> {
                for (ch in lower) {
                    if (ch in GERMAN_UNIQUE_CHARS) score += 4
                }
            }
            "fr" -> {
                for (ch in lower) {
                    if (ch in FRENCH_UNIQUE_CHARS) score += 3
                }
            }
            "es" -> {
                if (lower.contains('¿') || lower.contains('¡')) score += 8
                for (ch in lower) {
                    if (ch == 'ñ' || ch == 'á' || ch == 'í' || ch == 'ó' || ch == 'ú') score += 3
                }
            }
            "pt" -> {
                for (ch in lower) {
                    if (ch == 'ã' || ch == 'õ') score += 5
                    if (ch in PORTUGUESE_UNIQUE_CHARS) score += 2
                }
            }
            "pl" -> {
                for (ch in lower) {
                    if (ch in POLISH_UNIQUE_CHARS) score += 4
                }
            }
            "cs" -> {
                for (ch in lower) {
                    if (ch in CZECH_UNIQUE_CHARS) score += 4
                }
            }
            "tr" -> {
                for (ch in lower) {
                    if (ch in TURKISH_UNIQUE_CHARS) score += 4
                }
            }
        }
        return score
    }

    private fun resolveByLexicon(text: String, pair: DialogueSessionPair): DialogueArbitrationResult? {
        val words = text.lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{M}]+"""))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return null

        val stopA = STOPWORDS[pair.primaryLanguage.code] ?: emptySet()
        val stopB = STOPWORDS[pair.secondaryLanguage.code] ?: emptySet()

        val countA = words.count { it in stopA }
        val countB = words.count { it in stopB }

        if (countA == 0 && countB == 0) return null

        val total = countA + countB
        return if (countA > countB) {
            val conf = (0.75f + (countA.toFloat() / total.toFloat() * 0.20f)).coerceAtMost(0.95f)
            buildResult(pair.primaryLanguage, pair, conf, ResolutionMethod.LEXICAL_HEURISTIC, text)
        } else if (countB > countA) {
            val conf = (0.75f + (countB.toFloat() / total.toFloat() * 0.20f)).coerceAtMost(0.95f)
            buildResult(pair.secondaryLanguage, pair, conf, ResolutionMethod.LEXICAL_HEURISTIC, text)
        } else {
            null
        }
    }

    private fun belongsToScript(char: Char, script: ScriptCategory): Boolean = when (script) {
        ScriptCategory.CYRILLIC -> char in '\u0400'..'\u052F' || char in '\u2DE0'..'\u2DFF' || char in '\uA640'..'\uA69F'
        ScriptCategory.LATIN -> char in 'A'..'Z' || char in 'a'..'z' || char in '\u00C0'..'\u024F' || char in '\u1E00'..'\u1EFF'
        ScriptCategory.HAN -> char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF' || char in '\uF900'..'\uFAFF'
        ScriptCategory.JAPANESE -> char in '\u3040'..'\u309F' || char in '\u30A0'..'\u30FF' || char in '\u4E00'..'\u9FFF'
        ScriptCategory.KOREAN -> char in '\uAC00'..'\uD7AF' || char in '\u1100'..'\u11FF' || char in '\u3130'..'\u318F'
        ScriptCategory.ARABIC -> char in '\u0600'..'\u06FF' || char in '\u0750'..'\u077F' || char in '\u08A0'..'\u08FF' || char in '\uFB50'..'\uFDFF' || char in '\uFE70'..'\uFEFF'
        ScriptCategory.DEVANAGARI -> char in '\u0900'..'\u097F'
        ScriptCategory.BENGALI -> char in '\u0980'..'\u09FF'
        ScriptCategory.GUJARATI -> char in '\u0A80'..'\u0AFF'
        ScriptCategory.TAMIL -> char in '\u0B80'..'\u0BFF'
        ScriptCategory.TELUGU -> char in '\u0C00'..'\u0C7F'
        ScriptCategory.HEBREW -> char in '\u0590'..'\u05FF'
        ScriptCategory.THAI -> char in '\u0E00'..'\u0E7F'
        ScriptCategory.BURMESE -> char in '\u1000'..'\u109F'
        ScriptCategory.KHMER -> char in '\u1780'..'\u17FF'
        ScriptCategory.OTHER -> false
    }

    private fun isCjkScript(script: ScriptCategory): Boolean =
        script == ScriptCategory.HAN || script == ScriptCategory.JAPANESE || script == ScriptCategory.KOREAN

    private fun scriptCategoryFor(language: Language): ScriptCategory = when (language.code) {
        "ru", "uk", "mn" -> ScriptCategory.CYRILLIC
        "zh", "zh-Hant", "yue", "nan" -> ScriptCategory.HAN
        "ja" -> ScriptCategory.JAPANESE
        "ko" -> ScriptCategory.KOREAN
        "ar", "fa", "ur", "ug" -> ScriptCategory.ARABIC
        "hi", "mr" -> ScriptCategory.DEVANAGARI
        "bn" -> ScriptCategory.BENGALI
        "gu" -> ScriptCategory.GUJARATI
        "ta" -> ScriptCategory.TAMIL
        "te" -> ScriptCategory.TELUGU
        "he" -> ScriptCategory.HEBREW
        "th" -> ScriptCategory.THAI
        "my" -> ScriptCategory.BURMESE
        "km" -> ScriptCategory.KHMER
        else -> ScriptCategory.LATIN
    }

    private enum class ScriptCategory {
        LATIN, CYRILLIC, HAN, JAPANESE, KOREAN, ARABIC, DEVANAGARI, BENGALI, GUJARATI, TAMIL, TELUGU, HEBREW, THAI, BURMESE, KHMER, OTHER
    }

    companion object {
        private val AMBIGUOUS_TOKENS = setOf(
            "ok", "okay", "taxi", "uber", "hotel", "stop", "super", "aha", "mhm", "wow"
        )

        private val VIETNAMESE_UNIQUE_CHARS = setOf('ă', 'â', 'đ', 'ê', 'ô', 'ơ', 'ư')
        private val GERMAN_UNIQUE_CHARS = setOf('ä', 'ö', 'ü', 'ß')
        private val FRENCH_UNIQUE_CHARS = setOf('à', 'â', 'ç', 'è', 'é', 'ê', 'ë', 'î', 'ï', 'ô', 'ù', 'û', 'œ', 'æ')
        private val PORTUGUESE_UNIQUE_CHARS = setOf('ã', 'õ', 'ç', 'á', 'é', 'í', 'ó', 'ú', 'â', 'ê', 'ô', 'à')
        private val POLISH_UNIQUE_CHARS = setOf('ą', 'ć', 'ę', 'ł', 'ń', 'ó', 'ś', 'ź', 'ż')
        private val CZECH_UNIQUE_CHARS = setOf('á', 'č', 'ď', 'é', 'ě', 'í', 'ň', 'ó', 'ř', 'š', 'ť', 'ú', 'ů', 'ý', 'ž')
        private val TURKISH_UNIQUE_CHARS = setOf('ç', 'ğ', 'ı', 'ö', 'ş', 'ü')

        private val STOPWORDS = mapOf(
            "en" to setOf("the", "and", "is", "to", "of", "in", "it", "you", "that", "was", "for", "on", "are", "with", "they", "at", "be", "this", "have", "from", "or", "one", "not", "what", "all", "we", "can", "there", "do", "how", "if", "will", "so", "hello", "thank", "thanks", "please", "yes", "no", "good", "hi", "bye"),
            "ru" to setOf("и", "в", "не", "на", "я", "что", "быть", "с", "он", "а", "как", "по", "но", "они", "к", "у", "ты", "из", "мы", "за", "вы", "же", "от", "это", "да", "нет", "спасибо", "привет", "здравствуйте", "пожалуйста", "хорошо"),
            "vi" to setOf("và", "va", "là", "la", "của", "cua", "cho", "một", "mot", "với", "voi", "không", "khong", "xin", "chào", "chao", "chúc", "chuc", "các", "cac", "bạn", "ban", "ngày", "ngay", "tốt", "tot", "tôi", "toi", "anh", "em", "có", "co", "được", "duoc", "cảm", "cam", "ơn", "on"),
            "de" to setOf("der", "die", "das", "und", "ist", "zu", "ein", "eine", "mit", "für", "fur", "nicht", "im", "dem", "den", "sie", "er", "es", "ich", "wir", "auf", "ja", "nein", "danke", "bitte", "hallo", "guten", "wie", "was"),
            "fr" to setOf("le", "la", "les", "des", "du", "de", "et", "un", "une", "est", "pour", "avec", "dans", "sur", "pas", "vous", "nous", "je", "il", "elle", "oui", "non", "merci", "bonjour", "très", "tres"),
            "es" to setOf("el", "la", "los", "las", "de", "del", "en", "y", "es", "para", "por", "con", "una", "uno", "no", "si", "qué", "que", "hola", "gracias", "por favor", "cómo", "como", "dónde", "donde", "muy", "bien", "muchas"),
            "pt" to setOf("o", "a", "os", "as", "de", "do", "da", "em", "no", "na", "para", "por", "com", "uma", "um", "não", "nao", "que", "obrigado", "obrigada", "olá", "ola", "bom", "dia", "como", "você", "voce", "muito", "sim"),
            "it" to setOf("il", "lo", "la", "i", "gli", "le", "un", "una", "e", "di", "a", "da", "in", "con", "su", "per", "non", "si", "grazie", "ciao", "per favore", "come", "cosa", "sono", "molto", "bene"),
            "pl" to setOf("na", "do", "jest", "są", "sa", "dla", "oraz", "nie", "w", "z", "się", "sie", "to", "tak", "dziękuję", "dziekuje", "dzień", "dzien", "dobry", "proszę", "prosze", "jak", "bardzo"),
            "cs" to setOf("je", "jsou", "se", "si", "na", "pro", "ze", "do", "nebo", "jako", "v", "a", "to", "ano", "ne", "děkuji", "dekuji", "dobrý", "dobry", "den", "prosím", "prosim", "ahoj", "jak"),
            "tr" to setOf("ve", "bir", "için", "icin", "ile", "bu", "o", "da", "de", "mi", "mu", "var", "yok", "evet", "hayır", "hayir", "teşekkürler", "tesekkurler", "merhaba", "ben", "sen", "çok", "cok", "iyi"),
            "uk" to setOf("і", "та", "в", "у", "на", "з", "що", "як", "це", "не", "до", "для", "від", "по", "він", "вона", "вони", "ми", "ви", "я", "дякую", "привіт", "добрий", "день", "будь", "ласка", "так", "ні", "дуже")
        )
    }
}
