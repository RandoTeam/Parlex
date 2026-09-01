package com.translive.app.engine.dialogue

import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.DialogueMessage as DbDialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM Unit Test Suite for Dialogue Auto-Speak, Reverb Cooldown,
 * and Manual Bubble TTS Playback (Sub-phase D3.1).
 *
 * Verification Scope:
 * 1. Auto-speak ON: Turn completion invokes TTS, suppresses AEC, performs 300ms cooldown, resets VAD.
 * 2. Auto-speak OFF: Turn completion records message, skips TTS, skips cooldown, transitions immediately to LISTENING.
 * 3. Manual Bubble Speak: On-demand speakMessage() functions identically whether auto-speak is ON or OFF.
 * 4. Toggle & Persistence: Updating auto-speak mutates StateFlow UiState and persists to SettingsRepository.
 * 5. Zero-Emoji Compliance: Absolute zero emoji in fixtures, payloads, logs, and assertions.
 *
 * 100% Pure JVM (No Android Context, AudioTrack, or Robolectric dependencies).
 */
class DialogueAutoSpeakTest {

    enum class DialoguePhase {
        IDLE,
        LISTENING,
        RECOGNIZING,
        TRANSLATING,
        SPEAKING,
        ERROR
    }

    data class DialogueUiMessage(
        val sourceText: String,
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String,
        val sourceTransliteration: String? = null,
        val targetTransliteration: String? = null,
        val isLlmRefined: Boolean = false,
        val isImproving: Boolean = false,
        val dbMessageId: Long? = null
    ) {
        val directionTag: String
            get() = "" + sourceLang.uppercase() + " -> " + targetLang.uppercase()
    }

    data class DialogueUiState(
        val messages: List<DialogueUiMessage> = emptyList(),
        val phase: DialoguePhase = DialoguePhase.IDLE,
        val isConversationActive: Boolean = false,
        val isTranslationModelReady: Boolean = true,
        val isSttReady: Boolean = true,
        val isTtsReady: Boolean = true,
        val isAutoSpeakEnabled: Boolean = true,
        val hasMicPermission: Boolean = true,
        val sourceLanguage: Language = Language.RUSSIAN,
        val targetLanguage: Language = Language.ENGLISH,
        val error: String? = null
    )

    data class DialogueTurnRecord(
        val turnIndex: Int,
        val sourceLanguage: Language,
        val targetLanguage: Language,
        val sourceText: String,
        val translatedText: String,
        val wasAutoSpoken: Boolean,
        val cooldownAppliedMs: Long
    )

    class VirtualTimeProvider(private var currentTimeMs: Long = 0L) {
        fun now(): Long = currentTimeMs
        fun advanceTimeBy(deltaMs: Long) { currentTimeMs += deltaMs }
        fun setTime(timeMs: Long) { currentTimeMs = timeMs }
    }

    class FakeSettingsRepository {
        var dialogueAutoSpeak: Boolean = true
        var dialogueSourceLanguage: Language = Language.RUSSIAN
        var dialogueTargetLanguage: Language = Language.ENGLISH
        var showTransliteration: Boolean = false
        var translationPolicy: TranslationPolicy = TranslationPolicy.FAST
    }

    class FakeSpeechEngine {
        var isModelsDownloaded: Boolean = true
        var isListening: Boolean = false
        var isAecSuppressed: Boolean = false
        var vadResetCount: Int = 0
        val eventLog = mutableListOf<String>()

        fun areModelsDownloaded(): Boolean = isModelsDownloaded

        fun notifyTtsPlaybackStarted() {
            isAecSuppressed = true
            eventLog.add("AEC_SUPPRESS_START")
        }

        fun notifyTtsPlaybackFinished() {
            isAecSuppressed = false
            eventLog.add("AEC_SUPPRESS_END")
        }

        fun resetVad() {
            vadResetCount++
            eventLog.add("VAD_RESET")
        }
    }

