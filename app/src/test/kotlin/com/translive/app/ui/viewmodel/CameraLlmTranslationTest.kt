package com.translive.app.ui.viewmodel

import com.translive.app.data.TranslationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLlmTranslationTest {

    @Test
    fun cameraTranslationMode_hasFastAndQualityEntries() {
        val modes = CameraTranslationMode.entries
        assertTrue(modes.contains(CameraTranslationMode.FAST))
        assertTrue(modes.contains(CameraTranslationMode.QUALITY))
        assertEquals(2, modes.size)
    }

    @Test
    fun translationPolicy_fromPersisted_handlesAllKeysCorrectly() {
        assertEquals(TranslationPolicy.FAST, TranslationPolicy.fromPersisted("fast"))
        assertEquals(TranslationPolicy.FAST_WITH_LLM_IMPROVE, TranslationPolicy.fromPersisted("fast_with_llm_improve"))
        assertEquals(TranslationPolicy.LLM_ONLY, TranslationPolicy.fromPersisted("llm_only"))
        assertEquals(TranslationPolicy.FAST_WITH_LLM_IMPROVE, TranslationPolicy.fromPersisted(null))
        assertEquals(TranslationPolicy.FAST_WITH_LLM_IMPROVE, TranslationPolicy.fromPersisted("unknown"))
    }
}
