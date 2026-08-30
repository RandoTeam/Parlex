package com.translive.app.engine.pdf

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.translive.app.data.model.pdf.PdfPageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfExportManager @Inject constructor() {

    suspend fun exportToText(
        pages: List<PdfPageData>,
        outputFile: File,
        bilingual: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.bufferedWriter().use { writer ->
                for (page in pages) {
                    writer.write("==================== Страница ${page.pageIndex + 1} ====================\n\n")
                    for (para in page.paragraphs) {
                        if (bilingual) {
                            writer.write("[ОРИГИНАЛ]\n${para.sourceText}\n")
                            writer.write("[ПЕРЕВОД]\n${para.translatedText}\n\n")
                        } else {
                            writer.write("${para.translatedText.ifBlank { para.sourceText }}\n\n")
                        }
                    }
                    writer.write("\n")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun exportToPdf(
        pages: List<PdfPageData>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        try {
            for (pageData in pages) {
                val bitmap = pageData.translatedBitmap ?: pageData.originalBitmap ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageData.pageIndex + 1).create()
                val page = document.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                canvas.drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null)
                document.finishPage(page)
            }
            FileOutputStream(outputFile).use { out ->
                document.writeTo(out)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                document.close()
            } catch (_: Exception) {}
        }
    }
}
