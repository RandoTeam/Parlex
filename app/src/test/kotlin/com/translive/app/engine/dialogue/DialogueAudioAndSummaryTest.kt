package com.translive.app.engine.dialogue

import com.translive.app.data.model.AudioRecordingFormat
import com.translive.app.data.model.DialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.DialogueSessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM Unit Test Suite for Dialogue Audio Recording, Session Statistics,
 * and LiteRT Gemma 2 Summary Prompt Construction (Phase H).
 */
class DialogueAudioAndSummaryTest {

    private lateinit var sampleMessages: List<DialogueMessage>
    private lateinit var sampleSession: DialogueSession

    @Before
    fun setUp() {
        val now = 1725116400000L
        sampleSession = DialogueSession(
            id = 42L,
            languageA = "ru",
            languageB = "en",
            title = "Russian - English",
            createdAt = now,
            updatedAt = now + 120000L,
            durationMs = 120000L,
            totalTurns = 3,
            totalWords = 20,
            totalCharacters = 150,
            isRecorded = true,
            audioFilePath = "/sdcard/Android/data/com.translive.app/files/dialogues/dialogue_session_42.m4a",
            audioFormat = "AAC"
        )

        sampleMessages = listOf(
            DialogueMessage(
                id = 1L,
                sessionId = 42L,
                speaker = "RU",
                originalText = "Здравствуйте, мы готовы обсудить условия контракта.",
                translatedText = "Hello, we are ready to discuss the contract terms.",
                originalLanguage = "ru",
                translatedLanguage = "en",
                timestamp = now + 5000L,
                audioStartTimeMs = 5000L,
                audioDurationMs = 4000L,
                wordCount = 6,
                characterCount = 51
            ),
            DialogueMessage(
                id = 2L,
                sessionId = 42L,
                speaker = "EN",
                originalText = "Great. We agree with the delivery timeline and pricing.",
                translatedText = "Отлично. Мы согласны со сроками поставки и ценами.",
                originalLanguage = "en",
                translatedLanguage = "ru",
                timestamp = now + 15000L,
                audioStartTimeMs = 15000L,
                audioDurationMs = 5000L,
                wordCount = 9,
                characterCount = 55
            ),
            DialogueMessage(
                id = 3L,
                sessionId = 42L,
                speaker = "RU",
                originalText = "Отлично, подпишем соглашение завтра утром.",
                translatedText = "Great, we will sign the agreement tomorrow morning.",
                originalLanguage = "ru",
                translatedLanguage = "en",
                timestamp = now + 30000L,
                audioStartTimeMs = 30000L,
                audioDurationMs = 3500L,
                wordCount = 5,
                characterCount = 42
            )
        )
    }

    @Test
    fun testEmptySessionStatistics() {
        val stats = DialogueSessionStats.fromMessages(emptyList(), 4000L)

        assertEquals(0, stats.totalTurns)
        assertEquals(0L, stats.totalDurationMs)
        assertEquals(0, stats.totalWords)
        assertEquals(0, stats.totalCharacters)
        assertEquals(0.0, stats.speakerAlternationRate, 0.001)
        assertTrue(stats.perLanguageStats.isEmpty())
    }

    @Test
    fun testBilingualDialogueStatisticsAccurateWordAndCharCounts() {
        val stats = DialogueSessionStats.fromMessages(sampleMessages, 120000L)

        assertEquals(3, stats.totalTurns)
        assertEquals(120000L, stats.totalDurationMs)
        assertEquals(20, stats.totalWords)

        val ruStats = stats.perLanguageStats["ru"]
        assertNotNull(ruStats)
        assertEquals(2, ruStats!!.turnCount)
        assertEquals(11, ruStats.wordCount) // 6 + 5
        assertEquals(7500L, ruStats.totalAudioDurationMs) // 4000 + 3500

        val enStats = stats.perLanguageStats["en"]
        assertNotNull(enStats)
        assertEquals(1, enStats!!.turnCount)
        assertEquals(9, enStats.wordCount)
        assertEquals(5000L, enStats.totalAudioDurationMs)

        // Alternation rate: turn 0 (RU) -> turn 1 (EN) -> turn 2 (RU) => 2 switches / 2 opportunities = 100%
        assertEquals(1.0, stats.speakerAlternationRate, 0.001)
        assertEquals(20.0 / 3.0, stats.averageWordsPerTurn, 0.01)
    }

    @Test
    fun testMultilingualWithCjkWordCounting() {
        val cjkText = "你好，世界！ Parlex on-device translation is fast."
        val wordCount = DialogueSessionStats.countWords(cjkText)

        // "你好世界" (4 CJK chars = 4 words) + "Parlex", "on-device", "translation", "is", "fast." (5 words) = 9
        assertEquals(9, wordCount)
    }

    @Test
    fun testAudioFormatConfigurationsAndParameters() {
        val aac = AudioRecordingFormat.AAC
        assertEquals("m4a", aac.extension)
        assertEquals("audio/mp4a-latm", aac.mimeType)
        assertEquals(48000, aac.defaultBitrate)

        val wav = AudioRecordingFormat.WAV
        assertEquals("wav", wav.extension)
        assertEquals("audio/wav", wav.mimeType)
        assertEquals(256000, wav.defaultBitrate)

        assertEquals(AudioRecordingFormat.AAC, AudioRecordingFormat.fromId("aac"))
        assertEquals(AudioRecordingFormat.WAV, AudioRecordingFormat.fromId("WAV"))
        assertEquals(AudioRecordingFormat.AAC, AudioRecordingFormat.fromId("unknown"))
    }

    @Test
    fun testDialogueSessionEntitiesProperties() {
        assertEquals("Russian - English", sampleSession.title)
        assertTrue(sampleSession.isRecorded)
        assertEquals("AAC", sampleSession.audioFormat)
        assertEquals(120000L, sampleSession.durationMs)
        assertEquals(3, sampleSession.totalTurns)
        assertEquals(20, sampleSession.totalWords)
    }

    @Test
    fun testDialogueMessageAudioProperties() {
        val turn1 = sampleMessages[0]
        assertEquals(5000L, turn1.audioStartTimeMs)
        assertEquals(4000L, turn1.audioDurationMs)
        assertEquals(6, turn1.wordCount)
        assertEquals(51, turn1.characterCount)
        assertEquals("RU", turn1.speaker)
    }
}
