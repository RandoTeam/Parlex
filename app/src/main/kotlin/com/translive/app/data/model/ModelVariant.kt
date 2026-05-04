package com.translive.app.data.model

/**
 * All available GGUF quantizations for HY-MT1.5-1.8B.
 * Each variant has a HuggingFace download URL, file size, and RAM estimate.
 */
data class ModelVariant(
    val id: String,
    val quantName: String,
    val displayName: String,
    val description: String,
    val sizeBytes: Long,
    val ramEstimateMb: Int,
    val downloadUrl: String,
    val filename: String,
    val isRecommended: Boolean = false
) {
    val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
    val sizeGb: Double get() = sizeBytes / (1024.0 * 1024.0 * 1024.0)

    val sizeLabel: String get() = when {
        sizeGb >= 1.0 -> "%.2f ГБ".format(sizeGb)
        else -> "%.0f МБ".format(sizeMb)
    }

    companion object {
        private const val MRADERMACHER_BASE =
            "https://huggingface.co/mradermacher/HY-MT1.5-1.8B-GGUF/resolve/main"
        private const val TENCENT_BASE =
            "https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF/resolve/main"

        val ALL: List<ModelVariant> = listOf(
            ModelVariant(
                id = "2bit-tencent",
                quantName = "2-bit (Tencent)",
                displayName = "Ультралёгкая",
                description = "Оптимизирована Tencent для мобильных. Самая быстрая.",
                sizeBytes = 601_000_000L,
                ramEstimateMb = 1024,
                downloadUrl = "$TENCENT_BASE/Hy-MT1.5-1.8B-2bit.gguf?download=true",
                filename = "Hy-MT1.5-1.8B-2bit.gguf"
            ),
            ModelVariant(
                id = "q2_k",
                quantName = "Q2_K",
                displayName = "Минимальная",
                description = "Стандартная 2-bit квантизация llama.cpp",
                sizeBytes = 777_000_000L,
                ramEstimateMb = 1228,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q2_K.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q2_K.gguf"
            ),
            ModelVariant(
                id = "q3_k_s",
                quantName = "Q3_K_S",
                displayName = "Компактная",
                description = "3-bit, маленький размер",
                sizeBytes = 872_000_000L,
                ramEstimateMb = 1400,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q3_K_S.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q3_K_S.gguf"
            ),
            ModelVariant(
                id = "q3_k_m",
                quantName = "Q3_K_M",
                displayName = "Баланс 3-bit",
                description = "3-bit со средними важными слоями",
                sizeBytes = 951_000_000L,
                ramEstimateMb = 1500,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q3_K_M.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q3_K_M.gguf"
            ),
            ModelVariant(
                id = "q3_k_l",
                quantName = "Q3_K_L",
                displayName = "Улучшенная 3-bit",
                description = "3-bit с большими важными слоями",
                sizeBytes = 1_020_000_000L,
                ramEstimateMb = 1600,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q3_K_L.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q3_K_L.gguf"
            ),
            ModelVariant(
                id = "iq4_xs",
                quantName = "IQ4_XS",
                displayName = "iMatrix 4-bit",
                description = "4-bit с iMatrix оптимизацией",
                sizeBytes = 1_040_000_000L,
                ramEstimateMb = 1600,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.IQ4_XS.gguf?download=true",
                filename = "HY-MT1.5-1.8B.IQ4_XS.gguf"
            ),
            ModelVariant(
                id = "q4_k_s",
                quantName = "Q4_K_S",
                displayName = "Стандартная 4-bit",
                description = "4-bit, хороший баланс качество/размер",
                sizeBytes = 1_080_000_000L,
                ramEstimateMb = 1700,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q4_K_S.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q4_K_S.gguf"
            ),
            ModelVariant(
                id = "q4_k_m",
                quantName = "Q4_K_M",
                displayName = "Рекомендуемая",
                description = "Лучший баланс: качество ≈ F16, быстрая, компактная",
                sizeBytes = 1_130_000_000L,
                ramEstimateMb = 1800,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q4_K_M.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q4_K_M.gguf",
                isRecommended = true
            ),
            ModelVariant(
                id = "q5_k_m",
                quantName = "Q5_K_M",
                displayName = "Высокое качество",
                description = "5-bit, отличное качество",
                sizeBytes = 1_300_000_000L,
                ramEstimateMb = 2100,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q5_K_M.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q5_K_M.gguf"
            ),
            ModelVariant(
                id = "q6_k",
                quantName = "Q6_K",
                displayName = "Премиум",
                description = "6-bit, почти без потерь",
                sizeBytes = 1_470_000_000L,
                ramEstimateMb = 2300,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q6_K.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q6_K.gguf"
            ),
            ModelVariant(
                id = "q8_0",
                quantName = "Q8_0",
                displayName = "Максимальная точность",
                description = "8-bit, неотличима от оригинала",
                sizeBytes = 1_910_000_000L,
                ramEstimateMb = 2800,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.Q8_0.gguf?download=true",
                filename = "HY-MT1.5-1.8B.Q8_0.gguf"
            ),
            ModelVariant(
                id = "f16",
                quantName = "F16",
                displayName = "Полная (без квантизации)",
                description = "Оригинальные веса FP16. Максимальный размер.",
                sizeBytes = 3_590_000_000L,
                ramEstimateMb = 4500,
                downloadUrl = "$MRADERMACHER_BASE/HY-MT1.5-1.8B.f16.gguf?download=true",
                filename = "HY-MT1.5-1.8B.f16.gguf"
            )
        )

        fun findById(id: String): ModelVariant? = ALL.find { it.id == id }
    }
}
