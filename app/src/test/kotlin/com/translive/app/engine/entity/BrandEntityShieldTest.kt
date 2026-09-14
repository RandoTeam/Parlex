package com.translive.app.engine.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandEntityShieldTest {

    @Test
    fun isExactEntity_detectsKnownBrands() {
        assertTrue(BrandEntityShield.isExactEntity("Android Police"))
        assertTrue(BrandEntityShield.isExactEntity("android police"))
        assertTrue(BrandEntityShield.isExactEntity("DuckDuckGo"))
        assertTrue(BrandEntityShield.isExactEntity("YouTube"))
        assertTrue(BrandEntityShield.isExactEntity("Valnet"))
        assertTrue(BrandEntityShield.isExactEntity("Google"))
        assertFalse(BrandEntityShield.isExactEntity("Random text"))
        assertFalse(BrandEntityShield.isExactEntity("Police officer"))
    }

    @Test
    fun shieldEntities_replacesMultiWordBrandsWithTokens() {
        val input = "Android Police is focused on Android and Google."
        val (shielded, map) = BrandEntityShield.shieldEntities(input)

        // "Android Police" should be matched before "Android"
        assertTrue(shielded.contains("[[ENTITY_"))
        assertFalse(shielded.startsWith("Android Police"))
        assertEquals(3, map.size) // "Android Police", "Android", "Google"
    }

    @Test
    fun unshieldEntities_restoresOriginalTokensCleanly() {
        val input = "Android Police is focused on Android and Google."
        val (shielded, map) = BrandEntityShield.shieldEntities(input)

        // Simulate MT translating surrounding words while preserving tokens
        val simulatedTranslated = shielded
            .replace("is focused on", "сфокусирован на")
            .replace("and", "и")

        val restored = BrandEntityShield.unshieldEntities(simulatedTranslated, map)

        assertEquals("Android Police сфокусирован на Android и Google.", restored)
    }

    @Test
    fun unshieldEntities_handlesSpacesInjectedByMtEngine() {
        val map = mapOf("[[ENTITY_0]]" to "Android Police")
        val mutatedToken1 = "Читайте [ [ ENTITY_0 ] ] сегодня"
        val restored = BrandEntityShield.unshieldEntities(mutatedToken1, map)
        assertEquals("Читайте Android Police сегодня", restored)
    }

    @Test
    fun unshieldEntities_handlesDroppedOpeningBracketAndLowercase() {
        // Real-world Google ML Kit NMT output observed on OnePlus 13
        val map = mapOf("[[ENTITY_0]]" to "Android Police")
        val mutatedToken = "[Entity_0]]: Android Новости, отзывы, руководства"
        val restored = BrandEntityShield.unshieldEntities(mutatedToken, map)
        assertEquals("Android Police: Android Новости, отзывы, руководства", restored)
    }

    @Test
    fun unshieldEntities_handlesSingleBracketAndBareToken() {
        val map = mapOf("[[ENTITY_0]]" to "Android Police")
        assertEquals("Android Police", BrandEntityShield.unshieldEntities("[Entity_0]", map))
        assertEquals("Android Police", BrandEntityShield.unshieldEntities("entity_0", map))
    }
}
