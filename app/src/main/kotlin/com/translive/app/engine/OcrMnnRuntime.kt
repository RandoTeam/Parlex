package com.translive.app.engine

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boundary for the PP-OCR MNN runtime.
 *
 * The native implementation is deliberately capability-gated: an APK built
 * without MNN must report unavailable instead of silently falling back to CPU
 * or claiming that a GPU model is active. The detector/recognizer bridge will
 * be attached behind this boundary once the MNN checkout is restored for the
 * target ABI.
 */
@Singleton
class OcrMnnRuntime @Inject constructor() {

    data class Capability(
        val available: Boolean,
        val backend: String,
        val detail: String
    )

    fun capability(): Capability = runCatching {
        Capability(
            available = nativeIsAvailable(),
            backend = nativeBackendName(),
            detail = nativeDiagnostics()
        )
    }.getOrElse { error ->
        Log.i(TAG, "MNN OCR runtime is not packaged: ${error.message}")
        Capability(false, "unavailable", "MNN OCR native library is not packaged")
    }

    /** Backend selector: 0 CPU, 1 OpenCL, 2 Vulkan. Returns 0 on failure. */
    fun loadModel(path: String, backend: Int): Long = runCatching {
        nativeLoadModel(path, backend)
    }.getOrDefault(0L)

    fun releaseModel(handle: Long) {
        if (handle != 0L) runCatching { nativeReleaseModel(handle) }
    }

    fun runFloat(handle: Long, input: FloatArray, shape: IntArray): FloatArray? = runCatching {
        nativeRunFloat(handle, input, shape)
    }.getOrNull()

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeBackendName(): String
    private external fun nativeDiagnostics(): String
    private external fun nativeLoadModel(path: String, backend: Int): Long
    private external fun nativeReleaseModel(handle: Long)
    private external fun nativeRunFloat(handle: Long, input: FloatArray, shape: IntArray): FloatArray?

    companion object {
        private const val TAG = "OcrMnnRuntime"
    }
}