    class FakeSystemTtsEngine(
        private val timeProvider: VirtualTimeProvider,
        val playbackDurationMs: Long = 800L
    ) {
        var isSpeaking: Boolean = false
        var lastSpokenText: String? = null
        var lastSpokenLang: String? = null
        var speakInvocationCount: Int = 0
        var speakAndWaitInvocationCount: Int = 0
        val speechLog = mutableListOf<Pair<String, String>>()

        fun speak(text: String, langCode: String) {
            speakInvocationCount++
            isSpeaking = true
            lastSpokenText = text
            lastSpokenLang = langCode
            speechLog.add(text to langCode)
        }

        fun speakAndWait(text: String, langCode: String) {
            speakAndWaitInvocationCount++
            isSpeaking = true
            lastSpokenText = text
            lastSpokenLang = langCode
            speechLog.add(text to langCode)
            timeProvider.advanceTimeBy(playbackDurationMs)
            isSpeaking = false
        }

        fun stop() {
            isSpeaking = false
        }
    }

    class FakeFastTranslateEngine {
        fun translate(text: String, from: String, to: String): String {
            return when {
                from == "ru" && to == "en" && text == "Здравствуйте" -> "Hello"
                from == "ru" && to == "en" && text == "Где находится вокзал?" -> "Where is the station?"
                from == "ru" && to == "en" && text == "Спасибо" -> "Thank you"
                from == "en" && to == "ru" && text == "Hello" -> "Здравствуйте"
                from == "en" && to == "ru" && text == "The central station is straight ahead" -> "Центральный вокзал прямо по курсу"
                from == "en" && to == "ru" && text == "You are welcome" -> "Пожалуйста"
                from == "vi" && to == "en" && text == "Xin chào" -> "Hello"
                from == "en" && to == "vi" && text == "Hello" -> "Xin chào"
                else -> "Translated[" + from + "->" + to + "]: " + text
            }
        }
    }

    class FakeDialogueDao {
        private var nextSessionId = 1L
        private var nextMessageId = 1L
        val sessions = mutableMapOf<Long, DialogueSession>()
        val messages = mutableListOf<DbDialogueMessage>()

        fun insertSession(session: DialogueSession): Long {
            val id = nextSessionId++
            sessions[id] = session.copy(id = id)
            return id
        }

        fun insertMessage(message: DbDialogueMessage): Long {
            val id = nextMessageId++
            val saved = message.copy(id = id)
            messages.add(saved)
            return id
        }

        fun updateSessionTime(sessionId: Long) {
            val s = sessions[sessionId]
            if (s != null) {
                sessions[sessionId] = s.copy(updatedAt = System.currentTimeMillis())
            }
        }
    }

