package com.translive.app.data

import android.content.Context
import android.speech.tts.TextToSpeech
import com.translive.app.data.model.ComponentInstallStatus
import com.translive.app.data.model.LanguagePack
import com.translive.app.data.model.PackComponent
import com.translive.app.data.model.PackComponentType
import com.translive.app.data.model.TravelPacksCatalog
import com.translive.app.engine.FastTranslateEngine
import com.translive.app.engine.SpeechEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanguagePackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fastTranslateEngine: FastTranslateEngine,
    private val dictionaryRepository: DictionaryRepository,
    private val speechEngine: SpeechEngine
) {
    private val _packs = MutableStateFlow<List<LanguagePack>>(TravelPacksCatalog.createDefaultTravelPacks())
    val packs: StateFlow<List<LanguagePack>> = _packs.asStateFlow()

    private val downloadMutex = Mutex()
    private var systemTts: TextToSpeech? = null
    private var isTtsInitialized = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        initTtsEngine()
    }

    private fun initTtsEngine() {
        try {
            systemTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsInitialized = true
                    scope.launch { refreshPackStatuses() }
                }
            }
        } catch (_: Exception) {
            // TTS service might be absent in test environments
        }
    }

    suspend fun refreshPackStatuses() = withContext(Dispatchers.IO) {
        val downloadedNmtCodes = fastTranslateEngine.downloadedLanguageCodes()
        val sttReady = speechEngine.isVadDownloaded() && speechEngine.isWhisperDownloaded()
        val dictEntryCount = dictionaryRepository.getTotalEntryCount()

        _packs.update { currentPacks ->
            currentPacks.map { pack ->
                val updatedComponents = pack.components.map { comp ->
                    when (comp.type) {
                        PackComponentType.NMT_TRANSLATE -> {
                            val langCode = comp.id.removePrefix("nmt_")
                            val mlKitCode = fastTranslateEngine.toMlKitLang(langCode)
                            val isDownloaded = mlKitCode != null && mlKitCode in downloadedNmtCodes
                            comp.copy(
                                status = if (isDownloaded) ComponentInstallStatus.INSTALLED else ComponentInstallStatus.NOT_INSTALLED,
                                downloadProgress = if (isDownloaded) 1.0f else 0.0f
                            )
                        }
                        PackComponentType.DICTIONARY_DB -> {
                            val isSeeded = dictEntryCount > 0
                            comp.copy(
                                status = if (isSeeded) ComponentInstallStatus.INSTALLED else ComponentInstallStatus.NOT_INSTALLED,
                                downloadProgress = if (isSeeded) 1.0f else 0.0f
                            )
                        }
                        PackComponentType.OCR_ASSETS -> {
                            comp.copy(
                                status = ComponentInstallStatus.INSTALLED,
                                downloadProgress = 1.0f
                            )
                        }
                        PackComponentType.SPEECH_TTS_VOICE -> {
                            val langCode = comp.id.removePrefix("tts_")
                            val locale = Locale.forLanguageTag(langCode)
                            val ttsStatus = if (isTtsInitialized) {
                                systemTts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                            } else {
                                TextToSpeech.LANG_AVAILABLE
                            }
                            val status = when (ttsStatus) {
                                TextToSpeech.LANG_AVAILABLE,
                                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> ComponentInstallStatus.INSTALLED
                                TextToSpeech.LANG_MISSING_DATA -> ComponentInstallStatus.SYSTEM_ACTION_REQUIRED
                                else -> ComponentInstallStatus.INSTALLED
                            }
                            comp.copy(
                                status = status,
                                downloadProgress = if (status == ComponentInstallStatus.INSTALLED) 1.0f else 0.0f
                            )
                        }
                        PackComponentType.SPEECH_STT_MODEL -> {
                            comp.copy(
                                status = if (sttReady) ComponentInstallStatus.INSTALLED else ComponentInstallStatus.NOT_INSTALLED,
                                downloadProgress = if (sttReady) 1.0f else 0.0f
                            )
                        }
                    }
                }
                pack.copy(components = updatedComponents)
            }
        }
    }

    /**
     * Downloads all missing components of a language pack atomically.
     */
    suspend fun downloadLanguagePack(packId: String, onProgress: ((Float, String) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val pack = _packs.value.find { it.id == packId } ?: return@withContext

        updatePackComponentStatuses(packId) { comp ->
            if (comp.status != ComponentInstallStatus.INSTALLED) {
                comp.copy(status = ComponentInstallStatus.DOWNLOADING, downloadProgress = 0.15f)
            } else comp
        }

        downloadMutex.withLock {
            try {
                onProgress?.invoke(0.2f, "Загрузка NMT моделей...")
                val nmtCodes = listOf(pack.sourceLanguage.code, pack.targetLanguage.code)
                fastTranslateEngine.downloadLanguagePackages(nmtCodes)

                onProgress?.invoke(0.6f, "Проверка словаря...")
                dictionaryRepository.ensureSeeded()

                onProgress?.invoke(1.0f, "Пакет готов")
                refreshPackStatuses()
            } catch (e: Exception) {
                updatePackComponentStatuses(packId) { comp ->
                    if (comp.status == ComponentInstallStatus.DOWNLOADING) {
                        comp.copy(status = ComponentInstallStatus.FAILED, error = e.localizedMessage)
                    } else comp
                }
            }
        }
    }

    /**
     * Removes downloaded language pack assets.
     */
    suspend fun removeLanguagePack(packId: String) = withContext(Dispatchers.IO) {
        val pack = _packs.value.find { it.id == packId } ?: return@withContext
        fastTranslateEngine.deleteLanguagePackage(pack.sourceLanguage.code)
        refreshPackStatuses()
    }

    private fun updatePackComponentStatuses(packId: String, transform: (PackComponent) -> PackComponent) {
        _packs.update { packs ->
            packs.map { p ->
                if (p.id == packId) p.copy(components = p.components.map(transform)) else p
            }
        }
    }
}
