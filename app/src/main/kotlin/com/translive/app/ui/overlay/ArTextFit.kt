package com.translive.app.ui.overlay

/**
 * Pure JVM layout policy used by [ArTranslateOverlayView].
 *
 * Keeping this logic outside the View lets the unit tests exercise the real
 * production rules instead of a copy of the arithmetic.
 */
object ArTextFit {

    /** Initial font size in px for a block of [sourceHeightPx] and [estimatedLines]. */
    fun initialTextSizePx(
        sourceHeightPx: Float,
        estimatedLines: Int,
        minTextSizePx: Float,
        maxTextSizePx: Float
    ): Float {
        val lines = estimatedLines.coerceAtLeast(1)
        return (sourceHeightPx / lines * 0.86f).coerceIn(minTextSizePx, maxTextSizePx)
    }

    /** Number of lines that can be shown without leaving the source block. */
    fun maxLinesFor(sourceHeightPx: Float, lineSpacingPx: Float): Int =
        (sourceHeightPx / lineSpacingPx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)

    /** Font size is reduced until the layout fits or the floor is reached. */
    fun nextSmallerSize(currentPx: Float, minPx: Float): Float =
        (currentPx * 0.90f).coerceAtLeast(minPx)

    fun needsTruncation(layoutLineCount: Int, maxLines: Int): Boolean =
        layoutLineCount > maxLines.coerceAtLeast(1)
}
