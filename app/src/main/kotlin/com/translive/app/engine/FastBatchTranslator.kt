package com.translive.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-throughput, 1-pass delimited batch translation engine.
 *
 * Joins multiple on-screen text blocks into a single delimited payload (\n\n<<<§>>>\n\n)
 * to perform 1 neural network inference pass (~35ms) instead of 20-50 sequential inferences (1.5-2.5s).
 *
 * Includes automatic fallback with Semaphore(3) concurrency if NMT collapses delimiters.
 */
@Singleton
class FastBatchTranslator(
    private val translateFunc: suspend (String) -> String
) {
    @Inject
    constructor(fastTranslateEngine: FastTranslateEngine) : this(
        translateFunc = { text -> fastTranslateEngine.translate(text) }
    )

    companion object {
        const val DELIMITER = "\n\n<<<§>>>\n\n"
        val DELIMITER_REGEX = Regex("""\s*<<<\s*§\s*>>>\s*""")
        const val MAX_BATCH_CHARS = 1800
    }

    suspend fun translateBatch(
        texts: List<String>
    ): List<String> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()

        val splits = texts.map { TextPrefixProtector.split(it) }

        if (texts.size == 1) {
            val split = splits.first()
            if (split.cleanText.isBlank()) {
                return@withContext listOf(NmtTechGlossaryPostProcessor.postProcess(split.prefix))
            }
            val raw = translateFunc(split.cleanText)
            val combined = TextPrefixProtector.recombine(split.prefix, raw)
            return@withContext listOf(NmtTechGlossaryPostProcessor.postProcess(combined))
        }

        val nonBlankIndices = mutableListOf<Int>()
        val queryTexts = mutableListOf<String>()
        for (i in splits.indices) {
            if (splits[i].cleanText.isNotBlank()) {
                nonBlankIndices.add(i)
                queryTexts.add(splits[i].cleanText)
            }
        }

        val translatedQueries = if (queryTexts.isNotEmpty()) {
            // Chunk into safe sub-batches if total character length is large
            val chunks = mutableListOf<List<String>>()
            var currentChunk = mutableListOf<String>()
            var currentChunkLen = 0

            for (text in queryTexts) {
                val textLen = text.length
                if (currentChunkLen + textLen > MAX_BATCH_CHARS && currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk)
                    currentChunk = mutableListOf()
                    currentChunkLen = 0
                }
                currentChunk.add(text)
                currentChunkLen += textLen
            }
            if (currentChunk.isNotEmpty()) chunks.add(currentChunk)

            val translatedChunks = chunks.map { chunk ->
                async { processSingleDelimitedBatch(chunk) }
            }.awaitAll()

            translatedChunks.flatten()
        } else {
            emptyList()
        }

        var queryIdx = 0
        val finalResults = ArrayList<String>(texts.size)
        for (i in splits.indices) {
            val split = splits[i]
            val translatedBody = if (split.cleanText.isNotBlank()) {
                translatedQueries.getOrElse(queryIdx++) { split.cleanText }
            } else {
                ""
            }
            val combined = TextPrefixProtector.recombine(split.prefix, translatedBody)
            finalResults.add(NmtTechGlossaryPostProcessor.postProcess(combined))
        }

        finalResults
    }

    private suspend fun processSingleDelimitedBatch(chunk: List<String>): List<String> {
        if (chunk.isEmpty()) return emptyList()
        if (chunk.size == 1) {
            return listOf(translateFunc(chunk.first()))
        }

        // Clean internal newlines and join with sentinel delimiter
        val normalizedTexts = chunk.map { it.replace(Regex("""[\r\n]+"""), " ").trim() }
        val joinedPayload = normalizedTexts.joinToString(separator = DELIMITER)

        val rawTranslatedJoined = translateFunc(joinedPayload)

        // Split by delimiter regex (do not discard empty strings so count remains aligned)
        val splitResults = rawTranslatedJoined
            .split(DELIMITER_REGEX)
            .map { it.trim() }

        // If split results count matches inputs, return immediately
        if (splitResults.size == chunk.size) {
            return splitResults
        }

        // Delimiter collapse fallback: Coroutine dispatch with Semaphore(3)
        return coroutineScope {
            val semaphore = Semaphore(3)
            chunk.map { text ->
                async {
                    semaphore.withPermit {
                        translateFunc(text)
                    }
                }
            }.awaitAll()
        }
    }
}
