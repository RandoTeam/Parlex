package com.translive.app.data.model

/**
 * Explicit inference contract for a translation model family and runtime.
 * UI code must not select sampling parameters directly.
 */
data class TranslationProfile(
    val id: String,
    val promptStyle: PromptStyle,
    val useChatTemplate: Boolean,
    val sampling: TranslationSampling,
    val contextTokens: Int,
    val defaultMaxOutputTokens: Int,
    val liteRtUsesTranslationTags: Boolean = false
)

data class TranslationSampling(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val repetitionPenalty: Float
)

object TranslationProfiles {
    private val hyMt = TranslationProfile(
        id = "hy-mt-legacy",
        promptStyle = PromptStyle.HY_MT,
        useChatTemplate = true,
        sampling = TranslationSampling(
            temperature = 0.0f,
            topK = 1,
            topP = 1.0f,
            repetitionPenalty = 1.0f
        ),
        contextTokens = 1024,
        defaultMaxOutputTokens = 512
    )

    private val hyMt2 = TranslationProfile(
        id = "hy-mt2",
        promptStyle = PromptStyle.HY_MT2,
        useChatTemplate = true,
        sampling = TranslationSampling(
            temperature = 0.0f,
            topK = 1,
            topP = 1.0f,
            repetitionPenalty = 1.0f
        ),
        contextTokens = 1024,
        defaultMaxOutputTokens = 512
    )

    private val translateGemmaGguf = TranslationProfile(
        id = "translategemma-gguf",
        promptStyle = PromptStyle.TRANSLATE_GEMMA,
        useChatTemplate = true,
        sampling = TranslationSampling(
            temperature = 0.0f,
            topK = 1,
            topP = 1.0f,
            repetitionPenalty = 1.0f
        ),
        contextTokens = 1024,
        defaultMaxOutputTokens = 512
    )

    private val translateGemmaLiteRt = TranslationProfile(
        id = "translategemma-litert-lm",
        promptStyle = PromptStyle.TRANSLATE_GEMMA,
        useChatTemplate = false,
        sampling = TranslationSampling(
            temperature = 0.0f,
            topK = 1,
            topP = 1.0f,
            repetitionPenalty = 1.0f
        ),
        contextTokens = 2048,
        defaultMaxOutputTokens = 512,
        liteRtUsesTranslationTags = true
    )

    private val gemma4LiteRt = TranslationProfile(
        id = "gemma4-litert-lm",
        promptStyle = PromptStyle.TRANSLATE_GEMMA,
        useChatTemplate = true,
        sampling = TranslationSampling(temperature = 0.0f, topK = 1, topP = 1.0f, repetitionPenalty = 1.0f),
        contextTokens = 2048,
        defaultMaxOutputTokens = 512
    )

    fun forModel(family: ModelFamily?, runtime: ModelRuntime): TranslationProfile {
        if (runtime == ModelRuntime.LITERT_LM) {
            return if (family?.id == "gemma_4_litert") gemma4LiteRt else translateGemmaLiteRt
        }

        return when (family?.promptStyle) {
            PromptStyle.HY_MT2 -> hyMt2
            PromptStyle.TRANSLATE_GEMMA -> translateGemmaGguf
            PromptStyle.HY_MT,
            null -> hyMt
        }
    }
}
