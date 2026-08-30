package com.translive.app.engine.camera

import android.graphics.Rect
import com.translive.app.data.model.Language

/** A single word/line token from OCR with bounding box coordinates in bitmap space. */
data class TextFragment(
    val id: String,
    val text: String,
    val box: Rect,
    val confidence: Float = 1.0f
)

/** A cohesive OCR line formed by merging horizontally aligned fragments. */
data class ClusteredLine(
    val id: String,
    val text: String,
    val box: Rect,
    val fragments: List<TextFragment> = emptyList()
)

/** A paragraph or tabular block of clustered lines for contextual NMT. */
data class ParagraphBlock(
    val id: String,
    val text: String,
    val lines: List<ClusteredLine>,
    val boundingBox: Rect,
    val columnIndex: Int = 0
) {
    val cleanText: String
        get() = lines.joinToString(" ") { it.text.trim() }
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(\w+)-\s+(\w+)"""), "$1$2")
}

/** Sampled color and contrast information for rendering inpainting patches. */
data class SampledBackground(
    val topColor: Int,
    val bottomColor: Int,
    val primaryTextColor: Int,
    val strokeColor: Int,
    val isDarkBackground: Boolean
)

/** A translated paragraph with spatial coordinates for interactive inspection and rendering. */
data class BilingualParagraph(
    val id: String,
    val sourceText: String,
    val translatedText: String,
    val boundingBox: Rect,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val background: SampledBackground? = null
)
