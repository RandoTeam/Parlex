package com.translive.app.data.model

data class LanguageTurnStats(
    val languageCode: String,
    val turnCount: Int,
    val wordCount: Int,
    val characterCount: Int,
    val nonWhitespaceCharCount: Int,
    val totalAudioDurationMs: Long
)

data class DialogueSessionStats(
    val totalTurns: Int = 0,
    val totalDurationMs: Long = 0L,
    val totalWords: Int = 0,
    val totalCharacters: Int = 0,
    val totalNonWhitespaceCharacters: Int = 0,
    val perLanguageStats: Map<String, LanguageTurnStats> = emptyMap(),
    val averageWordsPerTurn: Double = 0.0,
    val speakerAlternationRate: Double = 0.0
) {
    companion object {
        fun countWords(text: String): Int {
            if (text.isBlank()) return 0
            var cjkCount = 0
            val nonCjkBuffer = StringBuilder()

            for (ch in text) {
                val codePoint = ch.code
                val isCjk = (codePoint in 0x4E00..0x9FFF) ||
                            (codePoint in 0x3400..0x4DBF) ||
                            (codePoint in 0x20000..0x2A6DF)
                if (isCjk) {
                    cjkCount++
                    nonCjkBuffer.append(' ')
                } else {
                    nonCjkBuffer.append(ch)
                }
            }

            val spaceDelimitedWords = nonCjkBuffer.toString()
                .trim()
                .split("\\s+".toRegex())
                .count { token -> token.any { it.isLetterOrDigit() } }

            return cjkCount + spaceDelimitedWords
        }

        fun fromMessages(messages: List<DialogueMessage>, sessionDurationMs: Long? = null): DialogueSessionStats {
            if (messages.isEmpty()) return DialogueSessionStats()

            var totalWords = 0
            var totalChars = 0
            var totalNonWsChars = 0
            val langMap = mutableMapOf<String, MutableTurnStatAccumulator>()
            var alternations = 0

            for (i in messages.indices) {
                val msg = messages[i]
                val words = countWords(msg.originalText)
                val chars = msg.originalText.length
                val nonWs = msg.originalText.count { !it.isWhitespace() }

                totalWords += words
                totalChars += chars
                totalNonWsChars += nonWs

                val accum = langMap.getOrPut(msg.originalLanguage) { MutableTurnStatAccumulator(msg.originalLanguage) }
                accum.turnCount++
                accum.wordCount += words
                accum.charCount += chars
                accum.nonWsCharCount += nonWs
                accum.audioDurationMs += msg.audioDurationMs

                if (i > 0 && messages[i].speaker != messages[i - 1].speaker) {
                    alternations++
                }
            }

            val computedDuration = sessionDurationMs ?: run {
                val first = messages.first().timestamp
                val last = messages.last().let { it.timestamp + it.audioDurationMs }
                (last - first).coerceAtLeast(0L)
            }

            val alternationRate = if (messages.size > 1) {
                alternations.toDouble() / (messages.size - 1)
            } else 0.0

            val avgWords = if (messages.isNotEmpty()) totalWords.toDouble() / messages.size else 0.0

            val resultPerLang = langMap.mapValues { (_, v) ->
                LanguageTurnStats(
                    languageCode = v.langCode,
                    turnCount = v.turnCount,
                    wordCount = v.wordCount,
                    characterCount = v.charCount,
                    nonWhitespaceCharCount = v.nonWsCharCount,
                    totalAudioDurationMs = v.audioDurationMs
                )
            }

            return DialogueSessionStats(
                totalTurns = messages.size,
                totalDurationMs = computedDuration,
                totalWords = totalWords,
                totalCharacters = totalChars,
                totalNonWhitespaceCharacters = totalNonWsChars,
                perLanguageStats = resultPerLang,
                averageWordsPerTurn = avgWords,
                speakerAlternationRate = alternationRate
            )
        }

        private class MutableTurnStatAccumulator(val langCode: String) {
            var turnCount: Int = 0
            var wordCount: Int = 0
            var charCount: Int = 0
            var nonWsCharCount: Int = 0
            var audioDurationMs: Long = 0L
        }
    }
}
