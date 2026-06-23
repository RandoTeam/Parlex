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
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("parlex_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_THREADS = "inference_threads"
        private const val KEY_IDLE_TIMEOUT = "idle_timeout_minutes"
        private const val KEY_BACKEND = "compute_backend"
        private const val KEY_TEXT_SOURCE_LANGUAGE = "text_source_language"
        private const val KEY_TEXT_SOURCE_AUTO = "text_source_auto"
        private const val KEY_TEXT_TARGET_LANGUAGE = "text_target_language"
        private const val KEY_CAMERA_SOURCE_LANGUAGE = "camera_source_language"
        private const val KEY_CAMERA_SOURCE_AUTO = "camera_source_auto"
        private const val KEY_CAMERA_TARGET_LANGUAGE = "camera_target_language"
        private const val KEY_DIALOGUE_SOURCE_LANGUAGE = "dialogue_source_language"
        private const val KEY_DIALOGUE_TARGET_LANGUAGE = "dialogue_target_language"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_FILE_LOGGING = "enable_file_logging"

        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"
        const val BACKEND_NPU = "npu"

        val THREAD_OPTIONS = listOf(1, 2, 3, 4, 6, 8)
        val TIMEOUT_OPTIONS = listOf(0, 1, 2, 5, 10, 30) // 0 = never unload
    }

    var appLanguageCode: String
        get() = prefs.getString(KEY_APP_LANGUAGE, AppLocale.SYSTEM) ?: AppLocale.SYSTEM
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, AppLocale.normalize(value)).apply()

    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 4)
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

    /** Idle timeout in minutes. 0 = never auto-unload. */
    var idleTimeoutMinutes: Int
        get() = prefs.getInt(KEY_IDLE_TIMEOUT, 2)
        set(value) = prefs.edit().putInt(KEY_IDLE_TIMEOUT, value).apply()

    var backend: String
        get() = prefs.getString(KEY_BACKEND, BACKEND_CPU) ?: BACKEND_CPU
        set(value) = prefs.edit().putString(KEY_BACKEND, value).apply()

    var textSourceLanguage: Language
        get() = getLanguage(KEY_TEXT_SOURCE_LANGUAGE, Language.RUSSIAN)
        set(value) = prefs.edit().putString(KEY_TEXT_SOURCE_LANGUAGE, value.code).apply()

    var textSourceAuto: Boolean
        get() = prefs.getBoolean(KEY_TEXT_SOURCE_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_TEXT_SOURCE_AUTO, value).apply()

    var textTargetLanguage: Language
        get() = getLanguage(KEY_TEXT_TARGET_LANGUAGE, Language.ENGLISH)
        set(value) = prefs.edit().putString(KEY_TEXT_TARGET_LANGUAGE, value.code).apply()

    var cameraSourceLanguage: Language
        get() = getLanguage(KEY_CAMERA_SOURCE_LANGUAGE, Language.RUSSIAN)
        set(value) = prefs.edit().putString(KEY_CAMERA_SOURCE_LANGUAGE, value.code).apply()

    var cameraSourceAuto: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_SOURCE_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_SOURCE_AUTO, value).apply()

    var cameraTargetLanguage: Language
        get() = getLanguage(KEY_CAMERA_TARGET_LANGUAGE, Language.ENGLISH)
        set(value) = prefs.edit().putString(KEY_CAMERA_TARGET_LANGUAGE, value.code).apply()

    var dialogueSourceLanguage: Language
        get() = getLanguage(KEY_DIALOGUE_SOURCE_LANGUAGE, Language.RUSSIAN)
        set(value) = prefs.edit().putString(KEY_DIALOGUE_SOURCE_LANGUAGE, value.code).apply()

    var dialogueTargetLanguage: Language
        get() = getLanguage(KEY_DIALOGUE_TARGET_LANGUAGE, Language.ENGLISH)
        set(value) = prefs.edit().putString(KEY_DIALOGUE_TARGET_LANGUAGE, value.code).apply()

    var fileLoggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILE_LOGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_FILE_LOGGING, value).apply()

    private fun getLanguage(key: String, default: Language): Language {
        val code = prefs.getString(key, null) ?: return default
        return Language.entries.find { it.code == code } ?: default
    }
}
