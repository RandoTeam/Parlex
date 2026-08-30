package com.translive.app.engine.camera

import android.graphics.Rect
import com.translive.app.data.model.DictionaryEntry
import com.translive.app.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTravelModeTest {

    @Test
    fun testLowLightDetectionOnSyntheticDarkFrame() {
        val analyzer = CameraEnvironmentQualityAnalyzer(
            gridDim = 8,
            lowLightLumaThreshold = 45f
        )
        // 8x8 dark grid (luma = 20/255)
        val darkSamples = FloatArray(64) { 20f / 255f }
        val output = analyzer.analyzeSampleArray(darkSamples, timestampMs = 1000L)

        assertTrue(output.isLowLight)
        assertTrue(output.averageLuma < 45f)
    }

    @Test
    fun testMotionShakeAndStabilizationDebounce() {
        val analyzer = CameraEnvironmentQualityAnalyzer(
            gridDim = 8,
            motionThreshold = 0.03f
        )

        val frame1 = FloatArray(64) { 0.5f }
        val output1 = analyzer.analyzeSampleArray(frame1, timestampMs = 1000L)
        assertFalse(output1.isShaking) // First frame establishes baseline

        // Massive shake delta
        val frame2 = FloatArray(64) { if (it % 2 == 0) 0.9f else 0.1f }
        val output2 = analyzer.analyzeSampleArray(frame2, timestampMs = 1050L)
        assertTrue(output2.isShaking)
        assertFalse(output2.canTriggerOcr) // OCR suppressed during active shake

        // Stabilized frame (identical to frame 2)
        val frame3 = FloatArray(64) { if (it % 2 == 0) 0.9f else 0.1f }
        val output3 = analyzer.analyzeSampleArray(frame3, timestampMs = 1100L)
        assertFalse(output3.isShaking)
        assertFalse(output3.canTriggerOcr) // Not yet reached 180ms stability window

        // Frame after 200ms of stability
        val output4 = analyzer.analyzeSampleArray(frame3, timestampMs = 1300L)
        assertFalse(output4.isShaking)
        assertTrue(output4.canTriggerOcr)
    }

    @Test
    fun testAllergenClassifierMultiLanguage() {
        // English & Vietnamese seafood + nuts
        val menu1 = "Grilled salmon with crushed peanuts and spicy chili"
        val allergens1 = AllergenClassifier.detectAllergens(menu1)
        assertTrue(allergens1.contains(FoodAllergen.SEAFOOD))
        assertTrue(allergens1.contains(FoodAllergen.NUTS))
        assertTrue(allergens1.contains(FoodAllergen.SPICY))

        // Russian dairy + gluten
        val menu2 = "Свежий пшеничный хлеб со сливочным сыром и маслом"
        val allergens2 = AllergenClassifier.detectAllergens(menu2)
        assertTrue(allergens2.contains(FoodAllergen.GLUTEN))
        assertTrue(allergens2.contains(FoodAllergen.DAIRY))

        // Italian seafood & vegetarian
        val menu3 = "Pizza ai frutti di mare e insalata vegetariana"
        val allergens3 = AllergenClassifier.detectAllergens(menu3)
        assertTrue(allergens3.contains(FoodAllergen.SEAFOOD))
        assertTrue(allergens3.contains(FoodAllergen.VEGETARIAN))

        // Pork & Bacon
        val menu4 = "Crispy bacon and pork sausage"
        val allergens4 = AllergenClassifier.detectAllergens(menu4)
        assertTrue(allergens4.contains(FoodAllergen.PORK))
    }

    @Test
    fun testTravelCardUiStateInstantiation() {
        val entry = DictionaryEntry(
            headword = "croissant",
            normalizedHeadword = "croissant",
            sourceLang = "fr",
            targetLang = "ru",
            definition = "круассан",
            partOfSpeech = "noun",
            pronunciation = "/kʁwa.sɑ̃/"
        )

        val state = TravelCardUiState(
            originalText = "Croissant au beurre",
            translatedText = "Круассан с маслом",
            sourceLanguage = Language.FRENCH,
            targetLanguage = Language.RUSSIAN,
            boundingBox = Rect(10, 20, 100, 80),
            currencyConversion = "≈ 250 ₽",
            dictionaryEntries = listOf(entry),
            isFavorite = true
        )

        assertEquals("Croissant au beurre", state.originalText)
        assertEquals("Круассан с маслом", state.translatedText)
        assertEquals(Language.FRENCH, state.sourceLanguage)
        assertEquals(Language.RUSSIAN, state.targetLanguage)
        assertEquals("≈ 250 ₽", state.currencyConversion)
        assertEquals(1, state.dictionaryEntries.size)
        assertTrue(state.isFavorite)
    }
}
