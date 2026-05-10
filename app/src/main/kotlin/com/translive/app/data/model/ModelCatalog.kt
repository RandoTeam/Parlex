package com.translive.app.data.model

/**
 * Central catalog of available translation model families and their GGUF quantizations.
 * Only translation-specialized models are included.
 * Data sourced from HuggingFace API (May 2026).
 */
object ModelCatalog {

    val ALL_FAMILIES: List<ModelFamily> = listOf(
        hyMtFamily(),
        translateGemmaFamily()
    )

    // ─── 1. HY-MT 1.5 1.8B (Tencent) ─────────────────────────────────

    private fun hyMtFamily(): ModelFamily {
        val b = "https://huggingface.co/mradermacher/HY-MT1.5-1.8B-GGUF/resolve/main"
        val p = "HY-MT1.5-1.8B"
        return ModelFamily(
            id = "hy_mt", name = "HY-MT 1.5 1.8B", developer = "Tencent",
            description = "Специализирован на перевод, 33 языка",
            languageCount = 33, parameterSize = "1.8B",
            promptStyle = PromptStyle.HY_MT, license = ModelLicense.APACHE_2,
            isSpecialized = true,
            variants = listOf(
                v("hy_mt:q2_k","Q2_K","Минимальная","2-bit",777_000_000L,1228,"$b/$p.Q2_K.gguf?download=true","$p.Q2_K.gguf"),
                v("hy_mt:q3_k_s","Q3_K_S","Компактная","3-bit, маленький",872_000_000L,1400,"$b/$p.Q3_K_S.gguf?download=true","$p.Q3_K_S.gguf"),
                v("hy_mt:q3_k_m","Q3_K_M","Баланс 3-bit","3-bit средний",951_000_000L,1500,"$b/$p.Q3_K_M.gguf?download=true","$p.Q3_K_M.gguf"),
                v("hy_mt:q3_k_l","Q3_K_L","Улучшенная 3-bit","3-bit большой",1_020_000_000L,1600,"$b/$p.Q3_K_L.gguf?download=true","$p.Q3_K_L.gguf"),
                v("hy_mt:iq4_xs","IQ4_XS","iMatrix 4-bit","4-bit iMatrix",1_040_000_000L,1600,"$b/$p.IQ4_XS.gguf?download=true","$p.IQ4_XS.gguf"),
                v("hy_mt:q4_k_s","Q4_K_S","Стандартная 4-bit","4-bit",1_080_000_000L,1700,"$b/$p.Q4_K_S.gguf?download=true","$p.Q4_K_S.gguf"),
                v("hy_mt:q4_k_m","Q4_K_M","Рекомендуемая","Лучший баланс",1_130_000_000L,1800,"$b/$p.Q4_K_M.gguf?download=true","$p.Q4_K_M.gguf",true),
                v("hy_mt:q5_k_m","Q5_K_M","Высокое качество","5-bit",1_300_000_000L,2100,"$b/$p.Q5_K_M.gguf?download=true","$p.Q5_K_M.gguf"),
                v("hy_mt:q6_k","Q6_K","Премиум","6-bit, почти без потерь",1_470_000_000L,2300,"$b/$p.Q6_K.gguf?download=true","$p.Q6_K.gguf"),
                v("hy_mt:q8_0","Q8_0","Максимальная точность","8-bit",1_910_000_000L,2800,"$b/$p.Q8_0.gguf?download=true","$p.Q8_0.gguf"),
                v("hy_mt:f16","F16","Полная","FP16, без квантизации",3_590_000_000L,4500,"$b/$p.f16.gguf?download=true","$p.f16.gguf")
            )
        )
    }

    // ─── 2. TranslateGemma 4B (Google) ────────────────────────────────

    private fun translateGemmaFamily(): ModelFamily {
        val b = "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main"
        val p = "translategemma-4b-it"
        return ModelFamily(
            id = "translate_gemma", name = "TranslateGemma 4B", developer = "Google",
            description = "Специализирован на перевод, 55 языков",
            languageCount = 55, parameterSize = "4B",
            promptStyle = PromptStyle.TRANSLATE_GEMMA, license = ModelLicense.GEMMA_TOU,
            isSpecialized = true,
            variants = listOf(
                v("translate_gemma:q2_k","Q2_K","Минимальная","2-bit",1_729_180_160L,2200,"$b/$p.Q2_K.gguf?download=true","$p.Q2_K.gguf"),
                v("translate_gemma:q3_k_s","Q3_K_S","Компактная","3-bit",1_937_379_840L,2500,"$b/$p.Q3_K_S.gguf?download=true","$p.Q3_K_S.gguf"),
                v("translate_gemma:q3_k_m","Q3_K_M","Баланс 3-bit","3-bit средний",2_098_475_520L,2700,"$b/$p.Q3_K_M.gguf?download=true","$p.Q3_K_M.gguf"),
                v("translate_gemma:q3_k_l","Q3_K_L","Улучшенная 3-bit","3-bit большой",2_236_101_120L,2900,"$b/$p.Q3_K_L.gguf?download=true","$p.Q3_K_L.gguf"),
                v("translate_gemma:iq4_xs","IQ4_XS","iMatrix 4-bit","4-bit iMatrix",2_279_641_600L,2900,"$b/$p.IQ4_XS.gguf?download=true","$p.IQ4_XS.gguf"),
                v("translate_gemma:q4_k_s","Q4_K_S","Стандартная 4-bit","4-bit",2_377_945_600L,3000,"$b/$p.Q4_K_S.gguf?download=true","$p.Q4_K_S.gguf"),
                v("translate_gemma:q4_k_m","Q4_K_M","Рекомендуемая","Лучший баланс",2_489_909_760L,3200,"$b/$p.Q4_K_M.gguf?download=true","$p.Q4_K_M.gguf",true),
                v("translate_gemma:q5_k_s","Q5_K_S","Качество 5-bit","5-bit",2_764_608_000L,3500,"$b/$p.Q5_K_S.gguf?download=true","$p.Q5_K_S.gguf"),
                v("translate_gemma:q5_k_m","Q5_K_M","Высокое качество","5-bit",2_829_713_920L,3600,"$b/$p.Q5_K_M.gguf?download=true","$p.Q5_K_M.gguf"),
                v("translate_gemma:q6_k","Q6_K","Премиум","6-bit",3_190_755_840L,4000,"$b/$p.Q6_K.gguf?download=true","$p.Q6_K.gguf"),
                v("translate_gemma:q8_0","Q8_0","Максимальная точность","8-bit",4_130_417_920L,5000,"$b/$p.Q8_0.gguf?download=true","$p.Q8_0.gguf"),
                v("translate_gemma:f16","F16","Полная","FP16",7_767_819_520L,9000,"$b/$p.f16.gguf?download=true","$p.f16.gguf")
            )
        )
    }

    // ─── Helper ───────────────────────────────────────────────────────

    private fun v(
        id: String, quant: String, display: String, desc: String,
        size: Long, ram: Int, url: String, file: String, rec: Boolean = false
    ) = ModelVariant(id, quant, display, desc, size, ram, url, file, rec)
}
