package com.translive.app.engine.clustering

import android.graphics.Rect
import com.translive.app.engine.OcrBlock
import com.translive.app.engine.OcrLine
import com.translive.app.engine.OcrResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private inline val Rect.rectWidth: Int get() = (right - left).coerceAtLeast(0)
private inline val Rect.rectHeight: Int get() = (bottom - top).coerceAtLeast(0)

data class ArClusterConfig(
    val maxVerticalGapFactor: Float = 1.25f,
    val minHorizontalOverlapRatio: Float = 0.45f,
    val columnGutterFactor: Float = 1.4f,
    val minLineWidthPx: Int = 8,
    val dehyphenate: Boolean = true
)

data class ArLineCluster(
    val id: String,
    val lines: List<OcrLine>,
    val consolidatedText: String,
    val boundingBox: Rect,
    val averageLineHeight: Float,
    val lineCount: Int
)

/**
 * Spatio-temporal bounding box clustering engine for AR Screen Translation.
 * Merges adjacent fragmented OCR lines into coherent paragraphs and sentences
 * using Union-Find connected components before neural translation.
 */
object ArBoundingBoxClusterer {

    private val HYPHEN_BREAK_REGEX = Regex("""(\p{L}+)-\s*$""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun cluster(
        ocrResult: OcrResult,
        config: ArClusterConfig = ArClusterConfig()
    ): List<ArLineCluster> {
        if (ocrResult.blocks.isEmpty()) return emptyList()

        val allValidLines = ocrResult.blocks.flatMap { it.lines }.filter { line ->
            line.text.isNotBlank() && line.boundingBox.rectWidth >= config.minLineWidthPx
        }
        if (allValidLines.isEmpty()) return emptyList()

        return clusterLines(allValidLines, config)
    }

    fun clusterLines(
        lines: List<OcrLine>,
        config: ArClusterConfig = ArClusterConfig()
    ): List<ArLineCluster> {
        val validLines = lines.filter { line ->
            line.text.isNotBlank() && line.boundingBox.rectWidth >= config.minLineWidthPx
        }
        if (validLines.isEmpty()) return emptyList()

        val sortedLines = validLines.sortedWith(
            compareBy<OcrLine> { it.boundingBox.top }.thenBy { it.boundingBox.left }
        )

        val n = sortedLines.size
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootI] = rootJ
            }
        }

        for (i in 0 until n) {
            val lineA = sortedLines[i]
            val rA = lineA.boundingBox
            val hA = rA.rectHeight.coerceAtLeast(1).toFloat()

            for (j in i + 1 until n) {
                val lineB = sortedLines[j]
                val rB = lineB.boundingBox
                val hB = rB.rectHeight.coerceAtLeast(1).toFloat()
                val avgHeight = (hA + hB) / 2f

                val verticalGap = rB.top - rA.bottom
                val maxAllowedVerticalGap = avgHeight * config.maxVerticalGapFactor

                val isGutterSeparated = isColumnGutterSeparated(
                    rA,
                    rB,
                    avgHeight * config.columnGutterFactor
                )

                val overlapRatio = calculateHorizontalOverlapRatio(rA, rB)
                val sharesColumn = overlapRatio >= config.minHorizontalOverlapRatio ||
                    (!isGutterSeparated && abs(rA.left - rB.left) < avgHeight * config.columnGutterFactor)

                val shouldMerge = verticalGap <= maxAllowedVerticalGap &&
                    verticalGap >= -(avgHeight * 0.5f) &&
                    !isGutterSeparated &&
                    sharesColumn

                if (shouldMerge) {
                    union(i, j)
                }
            }
        }

        val groupMap = mutableMapOf<Int, MutableList<OcrLine>>()
        for (i in 0 until n) {
            val root = find(i)
            groupMap.getOrPut(root) { mutableListOf() }.add(sortedLines[i])
        }

        return groupMap.values.mapIndexed { index, clusterLines ->
            val ordered = clusterLines.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
            val unionRect = computeUnionRect(ordered.map { it.boundingBox })
            val consolidatedText = consolidateClusterText(ordered, config.dehyphenate)
            val avgHeight = ordered.map { it.boundingBox.rectHeight }.average().toFloat()

            ArLineCluster(
                id = "cluster_$index",
                lines = ordered,
                consolidatedText = consolidatedText,
                boundingBox = unionRect,
                averageLineHeight = avgHeight,
                lineCount = ordered.size
            )
        }.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    }

    private fun isColumnGutterSeparated(a: Rect, b: Rect, minGutter: Float): Boolean {
        val noXOverlap = a.right < b.left || b.right < a.left
        val xDistance = if (a.right < b.left) (b.left - a.right) else (a.left - b.right)
        return noXOverlap && xDistance >= minGutter
    }

    fun calculateHorizontalOverlapRatio(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val right = min(a.right, b.right)
        val overlapWidth = max(0, right - left)
        val minWidth = min(a.rectWidth, b.rectWidth).coerceAtLeast(1)
        return overlapWidth.toFloat() / minWidth.toFloat()
    }

    fun computeUnionRect(rects: List<Rect>): Rect {
        if (rects.isEmpty()) return Rect()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        for (r in rects) {
            left = min(left, r.left)
            top = min(top, r.top)
            right = max(right, r.right)
            bottom = max(bottom, r.bottom)
        }

        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    private fun consolidateClusterText(lines: List<OcrLine>, dehyphenate: Boolean): String {
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines.first().text.trim()

        val sb = StringBuilder()
        for (i in lines.indices) {
            val lineText = lines[i].text.trim()
            if (lineText.isEmpty()) continue

            if (sb.isEmpty()) {
                sb.append(lineText)
                continue
            }

            if (dehyphenate && HYPHEN_BREAK_REGEX.containsMatchIn(sb)) {
                val match = HYPHEN_BREAK_REGEX.find(sb)
                if (match != null) {
                    val cutIndex = match.range.first + match.groupValues[1].length
                    sb.setLength(cutIndex)
                    sb.append(lineText)
                    continue
                }
            }

            val isCjk = isCjkCharacter(sb.last()) || isCjkCharacter(lineText.first())
            if (!isCjk) {
                sb.append(" ")
            }
            sb.append(lineText)
        }

        return sb.toString().replace(WHITESPACE_REGEX, " ").trim()
    }

    private fun isCjkCharacter(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            ub == Character.UnicodeBlock.HIRAGANA ||
            ub == Character.UnicodeBlock.KATAKANA ||
            ub == Character.UnicodeBlock.HANGUL_SYLLABLES
    }
}
