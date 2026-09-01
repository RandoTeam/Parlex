package com.translive.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.ModelRuntime
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.OcrMnnRuntime
import com.translive.app.engine.TranslationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val engine: TranslationEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val ocrMnnRuntime: OcrMnnRuntime,
    private val exchangeRateRepository: com.translive.app.data.ExchangeRateRepository,
    private val dialogueDao: com.translive.app.data.db.DialogueDao
) : ViewModel() {

    private val _appLanguage = MutableStateFlow(settings.appLanguageCode)
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _threads = MutableStateFlow(settings.threads)
    val threads: StateFlow<Int> = _threads.asStateFlow()

    private val _idleTimeout = MutableStateFlow(settings.idleTimeoutMinutes)
    val idleTimeout: StateFlow<Int> = _idleTimeout.asStateFlow()

    private val _backend = MutableStateFlow(settings.backend)
    val backend: StateFlow<String> = _backend.asStateFlow()

    private val _hideKeyboardOnTextTranslate = MutableStateFlow(settings.hideKeyboardOnTextTranslate)
    val hideKeyboardOnTextTranslate: StateFlow<Boolean> = _hideKeyboardOnTextTranslate.asStateFlow()

    private val _showTechnicalTranslationStats = MutableStateFlow(settings.showTechnicalTranslationStats)
    val showTechnicalTranslationStats: StateFlow<Boolean> = _showTechnicalTranslationStats.asStateFlow()

    private val _showTransliteration = MutableStateFlow(settings.showTransliteration)
    val showTransliteration: StateFlow<Boolean> = _showTransliteration.asStateFlow()

    private val _translationPolicy = MutableStateFlow(settings.translationPolicy)
    val translationPolicy: StateFlow<TranslationPolicy> = _translationPolicy.asStateFlow()

    private val _overlayStyle = MutableStateFlow(settings.overlayStyle)
    val overlayStyle: StateFlow<String> = _overlayStyle.asStateFlow()

    private val _screenSyncTargetWithMain = MutableStateFlow(settings.screenSyncTargetWithMain)
    val screenSyncTargetWithMain: StateFlow<Boolean> = _screenSyncTargetWithMain.asStateFlow()

    private val _screenTargetLanguage = MutableStateFlow(settings.screenTargetLanguage)
    val screenTargetLanguage: StateFlow<com.translive.app.data.model.Language> = _screenTargetLanguage.asStateFlow()

    private val _screenA11yShortcutBehavior = MutableStateFlow(settings.screenA11yShortcutBehavior)
    val screenA11yShortcutBehavior: StateFlow<com.translive.app.data.ScreenA11yShortcutBehavior> = _screenA11yShortcutBehavior.asStateFlow()

    private val _homeCurrency = MutableStateFlow(settings.homeCurrencyCode)
    val homeCurrency: StateFlow<String> = _homeCurrency.asStateFlow()

    private val _enableCurrencyConversion = MutableStateFlow(settings.enableCurrencyConversion)
    val enableCurrencyConversion: StateFlow<Boolean> = _enableCurrencyConversion.asStateFlow()

    private val _currencySyncPolicy = MutableStateFlow(settings.currencySyncPolicy)
    val currencySyncPolicy: StateFlow<com.translive.app.data.model.CurrencySyncPolicy> = _currencySyncPolicy.asStateFlow()

    val currencyLastUpdated: StateFlow<Long?> = exchangeRateRepository.lastUpdatedMillis

    private val _isCurrencyRefreshing = MutableStateFlow(false)
    val isCurrencyRefreshing: StateFlow<Boolean> = _isCurrencyRefreshing.asStateFlow()

    private val _dialogueAutoSpeak = MutableStateFlow(settings.dialogueAutoSpeak)
    val dialogueAutoSpeak: StateFlow<Boolean> = _dialogueAutoSpeak.asStateFlow()

    private val _dialogueRecordingEnabled = MutableStateFlow(settings.dialogueRecordingEnabled)
    val dialogueRecordingEnabled: StateFlow<Boolean> = _dialogueRecordingEnabled.asStateFlow()

    private val _dialogueAudioFormat = MutableStateFlow(settings.dialogueAudioFormat)
    val dialogueAudioFormat: StateFlow<com.translive.app.data.model.AudioRecordingFormat> = _dialogueAudioFormat.asStateFlow()

    private val _dialogueStorageStats = MutableStateFlow(com.translive.app.data.DialogueStorageStats())
    val dialogueStorageStats: StateFlow<com.translive.app.data.DialogueStorageStats> = _dialogueStorageStats.asStateFlow()

    private val _runtimeDiagnostics = MutableStateFlow<String?>(null)
    val runtimeDiagnostics: StateFlow<String?> = _runtimeDiagnostics.asStateFlow()

    private val _systemPermissionsState = MutableStateFlow(
        com.translive.app.ui.permissions.SystemPermissionManager.getPermissionsState(context)
    )
    val systemPermissionsState: StateFlow<com.translive.app.ui.permissions.SystemPermissionsState> = _systemPermissionsState.asStateFlow()

    init {
        viewModelScope.launch {
            exchangeRateRepository.getLastUpdatedTimestamp()
        }
        refreshSystemPermissions()
        refreshDialogueStorageStats()
    }

    fun refreshSystemPermissions() {
        _systemPermissionsState.value = com.translive.app.ui.permissions.SystemPermissionManager.getPermissionsState(context)
    }

    fun refreshDialogueStorageStats() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = com.translive.app.data.DialogueStorageCalculator.getDialoguesDirectory(context)
            _dialogueStorageStats.value = com.translive.app.data.DialogueStorageCalculator.calculate(dir)
        }
    }

    fun clearAllDialogueRecordings() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = com.translive.app.data.DialogueStorageCalculator.getDialoguesDirectory(context)
            com.translive.app.data.DialogueStorageCalculator.deleteAll(dir)
            dialogueDao.clearAllSessionAudioPaths()
            refreshDialogueStorageStats()
        }
    }

    fun setCurrencySyncPolicy(policy: com.translive.app.data.model.CurrencySyncPolicy) {
        settings.currencySyncPolicy = policy
        _currencySyncPolicy.value = policy
    }

    fun refreshExchangeRates() {
        if (_isCurrencyRefreshing.value) return
        viewModelScope.launch {
            _isCurrencyRefreshing.value = true
            try {
                exchangeRateRepository.refreshRates()
            } finally {
                _isCurrencyRefreshing.value = false
            }
        }
    }

    fun setDialogueAutoSpeak(value: Boolean) {
        settings.dialogueAutoSpeak = value
        _dialogueAutoSpeak.value = value
    }

    fun setDialogueRecordingEnabled(value: Boolean) {
        settings.dialogueRecordingEnabled = value
        _dialogueRecordingEnabled.value = value
    }

    fun setDialogueAudioFormat(value: com.translive.app.data.model.AudioRecordingFormat) {
        settings.dialogueAudioFormat = value
        _dialogueAudioFormat.value = value
    }

    fun setHomeCurrency(value: String) {
        settings.homeCurrencyCode = value
        _homeCurrency.value = value
    }

    fun setEnableCurrencyConversion(value: Boolean) {
        settings.enableCurrencyConversion = value
        _enableCurrencyConversion.value = value
    }

    fun setAppLanguage(value: String) {
        settings.appLanguageCode = value
        _appLanguage.value = value
    }

    fun setThreads(value: Int) {
        if (settings.threads == value) return
        settings.threads = value
        _threads.value = value
        unloadActiveRuntimeForReconfigure()
    }

    fun setIdleTimeout(minutes: Int) {
        settings.idleTimeoutMinutes = minutes
        _idleTimeout.value = minutes
    }

    fun setBackend(value: String) {
        if (settings.backend == value) return
        settings.backend = value
        _backend.value = value
        // Both runtimes choose the backend while creating a model context.
        // Unload it so the next translation cannot retain the old CPU/GPU context.
        if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            liteRtEngine.unloadModel()
        } else {
            engine.unloadModel()
        }
    }

    fun setHideKeyboardOnTextTranslate(value: Boolean) {
        settings.hideKeyboardOnTextTranslate = value
        _hideKeyboardOnTextTranslate.value = value
    }

    fun setShowTechnicalTranslationStats(value: Boolean) {
        settings.showTechnicalTranslationStats = value
        _showTechnicalTranslationStats.value = value
    }

    fun setShowTransliteration(value: Boolean) {
        settings.showTransliteration = value
        _showTransliteration.value = value
    }

    fun setTranslationPolicy(value: TranslationPolicy) {
        settings.translationPolicy = value
        _translationPolicy.value = value
        if (value == TranslationPolicy.FAST) {
            liteRtEngine.unloadModel()
            engine.unloadModel()
        }
    }

    fun setOverlayStyle(value: String) {
        settings.overlayStyle = value
        _overlayStyle.value = value
    }

    fun setScreenSyncTargetWithMain(value: Boolean) {
        settings.screenSyncTargetWithMain = value
        _screenSyncTargetWithMain.value = value
    }

    fun setScreenTargetLanguage(language: com.translive.app.data.model.Language) {
        settings.screenTargetLanguage = language
        _screenTargetLanguage.value = language
    }

    fun setScreenA11yShortcutBehavior(behavior: com.translive.app.data.ScreenA11yShortcutBehavior) {
        settings.screenA11yShortcutBehavior = behavior
        _screenA11yShortcutBehavior.value = behavior
    }

    fun activeModelSupportsCpu(): Boolean = modelRepository.getActiveVariant()?.supportsCpu ?: true

    /** GPU can be selected only when the active runtime is packaged in this APK. */
    fun activeModelSupportsGpu(): Boolean {
        val variant = modelRepository.getActiveVariant() ?: return false
        return when (variant.runtime) {
            ModelRuntime.LITERT_LM -> variant.supportsGpu
            ModelRuntime.GGUF -> true
            ModelRuntime.OCR -> false
        }
    }

    fun activeModelGpuRequiresOpenClBuild(): Boolean = false

    fun activeModelBackendLabel(): String = modelRepository.getActiveVariant()?.backendLabel ?: "CPU only"

    fun runRuntimeDiagnostics() {
        viewModelScope.launch {
            val translationReport = engine.collectRuntimeDiagnostics(context)
            val ocr = ocrMnnRuntime.capability()
            _runtimeDiagnostics.value = buildString {
                append(translationReport)
                append("\\n\\nOCR MNN backend: ")
                append(ocr.backend)
                append("\\nOCR MNN available: ")
                append(ocr.available)
                append("\\nOCR MNN details: ")
                append(ocr.detail)
            }
        }
    }

    fun clearRuntimeDiagnostics() {
        _runtimeDiagnostics.value = null
    }

    private fun unloadActiveRuntimeForReconfigure() {
        if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            liteRtEngine.unloadModel()
        } else {
            engine.unloadModel()
        }
    }
}
