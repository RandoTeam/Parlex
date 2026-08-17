package com.translive.app.engine

import android.graphics.PointF
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Independent PP-OCR post-processing primitives for the MNN tensor bridge. */
object PpOcrPostProcessor {
    private val NEIGHBOR_X = intArrayOf(1, -1, 0, 0)
    private val NEIGHBOR_Y = intArrayOf(0, 0, 1, -1)

    data class TextQuad(val points: List<PointF>, val score: Float)

    /** Greedy CTC decode. Index 0 is the CTC blank; repeated symbols collapse. */
    fun decodeCtc(
        logits: FloatArray,
        timeSteps: Int,
        classCount: Int,
        dictionary: List<String>,
        scoreThreshold: Float = 0f
    ): Pair<String, Float> {
        require(timeSteps > 0 && classCount > 1)
        require(logits.size >= timeSteps * classCount)
        var previous = 0
        var scoreSum = 0f
        var accepted = 0
        val output = StringBuilder()
        for (time in 0 until timeSteps) {
            val offset = time * classCount
            var bestIndex = 0
            var bestLogit = Float.NEGATIVE_INFINITY
            for (klass in 0 until classCount) {
                if (logits[offset + klass] > bestLogit) {
                    bestLogit = logits[offset + klass]
                    bestIndex = klass
                }
            }
            val confidence = softmaxProbability(logits, offset, classCount, bestIndex)
            if (bestIndex != 0 && bestIndex != previous && confidence >= scoreThreshold) {
                dictionary.getOrNull(bestIndex - 1)?.let(output::append)
                scoreSum += confidence
                accepted++
            }
            previous = bestIndex
        }
        return output.toString() to if (accepted == 0) 0f else scoreSum / accepted
    }

    /**
     * DB-style detector thresholding for a single probability map in NCHW
     * order. Returns connected-component bounding quads after simple contour
     * extraction; polygon unclip is intentionally left to the geometry stage.
     */
    fun thresholdMap(
        probability: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = 0.3f,
        minArea: Int = 3
    ): List<TextQuad> {
        require(width > 0 && height > 0 && probability.size >= width * height)
        val visited = BooleanArray(width * height)
        val result = mutableListOf<TextQuad>()
        val queueX = IntArray(width * height)
        val queueY = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val start = y * width + x
            if (visited[start] || probability[start] < threshold) continue
            var head = 0
            var tail = 0
            queueX[tail] = x; queueY[tail++] = y; visited[start] = true
            var minX = x; var maxX = x; var minY = y; var maxY = y
            var area = 0
            var score = 0f
            while (head < tail) {
                val cx = queueX[head]
                val cy = queueY[head++]
                val index = cy * width + cx
                area++
                score += probability[index]
                minX = min(minX, cx); maxX = max(maxX, cx)
                minY = min(minY, cy); maxY = max(maxY, cy)
                for (direction in 0 until 4) {
                    val nx = cx + NEIGHBOR_X[direction]
                    val ny = cy + NEIGHBOR_Y[direction]
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val ni = ny * width + nx
                    if (!visited[ni] && probability[ni] >= threshold) {
                        visited[ni] = true
                        queueX[tail] = nx; queueY[tail++] = ny
                    }
                }
            }
            if (area >= minArea) {
                result += TextQuad(
                    listOf(
                        PointF(minX.toFloat(), minY.toFloat()),
                        PointF(maxX.toFloat(), minY.toFloat()),
                        PointF(maxX.toFloat(), maxY.toFloat()),
                        PointF(minX.toFloat(), maxY.toFloat())
                    ),
                    score / area
                )
            }
        }
        return result
    }

    private fun softmaxProbability(values: FloatArray, offset: Int, count: Int, index: Int): Float {
        var maxValue = Float.NEGATIVE_INFINITY
        for (i in 0 until count) maxValue = max(maxValue, values[offset + i])
        var denominator = 0.0
        for (i in 0 until count) denominator += exp((values[offset + i] - maxValue).toDouble())
        return (exp((values[offset + index] - maxValue).toDouble()) / denominator).toFloat()
    }
}
