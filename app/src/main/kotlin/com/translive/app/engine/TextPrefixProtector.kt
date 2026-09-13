package com.translive.app.engine

/**
 * Detaches leading bullets, numbering, and Unicode emojis from text before NMT
 * to prevent SentencePiece/ML Kit on-device tokenizers from mangling or dropping them.
 */
object TextPrefixProtector {

    data class SplitResult(
        val prefix: String,
        val cleanText: String
    )

    fun split(rawText: String): SplitResult {
        if (rawText.isEmpty()) return SplitResult("", "")

        var cursor = 0
        val length = rawText.length

        // Check for leading numbered list like "1. ", "12) ", "(1) "
        val listMatch = Regex("""^(\(?\d+[\.\)]\s*)""").find(rawText)
        if (listMatch != null) {
            cursor = listMatch.value.length
        }

        while (cursor < length) {
            val cp = rawText.codePointAt(cursor)
            val charCount = Character.charCount(cp)

            if (isPrefixCodePoint(cp)) {
                cursor += charCount
            } else if (Character.isDigit(cp)) {
                val rest = rawText.substring(cursor)
                val numMatch = Regex("""^(\d+[\.\)]\s*)""").find(rest)
                if (numMatch != null) {
                    cursor += numMatch.value.length
                } else {
                    break
                }
            } else {
                break
            }
        }

        if (cursor == 0) {
            return SplitResult("", rawText)
        }

        return SplitResult(rawText.substring(0, cursor), rawText.substring(cursor))
    }

    private fun isPrefixCodePoint(cp: Int): Boolean {
        if (Character.isWhitespace(cp)) return true
        val type = Character.getType(cp).toByte()

        // Emojis and symbols (SMP and BMP)
        if (type == Character.OTHER_SYMBOL ||
            type == Character.MATH_SYMBOL ||
            type == Character.MODIFIER_SYMBOL) {
            return true
        }

        // Variation selectors, zero-width joiner, enclosing keycap
        if (cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3) return true

        // Bullets, dashes, list punctuation
        return when (cp) {
            0x2022, // • bullet
            0x2023, // ‣ triangular bullet
            0x25E6, // ◦ white bullet
            0x2043, // ⁃ hyphen bullet
            0x2219, // ∙ bullet operator
            0x25AA, // ▪ black small square
            0x25AB, // ▫ white small square
            0x25B6, // ▶ black right-pointing triangle
            0x25C0, // ◀ black left-pointing triangle
            0x2013, // – en dash
            0x2014, // — em dash
            '-'.code,
            '*'.code,
            '+'.code -> true
            else -> false
        }
    }

    fun recombine(prefix: String, translatedText: String): String {
        return "$prefix$translatedText"
    }
}
