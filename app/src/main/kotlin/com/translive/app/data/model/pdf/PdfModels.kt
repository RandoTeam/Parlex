package com.translive.app.data.model.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import com.translive.app.engine.OcrLine

data class PdfDocumentMetadata(
    val uri: String,
    val fileName: String,
    val pageCount: Int,
    val fileSizeFormatted: String = ""
)

data class PdfBoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromAndroidRect(rect: Rect): PdfBoundingBox =
            PdfBoundingBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}

data class PdfParagraph(
    val id: String,
    val pageIndex: Int,
    val lines: List<OcrLine>,
    val boundingBox: PdfBoundingBox,
    val sourceText: String,
    val translatedText: String = "",
    val isTranslating: Boolean = false
)

data class PdfPageData(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val originalBitmap: Bitmap? = null,
    val translatedBitmap: Bitmap? = null,
    val paragraphs: List<PdfParagraph> = emptyList(),
    val isOcrComplete: Boolean = false,
    val isTranslationComplete: Boolean = false
)

enum class DocumentViewMode {
    OVERLAY,        // In-place text replacement over PDF
    SIDE_BY_SIDE,   // Split view: Original Page vs Translated Cards
    ORIGINAL_ONLY   // Pure PDF view
}
