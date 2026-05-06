package com.translive.app.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.model.Language
import com.translive.app.engine.OcrEngine
import com.translive.app.engine.TranslationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranslatedBlock(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect
)

enum class CameraMode { LIVE, CAPTURE }

data class CameraUiState(
    val mode: CameraMode = CameraMode.LIVE,
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.RUSSIAN,
    val blocks: List<TranslatedBlock> = emptyList(),
    val isProcessing: Boolean = false,
    val capturedBitmap: Bitmap? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val hasCameraPermission: Boolean = false
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null

    /** Throttle: don't process if already processing */
    @Volatile
    private var isLiveProcessing = false

    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
        }
    }

    /**
     * Live mode: OCR only — show detected text boxes, no translation.
     * This is instant and doesn't touch the LLM at all.
     * (Like Google Translate: live boxes, translate only on capture.)
     */
    @androidx.camera.core.ExperimentalGetImage
    fun processLiveFrame(imageProxy: androidx.camera.core.ImageProxy) {
        if (isLiveProcessing || _uiState.value.mode != CameraMode.LIVE) {
            imageProxy.close()
            return
        }
        isLiveProcessing = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val ocrResult = ocrEngine.recognize(imageProxy, state.sourceLanguage.code)

                // Show OCR boxes only (no translation in live mode)
                val blocks = ocrResult.blocks.map {
                    TranslatedBlock(it.text, "", it.boundingBox)
                }

                _uiState.update {
                    it.copy(
                        blocks = blocks,
                        imageWidth = ocrResult.imageWidth,
                        imageHeight = ocrResult.imageHeight
                    )
                }
            } catch (_: Exception) {
                // Ignore frame errors
            } finally {
                isLiveProcessing = false
            }
        }
    }

    /**
     * Capture: freeze bitmap, OCR all text, then translate each block
     * sequentially using the mutex-protected translateSafe().
     */
    fun capture(bitmap: Bitmap) {
        _uiState.update {
            it.copy(
                mode = CameraMode.CAPTURE,
                capturedBitmap = bitmap,
                blocks = emptyList(),
                isProcessing = true
            )
        }

        translateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val ocrResult = ocrEngine.recognize(bitmap, state.sourceLanguage.code)

                if (ocrResult.blocks.isEmpty()) {
                    _uiState.update {
                        it.copy(isProcessing = false)
                    }
                    return@launch
                }

                // Show OCR boxes immediately (before translation)
                val ocrBlocks = ocrResult.blocks.map {
                    TranslatedBlock(it.text, "", it.boundingBox)
                }
                _uiState.update {
                    it.copy(
                        blocks = ocrBlocks,
                        imageWidth = ocrResult.imageWidth,
                        imageHeight = ocrResult.imageHeight
                    )
                }

                // Translate each block one by one (mutex ensures no crash)
                if (translationEngine.isLoaded) {
                    val translatedBlocks = mutableListOf<TranslatedBlock>()

                    for (block in ocrResult.blocks) {
                        val translated = try {
                            translationEngine.translateSafe(
                                block.text, state.sourceLanguage, state.targetLanguage
                            )
                        } catch (_: Exception) { "" }

                        translatedBlocks.add(
                            TranslatedBlock(block.text, translated, block.boundingBox)
                        )

                        // Update UI progressively as each block translates
                        val current = translatedBlocks.toList() +
                            ocrResult.blocks.drop(translatedBlocks.size).map {
                                TranslatedBlock(it.text, "", it.boundingBox)
                            }
                        _uiState.update { it.copy(blocks = current) }
                    }
                }

                _uiState.update { it.copy(isProcessing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    /**
     * Return to live camera mode.
     */
    fun backToLive() {
        translateJob?.cancel()
        _uiState.update {
            it.copy(
                mode = CameraMode.LIVE,
                capturedBitmap = null,
                blocks = emptyList(),
                isProcessing = false
            )
        }
    }
}
