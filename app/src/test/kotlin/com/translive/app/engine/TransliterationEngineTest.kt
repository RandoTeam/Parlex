package com.translive.app.engine

import com.translive.app.data.model.Language
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransliterationEngineTest {

    private val engine = TransliterationEngine()

    @Test
    fun `isLatinLanguage returns true for latin script languages`() {
        assertTrue(engine.isLatinLanguage("en"))
        assertTrue(engine.isLatinLanguage("fr"))
        assertTrue(engine.isLatinLanguage("de"))
        assertTrue(engine.isLatinLanguage("es"))
        assertTrue(engine.isLatinLanguage("ru") == false)
        assertTrue(engine.isLatinLanguage("zh") == false)
        assertTrue(engine.isLatinLanguage("ja") == false)
        assertTrue(engine.isLatinLanguage("hi") == false)
        assertTrue(engine.isLatinLanguage("ar") == false)
    }

    @Test
    fun `latin text returns null transliteration`() {
        val result = engine.transliterate("Hello world, how are you?", Language.ENGLISH)
        assertNull(result)
    }

    @Test
    fun `blank text returns null transliteration`() {
        assertNull(engine.transliterate("", Language.RUSSIAN))
        assertNull(engine.transliterate("   ", Language.RUSSIAN))
    }
}
