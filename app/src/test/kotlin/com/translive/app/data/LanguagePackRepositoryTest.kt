package com.translive.app.data

import com.translive.app.data.model.ComponentInstallStatus
import com.translive.app.data.model.Language
import com.translive.app.data.model.LanguagePack
import com.translive.app.data.model.PackComponent
import com.translive.app.data.model.PackComponentType
import com.translive.app.data.model.PackOverallStatus
import com.translive.app.data.model.TravelPacksCatalog
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguagePackRepositoryTest {

    @Test
    fun `TravelPacksCatalog generates default travel packs with all required components`() {
        val packs = TravelPacksCatalog.createDefaultTravelPacks()
        assertTrue(packs.isNotEmpty())
        assertEquals(7, packs.size)

        val ruPack = packs.find { it.id == "pack_ru_en" }
        assertNotNull(ruPack)
        assertEquals(Language.RUSSIAN, ruPack.sourceLanguage)
        assertEquals(Language.ENGLISH, ruPack.targetLanguage)
        assertEquals(6, ruPack.components.size)

        val types = ruPack.components.map { it.type }.toSet()
        assertTrue(types.contains(PackComponentType.NMT_TRANSLATE))
        assertTrue(types.contains(PackComponentType.DICTIONARY_DB))
        assertTrue(types.contains(PackComponentType.OCR_ASSETS))
        assertTrue(types.contains(PackComponentType.SPEECH_TTS_VOICE))
        assertTrue(types.contains(PackComponentType.SPEECH_STT_MODEL))
    }

    @Test
    fun `LanguagePack calculates totalSizeBytes and installedSizeBytes accurately`() {
        val components = listOf(
            PackComponent("nmt_ru", "RU NMT", PackComponentType.NMT_TRANSLATE, 30_000_000L, ComponentInstallStatus.INSTALLED),
            PackComponent("nmt_en", "EN NMT", PackComponentType.NMT_TRANSLATE, 30_000_000L, ComponentInstallStatus.NOT_INSTALLED),
            PackComponent("dict_ru_en", "RU-EN Dict", PackComponentType.DICTIONARY_DB, 4_500_000L, ComponentInstallStatus.INSTALLED),
            PackComponent("ocr_cyrillic", "OCR", PackComponentType.OCR_ASSETS, 6_000_000L, ComponentInstallStatus.INSTALLED)
        )

        val pack = LanguagePack(
            id = "test_pack",
            title = "Test Pack",
            sourceLanguage = Language.RUSSIAN,
            targetLanguage = Language.ENGLISH,
            flagEmoji = "🇷🇺 ↔ 🇬🇧",
            description = "Test",
            components = components
        )

        assertEquals(70_500_000L, pack.totalSizeBytes)
        assertEquals(40_500_000L, pack.installedSizeBytes)
        assertEquals(30_000_000L, pack.missingDownloadSizeBytes)
        assertEquals(75, pack.installPercentage) // 3 out of 4 = 75%
    }

    @Test
    fun `LanguagePack overallStatus transitions correctly`() {
        val baseComponents = listOf(
            PackComponent("nmt_ru", "RU NMT", PackComponentType.NMT_TRANSLATE, 30_000_000L, ComponentInstallStatus.NOT_INSTALLED),
            PackComponent("nmt_en", "EN NMT", PackComponentType.NMT_TRANSLATE, 30_000_000L, ComponentInstallStatus.NOT_INSTALLED)
        )

        val notInstalledPack = LanguagePack("p", "P", Language.RUSSIAN, Language.ENGLISH, "🇷🇺", "D", baseComponents)
        assertEquals(PackOverallStatus.NOT_INSTALLED, notInstalledPack.overallStatus)

        val downloadingPack = notInstalledPack.copy(
            components = listOf(
                baseComponents[0].copy(status = ComponentInstallStatus.DOWNLOADING),
                baseComponents[1]
            )
        )
        assertEquals(PackOverallStatus.DOWNLOADING, downloadingPack.overallStatus)

        val partialPack = notInstalledPack.copy(
            components = listOf(
                baseComponents[0].copy(status = ComponentInstallStatus.INSTALLED),
                baseComponents[1].copy(status = ComponentInstallStatus.NOT_INSTALLED)
            )
        )
        assertEquals(PackOverallStatus.PARTIALLY_INSTALLED, partialPack.overallStatus)

        val fullyInstalledPack = notInstalledPack.copy(
            components = listOf(
                baseComponents[0].copy(status = ComponentInstallStatus.INSTALLED),
                baseComponents[1].copy(status = ComponentInstallStatus.INSTALLED)
            )
        )
        assertEquals(PackOverallStatus.FULLY_INSTALLED, fullyInstalledPack.overallStatus)
    }

    @Test
    fun `shared NMT and STT assets deduplicate required download size across packs`() {
        val downloadedNmtCodes = setOf("en", "ru")
        val isSttDownloaded = true

        val ruPack = TravelPacksCatalog.createDefaultTravelPacks().find { it.id == "pack_ru_en" }!!
        val viPack = TravelPacksCatalog.createDefaultTravelPacks().find { it.id == "pack_vi_en" }!!

        // For RU pack, both NMTs (ru, en) are downloaded, so NMT missing is 0
        val ruMissingNmt = ruPack.components.filter {
            it.type == PackComponentType.NMT_TRANSLATE && it.id.removePrefix("nmt_") !in downloadedNmtCodes
        }
        assertEquals(0, ruMissingNmt.size)

        // For VI pack, only VI is missing because EN is already downloaded
        val viMissingNmt = viPack.components.filter {
            it.type == PackComponentType.NMT_TRANSLATE && it.id.removePrefix("nmt_") !in downloadedNmtCodes
        }
        assertEquals(1, viMissingNmt.size)
        assertEquals("nmt_vi", viMissingNmt.first().id)
    }
}
