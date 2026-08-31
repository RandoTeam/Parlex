package com.translive.app.engine.camera

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin mathematical and parsing components for Camera LLM translation.
 */
object CameraLlmChunker {
    fun <T> chunkTokens(tokens: List<T>, batchSize: Int): List<List<T>> {
        require(batchSize > 0) { "Batch size must be greater than 0" }
        if (tokens.isEmpty()) return emptyList()
        return tokens.chunked(batchSize)
    }

    fun computeChunkRanges(totalTokens: Int, batchSize: Int): List<IntRange> {
        require(batchSize > 0) { "Batch size must be greater than 0" }
        if (totalTokens <= 0) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (start < totalTokens) {
            val end = min(start + batchSize, totalTokens)
            ranges.add(start until end)
            start = end
        }
        return ranges
    }
}

object CameraLlmPromptFormatter {
    fun formatIndexedOcrPrompt(
        lines: List<String>,
        sourceLangName: String,
        targetLangName: String,
        tagPrefix: String = ""
    ): String {
        val formattedLines = lines.mapIndexed { index, text ->
            "[$tagPrefix$index] ${text.trim()}"
        }.joinToString("\n")

        return """
            Translate the OCR lines from $sourceLangName to $targetLangName.
            Preserve every line ID exactly, for example [0] -> [0].
            Return one translated line for each input line with its matching tag.
            Do not add explanations or extra lines.
            $formattedLines
        """.trimIndent()
    }
}

object CameraLlmTagParser {

    fun parseIndexedTranslations(
        rawOutput: String,
        expectedLineCount: Int,
        tagPrefix: String = ""
    ): List<String>? {
        if (expectedLineCount <= 0) return emptyList()

        val expectedTags = (0 until expectedLineCount).map { "$tagPrefix$it" }
        val valuesByTag = mutableMapOf<String, String>()

        // Line-by-line tag extraction
        val lines = rawOutput.lines()
        val lineTagRegex = Regex("""^\s*\[?($tagPrefix\d+)\]?\s*(?:[:\->=.]+\s*)?(.*)$""")

        for (line in lines) {
            val match = lineTagRegex.find(line.trim())
            if (match != null) {
                val tag = match.groupValues[1]
                val content = match.groupValues[2].trim()
                if (tag in expectedTags && content.isNotBlank()) {
                    valuesByTag[tag] = content
                }
            }
        }

        if (expectedTags.all { valuesByTag[it]?.isNotBlank() == true }) {
            return expectedTags.map { valuesByTag.getValue(it) }
        }

        // Fallback: block-level regex matching across continuous text
        val blockTagRegex = Regex("""\[($tagPrefix\d+)\]\s*(?:[:\->=.]+\s*)?""")
        val matches = blockTagRegex.findAll(rawOutput).toList()

        if (matches.size >= expectedLineCount) {
            valuesByTag.clear()
            for (i in matches.indices) {
                val tag = matches[i].groupValues[1]
                if (tag in expectedTags && tag !in valuesByTag) {
                    val valueStart = matches[i].range.last + 1
                    val valueEnd = matches.getOrNull(i + 1)?.range?.first ?: rawOutput.length
                    val chunk = rawOutput.substring(valueStart, valueEnd).trim()
                    // Extract first non-empty line of the chunk
                    val firstLine = chunk.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                    val cleanText = stripLeadingSeparators(firstLine)
                    if (cleanText.isNotBlank()) {
                        valuesByTag[tag] = cleanText
                    }
                }
            }

            if (expectedTags.all { valuesByTag[it]?.isNotBlank() == true }) {
                return expectedTags.map { valuesByTag.getValue(it) }
            }
        }

        // Fallback: clean line-by-line matching if exact count matches
        val cleanLines = lines
            .map { line -> stripTag(line, tagPrefix) }
            .filter { it.isNotBlank() }
            .filterNot { it.contains("Translate the OCR", ignoreCase = true) || it.contains("Preserve every line", ignoreCase = true) }

        return if (cleanLines.size == expectedLineCount) cleanLines else null
    }

    fun stripTag(line: String, tagPrefix: String = ""): String {
        var clean = line.replace(Regex("""^\s*(?:\[?$tagPrefix\d+\]?|\d+\.)\s*"""), "").trim()
        return stripLeadingSeparators(clean)
    }

    private fun stripLeadingSeparators(text: String): String {
        return text.replace(Regex("""^[\s:\->=.]+\s*"""), "").trim()
    }
}

object CameraLlmTokenBudget {
    const val DEFAULT_SAFETY_RESERVE_TOKENS = 8

    fun calculateAvailableGenerationTokens(
        contextLimit: Int,
        promptTokenCount: Int,
        requestedTokens: Int,
        reserveTokens: Int = DEFAULT_SAFETY_RESERVE_TOKENS
    ): Int {
        val available = contextLimit - promptTokenCount - reserveTokens
        return max(0, min(requestedTokens, available))
    }

    fun estimateTokenBudget(
        sourceTextLength: Int,
        minTokens: Int = 256,
        maxTokens: Int = 2048,
        expansionMultiplier: Int = 3
    ): Int {
        return (sourceTextLength * expansionMultiplier).coerceIn(minTokens, maxTokens)
    }

    fun isContextOverflow(
        contextLimit: Int,
        promptTokenCount: Int,
        reserveTokens: Int = DEFAULT_SAFETY_RESERVE_TOKENS
    ): Boolean {
        return (promptTokenCount + reserveTokens) >= contextLimit
    }
}
