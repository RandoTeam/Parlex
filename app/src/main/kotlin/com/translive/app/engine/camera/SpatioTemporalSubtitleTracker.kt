package com.translive.app.engine.camera

import android.graphics.Rect
import com.translive.app.data.model.Language
import com.translive.app.engine.OcrLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Spatio-Temporal Text Tracker and OCR Jitter Filter for Live Subtitle Camera Mode.
 *
 * Employs 3-stage matching:
 * 1. Bounding Box IoU & Centroid Distance.
 * 2. Levenshtein Fuzzy Distance for character-level noise invariance.
 * 3. EMA Coordinate Smoothing to eliminate visual jumping.
 */
class SpatioTemporalSubtitleTracker(
    private val iouThreshold: Float = 0.38f,
    private val ttlFrames: Int = 4,
    private val minHitsForStability: Int = 2
) {
    private data class TrackedEntry(
        val id: String,
        var rawText: String,
        var translatedText: String,
        val sourceLang: Language,
        val targetLang: Language,
        var smoothedLeft: Float,
        var smoothedTop: Float,
        var smoothedRight: Float,
        var smoothedBottom: Float,
        var lastSeenFrame: Int,
        var hits: Int,
        var confidence: Float
    ) {
        fun updateBounds(raw: Rect, alpha: Float = 0.45f) {
            smoothedLeft += alpha * (raw.left - smoothedLeft)
            smoothedTop += alpha * (raw.top - smoothedTop)
            smoothedRight += alpha * (raw.right - smoothedRight)
            smoothedBottom += alpha * (raw.bottom - smoothedBottom)
        }

        fun currentRect(): Rect = Rect().apply {
            left = smoothedLeft.toInt()
            top = smoothedTop.toInt()
            right = smoothedRight.toInt()
            bottom = smoothedBottom.toInt()
        }
    }

    private val activeTracks = mutableListOf<TrackedEntry>()
    private var frameCounter = 0
    private var nextTrackId = 1

    /**
     * Updates tracker with newly detected OCR lines and returns stabilized SubtitleLines.
     */
    fun update(
        detectedLines: List<OcrLine>,
        sourceLang: Language,
        targetLang: Language,
        translateFn: (List<String>) -> List<String>
    ): List<SubtitleLine> {
        frameCounter++
        val currentFrame = frameCounter
        val matchedTrackIds = mutableSetOf<String>()
        val unmatchedLines = mutableListOf<OcrLine>()

        // 1. Match detected lines against existing tracks
        for (line in detectedLines) {
            val text = line.text.trim()
            if (text.isBlank()) continue

            val bestMatch = findBestTrackMatch(line.boundingBox, text, matchedTrackIds)
            if (bestMatch != null) {
                matchedTrackIds.add(bestMatch.id)
                bestMatch.lastSeenFrame = currentFrame
                bestMatch.hits++
                bestMatch.updateBounds(line.boundingBox)

                // If text changed significantly, update rawText and re-translate
                if (text != bestMatch.rawText && calculateFuzzySimilarity(text, bestMatch.rawText) < 0.85f) {
                    bestMatch.rawText = text
                    val newTrans = translateFn(listOf(text)).firstOrNull() ?: text
                    bestMatch.translatedText = newTrans
                }
            } else {
                unmatchedLines.add(line)
            }
        }

        // 2. Translate new tracks in batch
        if (unmatchedLines.isNotEmpty()) {
            val textsToTranslate = unmatchedLines.map { it.text.trim() }
            val translations = translateFn(textsToTranslate)

            unmatchedLines.forEachIndexed { index, line ->
                val text = line.text.trim()
                val trans = translations.getOrElse(index) { text }
                val box = line.boundingBox

                val newEntry = TrackedEntry(
                    id = "sub_${nextTrackId++}",
                    rawText = text,
                    translatedText = trans,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    smoothedLeft = box.left.toFloat(),
                    smoothedTop = box.top.toFloat(),
                    smoothedRight = box.right.toFloat(),
                    smoothedBottom = box.bottom.toFloat(),
                    lastSeenFrame = currentFrame,
                    hits = 1,
                    confidence = 1.0f
                )
                activeTracks.add(newEntry)
            }
        }

        // 3. Evict stale tracks
        activeTracks.removeAll { currentFrame - it.lastSeenFrame > ttlFrames }

        // 4. Produce stable subtitles sorted top-to-bottom by vertical baseline
        return activeTracks
            .filter { it.hits >= minHitsForStability || it.lastSeenFrame == currentFrame }
            .sortedBy { it.smoothedTop }
            .map { entry ->
                SubtitleLine(
                    id = entry.id,
                    originalText = entry.rawText,
                    translatedText = entry.translatedText,
                    sourceLanguage = entry.sourceLang,
                    targetLanguage = entry.targetLang,
                    boundingBox = entry.currentRect(),
                    confidence = entry.confidence,
                    lastSeenFrame = entry.lastSeenFrame,
                    hitsCount = entry.hits,
                    isStable = entry.hits >= minHitsForStability
                )
            }
    }

    private fun findBestTrackMatch(
        box: Rect,
        text: String,
        matchedIds: Set<String>
    ): TrackedEntry? {
        var bestTrack: TrackedEntry? = null
        var bestScore = 0f

        for (track in activeTracks) {
            if (track.id in matchedIds) continue

            val trackRect = track.currentRect()
            val iou = calculateIoU(box, trackRect)
            val sim = calculateFuzzySimilarity(text, track.rawText)

            // Combined spatial and lexical score
            val combinedScore = (iou * 0.55f) + (sim * 0.45f)

            if (iou >= iouThreshold || (sim >= 0.75f && verticalOverlapRatio(box, trackRect) >= 0.45f)) {
                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    bestTrack = track
                }
            }
        }

        return bestTrack
    }

    fun reset() {
        activeTracks.clear()
        frameCounter = 0
    }

    companion object {
        fun calculateIoU(a: Rect, b: Rect): Float {
            val intersectLeft = max(a.left, b.left)
            val intersectTop = max(a.top, b.top)
            val intersectRight = min(a.right, b.right)
            val intersectBottom = min(a.bottom, b.bottom)

            if (intersectLeft < intersectRight && intersectTop < intersectBottom) {
                val interArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
                val areaA = (a.right - a.left) * (a.bottom - a.top)
                val areaB = (b.right - b.left) * (b.bottom - b.top)
                val unionArea = areaA + areaB - interArea
                return if (unionArea > 0) interArea.toFloat() / unionArea else 0f
            }
            return 0f
        }

        fun verticalOverlapRatio(a: Rect, b: Rect): Float {
            val top = max(a.top, b.top)
            val bottom = min(a.bottom, b.bottom)
            val overlap = (bottom - top).coerceAtLeast(0)
            val hA = (a.bottom - a.top).coerceAtLeast(1)
            val hB = (b.bottom - b.top).coerceAtLeast(1)
            return overlap.toFloat() / min(hA, hB).toFloat()
        }

        fun calculateFuzzySimilarity(s1: String, s2: String): Float {
            val clean1 = s1.lowercase().trim()
            val clean2 = s2.lowercase().trim()
            if (clean1 == clean2) return 1.0f
            if (clean1.isEmpty() || clean2.isEmpty()) return 0.0f

            val distance = levenshteinDistance(clean1, clean2)
            val maxLen = max(clean1.length, clean2.length)
            return (1.0f - (distance.toFloat() / maxLen.toFloat())).coerceIn(0f, 1.0f)
        }

        fun levenshteinDistance(s1: String, s2: String): Int {
            val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
            for (i in 0..s1.length) dp[i][0] = i
            for (j in 0..s2.length) dp[0][j] = j
            for (i in 1..s1.length) {
                for (j in 1..s2.length) {
                    val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
                }
            }
            return dp[s1.length][s2.length]
        }
    }
}
