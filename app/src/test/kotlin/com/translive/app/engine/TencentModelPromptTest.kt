package com.translive.app.engine

import com.translive.app.data.model.ModelCatalog
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.PromptStyle
import com.translive.app.data.model.TranslationProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentModelPromptTest {

    @Test
    fun testTencentModelFamiliesExistInCatalog() {
        val families = ModelCatalog.ALL_FAMILIES
        val hyMt15 = families.find { it.id == "hy_mt" }
        val hyMt2Mobile = families.find { it.id == "hy_mt2_1_8b" }
        val hyMt2Quality = families.find { it.id == "hy_mt2_7b" }

        assertNotNull("HY-MT 1.5 family must exist", hyMt15)
        assertNotNull("Hy-MT2 1.8B mobile family must exist", hyMt2Mobile)
        assertNotNull("Hy-MT2 7B quality family must exist", hyMt2Quality)

        assertEquals(PromptStyle.HY_MT, hyMt15!!.promptStyle)
        assertEquals(PromptStyle.HY_MT2, hyMt2Mobile!!.promptStyle)
        assertEquals(PromptStyle.HY_MT2, hyMt2Quality!!.promptStyle)

        assertEquals("Tencent", hyMt15.developer)
        assertEquals("Tencent", hyMt2Mobile.developer)
        assertEquals("Tencent", hyMt2Quality.developer)
    }

    @Test
    fun testTencentRecommendedVariantsAreStandardGguf() {
        val hyMt2Mobile = ModelCatalog.ALL_FAMILIES.find { it.id == "hy_mt2_1_8b" }!!
        val recVariant = hyMt2Mobile.variants.find { it.isRecommended }

        assertNotNull("Hy-MT2 1.8B must have a recommended variant", recVariant)
        assertEquals("Q4_K_M", recVariant!!.quantName)
        assertEquals(ModelRuntime.GGUF, recVariant.runtime)
        assertTrue("Recommended URL must point to official Hugging Face GGUF", recVariant.downloadUrl.contains("tencent/Hy-MT2-1.8B-GGUF"))
    }

    @Test
    fun testTranslationProfilesForTencent() {
        val hyMt2Mobile = ModelCatalog.ALL_FAMILIES.find { it.id == "hy_mt2_1_8b" }!!
        val profile = TranslationProfiles.forModel(hyMt2Mobile, ModelRuntime.GGUF)

        assertEquals("hy-mt2", profile.id)
        assertEquals(PromptStyle.HY_MT2, profile.promptStyle)
        assertEquals(0.0f, profile.sampling.temperature, 0.001f)
        assertTrue(profile.useChatTemplate)
    }
}
