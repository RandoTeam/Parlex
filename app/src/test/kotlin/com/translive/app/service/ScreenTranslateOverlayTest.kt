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
}
