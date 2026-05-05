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
    const val WHISPER_DESCRIPTION = "Многоязычное распознавание речи • ~40 МБ"
    const val WHISPER_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    const val WHISPER_ARCHIVE = "sherpa-onnx-whisper-tiny.tar.bz2"
    const val WHISPER_DIR = "sherpa-onnx-whisper-tiny"
    const val WHISPER_SIZE_BYTES = 42_000_000L // ~40 MB compressed
    const val WHISPER_SIZE_LABEL = "~42 МБ"
    const val WHISPER_RAM_MB = 200

    // Combined label
    const val COMBINED_LABEL = "Голос (STT)"
    const val COMBINED_DESCRIPTION = "Silero VAD (2 МБ) + Whisper Tiny (40 МБ)"
    const val COMBINED_SIZE_LABEL = "~42 МБ"
    val TOTAL_SIZE_BYTES = VAD_SIZE_BYTES + WHISPER_SIZE_BYTES
}
