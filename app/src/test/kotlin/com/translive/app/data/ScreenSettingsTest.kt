package com.translive.app.data

import com.translive.app.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSettingsTest {

    private class FakeSettingsRepository : SettingsRepository() {
        override var screenSyncTargetWithMain: Boolean = true
        override var screenTargetLanguage: Language = Language.RUSSIAN
        override var textTargetLanguage: Language = Language.ENGLISH
        override var screenA11yShortcutBehavior: ScreenA11yShortcutBehavior = ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE
    }

    @Test
    fun defaultValues_areCorrect() {
        val settings = SettingsRepository()

        assertTrue(settings.screenSyncTargetWithMain)
        assertEquals(Language.RUSSIAN, settings.screenTargetLanguage)
        assertEquals(ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE, settings.screenA11yShortcutBehavior)
    }

    @Test
    fun effectiveScreenTargetLanguage_whenSyncEnabled_tracksTextTargetLanguage() {
        val settings = FakeSettingsRepository()
        settings.screenSyncTargetWithMain = true
        settings.textTargetLanguage = Language.SPANISH
        settings.screenTargetLanguage = Language.GERMAN

        assertEquals(Language.SPANISH, settings.effectiveScreenTargetLanguage)
    }

    @Test
    fun effectiveScreenTargetLanguage_whenSyncDisabled_usesScreenTargetLanguage() {
        val settings = FakeSettingsRepository()
        settings.screenSyncTargetWithMain = false
        settings.textTargetLanguage = Language.SPANISH
        settings.screenTargetLanguage = Language.GERMAN

        assertEquals(Language.GERMAN, settings.effectiveScreenTargetLanguage)
    }

    @Test
    fun screenA11yShortcutBehavior_enumMapping_isConsistent() {
        assertEquals(
            ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE,
            ScreenA11yShortcutBehavior.fromId("single_shot_no_bubble")
        )
        assertEquals(
            ScreenA11yShortcutBehavior.TOGGLE_FLOATING_BUBBLE,
            ScreenA11yShortcutBehavior.fromId("toggle_floating_bubble")
        )
        // Fallback for null/unknown
        assertEquals(
            ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE,
            ScreenA11yShortcutBehavior.fromId(null)
        )
        assertEquals(
            ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE,
            ScreenA11yShortcutBehavior.fromId("invalid_id")
        )
    }
}
