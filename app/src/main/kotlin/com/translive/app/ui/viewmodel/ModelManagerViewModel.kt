package com.translive.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.ModelVariant
import com.translive.app.data.model.SttModelInfo
import com.translive.app.data.model.TtsModelInfo
import com.translive.app.engine.DownloadState
import com.translive.app.engine.ModelDownloadManager
import com.translive.app.engine.SpeechEngine
import com.translive.app.engine.TranslationEngine
import com.translive.app.engine.TtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ACTIVE,
    LOADING
}

data class ModelItemState(
    val variant: ModelVariant,
    val status: ModelStatus,
    val downloadState: DownloadState = DownloadState.Idle
)

data class ModelManagerUiState(
    val models: List<ModelItemState> = emptyList(),
    val totalDownloadedSize: Long = 0L,
    val availableSpace: Long = 0L,
    val isLoadingModel: Boolean = false,
    val ttsDownloaded: Boolean = false,
    val ttsDownloading: Boolean = false,
    val ttsProgress: Float = 0f,
    val sttDownloaded: Boolean = false,
    val sttDownloading: Boolean = false,
    val sttProgress: Float = 0f,
    val error: String? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val repo: ModelRepository,
    private val downloadManager: ModelDownloadManager,
    private val engine: TranslationEngine,
    private val ttsEngine: TtsEngine,
    private val speechEngine: SpeechEngine,
    private val settings: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ModelManagerVM"
    }

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    init {
        refreshModels()

        // Observe persistent download states from the singleton manager
        viewModelScope.launch {
            downloadManager.activeDownloads.collect { downloads ->
                updateDownloadStates(downloads)
            }
        }
    }

    private fun updateDownloadStates(downloads: Map<String, DownloadState>) {
        // Update GGUF model states
        val activeId = repo.getActiveModelId()
        val models = ModelVariant.ALL.map { variant ->
            val isDownloaded = repo.isDownloaded(variant)
            val isActive = variant.id == activeId && isDownloaded
            val downloadState = downloads[variant.id]

            val status = when {
                isActive -> ModelStatus.ACTIVE
                downloadState is DownloadState.Downloading -> ModelStatus.DOWNLOADING
                isDownloaded -> ModelStatus.DOWNLOADED
                else -> ModelStatus.NOT_DOWNLOADED
            }

            ModelItemState(
                variant = variant,
                status = status,
                downloadState = downloadState ?: DownloadState.Idle
            )
        }

        // Update TTS/STT progress from download states
        val ttsState = downloads["tts-kokoro"]
        val sttVadState = downloads["stt-vad"]
        val sttWhisperState = downloads["stt-whisper"]

        _uiState.update { old ->
            old.copy(
                models = models,
                totalDownloadedSize = repo.getTotalDownloadedSize(),
                availableSpace = repo.getAvailableSpace(),
                ttsDownloaded = ttsEngine.isModelDownloaded(),
                ttsDownloading = ttsState is DownloadState.Downloading,
                ttsProgress = when (ttsState) {
                    is DownloadState.Downloading -> ttsState.progress
                    else -> old.ttsProgress
                },
                sttDownloaded = speechEngine.areModelsDownloaded(),
                sttDownloading = sttVadState is DownloadState.Downloading ||
                        sttWhisperState is DownloadState.Downloading,
                sttProgress = when {
                    sttWhisperState is DownloadState.Downloading ->
                        0.05f + sttWhisperState.progress * 0.9f
                    sttVadState is DownloadState.Downloading ->
                        sttVadState.progress * 0.05f
                    else -> old.sttProgress
                }
            )
        }
    }

    fun refreshModels() {
        updateDownloadStates(downloadManager.activeDownloads.value)
    }

    fun downloadModel(variant: ModelVariant) {
        if (repo.getAvailableSpace() < variant.sizeBytes * 1.1) {
            _uiState.update { it.copy(error = "Недостаточно места: нужно ${variant.sizeLabel}") }
            return
        }

        val destFile = repo.getDownloadFile(variant)

        downloadManager.startDownload(variant, destFile) { state ->
            when (state) {
                is DownloadState.Completed -> {
                    if (repo.getActiveModelId() == null) {
                        selectModel(variant)
                    } else {
                        refreshModels()
                    }
                }
                is DownloadState.Failed -> {
                    _uiState.update { it.copy(error = "Ошибка: ${state.error}") }
                }
                else -> {}
            }
        }
    }

    fun cancelDownload(variant: ModelVariant) {
        downloadManager.cancelDownload(variant.id)
    }

    fun selectModel(variant: ModelVariant) {
        if (!repo.isDownloaded(variant)) return

        _uiState.update { it.copy(isLoadingModel = true, error = null) }

        viewModelScope.launch {
            try {
                engine.unloadModel()
                repo.setActiveModelId(variant.id)
                val path = repo.getModelPath(variant) ?: return@launch

                val threads = settings.threads
                val loaded = engine.loadModel(path, threads)

                if (!loaded) {
                    _uiState.update { it.copy(error = "Не удалось загрузить модель ${variant.quantName}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Ошибка: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoadingModel = false) }
                refreshModels()
            }
        }
    }

    fun deleteModel(variant: ModelVariant) {
        if (repo.getActiveModelId() == variant.id) {
            engine.unloadModel()
        }
        repo.deleteModel(variant)
        refreshModels()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun downloadTtsModel() {
        if (_uiState.value.ttsDownloading) return
        _uiState.update { it.copy(ttsDownloading = true, ttsProgress = 0f) }

        val ttsDir = File(ttsEngine.modelDir.parent ?: return)
        ttsDir.mkdirs()
        val archiveFile = File(ttsDir, TtsModelInfo.ARCHIVE_FILENAME)

        val ttsVariant = ModelVariant(
            id = "tts-kokoro",
            quantName = TtsModelInfo.DISPLAY_NAME,
            displayName = TtsModelInfo.DISPLAY_NAME,
            description = TtsModelInfo.DESCRIPTION,
            sizeBytes = TtsModelInfo.SIZE_BYTES,
            ramEstimateMb = TtsModelInfo.RAM_ESTIMATE_MB,
            downloadUrl = TtsModelInfo.DOWNLOAD_URL,
            filename = TtsModelInfo.ARCHIVE_FILENAME
        )

        downloadManager.startDownload(ttsVariant, archiveFile) { state ->
            when (state) {
                is DownloadState.Completed -> {
                    try {
                        _uiState.update { it.copy(ttsProgress = 0.99f) }
                        withContext(Dispatchers.IO) {
                            extractTarBz2(archiveFile, ttsDir)
                            archiveFile.delete()
                            ttsEngine.loadModel()
                        }
                        _uiState.update {
                            it.copy(ttsDownloading = false, ttsDownloaded = true, ttsProgress = 1f)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "TTS extract error: ${e.message}", e)
                        _uiState.update {
                            it.copy(ttsDownloading = false, error = "TTS extract: ${e.message}")
                        }
                    }
                }
                is DownloadState.Failed -> {
                    _uiState.update {
                        it.copy(ttsDownloading = false, error = "TTS: ${state.error}")
                    }
                }
                is DownloadState.Cancelled -> {
                    _uiState.update { it.copy(ttsDownloading = false) }
                }
                else -> {}
            }
        }
    }

    fun downloadSttModels() {
        if (_uiState.value.sttDownloading) return
        _uiState.update { it.copy(sttDownloading = true, sttProgress = 0f) }

        val sttDir = File(speechEngine.vadFile.parent ?: return)
        sttDir.mkdirs()

        // Step 1: VAD
        val vadVariant = ModelVariant(
            id = "stt-vad",
            quantName = SttModelInfo.VAD_DISPLAY_NAME,
            displayName = SttModelInfo.VAD_DISPLAY_NAME,
            description = "VAD",
            sizeBytes = SttModelInfo.VAD_SIZE_BYTES,
            ramEstimateMb = 50,
            downloadUrl = SttModelInfo.VAD_DOWNLOAD_URL,
            filename = SttModelInfo.VAD_FILENAME
        )

        downloadManager.startDownload(vadVariant, speechEngine.vadFile) { vadState ->
            when (vadState) {
                is DownloadState.Completed -> {
                    // VAD done — now download Whisper
                    downloadWhisper(sttDir)
                }
                is DownloadState.Failed -> {
                    _uiState.update {
                        it.copy(sttDownloading = false, error = "VAD: ${vadState.error}")
                    }
                }
                is DownloadState.Cancelled -> {
                    _uiState.update { it.copy(sttDownloading = false) }
                }
                else -> {}
            }
        }
    }

    private fun downloadWhisper(sttDir: File) {
        val whisperArchive = File(sttDir, SttModelInfo.WHISPER_ARCHIVE)
        val whisperVariant = ModelVariant(
            id = "stt-whisper",
            quantName = SttModelInfo.WHISPER_DISPLAY_NAME,
            displayName = SttModelInfo.WHISPER_DISPLAY_NAME,
            description = SttModelInfo.WHISPER_DESCRIPTION,
            sizeBytes = SttModelInfo.WHISPER_SIZE_BYTES,
            ramEstimateMb = SttModelInfo.WHISPER_RAM_MB,
            downloadUrl = "${SttModelInfo.WHISPER_BASE_URL}/${SttModelInfo.WHISPER_ARCHIVE}",
            filename = SttModelInfo.WHISPER_ARCHIVE
        )

        downloadManager.startDownload(whisperVariant, whisperArchive) { state ->
            when (state) {
                is DownloadState.Completed -> {
                    try {
                        _uiState.update { it.copy(sttProgress = 0.95f) }
                        withContext(Dispatchers.IO) {
                            extractTarBz2(whisperArchive, sttDir)
                            whisperArchive.delete()
                        }
                        _uiState.update {
                            it.copy(sttDownloading = false, sttDownloaded = true, sttProgress = 1f)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "STT extract error: ${e.message}", e)
                        _uiState.update {
                            it.copy(sttDownloading = false, error = "STT extract: ${e.message}")
                        }
                    }
                }
                is DownloadState.Failed -> {
                    _uiState.update {
                        it.copy(sttDownloading = false, error = "Whisper: ${state.error}")
                    }
                }
                is DownloadState.Cancelled -> {
                    _uiState.update { it.copy(sttDownloading = false) }
                }
                else -> {}
            }
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        val fis = FileInputStream(archive)
        val bis = BufferedInputStream(fis)
        val bzis = BZip2CompressorInputStream(bis)
        val tais = TarArchiveInputStream(bzis)

        try {
            var entry = tais.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        tais.copyTo(out)
                    }
                }
                entry = tais.nextEntry
            }
        } finally {
            tais.close()
            bzis.close()
            bis.close()
            fis.close()
        }
    }
}
