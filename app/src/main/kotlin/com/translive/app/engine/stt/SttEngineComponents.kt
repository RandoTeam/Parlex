package com.translive.app.engine.stt

/**
 * Phase V: Multi-Engine Speech-to-Text (STT) Architecture.
 * Catalogs 2026 on-device ASR models (Zipformer streaming, SenseVoice-Small, Whisper Tiny, Qwen3-ASR).
 */
enum class SttEngineType(val id: String) {
    WHISPER_TINY("whisper_tiny"),
    SENSE_VOICE_SMALL("sense_voice_small"),
    ZIPFORMER_STREAMING("zipformer_streaming"),
    QWEN3_ASR("qwen3_asr")
}

data class SttEngineDescriptor(
    val type: SttEngineType,
    val displayName: String,
    val isNativeStreaming: Boolean,
    val sizeBytes: Long,
    val ramEstimateMb: Int,
    val supportedLanguages: Set<String>,
    val description: String
)

class SttEngineRegistry {
    private val engines = listOf(
        SttEngineDescriptor(
            type = SttEngineType.WHISPER_TINY,
            displayName = "Whisper Tiny",
            isNativeStreaming = false,
            sizeBytes = 42_000_000L,
            ramEstimateMb = 200,
            supportedLanguages = setOf(
                "ru", "en", "zh", "es", "de", "fr", "vi", "ja", "ko", "it", "pt", "tr", "ar", "hi", "pl", "uk", "nl"
            ),
            description = "Lightweight multilingual model (99 languages)"
        ),
        SttEngineDescriptor(
            type = SttEngineType.SENSE_VOICE_SMALL,
            displayName = "SenseVoice Small INT8",
            isNativeStreaming = false,
            sizeBytes = 240_000_000L,
            ramEstimateMb = 400,
            supportedLanguages = setOf("zh", "en", "ja", "ko", "yue"),
            description = "Ultra-fast non-autoregressive ASR (15x faster than Whisper-Large on ZH/EN/JA/KO)"
        ),
        SttEngineDescriptor(
            type = SttEngineType.ZIPFORMER_STREAMING,
            displayName = "Zipformer Streaming",
            isNativeStreaming = true,
            sizeBytes = 65_000_000L,
            ramEstimateMb = 180,
            supportedLanguages = setOf("ru", "en", "zh", "vi", "fr", "ko", "bn"),
            description = "True real-time streaming transducer with instantaneous word display"
        ),
        SttEngineDescriptor(
            type = SttEngineType.QWEN3_ASR,
            displayName = "Qwen3-ASR 0.6B INT8",
            isNativeStreaming = false,
            sizeBytes = 878_702_423L,
            ramEstimateMb = 1800,
            supportedLanguages = setOf("ru", "en", "zh", "es", "de", "fr", "vi", "ja", "ko"),
            description = "High-precision offline quality ASR"
        )
    )

    fun getAllEngines(): List<SttEngineDescriptor> = engines

    fun getEngine(type: SttEngineType): SttEngineDescriptor? = engines.firstOrNull { it.type == type }
}

class SttEngineSelector(
    private val downloadedEngines: Set<SttEngineType>,
    private val preferStreaming: Boolean = true
) {
    private val registry = SttEngineRegistry()

    fun selectBestEngine(languageCode: String): SttEngineType {
        val lang = languageCode.lowercase().take(2)

        // 1. Prefer native streaming (Zipformer) if downloaded and language is supported
        if (preferStreaming && downloadedEngines.contains(SttEngineType.ZIPFORMER_STREAMING)) {
            val zipformer = registry.getEngine(SttEngineType.ZIPFORMER_STREAMING)
            if (zipformer?.supportedLanguages?.contains(lang) == true) {
                return SttEngineType.ZIPFORMER_STREAMING
            }
        }

        // 2. Prefer SenseVoice for ultra-fast response on ZH/EN/JA/KO if downloaded
        if (downloadedEngines.contains(SttEngineType.SENSE_VOICE_SMALL)) {
            val senseVoice = registry.getEngine(SttEngineType.SENSE_VOICE_SMALL)
            if (senseVoice?.supportedLanguages?.contains(lang) == true) {
                return SttEngineType.SENSE_VOICE_SMALL
            }
        }

        // 3. Fallback to Whisper Tiny or whatever is downloaded
        return if (downloadedEngines.contains(SttEngineType.WHISPER_TINY)) {
            SttEngineType.WHISPER_TINY
        } else {
            downloadedEngines.firstOrNull() ?: SttEngineType.WHISPER_TINY
        }
    }
}

class TwoPassSttPipeline {
    var currentLiveText: String = ""
        private set

    var isFinalized: Boolean = false
        private set

    fun onStreamingPartial(partialText: String) {
        if (!isFinalized) {
            currentLiveText = partialText
        }
    }

    fun onRescoringFinal(finalText: String) {
        currentLiveText = finalText
        isFinalized = true
    }

    fun reset() {
        currentLiveText = ""
        isFinalized = false
    }
}