    class DialogueAutoSpeakHarness(
        private val settings: FakeSettingsRepository,
        private val speechEngine: FakeSpeechEngine,
        private val ttsEngine: FakeSystemTtsEngine,
        private val fastEngine: FakeFastTranslateEngine,
        private val dialogueDao: FakeDialogueDao,
        private val arbiter: DialogueLanguageArbiter,
        private val timeProvider: VirtualTimeProvider,
        val reverbCooldownMs: Long = 300L
    ) {
        private val _uiState = MutableStateFlow(
            DialogueUiState(
                sourceLanguage = settings.dialogueSourceLanguage,
                targetLanguage = settings.dialogueTargetLanguage,
                isAutoSpeakEnabled = settings.dialogueAutoSpeak
            )
        )
        val uiState = _uiState.asStateFlow()

        var currentSessionId: Long? = null
        var turnContext = DialogueTurnContext.EMPTY
        val history = mutableListOf<DialogueTurnRecord>()
        val phaseHistory = mutableListOf<DialoguePhase>()

        init {
            recordPhase(DialoguePhase.IDLE)
        }

        private fun recordPhase(phase: DialoguePhase) {
            _uiState.update { it.copy(phase = phase) }
            phaseHistory.add(phase)
        }

        fun toggleAutoSpeak() {
            val updated = !_uiState.value.isAutoSpeakEnabled
            _uiState.update { it.copy(isAutoSpeakEnabled = updated) }
            settings.dialogueAutoSpeak = updated
        }

        fun setAutoSpeak(enabled: Boolean) {
            _uiState.update { it.copy(isAutoSpeakEnabled = enabled) }
            settings.dialogueAutoSpeak = enabled
        }

        fun startConversation() {
            if (_uiState.value.isConversationActive) return
            _uiState.update {
                it.copy(
                    isConversationActive = true,
                    error = null
                )
            }
            recordPhase(DialoguePhase.LISTENING)
            speechEngine.isListening = true

            val session = DialogueSession(
                languageA = _uiState.value.sourceLanguage.code,
                languageB = _uiState.value.targetLanguage.code,
                title = "" + _uiState.value.sourceLanguage.displayName + " - " + _uiState.value.targetLanguage.displayName
            )
            currentSessionId = dialogueDao.insertSession(session)
            turnContext = DialogueTurnContext.EMPTY
        }

        fun stopConversation() {
            speechEngine.isListening = false
            ttsEngine.stop()
            speechEngine.notifyTtsPlaybackFinished()
            currentSessionId = null
            turnContext = DialogueTurnContext.EMPTY
            _uiState.update { it.copy(isConversationActive = false) }
            recordPhase(DialoguePhase.IDLE)
        }

        fun speakMessage(text: String, langCode: String) {
            ttsEngine.speak(text, langCode)
        }

        fun processTurn(spokenText: String) {
            if (!_uiState.value.isConversationActive) return
            val clean = spokenText.trim()
            if (clean.isBlank()) return

            recordPhase(DialoguePhase.TRANSLATING)

            val pair = DialogueSessionPair(
                primaryLanguage = _uiState.value.sourceLanguage,
                secondaryLanguage = _uiState.value.targetLanguage
            )

            val arbitration = arbiter.arbitrate(
                spokenText = clean,
                pair = pair,
                context = turnContext
            )
            val fromLang = arbitration.resolvedLanguage
            val toLang = arbitration.targetLanguage

            val translated = fastEngine.translate(clean, fromLang.code, toLang.code)

            val sessId = currentSessionId
            var dbId: Long? = null
            if (sessId != null) {
                val dbMsg = DbDialogueMessage(
                    sessionId = sessId,
                    speaker = fromLang.code.uppercase(),
                    originalText = clean,
                    translatedText = translated,
                    originalLanguage = fromLang.code,
                    translatedLanguage = toLang.code
                )
                dbId = dialogueDao.insertMessage(dbMsg)
                dialogueDao.updateSessionTime(sessId)
            }

            val uiMsg = DialogueUiMessage(
                sourceText = clean,
                translatedText = translated,
                sourceLang = fromLang.code,
                targetLang = toLang.code,
                dbMessageId = dbId
            )
            _uiState.update { it.copy(messages = it.messages + uiMsg) }

            val autoSpeak = _uiState.value.isAutoSpeakEnabled
            var cooldownApplied = 0L

            if (autoSpeak) {
                recordPhase(DialoguePhase.SPEAKING)
                speechEngine.notifyTtsPlaybackStarted()
                ttsEngine.speakAndWait(translated, toLang.code)
                speechEngine.notifyTtsPlaybackFinished()

                timeProvider.advanceTimeBy(reverbCooldownMs)
                cooldownApplied = reverbCooldownMs
                speechEngine.resetVad()
            }

            turnContext = DialogueTurnContext(
                previousLanguage = fromLang,
                isSameSpeaker = false,
                turnIndex = turnContext.turnIndex + 1
            )

            history.add(
                DialogueTurnRecord(
                    turnIndex = turnContext.turnIndex,
                    sourceLanguage = fromLang,
                    targetLanguage = toLang,
                    sourceText = clean,
                    translatedText = translated,
                    wasAutoSpoken = autoSpeak,
                    cooldownAppliedMs = cooldownApplied
                )
            )

            if (_uiState.value.isConversationActive) {
                recordPhase(DialoguePhase.LISTENING)
            }
        }
    }

    private lateinit var settings: FakeSettingsRepository
    private lateinit var speechEngine: FakeSpeechEngine
    private lateinit var ttsEngine: FakeSystemTtsEngine
    private lateinit var fastEngine: FakeFastTranslateEngine
    private lateinit var dialogueDao: FakeDialogueDao
    private lateinit var arbiter: DialogueLanguageArbiter
    private lateinit var timeProvider: VirtualTimeProvider
    private lateinit var harness: DialogueAutoSpeakHarness

