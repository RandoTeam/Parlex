package com.translive.app.engine.pdf

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.translive.app.data.model.pdf.PdfBoundingBox
import com.translive.app.data.model.pdf.PdfParagraph
import com.translive.app.engine.OcrBlock
import com.translive.app.engine.OcrLine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

@Singleton
class PdfDocumentProcessor @Inject constructor() {

    fun buildParagraphs(ocrBlocks: List<OcrBlock>, pageIndex: Int): List<PdfParagraph> {
        val paragraphs = mutableListOf<PdfParagraph>()

        for ((blockIndex, block) in ocrBlocks.withIndex()) {
            val sortedLines = block.lines.sortedWith(
                compareBy({ it.boundingBox.top }, { it.boundingBox.left })
            )
            if (sortedLines.isEmpty()) continue

            val medianHeight = sortedLines.map { (it.boundingBox.bottom - it.boundingBox.top).coerceAtLeast(1) }
                .sorted()
                .let { if (it.isEmpty()) 20f else it[it.size / 2].toFloat() }

            var currentLines = mutableListOf<OcrLine>()
            var prevLine: OcrLine? = null

            for (line in sortedLines) {
                val prev = prevLine
                val startsNewPara = prev != null && currentLines.isNotEmpty() && (
                    line.boundingBox.top - prev.boundingBox.bottom > medianHeight * 1.3f ||
                    (prev.text.trim().endsWith(".") || prev.text.trim().endsWith("!") || prev.text.trim().endsWith("?")) &&
                    abs(line.boundingBox.left - prev.boundingBox.left) > medianHeight * 0.9f
                )

                if (startsNewPara) {
                    paragraphs.add(createParagraph(currentLines, pageIndex, "${blockIndex}_${paragraphs.size}"))
                    currentLines = mutableListOf()
                }
                currentLines.add(line)
                prevLine = line
            }

            if (currentLines.isNotEmpty()) {
                paragraphs.add(createParagraph(currentLines, pageIndex, "${blockIndex}_${paragraphs.size}"))
            }
        }
        return paragraphs
    }

    private fun createParagraph(lines: List<OcrLine>, pageIndex: Int, idSuffix: String): PdfParagraph {
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE

        val textBuilder = StringBuilder()
        var prevEndedWithHyphen = false

        for ((i, line) in lines.withIndex()) {
            minLeft = minOf(minLeft, line.boundingBox.left)
            minTop = minOf(minTop, line.boundingBox.top)
            maxRight = maxOf(maxRight, line.boundingBox.right)
            maxBottom = maxOf(maxBottom, line.boundingBox.bottom)

            val text = line.text.trim()
            if (prevEndedWithHyphen) {
                textBuilder.append(text)
            } else {
                if (textBuilder.isNotEmpty()) textBuilder.append(" ")
                textBuilder.append(if (text.endsWith("-") && i < lines.size - 1) text.removeSuffix("-") else text)
            }
            prevEndedWithHyphen = text.endsWith("-") && i < lines.size - 1
        }

        return PdfParagraph(
            id = "p_${pageIndex}_$idSuffix",
            pageIndex = pageIndex,
            lines = lines,
            boundingBox = PdfBoundingBox(minLeft, minTop, maxRight, maxBottom),
            sourceText = textBuilder.toString().trim()
        )
    }

    fun paintTranslatedOverlays(original: Bitmap, paragraphs: List<PdfParagraph>): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val bgPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val strokePaint = Paint().apply {
            color = Color.parseColor("#E0E0E0")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        for (para in paragraphs) {
            if (para.translatedText.isBlank()) continue
            val box = para.boundingBox
            if (box.width <= 0 || box.height <= 0) continue

            // White patch over original text
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), bgPaint)
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), strokePaint)

            val lineCount = max(1, para.lines.size)
            val estimatedTextSize = (box.height.toFloat() / lineCount * 0.75f).coerceIn(12f, 40f)

            val textPaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.BLACK
                textSize = estimatedTextSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            val boxWidth = max(20, box.width)
            val staticLayout = StaticLayout.Builder.obtain(
                para.translatedText, 0, para.translatedText.length, textPaint, boxWidth
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
             .setIncludePad(false)
             .build()

            canvas.save()
            canvas.translate(box.left.toFloat() + 2f, box.top.toFloat() + 2f)
            staticLayout.draw(canvas)
            canvas.restore()
        }
        return result
    }
}
