package com.translive.app.data

import android.content.Context
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

        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"
        const val BACKEND_NPU = "npu"

        val THREAD_OPTIONS = listOf(1, 2, 3, 4, 6, 8)
        val TIMEOUT_OPTIONS = listOf(0, 1, 2, 5, 10, 30) // 0 = never unload
    }

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
}
