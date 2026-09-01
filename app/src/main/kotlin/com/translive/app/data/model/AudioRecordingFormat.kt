package com.translive.app.data.model

/**
 * Supported audio recording formats for dialogue sessions.
 */
enum class AudioRecordingFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
    val defaultBitrate: Int
) {
    AAC(
        id = "AAC",
        extension = "m4a",
        mimeType = "audio/mp4a-latm",
        defaultBitrate = 48_000 // 48 kbps mono: high voice fidelity, ~360 KB / 10 min
    ),
    WAV(
        id = "WAV",
        extension = "wav",
        mimeType = "audio/wav",
        defaultBitrate = 256_000 // 16 kHz * 16-bit * 1 ch = 256 kbps uncompressed PCM
    );

    companion object {
        fun fromId(id: String?): AudioRecordingFormat =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: AAC
    }
}
