package com.translive.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPrefixProtectorTest {

    @Test
    fun `test bullet and emoji prefix detachment`() {
        val input = "• 🔒 Private by default — Most AI apps"
        val split = TextPrefixProtector.split(input)

        assertEquals("• 🔒 ", split.prefix)
        assertEquals("Private by default — Most AI apps", split.cleanText)

        val recombined = TextPrefixProtector.recombine(split.prefix, "Приватно по умолчанию — Большинство приложений")
        assertEquals("• 🔒 Приватно по умолчанию — Большинство приложений", recombined)
    }

    @Test
    fun `test airplane emoji and bullet detachment`() {
        val input = "• ✈️ Works offline — No internet needed"
        val split = TextPrefixProtector.split(input)

        assertEquals("• ✈️ ", split.prefix)
        assertEquals("Works offline — No internet needed", split.cleanText)
    }

    @Test
    fun `test phone emoji and bullet detachment`() {
        val input = "• 📱 Runs on hardware you already own"
        val split = TextPrefixProtector.split(input)

        assertEquals("• 📱 ", split.prefix)
        assertEquals("Runs on hardware you already own", split.cleanText)
    }

    @Test
    fun `test numbered list prefix`() {
        val input = "1. First step in the guide"
        val split = TextPrefixProtector.split(input)

        assertEquals("1. ", split.prefix)
        assertEquals("First step in the guide", split.cleanText)
    }

    @Test
    fun `test no prefix returns unchanged text`() {
        val input = "Why PocketPal AI?"
        val split = TextPrefixProtector.split(input)

        assertEquals("", split.prefix)
        assertEquals("Why PocketPal AI?", split.cleanText)
    }

    @Test
    fun `test only bullet and emoji returns empty clean text`() {
        val input = "• 🔒"
        val split = TextPrefixProtector.split(input)

        assertEquals("• 🔒", split.prefix)
        assertEquals("", split.cleanText)
    }

    @Test
    fun `test tech glossary post processor fixes calques`() {
        val badNmt1 = "Этот проект построен на открытом воздухе для всех."
        val fixed1 = NmtTechGlossaryPostProcessor.postProcess(badNmt1)
        assertEquals("Этот проект разрабатывается открыто для всех.", fixed1)

        val badNmt2 = "PocketPal Flips, что вокруг: AI живет на телефоне"
        val fixed2 = NmtTechGlossaryPostProcessor.postProcess(badNmt2)
        assertEquals("PocketPal меняет этот подход: AI живет на телефоне", fixed2)

        val badNmt3 = "и ваши разговоры никогда не оставляют ее."
        val fixed3 = NmtTechGlossaryPostProcessor.postProcess(badNmt3)
        assertEquals("и ваши разговоры никогда не покидают его.", fixed3)

        val badNmt4 = "Большинство приложений AI - это тонкое окно на чужом сервере"
        val fixed4 = NmtTechGlossaryPostProcessor.postProcess(badNmt4)
        assertEquals("Большинство приложений AI - это простая оболочка для чужого сервера", fixed4)
    }
}
