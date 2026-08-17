package com.translive.app.data.model

/**
 * A single GGUF quantization variant of a model.
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
    val supportsGpu: Boolean = false
) {

    val backendLabel: String
        get() = when {
            runtime == ModelRuntime.OCR -> "OCR (MNN OpenCL / Vulkan)"
            runtime == ModelRuntime.GGUF -> "CPU / GPU (OpenCL)"
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

    /** Extract family ID from namespaced ID */
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
