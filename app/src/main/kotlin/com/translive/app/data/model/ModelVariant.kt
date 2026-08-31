package com.translive.app.data.model

enum class ModelPerformanceTier(val badgeLabel: String) {
    FAST_BUDGET("⚡ Fast / Budget"),
    BALANCED("⭐ Balanced"),
    MAX_QUALITY("💎 Max Quality"),
    GPU_ACCELERATED("🚀 GPU-Accelerated")
}

/**
 * A single GGUF or LiteRT-LM quantization variant of a model.
 * Each variant belongs to a [ModelFamily] and has a namespaced ID: "familyId:quantId".
 */
data class ModelVariant(
    /** Namespaced ID: "hy_mt:q4_k_m", "qwen3_1_7b:q8_0", etc. */
    val id: String,
    val quantName: String,
    val displayName: String,
    val description: String,
    val sizeBytes: Long,
    val ramEstimateMb: Int,
    val downloadUrl: String,
    val filename: String,
    val isRecommended: Boolean = false,
    val runtime: ModelRuntime = ModelRuntime.GGUF,
    /** SHA-256 published by the artifact source. Null only for legacy/import-only models. */
    val sha256: String? = null,
    /** Backends verified for this exact model artifact, not just its model family. */
    val supportsCpu: Boolean = true,
    val supportsGpu: Boolean = false,
    val explicitTier: ModelPerformanceTier? = null
) {

    val performanceTier: ModelPerformanceTier
        get() = explicitTier ?: when {
            runtime == ModelRuntime.LITERT_LM && supportsGpu -> ModelPerformanceTier.GPU_ACCELERATED
            quantName.contains("1.25", ignoreCase = true) ||
            quantName.contains("2bit", ignoreCase = true) ||
            quantName.startsWith("Q2", ignoreCase = true) ||
            quantName.startsWith("Q3", ignoreCase = true) -> ModelPerformanceTier.FAST_BUDGET
            isRecommended || quantName.startsWith("Q4", ignoreCase = true) || quantName.startsWith("IQ4", ignoreCase = true) -> ModelPerformanceTier.BALANCED
            else -> ModelPerformanceTier.MAX_QUALITY
        }

    val backendLabel: String
        get() = when {
            runtime == ModelRuntime.OCR -> "OCR (MNN OpenCL / Vulkan)"
            runtime == ModelRuntime.LITERT_LM && supportsGpu && !supportsCpu -> "LiteRT-LM (GPU only)"
            runtime == ModelRuntime.LITERT_LM -> "LiteRT-LM (CPU / GPU)"
            runtime == ModelRuntime.GGUF -> "GGUF (llama.cpp CPU/GPU)"
            supportsCpu && supportsGpu -> "CPU / GPU"
            supportsGpu -> "GPU only"
            else -> "CPU only"
        }

    val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
    val sizeGb: Double get() = sizeBytes / (1024.0 * 1024.0 * 1024.0)

    val sizeLabel: String get() = when {
        sizeGb >= 1.0 -> "%.2f ГБ".format(sizeGb)
        else -> "%.0f МБ".format(sizeMb)
    }

    val familyId: String get() = id.substringBefore(":")

    /** Extract quant ID from namespaced ID */
    val quantId: String get() = id.substringAfter(":")

    companion object {
        /**
         * Legacy compatibility: flat list of all variants across all families.
         * Prefer [ModelFamily] / [ModelCatalog] for new code.
         */
        val ALL: List<ModelVariant> get() = ModelCatalog.ALL_FAMILIES.flatMap { it.variants }

        fun findById(id: String): ModelVariant? = ModelFamily.findVariantById(id)
    }
}
