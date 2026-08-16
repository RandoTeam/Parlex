package com.translive.app.data.model

/**
 * STT model info for Sherpa-ONNX: Whisper tiny + Silero VAD.
 */
object SttModelInfo {
    // Silero VAD v5
    const val VAD_DISPLAY_NAME = "Silero VAD v5"
    const val VAD_DOWNLOAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    const val VAD_FILENAME = "silero_vad.onnx"
    const val VAD_SIZE_BYTES = 2_200_000L // ~2 MB

    // Whisper tiny multilingual (supports RU + EN + many more)
    const val WHISPER_DISPLAY_NAME = "Whisper Tiny"
    const val WHISPER_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    const val WHISPER_ARCHIVE = "sherpa-onnx-whisper-tiny.tar.bz2"
    const val WHISPER_DIR = "sherpa-onnx-whisper-tiny"
    const val WHISPER_SIZE_BYTES = 42_000_000L // ~40 MB compressed
    const val WHISPER_RAM_MB = 200

    val TOTAL_SIZE_BYTES = VAD_SIZE_BYTES + WHISPER_SIZE_BYTES

    // Qwen3-ASR 0.6B, converted and quantized for offline sherpa-onnx.
    // Download is intentionally explicit: it is a quality mode, not a bundled asset.
    const val QWEN3_DISPLAY_NAME = "Qwen3-ASR 0.6B INT8"
    const val QWEN3_ARCHIVE = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
    const val QWEN3_DIR = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    const val QWEN3_DOWNLOAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$QWEN3_ARCHIVE"
    const val QWEN3_SHA256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96"
    const val QWEN3_ARCHIVE_SIZE_BYTES = 878_702_423L
    const val QWEN3_RAM_MB = 1800

    /** Known non-translation downloads, also used to restore paused tasks after process death. */
    fun findDownloadVariant(id: String): ModelVariant? = when (id) {
        "stt-vad" -> ModelVariant(
            id = id, quantName = VAD_DISPLAY_NAME, displayName = VAD_DISPLAY_NAME,
            description = "VAD", sizeBytes = VAD_SIZE_BYTES, ramEstimateMb = 50,
            downloadUrl = VAD_DOWNLOAD_URL, filename = VAD_FILENAME
        )
        "stt-whisper" -> ModelVariant(
            id = id, quantName = WHISPER_DISPLAY_NAME, displayName = WHISPER_DISPLAY_NAME,
            description = WHISPER_DISPLAY_NAME, sizeBytes = WHISPER_SIZE_BYTES,
            ramEstimateMb = WHISPER_RAM_MB,
            downloadUrl = "$WHISPER_BASE_URL/$WHISPER_ARCHIVE", filename = WHISPER_ARCHIVE
        )
        "stt-qwen3-asr-0.6b" -> ModelVariant(
            id = id, quantName = QWEN3_DISPLAY_NAME, displayName = QWEN3_DISPLAY_NAME,
            description = "Offline quality ASR, CPU", sizeBytes = QWEN3_ARCHIVE_SIZE_BYTES,
            ramEstimateMb = QWEN3_RAM_MB, downloadUrl = QWEN3_DOWNLOAD_URL,
            filename = QWEN3_ARCHIVE, sha256 = QWEN3_SHA256
        )
        else -> null
    }
}
