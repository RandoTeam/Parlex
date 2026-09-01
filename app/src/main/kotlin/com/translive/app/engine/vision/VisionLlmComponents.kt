package com.translive.app.engine.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Phase AI: On-Device Vision LLM & Screen Analysis Architecture.
 * Catalogs 2026 compact multimodal models (MiniCPM-V 4.6, SmolVLM-2, Gemma 4 Edge).
 */
enum class VisionLlmModelType(val id: String) {
    MINI_CPM_V_4_6("minicpm_v_4_6_int4"),
    SMOL_VLM_2("smolvlm_2_500m_int4"),
    GEMMA_4_EDGE_E2B("gemma_4_edge_e2b_int4")
}

data class VisionLlmModelDescriptor(
    val type: VisionLlmModelType,
    val displayName: String,
    val sizeBytes: Long,
    val ramEstimateMb: Int,
    val parameterCount: String,
    val description: String,
    val downloadUrl: String
)

class VisionLlmCatalog {
    private val models = listOf(
        VisionLlmModelDescriptor(
            type = VisionLlmModelType.MINI_CPM_V_4_6,
            displayName = "MiniCPM-V 4.6 INT4",
            sizeBytes = 1_080_000_000L,
            ramEstimateMb = 1500,
            parameterCount = "1.3B (SigLIP2-400M + Qwen3.5-0.8B)",
            description = "High-accuracy visual OCR, document understanding & visual reasoning",
            downloadUrl = "https://huggingface.co/openbmb/MiniCPM-V-4_6-gguf/resolve/main/minicpm-v-4_6-q4_k_m.gguf"
        ),
        VisionLlmModelDescriptor(
            type = VisionLlmModelType.SMOL_VLM_2,
            displayName = "SmolVLM-2 500M INT4",
            sizeBytes = 320_000_000L,
            ramEstimateMb = 650,
            parameterCount = "500M",
            description = "Ultra-compact mobile vision model for low-memory devices",
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Instruct-GGUF/resolve/main/smolvlm2-500m-q4_k_m.gguf"
        ),
        VisionLlmModelDescriptor(
            type = VisionLlmModelType.GEMMA_4_EDGE_E2B,
            displayName = "Gemma 4 Edge E2B INT4",
            sizeBytes = 1_450_000_000L,
            ramEstimateMb = 1900,
            parameterCount = "2.0B",
            description = "Multimodal text, document, and image perception",
            downloadUrl = "https://huggingface.co/google/gemma-4-edge-2b-it-gguf/resolve/main/gemma-4-edge-2b-q4_k_m.gguf"
        )
    )

    fun getAllModels(): List<VisionLlmModelDescriptor> = models

    fun getModel(type: VisionLlmModelType): VisionLlmModelDescriptor? = models.firstOrNull { it.type == type }
}

data class ScaledDimensions(
    val width: Int,
    val height: Int,
    val aspectRatio: Float
)

object ImageDimensionScaler {
    fun computeScaledDimensions(sourceWidth: Int, sourceHeight: Int, maxDimension: Int = 1024): ScaledDimensions {
        require(sourceWidth > 0 && sourceHeight > 0) { "Dimensions must be positive" }
        val maxSrc = max(sourceWidth, sourceHeight)

        if (maxSrc <= maxDimension) {
            return ScaledDimensions(
                width = sourceWidth,
                height = sourceHeight,
                aspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            )
        }

        val scale = maxDimension.toFloat() / maxSrc.toFloat()
        val scaledWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)

        return ScaledDimensions(
            width = scaledWidth,
            height = scaledHeight,
            aspectRatio = scaledWidth.toFloat() / scaledHeight.toFloat()
        )
    }
}

enum class VisionAnalysisPromptType {
    TRANSLATE_AND_EXPLAIN,
    EXPLAIN_IMAGE_CONTENT,
    SUMMARIZE_SCREEN,
    CUSTOM_QUERY
}

object VisionPromptBuilder {
    fun buildPrompt(
        type: VisionAnalysisPromptType,
        targetLangName: String = "Русский",
        customQuestion: String = ""
    ): String {
        return when (type) {
            VisionAnalysisPromptType.TRANSLATE_AND_EXPLAIN ->
                "Переведи весь текст на этом скриншоте на язык '$targetLangName' и кратко объясни контекст в 2-3 предложениях."
            VisionAnalysisPromptType.EXPLAIN_IMAGE_CONTENT ->
                "Опиши подробно, что изображено на этом экране на языке '$targetLangName'."
            VisionAnalysisPromptType.SUMMARIZE_SCREEN ->
                "Суммаризируй главные тезисы и ключевую информацию с этого экрана на языке '$targetLangName'."
            VisionAnalysisPromptType.CUSTOM_QUERY ->
                customQuestion.ifBlank { "Объясни содержание этого изображения на языке '$targetLangName'." }
        }
    }
}

class StreamingTextAccumulator {
    private val builder = StringBuilder()
    var isCancelled: Boolean = false
        private set

    val currentText: String
        get() = builder.toString()

    fun appendToken(token: String) {
        if (!isCancelled) {
            builder.append(token)
        }
    }

    fun cancel() {
        isCancelled = true
    }

    fun reset() {
        builder.clear()
        isCancelled = false
    }
}
