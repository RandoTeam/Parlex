package com.translive.app.engine

import com.translive.app.ui.overlay.CandidateBlock
import kotlin.math.max
import kotlin.math.min

object CandidateMerger {

    fun merge(
        nativeNodes: List<CandidateBlock>,
        ocrNodes: List<CandidateBlock>
    ): List<CandidateBlock> {
        val merged = nativeNodes.toMutableList()
        for (ocr in ocrNodes) {
            val ocrWidth = (ocr.boundingBox.right - ocr.boundingBox.left).coerceAtLeast(0)
            val ocrHeight = (ocr.boundingBox.bottom - ocr.boundingBox.top).coerceAtLeast(0)
            val ocrArea = (ocrWidth * ocrHeight).toFloat()
            if (ocrArea <= 0f) continue

            val isCovered = nativeNodes.any { native ->
                val interLeft = max(ocr.boundingBox.left, native.boundingBox.left)
                val interTop = max(ocr.boundingBox.top, native.boundingBox.top)
                val interRight = min(ocr.boundingBox.right, native.boundingBox.right)
                val interBottom = min(ocr.boundingBox.bottom, native.boundingBox.bottom)

                val interWidth = (interRight - interLeft).coerceAtLeast(0)
                val interHeight = (interBottom - interTop).coerceAtLeast(0)
                val intersectArea = (interWidth * interHeight).toFloat()

                (intersectArea / ocrArea) > 0.50f
            }

            if (!isCovered) {
                merged.add(ocr)
            }
        }
        return merged
    }
}