    @Before
    fun setUp() {
        settings = FakeSettingsRepository()
        speechEngine = FakeSpeechEngine()
        timeProvider = VirtualTimeProvider(0L)
        ttsEngine = FakeSystemTtsEngine(timeProvider, playbackDurationMs = 800L)
        fastEngine = FakeFastTranslateEngine()
        dialogueDao = FakeDialogueDao()
        arbiter = DialogueLanguageArbiter()

        harness = DialogueAutoSpeakHarness(
            settings = settings,
            speechEngine = speechEngine,
            ttsEngine = ttsEngine,
            fastEngine = fastEngine,
            dialogueDao = dialogueDao,
            arbiter = arbiter,
            timeProvider = timeProvider,
            reverbCooldownMs = 300L
        )
    }

    @Test
    fun testAutoSpeakEnabled_completedTurn_invokesTtsAecSuppressionAnd300msCooldown() {
        harness.setAutoSpeak(true)
        harness.startConversation()
        val initialTime = timeProvider.now()

        harness.processTurn("Где находится вокзал?")

        val state = harness.uiState.value
        assertEquals(DialoguePhase.LISTENING, state.phase)
        assertEquals(1, state.messages.size)
        assertEquals("Where is the station?", state.messages[0].translatedText)
        assertEquals("en", state.messages[0].targetLang)

        assertEquals(1, ttsEngine.speakAndWaitInvocationCount)
        assertEquals(0, ttsEngine.speakInvocationCount)
        assertEquals("Where is the station?", ttsEngine.lastSpokenText)
        assertEquals("en", ttsEngine.lastSpokenLang)

        assertEquals(listOf("AEC_SUPPRESS_START", "AEC_SUPPRESS_END", "VAD_RESET"), speechEngine.eventLog)
        assertFalse(speechEngine.isAecSuppressed)

        val elapsed = timeProvider.now() - initialTime
        assertEquals(1100L, elapsed)
        assertEquals(1, speechEngine.vadResetCount)

        assertEquals(1, harness.history.size)
        assertTrue(harness.history[0].wasAutoSpoken)
        assertEquals(300L, harness.history[0].cooldownAppliedMs)

        val expectedPhases = listOf(
            DialoguePhase.IDLE,
            DialoguePhase.LISTENING,
            DialoguePhase.TRANSLATING,
            DialoguePhase.SPEAKING,
            DialoguePhase.LISTENING
        )
        assertEquals(expectedPhases, harness.phaseHistory)
    }

    @Test
    fun testAutoSpeakEnabled_consecutiveTurns_eachTurnExecutesTtsAndCooldown() {
        harness.setAutoSpeak(true)
        harness.startConversation()
        val initialTime = timeProvider.now()

        harness.processTurn("Здравствуйте")
        harness.processTurn("Hello")

        assertEquals(2, harness.uiState.value.messages.size)
        assertEquals(2, ttsEngine.speakAndWaitInvocationCount)
        assertEquals("Здравствуйте", ttsEngine.lastSpokenText)
        assertEquals("ru", ttsEngine.lastSpokenLang)

        assertEquals(2200L, timeProvider.now() - initialTime)
        assertEquals(2, speechEngine.vadResetCount)
        assertEquals(2, harness.history.size)
        assertTrue(harness.history[0].wasAutoSpoken)
        assertTrue(harness.history[1].wasAutoSpoken)
    }

