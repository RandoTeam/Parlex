package com.translive.app.engine.camera

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 2-Stage OCR Layout Analysis:
 * 1. Inline Fragment Merging: Merges fragmented word bounding boxes on the same horizontal baseline.
 * 2. Density-Aware Paragraph Clustering: Groups consecutive lines into contextual blocks with column gutter detection.
 */
object BoundingBoxClustering {

    private const val VERTICAL_OVERLAP_RATIO_THRESHOLD = 0.50f
    private const val HORIZONTAL_MERGE_GAP_FACTOR = 0.85f
    private const val VERTICAL_PARAGRAPH_GAP_FACTOR = 1.35f
    private const val COLUMN_GUTTER_FACTOR = 1.4f

    /**
     * Stage 1: Merges inline fragments (words/sub-lines) into cohesive lines.
     */
    fun mergeInlineFragments(fragments: List<TextFragment>): List<ClusteredLine> {
        if (fragments.isEmpty()) return emptyList()

        val sorted = fragments.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val mergedLines = mutableListOf<MutableList<TextFragment>>()

        for (fragment in sorted) {
            val matchingLine = mergedLines.firstOrNull { line ->
                val lineBox = unionRects(line.map { it.box })
                val vertOverlap = verticalOverlapRatio(lineBox, fragment.box)
                val lineH = (lineBox.bottom - lineBox.top).coerceAtLeast(1)
                val fragH = (fragment.box.bottom - fragment.box.top).coerceAtLeast(1)
                val avgHeight = (lineH + fragH) / 2f
                val horizGap = fragment.box.left - lineBox.right

                vertOverlap >= VERTICAL_OVERLAP_RATIO_THRESHOLD &&
                    horizGap <= avgHeight * HORIZONTAL_MERGE_GAP_FACTOR &&
                    horizGap >= -avgHeight * 0.35f
            }

            if (matchingLine != null) {
                matchingLine.add(fragment)
            } else {
                mergedLines.add(mutableListOf(fragment))
            }
        }

        return mergedLines.mapIndexed { index, lineFragments ->
            val orderedFragments = lineFragments.sortedBy { it.box.left }
            val joinedText = orderedFragments.joinToString(" ") { it.text.trim() }
            val unionBox = unionRects(orderedFragments.map { it.box })
            ClusteredLine(
                id = "line_$index",
                text = joinedText,
                box = unionBox,
                fragments = orderedFragments
            )
        }
    }

    /**
     * Stage 2: Clusters lines into contextual paragraph blocks for machine translation.
     */
    fun clusterParagraphBlocks(lines: List<ClusteredLine>): List<ParagraphBlock> {
        if (lines.isEmpty()) return emptyList()

        val sortedLines = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val medianHeight = sortedLines.map { (it.box.bottom - it.box.top).coerceAtLeast(1) }.sorted().let {
            if (it.isEmpty()) 16f
            else it[it.size / 2].toFloat().coerceAtLeast(12f)
        }

        val blocks = mutableListOf<MutableList<ClusteredLine>>()
        var currentBlock = mutableListOf<ClusteredLine>()
        var previousLine: ClusteredLine? = null

        for (line in sortedLines) {
            val prev = previousLine
            val startsNewBlock = prev != null && currentBlock.isNotEmpty() && (
                (line.box.top - prev.box.bottom) > medianHeight * VERTICAL_PARAGRAPH_GAP_FACTOR ||
                horizontalGutterSeparation(prev.box, line.box, medianHeight * COLUMN_GUTTER_FACTOR)
            )

            if (startsNewBlock) {
                blocks.add(currentBlock)
                currentBlock = mutableListOf()
            }

            currentBlock.add(line)
            previousLine = line
        }

        if (currentBlock.isNotEmpty()) {
            blocks.add(currentBlock)
        }

        return blocks.mapIndexed { blockIndex, blockLines ->
            val ordered = blockLines.sortedBy { it.box.top }
            val combinedText = ordered.joinToString("\n") { it.text }
            val blockBox = unionRects(ordered.map { it.box })
            ParagraphBlock(
                id = "block_$blockIndex",
                text = combinedText,
                lines = ordered,
                boundingBox = blockBox
            )
        }
    }

    private fun verticalOverlapRatio(a: Rect, b: Rect): Float {
        val top = max(a.top, b.top)
        val bottom = min(a.bottom, b.bottom)
        val overlap = (bottom - top).coerceAtLeast(0)
        val hA = (a.bottom - a.top).coerceAtLeast(1)
        val hB = (b.bottom - b.top).coerceAtLeast(1)
        val minH = min(hA, hB).coerceAtLeast(1)
        return overlap.toFloat() / minH.toFloat()
    }

    private fun horizontalGutterSeparation(a: Rect, b: Rect, minGutter: Float): Boolean {
        val noXOverlap = a.right < b.left || b.right < a.left
        val xDistance = if (a.right < b.left) (b.left - a.right) else (a.left - b.right)
        return noXOverlap && xDistance >= minGutter
    }

    fun unionRects(rects: List<Rect>): Rect {
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
}
