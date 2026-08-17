package com.translive.app.engine

import com.translive.app.data.model.Language
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraLanguageMatrixTest {
    @Test
    fun `matrix contains every directed pair`() {
        val matrix = CameraLanguageMatrix.all { code ->
            code.takeIf { it in setOf("en", "ru", "fr", "zh") }
        }
        val expected = Language.allLanguages.size * (Language.allLanguages.size - 1)
        assertEquals(expected, matrix.size)
        assertEquals(expected, matrix.map { it.source.code to it.target.code }.toSet().size)
        assertTrue(matrix.any { it.source == Language.RUSSIAN && it.target == Language.ENGLISH })
        assertTrue(matrix.any { it.source == Language.ENGLISH && it.target == Language.RUSSIAN })
        assertTrue(matrix.any { it.source == Language.CANTONESE && it.route == CameraLanguageMatrix.Route.LOCAL_LLM })
    }

    @Test
    fun `fast pair requires both reusable packages`() {
        val pair = CameraLanguageMatrix.forPair(Language.RUSSIAN, Language.ENGLISH) { code ->
            code.takeIf { it == "ru" || it == "en" }
        }
        assertEquals(CameraLanguageMatrix.Route.FAST_PACKAGE, pair.route)
        assertEquals(setOf("ru", "en"), pair.requiredFastPackages)
    }

    @Test
    fun `same language needs no package and unsupported languages keep local fallback`() {
        val same = CameraLanguageMatrix.forPair(Language.RUSSIAN, Language.RUSSIAN) { "ru" }
        assertTrue(same.isSameLanguage)
        assertEquals(CameraLanguageMatrix.Route.FAST_PACKAGE, same.route)
        assertTrue(same.requiredFastPackages.isEmpty())

        Language.allLanguages
            .filter { it !in setOf(Language.RUSSIAN, Language.ENGLISH) }
            .forEach { language ->
                val forward = CameraLanguageMatrix.forPair(Language.RUSSIAN, language) { code ->
                    code.takeIf { it == "ru" || it == "en" }
                }
                val reverse = CameraLanguageMatrix.forPair(language, Language.RUSSIAN) { code ->
                    code.takeIf { it == "ru" || it == "en" }
                }
                assertEquals(CameraLanguageMatrix.Route.LOCAL_LLM, forward.route)
                assertEquals(CameraLanguageMatrix.Route.LOCAL_LLM, reverse.route)
            }
    }
}
