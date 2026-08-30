package com.translive.app.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Language
import com.translive.app.data.model.pdf.DocumentViewMode
import com.translive.app.data.model.pdf.PdfPageData
import com.translive.app.data.model.pdf.PdfParagraph
import com.translive.app.engine.FastTranslateEngine
import com.translive.app.engine.LanguageDetectionEngine
import com.translive.app.engine.OcrEngine
import com.translive.app.engine.TranslationEngine
import com.translive.app.engine.pdf.PdfDocumentProcessor
import com.translive.app.engine.pdf.PdfExportManager
import com.translive.app.engine.pdf.PdfRendererManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DocumentTranslateUiState(
    val documentUri: Uri? = null,
    val documentName: String = "",
    val pageCount: Int = 0,
    val currentPageIndex: Int = 0,
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.RUSSIAN,
    val viewMode: DocumentViewMode = DocumentViewMode.OVERLAY,
    val isLoadingDocument: Boolean = false,
    val isProcessingPage: Boolean = false,
    val isBatchTranslating: Boolean = false,
    val batchProgress: Float = 0f,
    val pages: Map<Int, PdfPageData> = emptyMap(),
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

@HiltViewModel
class DocumentTranslateViewModel @Inject constructor(
    private val app: Application,
    private val pdfRendererManager: PdfRendererManager,
    private val pdfProcessor: PdfDocumentProcessor,
    private val pdfExportManager: PdfExportManager,
    private val ocrEngine: OcrEngine,
    private val fastTranslateEngine: FastTranslateEngine,
    private val translationEngine: TranslationEngine,
    private val languageDetectionEngine: LanguageDetectionEngine,
    private val settings: SettingsRepository
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(
        DocumentTranslateUiState(
            sourceLanguage = settings.textSourceLanguage,
            targetLanguage = settings.textTargetLanguage
        )
    )
    val uiState: StateFlow<DocumentTranslateUiState> = _uiState.asStateFlow()

    fun loadDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDocument = true, errorMessage = null) }
            try {
                val fileName = queryFileName(uri) ?: "document.pdf"
                val pageCount = pdfRendererManager.openDocument(app, uri)

                _uiState.update {
                    it.copy(
                        documentUri = uri,
                        documentName = fileName,
                        pageCount = pageCount,
                        currentPageIndex = 0,
                        isLoadingDocument = false,
                        pages = emptyMap()
                    )
                }

                if (pageCount > 0) {
                    loadAndProcessPage(0)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingDocument = false,
                        errorMessage = "Ошибка открытия PDF: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectPage(index: Int) {
        val count = _uiState.value.pageCount
        if (index in 0 until count) {
            _uiState.update { it.copy(currentPageIndex = index) }
            loadAndProcessPage(index)
        }
    }

    fun setViewMode(mode: DocumentViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
        retranslateCurrentPage()
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
        retranslateCurrentPage()
    }

    private fun loadAndProcessPage(pageIndex: Int) {
        val existing = _uiState.value.pages[pageIndex]
        if (existing?.originalBitmap != null && existing.isTranslationComplete) return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingPage = true) }
            try {
                // 1. Render high-DPI original bitmap (200 DPI)
                val originalBitmap = pdfRendererManager.renderPage(pageIndex, 200)

                var pageData = existing ?: PdfPageData(
                    pageIndex = pageIndex,
                    width = originalBitmap.width,
                    height = originalBitmap.height,
                    originalBitmap = originalBitmap
                )
                pageData = pageData.copy(originalBitmap = originalBitmap)

                // 2. OCR if not done yet
                if (!pageData.isOcrComplete) {
                    val ocrResult = ocrEngine.recognize(originalBitmap, _uiState.value.sourceLanguage.code)
                    val paragraphs = pdfProcessor.buildParagraphs(ocrResult.blocks, pageIndex)
                    pageData = pageData.copy(
                        paragraphs = paragraphs,
                        isOcrComplete = true
                    )
                }

                // 3. Translate paragraphs
                pageData = translateParagraphs(pageData)

                // 4. Update UI State
                val updatedPages = _uiState.value.pages.toMutableMap()
                updatedPages[pageIndex] = pageData
                _uiState.update {
                    it.copy(
                        pages = updatedPages,
                        isProcessingPage = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessingPage = false,
                        errorMessage = "Ошибка обработки страницы: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun translateParagraphs(pageData: PdfPageData): PdfPageData = withContext(Dispatchers.IO) {
        val srcLang = _uiState.value.sourceLanguage
        val tgtLang = _uiState.value.targetLanguage
        val paras = pageData.paragraphs

        if (paras.isEmpty()) {
            return@withContext pageData.copy(isTranslationComplete = true)
        }

        val textsToTranslate = paras.map { it.sourceText }
        fastTranslateEngine.activateDownloadedPair(srcLang.code, tgtLang.code)
        val translatedLines = fastTranslateEngine.translateLines(textsToTranslate)

        val updatedParas = paras.mapIndexed { idx, p ->
            val trans = translatedLines.getOrNull(idx) ?: p.sourceText
            p.copy(translatedText = trans)
        }

        val original = pageData.originalBitmap
        val translatedBitmap = if (original != null) {
            pdfProcessor.paintTranslatedOverlays(original, updatedParas)
        } else null

        pageData.copy(
            paragraphs = updatedParas,
            translatedBitmap = translatedBitmap,
            isTranslationComplete = true
        )
    }

    private fun retranslateCurrentPage() {
        val currentIdx = _uiState.value.currentPageIndex
        val existing = _uiState.value.pages[currentIdx] ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingPage = true) }
            val updated = translateParagraphs(existing)
            val updatedMap = _uiState.value.pages.toMutableMap()
            updatedMap[currentIdx] = updated
            _uiState.update { it.copy(pages = updatedMap, isProcessingPage = false) }
        }
    }

    fun exportTranslatedText(bilingual: Boolean, onDone: (File?) -> Unit) {
        viewModelScope.launch {
            val pagesList = (0 until _uiState.value.pageCount).mapNotNull { _uiState.value.pages[it] }
            val exportDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "exports")
            exportDir.mkdirs()
            val file = File(exportDir, "${_uiState.value.documentName.removeSuffix(".pdf")}_translated.txt")
            val success = pdfExportManager.exportToText(pagesList, file, bilingual)
            onDone(if (success) file else null)
        }
    }

    fun exportTranslatedPdf(onDone: (File?) -> Unit) {
        viewModelScope.launch {
            val pagesList = (0 until _uiState.value.pageCount).mapNotNull { _uiState.value.pages[it] }
            val exportDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "exports")
            exportDir.mkdirs()
            val file = File(exportDir, "${_uiState.value.documentName.removeSuffix(".pdf")}_translated.pdf")
            val success = pdfExportManager.exportToPdf(pagesList, file)
            onDone(if (success) file else null)
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            pdfRendererManager.closeDocument()
        }
    }
}
