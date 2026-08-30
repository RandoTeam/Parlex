package com.translive.app.data

import android.content.Context
import com.translive.app.i18n.AppLocale
import com.translive.app.data.model.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent app settings via SharedPreferences.
 */
@Singleton
open class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context?
) {
    constructor() : this(null)

    private val prefs = context?.getSharedPreferences("parlex_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_THREADS = "inference_threads"
        private const val KEY_IDLE_TIMEOUT = "idle_timeout_minutes"
        private const val KEY_BACKEND = "compute_backend"
        private const val KEY_TEXT_SOURCE_LANGUAGE = "text_source_language"
        private const val KEY_TEXT_SOURCE_AUTO = "text_source_auto"
        private const val KEY_TEXT_TARGET_LANGUAGE = "text_target_language"
        private const val KEY_HIDE_KEYBOARD_ON_TEXT_TRANSLATE = "hide_keyboard_on_text_translate"
        private const val KEY_SHOW_TECHNICAL_TRANSLATION_STATS = "show_technical_translation_stats"
        private const val KEY_SHOW_TRANSLITERATION = "show_transliteration"
        private const val KEY_TRANSLATION_POLICY = "translation_policy"
        private const val KEY_CAMERA_SOURCE_LANGUAGE = "camera_source_language"
        private const val KEY_CAMERA_SOURCE_AUTO = "camera_source_auto"
        private const val KEY_CAMERA_TARGET_LANGUAGE = "camera_target_language"
        private const val KEY_DIALOGUE_SOURCE_LANGUAGE = "dialogue_source_language"
        private const val KEY_DIALOGUE_TARGET_LANGUAGE = "dialogue_target_language"
        private const val KEY_SPEECH_MODEL = "speech_model"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_HOME_CURRENCY_CODE = "home_currency_code"
        private const val KEY_ENABLE_CURRENCY_CONVERSION = "enable_currency_conversion"

        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"

        const val SPEECH_MODEL_WHISPER_TINY = "whisper_tiny"
        const val SPEECH_MODEL_QWEN3_ASR_06B = "qwen3_asr_0_6b"

        val THREAD_OPTIONS = listOf(1, 2, 3, 4, 6, 8)
        val TIMEOUT_OPTIONS = listOf(0, 1, 2, 5, 10, 30) // 0 = never unload
    }

    var appLanguageCode: String
        get() = prefs?.getString(KEY_APP_LANGUAGE, AppLocale.SYSTEM) ?: AppLocale.SYSTEM
        set(value) = prefs?.edit()?.putString(KEY_APP_LANGUAGE, AppLocale.normalize(value))?.apply() ?: Unit

    var threads: Int
        get() = prefs?.getInt(KEY_THREADS, 4) ?: 4
        set(value) = prefs?.edit()?.putInt(KEY_THREADS, value)?.apply() ?: Unit

    /** Idle timeout in minutes. 0 = never auto-unload. */
    var idleTimeoutMinutes: Int
        get() = prefs?.getInt(KEY_IDLE_TIMEOUT, 2) ?: 2
        set(value) = prefs?.edit()?.putInt(KEY_IDLE_TIMEOUT, value)?.apply() ?: Unit

    var backend: String
        get() = when (prefs?.getString(KEY_BACKEND, BACKEND_CPU)) {
            BACKEND_GPU -> BACKEND_GPU
            else -> BACKEND_CPU // Includes migration from the removed NPU setting.
        }
        set(value) = prefs?.edit()?.putString(KEY_BACKEND, value)?.apply() ?: Unit

    var textSourceLanguage: Language
        get() = getLanguage(KEY_TEXT_SOURCE_LANGUAGE, Language.RUSSIAN)
        set(value) = prefs?.edit()?.putString(KEY_TEXT_SOURCE_LANGUAGE, value.code)?.apply() ?: Unit

    var textSourceAuto: Boolean
        get() = prefs?.getBoolean(KEY_TEXT_SOURCE_AUTO, false) ?: false
        set(value) = prefs?.edit()?.putBoolean(KEY_TEXT_SOURCE_AUTO, value)?.apply() ?: Unit

    var textTargetLanguage: Language
        get() = getLanguage(KEY_TEXT_TARGET_LANGUAGE, Language.ENGLISH)
        set(value) = prefs?.edit()?.putString(KEY_TEXT_TARGET_LANGUAGE, value.code)?.apply() ?: Unit

    /** Hide the IME before starting a text translation. Enabled by default. */
    var hideKeyboardOnTextTranslate: Boolean
        get() = prefs?.getBoolean(KEY_HIDE_KEYBOARD_ON_TEXT_TRANSLATE, true) ?: true
        set(value) = prefs?.edit()?.putBoolean(KEY_HIDE_KEYBOARD_ON_TEXT_TRANSLATE, value)?.apply() ?: Unit

    /** Detailed inference data is opt-in and hidden in the normal translator UI. */
    var showTechnicalTranslationStats: Boolean
        get() = prefs?.getBoolean(KEY_SHOW_TECHNICAL_TRANSLATION_STATS, false) ?: false
        set(value) = prefs?.edit()?.putBoolean(KEY_SHOW_TECHNICAL_TRANSLATION_STATS, value)?.apply() ?: Unit

    /** Transliterate non-Latin scripts to Latin phonetic text. Enabled by default. */
    var showTransliteration: Boolean
        get() = prefs?.getBoolean(KEY_SHOW_TRANSLITERATION, true) ?: true
        set(value) = prefs?.edit()?.putBoolean(KEY_SHOW_TRANSLITERATION, value)?.apply() ?: Unit

    var translationPolicy: TranslationPolicy
        get() = TranslationPolicy.fromPersisted(prefs?.getString(KEY_TRANSLATION_POLICY, null))
        set(value) = prefs?.edit()?.putString(KEY_TRANSLATION_POLICY, value.persistedValue)?.apply() ?: Unit

    var cameraSourceLanguage: Language
        get() = getLanguage(KEY_CAMERA_SOURCE_LANGUAGE, Language.RUSSIAN)
        set(value) = prefs?.edit()?.putString(KEY_CAMERA_SOURCE_LANGUAGE, value.code)?.apply() ?: Unit

    var cameraSourceAuto: Boolean
        get() = prefs?.getBoolean(KEY_CAMERA_SOURCE_AUTO, false) ?: false
        set(value) = prefs?.edit()?.putBoolean(KEY_CAMERA_SOURCE_AUTO, value)?.apply() ?: Unit

    var cameraTargetLanguage: Language
        get() = getLanguage(KEY_CAMERA_TARGET_LANGUAGE, Language.ENGLISH)
        set(value) = prefs?.edit()?.putString(KEY_CAMERA_TARGET_LANGUAGE, value.code)?.apply() ?: Unit

    var dialogueSourceLanguage: Language
        get() = getLanguage(KEY_DIALOGUE_SOURCE_LANGUAGE, Language.RUSSIAN)
        set(value) = prefs?.edit()?.putString(KEY_DIALOGUE_SOURCE_LANGUAGE, value.code)?.apply() ?: Unit

    var dialogueTargetLanguage: Language
        get() = getLanguage(KEY_DIALOGUE_TARGET_LANGUAGE, Language.ENGLISH)
        set(value) = prefs?.edit()?.putString(KEY_DIALOGUE_TARGET_LANGUAGE, value.code)?.apply() ?: Unit

    /** The speech model explicitly selected in Models. Whisper stays the small, fast default. */
    var speechModel: String
        get() = when (prefs?.getString(KEY_SPEECH_MODEL, SPEECH_MODEL_WHISPER_TINY)) {
            SPEECH_MODEL_QWEN3_ASR_06B -> SPEECH_MODEL_QWEN3_ASR_06B
            else -> SPEECH_MODEL_WHISPER_TINY
        }
        set(value) = prefs?.edit()?.putString(KEY_SPEECH_MODEL, value)?.apply() ?: Unit

    /** User home currency code: "AUTO", "RUB", "USD", "EUR", "VND", etc. */
    open var homeCurrencyCode: String
        get() = prefs?.getString(KEY_HOME_CURRENCY_CODE, "AUTO") ?: "AUTO"
        set(value) = prefs?.edit()?.putString(KEY_HOME_CURRENCY_CODE, value)?.apply() ?: Unit

    /** Whether inline currency conversion in parentheses is enabled */
    open var enableCurrencyConversion: Boolean
        get() = prefs?.getBoolean(KEY_ENABLE_CURRENCY_CONVERSION, true) ?: true
        set(value) = prefs?.edit()?.putBoolean(KEY_ENABLE_CURRENCY_CONVERSION, value)?.apply() ?: Unit

    private fun getLanguage(key: String, default: Language): Language {
        val code = prefs?.getString(key, null) ?: return default
        return Language.entries.find { it.code == code } ?: default
    }
}
