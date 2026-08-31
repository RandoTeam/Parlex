package com.translive.app.data.model

enum class PackComponentType {
    NMT_TRANSLATE,      // Fast NMT on-device model (ML Kit)
    DICTIONARY_DB,      // Offline SQLite / Room lexicon
    OCR_ASSETS,         // Vision / MNN / Tesseract OCR script assets
    SPEECH_TTS_VOICE,   // Android System TTS voice readiness
    SPEECH_STT_MODEL    // Offline Whisper / Qwen3 ASR speech model
}

enum class ComponentInstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    SYSTEM_ACTION_REQUIRED,
    FAILED
}

data class PackComponent(
    val id: String,
    val name: String,
    val type: PackComponentType,
    val sizeBytes: Long,
    val status: ComponentInstallStatus = ComponentInstallStatus.NOT_INSTALLED,
    val downloadProgress: Float = 0f,
    val error: String? = null
)

enum class PackOverallStatus {
    NOT_INSTALLED,
    PARTIALLY_INSTALLED,
    DOWNLOADING,
    FULLY_INSTALLED,
    ACTION_REQUIRED
}

data class LanguagePack(
    val id: String,
    val title: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val flagEmoji: String,
    val description: String,
    val components: List<PackComponent>,
    val isPresetTravelPack: Boolean = true
) {
    val totalSizeBytes: Long get() = components.sumOf { it.sizeBytes }
    val installedSizeBytes: Long get() = components.filter { it.status == ComponentInstallStatus.INSTALLED }.sumOf { it.sizeBytes }

    val overallStatus: PackOverallStatus get() {
        if (components.any { it.status == ComponentInstallStatus.DOWNLOADING }) return PackOverallStatus.DOWNLOADING
        val installedCount = components.count { it.status == ComponentInstallStatus.INSTALLED }
        if (installedCount == components.size) return PackOverallStatus.FULLY_INSTALLED
        if (components.any { it.status == ComponentInstallStatus.SYSTEM_ACTION_REQUIRED }) return PackOverallStatus.ACTION_REQUIRED
        if (installedCount > 0) return PackOverallStatus.PARTIALLY_INSTALLED
        return PackOverallStatus.NOT_INSTALLED
    }

    val installPercentage: Int get() {
        if (components.isEmpty()) return 0
        val installedCount = components.count { it.status == ComponentInstallStatus.INSTALLED }
        return ((installedCount.toFloat() / components.size.toFloat()) * 100).toInt()
    }

    val missingDownloadSizeBytes: Long get() = components
        .filter { it.status == ComponentInstallStatus.NOT_INSTALLED || it.status == ComponentInstallStatus.FAILED }
        .sumOf { it.sizeBytes }
}

