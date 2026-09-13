package com.translive.app.service

import android.graphics.Rect
import com.translive.app.ui.overlay.ArTranslatedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScreenTranslateOverlayTest {

    @Test
    fun arTranslatedBox_storesBoundingBoxAndLanguagesCorrectly() {
        val rect = Rect(10, 20, 200, 80)
        val box = ArTranslatedBox(
            rawText = "Welcome to our shop",
            translatedText = "Добро пожаловать в наш магазин",
            boundingBox = rect,
            sourceLangCode = "en",
            targetLangCode = "ru"
        )

        assertEquals("Welcome to our shop", box.rawText)
        assertEquals("Добро пожаловать в наш магазин", box.translatedText)
        assertNotNull(box.boundingBox)
        assertEquals("en", box.sourceLangCode)
        assertEquals("ru", box.targetLangCode)
    }

    @Test
    fun arTranslatedBox_supportsDefaultValues() {
        val box = ArTranslatedBox(
            rawText = "Hello",
            translatedText = "Привет",
            boundingBox = Rect(0, 0, 100, 50)
        )

        assertEquals("auto", box.sourceLangCode)
        assertEquals("ru", box.targetLangCode)
    }

    @Test
    fun screenTranslateConstants_haveExpectedActionNames() {
        assertEquals("com.translive.app.action.REQUEST_SCREEN_CAPTURE", ScreenTranslateOverlayService.ACTION_REQUEST_SCREEN_CAPTURE)
        assertEquals("com.translive.app.action.REQUEST_LIVE_TRANSLATE", ScreenTranslateOverlayService.ACTION_REQUEST_LIVE_TRANSLATE)
        assertEquals("com.translive.app.action.SHOW_SCREEN_TRANSLATION", ScreenTranslateOverlayService.ACTION_SHOW_TRANSLATION)
    }

    @Test
    fun arTranslatedBox_preservesExactCoordinatesWithoutClusteringDistortion() {
        val widget1Bounds = Rect().apply { left = 50; top = 100; right = 300; bottom = 150 }
        val widget2Bounds = Rect().apply { left = 50; top = 160; right = 200; bottom = 200 }

        val box1 = ArTranslatedBox("Header", "Заголовок", widget1Bounds, "en", "ru")
        val box2 = ArTranslatedBox("Button", "Кнопка", widget2Bounds, "en", "ru")

        assertEquals(100, box1.boundingBox.top)
        assertEquals(150, box1.boundingBox.bottom)
        assertEquals(160, box2.boundingBox.top)
        assertEquals(200, box2.boundingBox.bottom)
    }

    @Test
    fun screenTextNoiseFilter_filtersNonTranslatableUiArtifacts() {
        val candidateTexts = listOf(
            "12:45",
            "98%",
            "Settings and Profile",
            "https://example.com",
            "1,234.50",
            "Order Confirmation",
            "..."
        )

        val translatable = candidateTexts.filter { ScreenTextNoiseFilter.isTranslatable(it) }
        assertEquals(listOf("Settings and Profile", "Order Confirmation"), translatable)
    }

    @Test
    fun arTranslatedBox_supportsLanguagePairUpdate() {
        val original = ArTranslatedBox("Hello world", "Привет мир", Rect(0, 0, 100, 50), "en", "ru")
        val retranslated = original.copy(
            translatedText = "Hallo Welt",
            targetLangCode = "de"
        )

        assertEquals("Hello world", retranslated.rawText)
        assertEquals("Hallo Welt", retranslated.translatedText)
        assertEquals("en", retranslated.sourceLangCode)
        assertEquals("de", retranslated.targetLangCode)
        assertEquals(original.boundingBox.left, retranslated.boundingBox.left)
        assertEquals(original.boundingBox.top, retranslated.boundingBox.top)
        assertEquals(original.boundingBox.right, retranslated.boundingBox.right)
        assertEquals(original.boundingBox.bottom, retranslated.boundingBox.bottom)
    }
}
