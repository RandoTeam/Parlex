package com.translive.app.data.model

/**
 * Central catalog of available translation model families and their GGUF quantizations.
 * Only translation-specialized models are included.
 * Data sourced from HuggingFace API (May 2026).
 */
object ModelCatalog {

    val ALL_FAMILIES: List<ModelFamily> = listOf(
        hyMt2MobileFamily(),
        hyMt2QualityFamily(),
        hyMtFamily(),
        translateGemmaFamily(),
        gemma4LiteRtFamily()
    )

    // ─── HY-MT 1.5 1.8B (Tencent) ────────────────────────────────────

    private fun hyMtFamily(): ModelFamily {
        val compact = "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main"
        val bit125 = "https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main"
        val bit2 = "https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF/resolve/main"
        return ModelFamily(
            id = "hy_mt", name = "HY-MT 1.5 1.8B", developer = "Tencent",
            description = "Специализирован на перевод, 33 языка, official Tencent GGUF",
            languageCount = 33, parameterSize = "1.8B",
            promptStyle = PromptStyle.HY_MT, license = ModelLicense.TENCENT_HY_COMMUNITY,
            isSpecialized = true,
            variants = listOf(
                v("hy_mt:1_25bit","1.25bit","Ультракомпактная","AngelSlim 1.25-bit, official Tencent",461_861_216L,900,"$bit125/Hy-MT1.5-1.8B-1.25bit.gguf?download=true","Hy-MT1.5-1.8B-1.25bit.gguf"),
                v("hy_mt:2bit","2bit","Минимальная","AngelSlim 2-bit, official Tencent",600_534_880L,1_100,"$bit2/Hy-MT1.5-1.8B-2bit.gguf?download=true","Hy-MT1.5-1.8B-2bit.gguf"),
                v("hy_mt:q4_k_m","Q4_K_M","Рекомендуемая","4-bit, official Tencent standard GGUF",1_133_080_512L,1_800,"$compact/HY-MT1.5-1.8B-Q4_K_M.gguf?download=true","HY-MT1.5-1.8B-Q4_K_M.gguf",rec = true),
                v("hy_mt:q6_k","Q6_K","Премиум","6-bit, почти без потерь",1_474_785_216L,2_300,"$compact/HY-MT1.5-1.8B-Q6_K.gguf?download=true","HY-MT1.5-1.8B-Q6_K.gguf"),
                v("hy_mt:q8_0","Q8_0","Максимальная точность","8-bit",1_908_528_288L,2_800,"$compact/HY-MT1.5-1.8B-Q8_0.gguf?download=true","HY-MT1.5-1.8B-Q8_0.gguf")
            )
        )
    }

    // ─── Hy-MT2 1.8B (Tencent) ───────────────────────────────────────

    private fun hyMt2MobileFamily(): ModelFamily {
        val compact = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main"
        val bit125 = "https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/main"
        val bit2 = "https://huggingface.co/tencent/Hy-MT2-1.8B-2Bit-GGUF/resolve/main"
        return ModelFamily(
            id = "hy_mt2_1_8b", name = "Hy-MT2 1.8B", developer = "Tencent",
            description = "Fast-thinking перевод, 33 языка, мобильные GGUF",
            languageCount = 33, parameterSize = "1.8B",
            promptStyle = PromptStyle.HY_MT2, license = ModelLicense.APACHE_2,
            isSpecialized = true,
            variants = listOf(
                v(
                    "hy_mt2_1_8b:1_25bit",
                    "1.25Bit",
                    "Ультракомпактная",
                    "AngelSlim 1.25-bit, около 440 МБ",
                    461_860_736L,
                    900,
                    "$bit125/Hy-MT2-1.8B-1.25Bit.gguf?download=true",
                    "Hy-MT2-1.8B-1.25Bit.gguf"
                ),
                v(
                    "hy_mt2_1_8b:2bit",
                    "2Bit",
                    "Минимальная",
                    "AngelSlim 2-bit (Tencent AngelSlim)",
                    600_534_880L,
                    1_100,
                    "$bit2/Hy-MT2-1.8B-2Bit.gguf?download=true",
                    "Hy-MT2-1.8B-2Bit.gguf",
                    rec = false
                ),
                v(
                    "hy_mt2_1_8b:q4_k_m",
                    "Q4_K_M",
                    "Рекомендуемая",
                    "4-bit, официальный стандартный GGUF, лучший баланс",
                    1_133_080_448L,
                    1_800,
                    "$compact/Hy-MT2-1.8B-Q4_K_M.gguf?download=true",
                    "Hy-MT2-1.8B-Q4_K_M.gguf",
                    rec = true
                ),
                v(
                    "hy_mt2_1_8b:q6_k",
                    "Q6_K",
                    "Премиум",
                    "6-bit, почти без потерь",
                    1_474_785_120L,
                    2_300,
                    "$compact/Hy-MT2-1.8B-Q6_K.gguf?download=true",
                    "Hy-MT2-1.8B-Q6_K.gguf"
                ),
                v(
                    "hy_mt2_1_8b:q8_0",
                    "Q8_0",
                    "Максимальная точность",
                    "8-bit",
                    1_908_528_192L,
                    2_800,
                    "$compact/Hy-MT2-1.8B-Q8_0.gguf?download=true",
                    "Hy-MT2-1.8B-Q8_0.gguf"
                )
            )
        )
    }

