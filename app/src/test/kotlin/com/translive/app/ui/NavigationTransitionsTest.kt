package com.translive.app.ui

import com.translive.app.data.model.Language
import com.translive.app.i18n.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTransitionsTest {

    @Test
    fun appLocale_supportedLanguages_containsExpectedLocales() {
        val supported = AppLocale.supportedLanguageCodes
        assertTrue(supported.contains("ru"))
        assertTrue(supported.contains("en"))
        assertTrue(supported.contains("zh-CN"))
        assertTrue(supported.contains("zh-TW"))
        assertTrue(supported.contains("system"))
    }

    @Test
    fun languagesMatrix_allLanguages_containFlagsAndDisplayNames() {
        val languages = Language.allLanguages
        assertTrue(languages.isNotEmpty())
        for (lang in languages) {
            assertNotNull(lang.code)
            assertNotNull(lang.displayName)
            assertNotNull(lang.nativeName)
            assertNotNull(lang.flag)
            assertTrue(lang.code.isNotBlank())
            assertTrue(lang.displayName.isNotBlank())
            assertTrue(lang.flag.isNotBlank())
        }
    }
}
