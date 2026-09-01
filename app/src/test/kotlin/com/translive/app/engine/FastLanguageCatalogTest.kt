package com.translive.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Sub-Phase M2:
 * 59-Language Mapping & Catalog Expansion in FastTranslateEngine.
 */
class FastLanguageCatalogTest {

    private val engine = FastTranslateEngine()

    @Test
    fun toMlKitLang_mapsCoreWorldLanguages() {
        assertEquals("ru", engine.toMlKitLang("ru"))
        assertEquals("en", engine.toMlKitLang("en"))
        assertEquals("zh", engine.toMlKitLang("zh"))
        assertEquals("es", engine.toMlKitLang("es"))
        assertEquals("de", engine.toMlKitLang("de"))
        assertEquals("fr", engine.toMlKitLang("fr"))
        assertEquals("vi", engine.toMlKitLang("vi"))
        assertEquals("ja", engine.toMlKitLang("ja"))
        assertEquals("ko", engine.toMlKitLang("ko"))
    }

    @Test
    fun toMlKitLang_mapsExtendedEuropeanAndRegionalLanguages() {
        assertEquals("eo", engine.toMlKitLang("eo")) // Esperanto
        assertEquals("sk", engine.toMlKitLang("sk")) // Slovak
        assertEquals("sl", engine.toMlKitLang("sl")) // Slovenian
        assertEquals("bg", engine.toMlKitLang("bg")) // Bulgarian
        assertEquals("hr", engine.toMlKitLang("hr")) // Croatian
        assertEquals("lt", engine.toMlKitLang("lt")) // Lithuanian
        assertEquals("lv", engine.toMlKitLang("lv")) // Latvian
        assertEquals("et", engine.toMlKitLang("et")) // Estonian
        assertEquals("fi", engine.toMlKitLang("fi")) // Finnish
        assertEquals("hu", engine.toMlKitLang("hu")) // Hungarian
        assertEquals("ro", engine.toMlKitLang("ro")) // Romanian
        assertEquals("sv", engine.toMlKitLang("sv")) // Swedish
        assertEquals("no", engine.toMlKitLang("no")) // Norwegian
        assertEquals("da", engine.toMlKitLang("da")) // Danish
        assertEquals("el", engine.toMlKitLang("el")) // Greek
        assertEquals("is", engine.toMlKitLang("is")) // Icelandic
        assertEquals("ga", engine.toMlKitLang("ga")) // Irish
        assertEquals("cy", engine.toMlKitLang("cy")) // Welsh
        assertEquals("ca", engine.toMlKitLang("ca")) // Catalan
        assertEquals("gl", engine.toMlKitLang("gl")) // Galician
        assertEquals("sq", engine.toMlKitLang("sq")) // Albanian
        assertEquals("mk", engine.toMlKitLang("mk")) // Macedonian
        assertEquals("be", engine.toMlKitLang("be")) // Belarusian
        assertEquals("ka", engine.toMlKitLang("ka")) // Georgian
        assertEquals("sw", engine.toMlKitLang("sw")) // Swahili
        assertEquals("af", engine.toMlKitLang("af")) // Afrikaans
        assertEquals("ht", engine.toMlKitLang("ht")) // Haitian Creole
        assertEquals("kn", engine.toMlKitLang("kn")) // Kannada
        assertEquals("mt", engine.toMlKitLang("mt")) // Maltese
    }

    @Test
    fun toMlKitLang_unsupportedDialectsReturnNullOrFallback() {
        assertNull(engine.toMlKitLang("my")) // Burmese (not in ML Kit)
        assertNull(engine.toMlKitLang("km")) // Khmer (not in ML Kit)
        assertNull(engine.toMlKitLang("mn")) // Mongolian (not in ML Kit)
        assertNull(engine.toMlKitLang("hy")) // Armenian (not in ML Kit)
        assertNull(engine.toMlKitLang("az")) // Azerbaijani (not in ML Kit)
        assertNull(engine.toMlKitLang("kk")) // Kazakh (not in ML Kit)
    }
}