    @Test
    fun testAutoSpeakDisabled_completedTurn_recordsMessage_skipsTtsAndCooldown_immediatelyListening() {
        harness.setAutoSpeak(false)
        harness.startConversation()
        val initialTime = timeProvider.now()

        harness.processTurn("Где находится вокзал?")

        val state = harness.uiState.value
        assertEquals(DialoguePhase.LISTENING, state.phase)
        assertEquals(1, state.messages.size)
        assertEquals("Where is the station?", state.messages[0].translatedText)

        assertEquals(1, dialogueDao.messages.size)
        assertEquals("Where is the station?", dialogueDao.messages[0].translatedText)

        assertEquals(0, ttsEngine.speakAndWaitInvocationCount)
        assertEquals(0, ttsEngine.speakInvocationCount)
        assertNull(ttsEngine.lastSpokenText)
        assertNull(ttsEngine.lastSpokenLang)

        assertTrue(speechEngine.eventLog.isEmpty())
        assertFalse(speechEngine.isAecSuppressed)
        assertEquals(0, speechEngine.vadResetCount)

        val elapsed = timeProvider.now() - initialTime
        assertEquals(0L, elapsed)

        assertEquals(1, harness.history.size)
        assertFalse(harness.history[0].wasAutoSpoken)
        assertEquals(0L, harness.history[0].cooldownAppliedMs)

        val expectedPhases = listOf(
            DialoguePhase.IDLE,
            DialoguePhase.LISTENING,
            DialoguePhase.TRANSLATING,
            DialoguePhase.LISTENING
        )
        assertEquals(expectedPhases, harness.phaseHistory)
        assertFalse(harness.phaseHistory.contains(DialoguePhase.SPEAKING))
    }

    @Test
    fun testAutoSpeakDisabled_rapidConsecutiveTurns_processesInstantlyWithoutDelays() {
        harness.setAutoSpeak(false)
        harness.startConversation()
        val initialTime = timeProvider.now()

        harness.processTurn("Здравствуйте")
        harness.processTurn("The central station is straight ahead")
        harness.processTurn("Спасибо")

        assertEquals(3, harness.uiState.value.messages.size)
        assertEquals(0, ttsEngine.speakAndWaitInvocationCount)
        assertEquals(0, ttsEngine.speakInvocationCount)
        assertEquals(0L, timeProvider.now() - initialTime)
        assertEquals(0, speechEngine.vadResetCount)

        assertEquals("Hello", harness.uiState.value.messages[0].translatedText)
        assertEquals("Центральный вокзал прямо по курсу", harness.uiState.value.messages[1].translatedText)
        assertEquals("Thank you", harness.uiState.value.messages[2].translatedText)
    }

    @Test
    fun testManualBubbleSpeak_whenAutoSpeakIsDisabled_triggersTtsPlayback() {
        harness.setAutoSpeak(false)
        harness.startConversation()
        harness.processTurn("Где находится вокзал?")

        assertEquals(0, ttsEngine.speakInvocationCount)
        assertEquals(0, ttsEngine.speakAndWaitInvocationCount)

        val msg = harness.uiState.value.messages[0]
        harness.speakMessage(msg.translatedText, msg.targetLang)

        assertEquals(1, ttsEngine.speakInvocationCount)
        assertEquals(0, ttsEngine.speakAndWaitInvocationCount)
        assertEquals("Where is the station?", ttsEngine.lastSpokenText)
        assertEquals("en", ttsEngine.lastSpokenLang)
        assertTrue(ttsEngine.speechLog.contains("Where is the station?" to "en"))
    }

    @Test
    fun testManualBubbleSpeak_whenAutoSpeakIsEnabled_triggersTtsPlayback() {
        harness.setAutoSpeak(true)
        harness.startConversation()
        harness.processTurn("Здравствуйте")

        assertEquals(1, ttsEngine.speakAndWaitInvocationCount)
        assertEquals(0, ttsEngine.speakInvocationCount)

        harness.speakMessage("Hello", "en")

        assertEquals(1, ttsEngine.speakInvocationCount)
        assertEquals(1, ttsEngine.speakAndWaitInvocationCount)
        assertEquals("Hello", ttsEngine.lastSpokenText)
        assertEquals("en", ttsEngine.lastSpokenLang)
    }

    @Test
    fun testManualBubbleSpeak_historicalBubble_playsCorrectTargetLanguage() {
        harness.startConversation()
        harness.processTurn("The central station is straight ahead")
        val msg = harness.uiState.value.messages[0]

        harness.speakMessage(msg.translatedText, msg.targetLang)

        assertEquals("Центральный вокзал прямо по курсу", ttsEngine.lastSpokenText)
        assertEquals("ru", ttsEngine.lastSpokenLang)
    }