    // ─── Hy-MT2 7B (Tencent) ─────────────────────────────────────────

    private fun hyMt2QualityFamily(): ModelFamily {
        val b = "https://huggingface.co/tencent/Hy-MT2-7B-GGUF/resolve/main"
        return ModelFamily(
            id = "hy_mt2_7b", name = "Hy-MT2 7B", developer = "Tencent",
            description = "Fast-thinking перевод, 33 языка, высокий класс качества",
            languageCount = 33, parameterSize = "7B",
            promptStyle = PromptStyle.HY_MT2, license = ModelLicense.APACHE_2,
            isSpecialized = true,
            variants = listOf(
                v(
                    "hy_mt2_7b:q4_k_m",
                    "Q4_K_M",
                    "Рекомендуемая",
                    "4-bit, качество 7B для мощных телефонов",
                    4_624_648_896L,
                    6_500,
                    "$b/Hy-MT2-7B-Q4_K_M.gguf?download=true",
                    "Hy-MT2-7B-Q4_K_M.gguf",
                    rec = true
                ),
                v(
                    "hy_mt2_7b:q6_k",
                    "Q6_K",
                    "Премиум",
                    "6-bit, выше качество, больше RAM",
                    6_164_482_720L,
                    8_500,
                    "$b/HY-MT2-7B-Q6_K.gguf?download=true",
                    "HY-MT2-7B-Q6_K.gguf"
                ),
                v(
                    "hy_mt2_7b:q8_0",
                    "Q8_0",
                    "Максимальная точность",
                    "8-bit, тяжелый режим",
                    7_981_928_896L,
                    10_500,
                    "$b/HY-MT2-7B-Q8_0.gguf?download=true",
                    "HY-MT2-7B-Q8_0.gguf"
                )
            )
        )
    }

    // ─── TranslateGemma 4B (Google) ──────────────────────────────────

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
                v("translate_gemma:q4_k_m","Q4_K_M","Рекомендуемая","Лучший баланс",2_489_909_760L,3200,"$b/$p.Q4_K_M.gguf?download=true","$p.Q4_K_M.gguf",rec = true),
                v("translate_gemma:q5_k_s","Q5_K_S","Качество 5-bit","5-bit",2_764_608_000L,3500,"$b/$p.Q5_K_S.gguf?download=true","$p.Q5_K_S.gguf"),
                v("translate_gemma:q5_k_m","Q5_K_M","Высокое качество","5-bit",2_829_713_920L,3600,"$b/$p.Q5_K_M.gguf?download=true","$p.Q5_K_M.gguf"),
                v("translate_gemma:q6_k","Q6_K","Премиум","6-bit",3_190_755_840L,4000,"$b/$p.Q6_K.gguf?download=true","$p.Q6_K.gguf"),
                v("translate_gemma:q8_0","Q8_0","Максимальная точность","8-bit",4_130_417_920L,5000,"$b/$p.Q8_0.gguf?download=true","$p.Q8_0.gguf"),
                v("translate_gemma:f16","F16","Полная","FP16",7_767_819_520L,9000,"$b/$p.f16.gguf?download=true","$p.f16.gguf")
            )
        )
    }

    // ─── Gemma 4 LiteRT (Google) ─────────────────────────────────────

    private fun gemma4LiteRtFamily(): ModelFamily {
        val b = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main"
        return ModelFamily(
            id = "gemma_4_litert", name = "Gemma 4", developer = "Google / LiteRT Community",
            description = "Official LiteRT-LM models for GPU; strict translation prompt",
            languageCount = 0, parameterSize = "E2B / E4B",
            promptStyle = PromptStyle.TRANSLATE_GEMMA, license = ModelLicense.APACHE_2,
            isSpecialized = false,
            variants = listOf(
                v(
                    "gemma_4_litert:gpu",
                    "E2B LiteRT",
                    "GPU",
                    "Official LiteRT GPU bundle, ~2 GB",
                    2_008_432_640L,
                    3_000,
                    "$b/gemma-4-E2B-it-gpu.litertlm?download=true",
                    "gemma-4-E2B-it-gpu.litertlm",
                    rec = true,
                    runtime = ModelRuntime.LITERT_LM,
                    sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
                    cpu = false,
                    gpu = true
                ),
                v(
                    "gemma_4_litert:e4b_qat",
                    "E4B LiteRT",
                    "GPU quality",
                    "Official mixed INT4/INT8 LiteRT bundle, ~3.66 GB",
                    3_659_530_240L,
                    4_000,
                    "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
                    "gemma-4-E4B-it.litertlm",
                    runtime = ModelRuntime.LITERT_LM,
                    sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
                    cpu = true,
                    gpu = true
                )
            )
        )
    }

    // ─── Helper ───────────────────────────────────────────────────────

    private fun v(
        id: String, quant: String, display: String, desc: String,
        size: Long, ram: Int, url: String, file: String, rec: Boolean = false,
        runtime: ModelRuntime = ModelRuntime.GGUF,
        sha256: String? = null,
        cpu: Boolean = true,
        gpu: Boolean = false
    ) = ModelVariant(id, quant, display, desc, size, ram, url, file, rec, runtime, sha256, cpu, gpu)
}