object TravelPacksCatalog {
    fun createDefaultTravelPacks(): List<LanguagePack> = listOf(
        createTravelPack(
            id = "pack_ru_en",
            title = "Русский ↔ Английский Travel Pack",
            flag = "🇷🇺 ↔ 🇬🇧",
            source = Language.RUSSIAN,
            target = Language.ENGLISH,
            description = "NMT-перевод, офлайн-словарь 85k слов, кириллический OCR, синтез и распознавание речи.",
            ocrComponent = PackComponent(
                id = "ocr_cyrillic",
                name = "PP-OCRv6 Кириллица",
                type = PackComponentType.OCR_ASSETS,
                sizeBytes = 6_200_000L
            ),
            dictComponent = PackComponent(
                id = "dict_ru_en",
                name = "RU ↔ EN Офлайн-словарь",
                type = PackComponentType.DICTIONARY_DB,
                sizeBytes = 4_500_000L
            )
        ),
        createTravelPack(
            id = "pack_vi_en",
            title = "Вьетнамский ↔ Английский Travel Pack",
            flag = "🇻🇳 ↔ 🇬🇧",
            source = Language.VIETNAMESE,
            target = Language.ENGLISH,
            description = "NMT-перевод, офлайн-словарь 60k слов, Latin OCR, вьетнамский TTS/STT.",
            ocrComponent = PackComponent(
                id = "ocr_latin",
                name = "Латинский OCR движок",
                type = PackComponentType.OCR_ASSETS,
                sizeBytes = 0L
            ),
            dictComponent = PackComponent(
                id = "dict_vi_en",
                name = "VI ↔ EN Офлайн-словарь",
                type = PackComponentType.DICTIONARY_DB,
                sizeBytes = 3_800_000L
            )
        ),
        createTravelPack(
            id = "pack_zh_en",
            title = "Китайский ↔ Английский Travel Pack",
            flag = "🇨🇳 ↔ 🇬🇧",
            source = Language.CHINESE_SIMPLIFIED,
            target = Language.ENGLISH,
            description = "NMT-перевод, пиньинь-словарь, CJK Vision OCR, путунхуа синтез речи.",
            ocrComponent = PackComponent(
                id = "ocr_cjk",
                name = "ML Kit CJK Иероглифический OCR",
                type = PackComponentType.OCR_ASSETS,
                sizeBytes = 10_500_000L
            ),
            dictComponent = PackComponent(
                id = "dict_zh_en",
                name = "ZH ↔ EN Пиньинь-словарь",
                type = PackComponentType.DICTIONARY_DB,
                sizeBytes = 5_200_000L
            )
        ),
        createTravelPack(
            id = "pack_es_en",
            title = "Испанский ↔ Английский Travel Pack",
            flag = "🇪🇸 ↔ 🇬🇧",
            source = Language.SPANISH,
            target = Language.ENGLISH,
            description = "NMT-перевод, словарь 75k слов, Latin OCR, испанский голос TTS.",
            ocrComponent = PackComponent(
                id = "ocr_latin",
                name = "Латинский OCR движок",
                type = PackComponentType.OCR_ASSETS,
                sizeBytes = 0L
            ),
            dictComponent = PackComponent(
                id = "dict_es_en",
                name = "ES ↔ EN Офлайн-словарь",
                type = PackComponentType.DICTIONARY_DB,
                sizeBytes = 4_100_000L
            )
        ),
        createTravelPack(
            id = "pack_ja_en",
            title = "Японский ↔ Английский Travel Pack",
            flag = "🇯🇵 ↔ 🇬🇧",
            source = Language.JAPANESE,
            target = Language.ENGLISH,
            description = "NMT-перевод, кандзи-ромадзи словарь, японский OCR, японский голос TTS.",
            ocrComponent = PackComponent(
                id = "ocr_japanese",
                name = "ML Kit Japanese OCR",
                type = PackComponentType.OCR_ASSETS,
                sizeBytes = 11_200_000L
            ),
            dictComponent = PackComponent(
                id = "dict_ja_en",
                name = "JA ↔ EN Офлайн-словарь",
                type = PackComponentType.DICTIONARY_DB,
                sizeBytes = 5_800_000L
            )
        ),
        createTravelPack(
            id = "pack_de_en",
            title = "Немецкий ↔ Английский Travel Pack",
            flag = "🇩🇪 ↔ 🇬🇧",
            source = Language.GERMAN,
            target = Language.ENGLISH,
            description = "NMT-перевод, немецкий словарь, Latin OCR, немецкий голос TTS.",
            ocrComponent = PackComponent(
                id = "ocr_latin",
                name = "Латинский OCR движок",
                type = PackComponentType.OCR_ASSETS,
                sizeBytes = 0L
            ),
            dictComponent = PackComponent(
                id = "dict_de_en",
                name = "DE ↔ EN Офлайн-словарь",
                type = PackComponentType.DICTIONARY_DB,
                sizeBytes = 4_300_000L
            )
        ),
        createTravelPack(
            id = "pack_fr_en",
            title = "Французский ↔ Английский Travel Pack",
            flag = "🇫🇷 ↔ 🇬🇧",
            source = Language.FRENCH,
            target = Language.ENGLISH,
            description = "NMT-перевод, французский словарь, Latin OCR, французский голос TTS.",
            ocrComponent = PackComponent(
                id = "ocr_latin",
                name = "Латинский OCR движок",
                type = PackComponentType.OCR_ASSETS,
                sizeBytes = 0L
            ),
            dictComponent = PackComponent(
                id = "dict_fr_en",
                name = "FR ↔ EN Офлайн-словарь",
                type = PackComponentType.DICTIONARY_DB,
                sizeBytes = 4_600_000L
            )
        )
    )

    private fun createTravelPack(
        id: String,
        title: String,
        flag: String,
        source: Language,
        target: Language,
        description: String,
        ocrComponent: PackComponent,
        dictComponent: PackComponent
    ): LanguagePack {
        return LanguagePack(
            id = id,
            title = title,
            sourceLanguage = source,
            targetLanguage = target,
            flagEmoji = flag,
            description = description,
            components = listOf(
                PackComponent(
                    id = "nmt_${source.code}",
                    name = "${source.displayName} NMT-пакет",
                    type = PackComponentType.NMT_TRANSLATE,
                    sizeBytes = 30_000_000L
                ),
                PackComponent(
                    id = "nmt_${target.code}",
                    name = "${target.displayName} NMT-пакет",
                    type = PackComponentType.NMT_TRANSLATE,
                    sizeBytes = 30_000_000L
                ),
                dictComponent,
                ocrComponent,
                PackComponent(
                    id = "tts_${source.code}",
                    name = "${source.displayName} TTS-голос",
                    type = PackComponentType.SPEECH_TTS_VOICE,
                    sizeBytes = 0L
                ),
                PackComponent(
                    id = "stt_${source.code}",
                    name = "Офлайн STT-модель (Whisper)",
                    type = PackComponentType.SPEECH_STT_MODEL,
                    sizeBytes = 39_000_000L
                )
            )
        )
    }
}