    @Test
    fun testToggleAutoSpeak_updatesUiStateAndPersistsToSettingsRepository() {
        assertTrue(harness.uiState.value.isAutoSpeakEnabled)
        assertTrue(settings.dialogueAutoSpeak)

        harness.toggleAutoSpeak()

        assertFalse(harness.uiState.value.isAutoSpeakEnabled)
        assertFalse(settings.dialogueAutoSpeak)

        harness.toggleAutoSpeak()

        assertTrue(harness.uiState.value.isAutoSpeakEnabled)
        assertTrue(settings.dialogueAutoSpeak)
    }

    @Test
    fun testSetAutoSpeakExplicit_updatesUiStateAndPersists() {
        harness.setAutoSpeak(false)
        assertFalse(harness.uiState.value.isAutoSpeakEnabled)
        assertFalse(settings.dialogueAutoSpeak)

        harness.setAutoSpeak(false)
        assertFalse(harness.uiState.value.isAutoSpeakEnabled)
        assertFalse(settings.dialogueAutoSpeak)

        harness.setAutoSpeak(true)
        assertTrue(harness.uiState.value.isAutoSpeakEnabled)
        assertTrue(settings.dialogueAutoSpeak)
    }

    @Test
    fun testInitialState_loadsFromPersistedSettings() {
        val customSettings = FakeSettingsRepository().apply {
            dialogueAutoSpeak = false
        }

        val customHarness = DialogueAutoSpeakHarness(
            settings = customSettings,
            speechEngine = speechEngine,
            ttsEngine = ttsEngine,
            fastEngine = fastEngine,
            dialogueDao = dialogueDao,
            arbiter = arbiter,
            timeProvider = timeProvider
        )

        assertFalse(customHarness.uiState.value.isAutoSpeakEnabled)
    }

    @Test
    fun testDynamicToggleDuringActiveConversation_affectsSubsequentTurnsInstantly() {
        harness.setAutoSpeak(true)
        harness.startConversation()

        harness.processTurn("Здравствуйте")
        assertEquals(1, ttsEngine.speakAndWaitInvocationCount)
        assertTrue(harness.history[0].wasAutoSpoken)

        harness.toggleAutoSpeak()
        assertFalse(harness.uiState.value.isAutoSpeakEnabled)

        harness.processTurn("Hello")
        assertEquals(1, ttsEngine.speakAndWaitInvocationCount)
        assertFalse(harness.history[1].wasAutoSpoken)

        harness.toggleAutoSpeak()
        assertTrue(harness.uiState.value.isAutoSpeakEnabled)

        harness.processTurn("Спасибо")
        assertEquals(2, ttsEngine.speakAndWaitInvocationCount)
        assertTrue(harness.history[2].wasAutoSpoken)
    }

    @Test
    fun testBlankOrWhitespaceUtterance_isIgnoredWithoutStateCorruption() {
        harness.startConversation()
        harness.processTurn("   ")

        assertEquals(0, harness.uiState.value.messages.size)
        assertEquals(0, ttsEngine.speakAndWaitInvocationCount)
        assertEquals(DialoguePhase.LISTENING, harness.uiState.value.phase)
    }

    private fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (
                cp in 0x1F300..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x1F1E6..0x1F1FF ||
                cp in 0xFE00..0xFE0F ||
                cp in 0x1F900..0x1F9FF
            ) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    @Test
    fun testStrictZeroEmojiCompliance_allFixturesAndStatePayloads() {
        val testStrings = listOf(
            harness.uiState.value.sourceLanguage.displayName,
            harness.uiState.value.targetLanguage.displayName,
            "Russian - English",
            "Where is the station?",
            "Центральный вокзал прямо по курсу",
            "Translated[ru->en]: text",
            "AEC_SUPPRESS_START",
            "AEC_SUPPRESS_END",
            "VAD_RESET"
        )

        for (str in testStrings) {
            assertFalse(
                "Zero-emoji violation detected in: " + str,
                containsEmoji(str)
            )
        }
    }
}
