package com.translive.app.engine.benchmark

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

@Singleton
class MultiLanguageEvaluator @Inject constructor() {

    fun evaluate(sample: BenchmarkSample, output: String): QualityMetrics {
        val notes = mutableListOf<String>()

        // 1. Tag leakage check (<src>, <dst>, <ctrl...>, <start_of_turn>)
        val hasTagLeakage = output.contains("<src>") ||
            output.contains("<dst>") ||
            output.contains("<text>") ||
            output.contains("<ctrl") ||
            output.contains("<start_of_turn>") ||
            output.contains("<end_of_turn>")
        if (hasTagLeakage) notes.add("Control or prompt tags leaked into output")

        // 2. Repetition loop detection
        val hasRepetition = detectRepetition(output)
        if (hasRepetition) notes.add("Repetitive cyclic generation detected")

        // 3. Line ID preservation check (for Structured OCR)
        val lineIdPreservation = if (sample.isStructuredOcr) {
            calculateLineIdPreservation(sample.expectedLineIds, output)
        } else {
            1.0
        }
        if (sample.isStructuredOcr && lineIdPreservation < 0.99) {
            notes.add("Missing or corrupted OCR line IDs: preservation = $lineIdPreservation")
        }

        // 4. Character length ratio and hallucination heuristic
        val lengthRatio = if (sample.referenceTranslation.isNotEmpty()) {
            output.length.toDouble() / sample.referenceTranslation.length.toDouble()
        } else 1.0

        val hasHallucination = (sample.referenceTranslation.isNotEmpty() && (lengthRatio > 3.5 || lengthRatio < 0.15)) || hasRepetition

        // 5. Sentence BLEU calculation
        val bleu = computeSentenceBleu(sample.referenceTranslation, output)

        return QualityMetrics(
            sampleId = sample.id,
            sentenceBleu = bleu,
            lengthRatio = lengthRatio,
            lineIdPreservationRatio = lineIdPreservation,
            hasHallucination = hasHallucination,
            hasRepetitionLoop = hasRepetition,
            hasTagLeakage = hasTagLeakage,
            languageIntegrityPass = !hasHallucination && !hasTagLeakage && lineIdPreservation >= 0.90,
            qualityNotes = notes
        )
    }

    /**
     * Pure Kotlin sentence BLEU-4 computation with brevity penalty.
     */
    fun computeSentenceBleu(reference: String, hypothesis: String): Double {
        val refTokens = tokenize(reference)
        val hypTokens = tokenize(hypothesis)

        if (refTokens.isEmpty() || hypTokens.isEmpty()) return 0.0

        val maxN = 4
        var logPrecisionSum = 0.0
        var validN = 0

        for (n in 1..maxN) {
            val hypNgrams = getNgrams(hypTokens, n)
            val refNgrams = getNgrams(refTokens, n)

            if (hypNgrams.isEmpty()) break

            val refCounts = mutableMapOf<List<String>, Int>()
            for (ng in refNgrams) {
                refCounts[ng] = (refCounts[ng] ?: 0) + 1
            }

            var clippedMatches = 0
            val hypCounts = mutableMapOf<List<String>, Int>()
            for (ng in hypNgrams) {
                hypCounts[ng] = (hypCounts[ng] ?: 0) + 1
            }

            for ((ng, count) in hypCounts) {
                val match = min(count, refCounts[ng] ?: 0)
                clippedMatches += match
            }

            val precision = (clippedMatches + 1e-4) / (hypNgrams.size + 1e-4)
            logPrecisionSum += ln(precision)
            validN++
        }

        if (validN == 0) return 0.0
        val geoMeanPrecision = exp(logPrecisionSum / validN)

        // Brevity Penalty (BP)
        val r = refTokens.size
        val c = hypTokens.size
        val bp = if (c > r) 1.0 else exp(1.0 - (r.toDouble() / max(1, c).toDouble()))

        return (bp * geoMeanPrecision).coerceIn(0.0, 1.0)
    }

    fun calculateLineIdPreservation(expectedIds: List<String>, output: String): Double {
        if (expectedIds.isEmpty()) return 1.0
        val foundCount = expectedIds.count { id ->
            output.contains(id)
        }
        return foundCount.toDouble() / expectedIds.size.toDouble()
    }

    private fun detectRepetition(text: String): Boolean {
        if (text.length < 20) return false
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 4) return false

        // Check 1-gram, 2-gram, 3-gram, 4-gram window repetition
        for (n in 1..4) {
            if (words.size >= n * 2) {
                for (i in 0..words.size - (n * 2)) {
                    val phrase1 = words.subList(i, i + n).joinToString(" ")
                    val phrase2 = words.subList(i + n, i + (n * 2)).joinToString(" ")
                    if (phrase1.equals(phrase2, ignoreCase = true) && phrase1.length >= 4) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun tokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val words = text.lowercase(Locale.ROOT).split(Regex("[\\s\\p{Punct}]+"))
        for (word in words) {
            if (word.isEmpty()) continue
            var currentLatin = StringBuilder()
            for (ch in word) {
                if (isCjk(ch)) {
                    if (currentLatin.isNotEmpty()) {
                        result.add(currentLatin.toString())
                        currentLatin = StringBuilder()
                    }
                    result.add(ch.toString())
                } else {
                    currentLatin.append(ch)
                }
            }
            if (currentLatin.isNotEmpty()) {
                result.add(currentLatin.toString())
            }
        }
        return result
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF

    private fun getNgrams(tokens: List<String>, n: Int): List<List<String>> {
        if (tokens.size < n) return emptyList()
        val ngrams = mutableListOf<List<String>>()
        for (i in 0..tokens.size - n) {
            ngrams.add(tokens.subList(i, i + n))
        }
        return ngrams
    }
}
