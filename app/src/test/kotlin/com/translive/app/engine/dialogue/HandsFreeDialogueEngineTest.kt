package com.translive.app.engine.dialogue

import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HandsFreeDialogueEngineTest {

    enum class HandsFreeDialogueState {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING,
        COOLDOWN,
        STOPPED,
        ERROR
    }

    data class DialogueTurnRecord(
        val turnIndex: Int,
        val speakerLanguage: Language,
        val targetLanguage: Language,
        val sourceText: String,
        val translatedText: String,
        val isLlm: Boolean,
        val resolutionMethod: ResolutionMethod,
        val speakerBadge: String
    )

    data class DialogueUiMessage(
        val sourceText: String,
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String,
        val isLlmRefined: Boolean = false,
        val runtimeBadge: String = if (isLlmRefined) "[LLM]" else "[FAST]"
    )

    class VirtualTimeProvider(private var currentTimeMs: Long = 0L) {
        fun now(): Long = currentTimeMs
        fun advanceTimeBy(deltaMs: Long) { currentTimeMs += deltaMs }
        fun setTime(timeMs: Long) { currentTimeMs = timeMs }
    }

    class FakeFastTranslateEngine {
        var isReady: Boolean = true
        var downloadedPackages: MutableSet<String> = mutableSetOf("ru", "en", "vi", "zh")
        var shouldThrow: Boolean = false

        fun arePackagesDownloaded(sourceLang: String, targetLang: String): Boolean {
            return sourceLang in downloadedPackages && targetLang in downloadedPackages
        }

        fun translate(text: String, sourceLang: String, targetLang: String): String {
            if (shouldThrow) throw IllegalStateException("Fast NMT translation failure")
            return when {
                sourceLang == "ru" && targetLang == "en" && text == "Здравствуйте" -> "Hello"
                sourceLang == "ru" && targetLang == "en" && text == "Где находится вокзал?" -> "Where is the station?"
                sourceLang == "ru" && targetLang == "en" && text == "Большое спасибо за помощь!" -> "Thank you very much for your help!"
                sourceLang == "en" && targetLang == "ru" && text == "The central station is straight ahead." -> "Центральный вокзал прямо по курсу."
                sourceLang == "en" && targetLang == "ru" && text == "You are very welcome, have a great trip!" -> "Пожалуйста, приятной поездки!"
                sourceLang == "en" && targetLang == "ru" && text == "Turn right at the traffic light" -> "Поверните направо на светофоре"
                sourceLang == "vi" && targetLang == "en" && text == "Xin chào, sân bay ở đâu?" -> "Hello, where is the airport?"
                sourceLang == "en" && targetLang == "vi" && text == "The airport is 5 kilometers away." -> "Sân bay cách đây 5 km."
                sourceLang == "ru" && targetLang == "en" && text.equals("OK", ignoreCase = true) -> "OK"
                sourceLang == "en" && targetLang == "ru" && text.equals("OK", ignoreCase = true) -> "Хорошо"
                sourceLang == "en" && targetLang == "ru" && text == "100" -> "100"
                sourceLang == "ru" && targetLang == "en" && text == "100" -> "100"
                else -> "FastNMT[" + sourceLang + "->" + targetLang + "]: " + text
            }
        }
    }

    class FakeLlmTranslateEngine {
        var isLoaded: Boolean = false
        var activeModelPath: String? = null
        var shouldThrow: Boolean = false

        fun translateSafe(text: String, source: Language, target: Language): String {
            if (!isLoaded || activeModelPath == null) {
                throw IllegalStateException("LLM runtime not loaded")
            }
            if (shouldThrow) throw RuntimeException("LLM inference error")
            return "LLM[" + source.code + "->" + target.code + "]: " + text
        }
    }

    class FakeSpeechEngine {
        var isModelsDownloaded: Boolean = true
        var isListening: Boolean = false
        var isAecSuppressed: Boolean = false
        var vadResetCount: Int = 0

        fun areModelsDownloaded(): Boolean = isModelsDownloaded

        fun notifyTtsPlaybackStarted() {
            isAecSuppressed = true
        }

        fun notifyTtsPlaybackFinished() {
            isAecSuppressed = false
        }

        fun resetVad() {
            vadResetCount++
        }
    }

    class FakeSystemTtsEngine {
        var isPlaying: Boolean = false
        var lastSpokenText: String? = null
        var lastSpokenLang: String? = null

        fun speak(text: String, langCode: String) {
            isPlaying = true
            lastSpokenText = text
            lastSpokenLang = langCode
        }

        fun stop() {
            isPlaying = false
        }
    }

    class HandsFreeDialogueEngine(
        val sessionPair: DialogueSessionPair,
        val translationPolicy: TranslationPolicy,
        private val arbiter: DialogueLanguageArbiter,
        private val fastEngine: FakeFastTranslateEngine,
        private val llmEngine: FakeLlmTranslateEngine,
        private val speechEngine: FakeSpeechEngine,
        private val ttsEngine: FakeSystemTtsEngine,
        private val timeProvider: VirtualTimeProvider,
        val reverbCooldownMs: Long = 300L
    ) {
        var state: HandsFreeDialogueState = HandsFreeDialogueState.IDLE
            private set

        var lastError: String? = null
            private set

        var activeTurnContext: DialogueTurnContext = DialogueTurnContext.EMPTY
            private set

        private var currentSpokenText: String? = null
        private var currentResolvedResult: DialogueArbitrationResult? = null
        private var currentTranslatedText: String? = null
        private var isCurrentLlm: Boolean = false
        private var cooldownStartTimeMs: Long = 0L

        private val _history = mutableListOf<DialogueTurnRecord>()
        val history: List<DialogueTurnRecord> get() = _history.toList()

        private val _uiMessages = mutableListOf<DialogueUiMessage>()
        val uiMessages: List<DialogueUiMessage> get() = _uiMessages.toList()

        val stateTransitions = mutableListOf<HandsFreeDialogueState>()

        init {
            recordState(HandsFreeDialogueState.IDLE)
        }

        private fun recordState(newState: HandsFreeDialogueState) {
            state = newState
            stateTransitions.add(newState)
        }

        fun isAecGuardActive(): Boolean {
            if (state == HandsFreeDialogueState.SPEAKING) return true
            if (state == HandsFreeDialogueState.COOLDOWN) {
                val elapsed = timeProvider.now() - cooldownStartTimeMs
                return elapsed < reverbCooldownMs
            }
            return false
        }

        fun start(): Boolean {
            if (translationPolicy == TranslationPolicy.FAST || translationPolicy == TranslationPolicy.FAST_WITH_LLM_IMPROVE) {
                val hasFastPacks = fastEngine.arePackagesDownloaded(
                    sessionPair.primaryLanguage.code,
                    sessionPair.secondaryLanguage.code
                )
                if (!hasFastPacks) {
                    recordState(HandsFreeDialogueState.ERROR)
                    lastError = "Missing Fast NMT packages for " + sessionPair.primaryLanguage.code + "-" + sessionPair.secondaryLanguage.code
                    return false
                }
            } else if (translationPolicy == TranslationPolicy.LLM_ONLY) {
                if (!llmEngine.isLoaded && llmEngine.activeModelPath == null) {
                    recordState(HandsFreeDialogueState.ERROR)
                    lastError = "Translation model missing for LLM_ONLY policy"
                    return false
                }
            }

            if (!speechEngine.areModelsDownloaded()) {
                recordState(HandsFreeDialogueState.ERROR)
                lastError = "STT speech models missing or corrupt"
                return false
            }

            speechEngine.isListening = true
            recordState(HandsFreeDialogueState.LISTENING)
            return true
        }

        fun stop() {
            speechEngine.isListening = false
            ttsEngine.stop()
            speechEngine.notifyTtsPlaybackFinished()
            currentSpokenText = null
            currentResolvedResult = null
            currentTranslatedText = null
            recordState(HandsFreeDialogueState.STOPPED)
        }

        fun onSpeechDetected(text: String): Boolean {
            if (state != HandsFreeDialogueState.LISTENING) return false
            if (isAecGuardActive()) return false

            val clean = text.trim()
            if (clean.isBlank()) return false

            recordState(HandsFreeDialogueState.PROCESSING)
            currentSpokenText = clean

            val arbitration = arbiter.arbitrate(
                spokenText = clean,
                pair = sessionPair,
                context = activeTurnContext
            )
            currentResolvedResult = arbitration

            val fromLang = arbitration.resolvedLanguage
            val toLang = arbitration.targetLanguage

            val (translated, isLlm) = when (translationPolicy) {
                TranslationPolicy.FAST, TranslationPolicy.FAST_WITH_LLM_IMPROVE -> {
                    val res = fastEngine.translate(clean, fromLang.code, toLang.code)
                    Pair(res, false)
                }
                TranslationPolicy.LLM_ONLY -> {
                    val res = llmEngine.translateSafe(clean, fromLang, toLang)
                    Pair(res, true)
                }
            }

            currentTranslatedText = translated
            isCurrentLlm = isLlm

            recordState(HandsFreeDialogueState.SPEAKING)
            speechEngine.notifyTtsPlaybackStarted()
            ttsEngine.speak(translated, toLang.code)

            return true
        }

        fun onTtsCompleted() {
            if (state != HandsFreeDialogueState.SPEAKING) return

            speechEngine.notifyTtsPlaybackFinished()
            ttsEngine.stop()

            val res = currentResolvedResult
            val srcText = currentSpokenText
            val tgtText = currentTranslatedText

            if (res != null && srcText != null && tgtText != null) {
                val turnRecord = DialogueTurnRecord(
                    turnIndex = activeTurnContext.turnIndex + 1,
                    speakerLanguage = res.resolvedLanguage,
                    targetLanguage = res.targetLanguage,
                    sourceText = srcText,
                    translatedText = tgtText,
                    isLlm = isCurrentLlm,
                    resolutionMethod = res.resolutionMethod,
                    speakerBadge = res.resolvedLanguage.code.uppercase()
                )
                _history.add(turnRecord)

                _uiMessages.add(
                    DialogueUiMessage(
                        sourceText = srcText,
                        translatedText = tgtText,
                        sourceLang = res.resolvedLanguage.code,
                        targetLang = res.targetLanguage.code,
                        isLlmRefined = isCurrentLlm
                    )
                )

                activeTurnContext = DialogueTurnContext(
                    previousLanguage = res.resolvedLanguage,
                    isSameSpeaker = false,
                    turnIndex = turnRecord.turnIndex
                )
            }

            cooldownStartTimeMs = timeProvider.now()
            recordState(HandsFreeDialogueState.COOLDOWN)
        }

        fun pollCooldown() {
            if (state == HandsFreeDialogueState.COOLDOWN) {
                val elapsed = timeProvider.now() - cooldownStartTimeMs
                if (elapsed >= reverbCooldownMs) {
                    speechEngine.resetVad()
                    currentSpokenText = null
                    currentResolvedResult = null
                    currentTranslatedText = null
                    recordState(HandsFreeDialogueState.LISTENING)
                }
            }
        }
    }

    private lateinit var arbiter: DialogueLanguageArbiter
    private lateinit var virtualTime: VirtualTimeProvider
    private lateinit var fastEngine: FakeFastTranslateEngine
    private lateinit var llmEngine: FakeLlmTranslateEngine
    private lateinit var speechEngine: FakeSpeechEngine
    private lateinit var ttsEngine: FakeSystemTtsEngine

    private val pairRuEn = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)
    private val pairViEn = DialogueSessionPair(Language.VIETNAMESE, Language.ENGLISH)

    @Before
    fun setUp() {
        arbiter = DialogueLanguageArbiter()
        virtualTime = VirtualTimeProvider(0L)
        fastEngine = FakeFastTranslateEngine()
        llmEngine = FakeLlmTranslateEngine()
        speechEngine = FakeSpeechEngine()
        ttsEngine = FakeSystemTtsEngine()
    }

    private fun createEngine(
        pair: DialogueSessionPair = pairRuEn,
        policy: TranslationPolicy = TranslationPolicy.FAST
    ): HandsFreeDialogueEngine {
        return HandsFreeDialogueEngine(
            sessionPair = pair,
            translationPolicy = policy,
            arbiter = arbiter,
            fastEngine = fastEngine,
            llmEngine = llmEngine,
            speechEngine = speechEngine,
            ttsEngine = ttsEngine,
            timeProvider = virtualTime,
            reverbCooldownMs = 300L
        )
    }

    @Test
    fun testLanguageArbitration_AlternatingRussianAndEnglishInputs_AttributedToRespectiveSides() {
        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)
        assertTrue(engine.start())
        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)

        val ruUtterance1 = "Здравствуйте"
        assertTrue(engine.onSpeechDetected(ruUtterance1))
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)
        engine.onTtsCompleted()
        assertEquals(HandsFreeDialogueState.COOLDOWN, engine.state)
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()
        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)

        assertEquals(1, engine.history.size)
        val turn1 = engine.history[0]
        assertEquals(Language.RUSSIAN, turn1.speakerLanguage)
        assertEquals(Language.ENGLISH, turn1.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, turn1.resolutionMethod)
        assertEquals("Hello", turn1.translatedText)

        val enUtterance1 = "The central station is straight ahead."
        assertTrue(engine.onSpeechDetected(enUtterance1))
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()
        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)

        assertEquals(2, engine.history.size)
        val turn2 = engine.history[1]
        assertEquals(Language.ENGLISH, turn2.speakerLanguage)
        assertEquals(Language.RUSSIAN, turn2.targetLanguage)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, turn2.resolutionMethod)
        assertEquals("Центральный вокзал прямо по курсу.", turn2.translatedText)

        val ruUtterance2 = "Большое спасибо за помощь!"
        assertTrue(engine.onSpeechDetected(ruUtterance2))
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        assertEquals(3, engine.history.size)
        val turn3 = engine.history[2]
        assertEquals(Language.RUSSIAN, turn3.speakerLanguage)
        assertEquals(Language.ENGLISH, turn3.targetLanguage)

        val enUtterance2 = "You are very welcome, have a great trip!"
        assertTrue(engine.onSpeechDetected(enUtterance2))
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        assertEquals(4, engine.history.size)
        val turn4 = engine.history[3]
        assertEquals(Language.ENGLISH, turn4.speakerLanguage)
        assertEquals(Language.RUSSIAN, turn4.targetLanguage)
    }

    @Test
    fun testLanguageArbitration_AmbiguousPhrases_AlternationPriorSwitchesSpeakersNaturally() {
        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)
        assertTrue(engine.start())

        assertTrue(engine.onSpeechDetected("Здравствуйте"))
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        assertEquals(Language.RUSSIAN, engine.activeTurnContext.previousLanguage)

        assertTrue(engine.onSpeechDetected("OK"))
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        assertEquals(2, engine.history.size)
        val turn2 = engine.history[1]
        assertEquals(Language.ENGLISH, turn2.speakerLanguage)
        assertEquals(Language.RUSSIAN, turn2.targetLanguage)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, turn2.resolutionMethod)
        assertEquals(Language.ENGLISH, engine.activeTurnContext.previousLanguage)

        assertTrue(engine.onSpeechDetected("100"))
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        assertEquals(3, engine.history.size)
        val turn3 = engine.history[2]
        assertEquals(Language.RUSSIAN, turn3.speakerLanguage)
        assertEquals(Language.ENGLISH, turn3.targetLanguage)
        assertEquals(ResolutionMethod.ALTERNATION_PRIOR, turn3.resolutionMethod)
        assertEquals(Language.RUSSIAN, engine.activeTurnContext.previousLanguage)
    }

    @Test
    fun testLanguageArbitration_AmbiguousPhrases_WithoutPriorContext_FallsBackToPrimaryLanguage() {
        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)
        assertTrue(engine.start())

        assertTrue(engine.onSpeechDetected("OK"))
        engine.onTtsCompleted()

        assertEquals(1, engine.history.size)
        val turn1 = engine.history[0]
        assertEquals(Language.RUSSIAN, turn1.speakerLanguage)
        assertEquals(Language.ENGLISH, turn1.targetLanguage)
        assertEquals(ResolutionMethod.DEFAULT_FALLBACK, turn1.resolutionMethod)
    }

    @Test
    fun testFastNmt_StartsImmediatelyWhenPackagesAvailable_WithoutLlmModel() {
        llmEngine.isLoaded = false
        llmEngine.activeModelPath = null

        fastEngine.isReady = true
        fastEngine.downloadedPackages = mutableSetOf("ru", "en")

        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)

        val started = engine.start()
        assertTrue("Fast NMT policy must allow dialogue to start immediately without LLM", started)
        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)
        assertNull(engine.lastError)

        assertTrue(engine.onSpeechDetected("Здравствуйте"))
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)
        engine.onTtsCompleted()

        assertEquals(1, engine.history.size)
        val turn = engine.history[0]
        assertEquals("Hello", turn.translatedText)
        assertFalse("Message must be flagged as fast NMT (isLlm = false)", turn.isLlm)
    }

    @Test
    fun testFastNmtWithLlmImprovePolicy_AllowsImmediateDialogueStartWithoutBlocking() {
        llmEngine.isLoaded = false
        llmEngine.activeModelPath = null
        fastEngine.downloadedPackages = mutableSetOf("ru", "en")

        val engine = createEngine(pairRuEn, TranslationPolicy.FAST_WITH_LLM_IMPROVE)

        val started = engine.start()
        assertTrue("FAST_WITH_LLM_IMPROVE policy must start immediately on Fast NMT path", started)
        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)

        assertTrue(engine.onSpeechDetected("Здравствуйте"))
        engine.onTtsCompleted()

        assertEquals(1, engine.history.size)
        assertEquals(1, engine.uiMessages.size)
        val uiMsg = engine.uiMessages[0]
        assertFalse(uiMsg.isLlmRefined)
        assertEquals("[FAST]", uiMsg.runtimeBadge)
    }

    @Test
    fun testLlmOnlyPolicy_BlocksAndFailsWhenLlmModelMissing() {
        llmEngine.isLoaded = false
        llmEngine.activeModelPath = null

        val engine = createEngine(pairRuEn, TranslationPolicy.LLM_ONLY)

        val started = engine.start()
        assertFalse("LLM_ONLY policy must fail to start when LLM model is missing", started)
        assertEquals(HandsFreeDialogueState.ERROR, engine.state)
        assertNotNull(engine.lastError)
        assertTrue(engine.lastError!!.contains("Translation model missing"))
    }

    @Test
    fun testFastNmt_MissingPackages_FailsGracefullyWithExplicitError() {
        fastEngine.downloadedPackages = mutableSetOf("es")

        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)

        val started = engine.start()
        assertFalse("Engine must not start if required Fast NMT language packages are missing", started)
        assertEquals(HandsFreeDialogueState.ERROR, engine.state)
        assertTrue(engine.lastError!!.contains("Missing Fast NMT packages"))
    }

    @Test
    fun testTurnLifecycle_CompleteCycle_ListeningToProcessingToSpeakingToCooldownToListening() {
        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)
        engine.start()

        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)

        val detected = engine.onSpeechDetected("Здравствуйте")
        assertTrue(detected)
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)
        assertTrue(engine.isAecGuardActive())

        engine.onTtsCompleted()
        assertEquals(HandsFreeDialogueState.COOLDOWN, engine.state)
        assertTrue(engine.isAecGuardActive())

        virtualTime.advanceTimeBy(150L)
        engine.pollCooldown()
        assertEquals(HandsFreeDialogueState.COOLDOWN, engine.state)
        assertTrue(engine.isAecGuardActive())

        val echoSpeechAccepted = engine.onSpeechDetected("Echo feedback utterance")
        assertFalse("Microphone frame during 300ms reverb cooldown must be suppressed by AEC guard", echoSpeechAccepted)

        virtualTime.advanceTimeBy(150L)
        engine.pollCooldown()
        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)
        assertFalse(engine.isAecGuardActive())
        assertEquals(1, speechEngine.vadResetCount)

        val expectedSequence = listOf(
            HandsFreeDialogueState.IDLE,
            HandsFreeDialogueState.LISTENING,
            HandsFreeDialogueState.PROCESSING,
            HandsFreeDialogueState.SPEAKING,
            HandsFreeDialogueState.COOLDOWN,
            HandsFreeDialogueState.LISTENING
        )
        assertEquals(expectedSequence, engine.stateTransitions)
    }

    @Test
    fun testTurnLifecycle_AecEchoSuppressionDuringSpeakingAndCooldown() {
        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)
        engine.start()

        assertTrue(engine.onSpeechDetected("Здравствуйте"))
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)

        val micInputDuringSpeaking = engine.onSpeechDetected("Loud speaker bleed text")
        assertFalse(micInputDuringSpeaking)

        engine.onTtsCompleted()
        assertEquals(HandsFreeDialogueState.COOLDOWN, engine.state)

        virtualTime.advanceTimeBy(100L)
        engine.pollCooldown()
        val micInputDuringCooldown = engine.onSpeechDetected("Reverb tail noise")
        assertFalse(micInputDuringCooldown)

        virtualTime.advanceTimeBy(200L)
        engine.pollCooldown()
        assertEquals(HandsFreeDialogueState.LISTENING, engine.state)

        val cleanSpeechAccepted = engine.onSpeechDetected("The central station is straight ahead.")
        assertTrue(cleanSpeechAccepted)
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)
    }

    @Test
    fun testTurnLifecycle_StopInterruption_HaltsImmediatelyFromAnyPhase() {
        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)
        engine.start()

        engine.onSpeechDetected("Здравствуйте")
        assertEquals(HandsFreeDialogueState.SPEAKING, engine.state)

        engine.stop()
        assertEquals(HandsFreeDialogueState.STOPPED, engine.state)
        assertFalse(speechEngine.isListening)
        assertFalse(ttsEngine.isPlaying)

        val inputAfterStop = engine.onSpeechDetected("Should be ignored")
        assertFalse(inputAfterStop)
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
    fun testZeroEmojiCompliance_AcrossAllStateStringsSessionTitlesAndBadges() {
        val engine = createEngine(pairRuEn, TranslationPolicy.FAST)
        engine.start()
        engine.onSpeechDetected("Здравствуйте")
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        val sessionTitle = "" + pairRuEn.primaryLanguage.displayName + " - " + pairRuEn.secondaryLanguage.displayName
        assertFalse("Session title '" + sessionTitle + "' must not contain emoji", containsEmoji(sessionTitle))

        for (turn in engine.history) {
            assertFalse("Speaker badge '" + turn.speakerBadge + "' must not contain emoji", containsEmoji(turn.speakerBadge))
            assertFalse("Source text '" + turn.sourceText + "' must not contain emoji", containsEmoji(turn.sourceText))
            assertFalse("Translated text '" + turn.translatedText + "' must not contain emoji", containsEmoji(turn.translatedText))
        }

        for (uiMsg in engine.uiMessages) {
            assertFalse("Runtime badge '" + uiMsg.runtimeBadge + "' must not contain emoji", containsEmoji(uiMsg.runtimeBadge))
        }

        for (state in HandsFreeDialogueState.values()) {
            assertFalse("State '" + state.name + "' must not contain emoji", containsEmoji(state.name))
        }

        for (lang in Language.allLanguages) {
            assertFalse("Language displayName '" + lang.displayName + "' must not contain emoji", containsEmoji(lang.displayName))
            assertFalse("Language nativeName '" + lang.nativeName + "' must not contain emoji", containsEmoji(lang.nativeName))
        }
    }

    @Test
    fun testPureJvmIsolation_DeterministicExecutionWithoutAndroidFramework() {
        val engine = createEngine(pairViEn, TranslationPolicy.FAST)
        assertTrue(engine.start())

        assertTrue(engine.onSpeechDetected("Xin chào, sân bay ở đâu?"))
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        assertEquals(1, engine.history.size)
        val turn1 = engine.history[0]
        assertEquals(Language.VIETNAMESE, turn1.speakerLanguage)
        assertEquals(Language.ENGLISH, turn1.targetLanguage)
        assertEquals("Hello, where is the airport?", turn1.translatedText)

        assertTrue(engine.onSpeechDetected("The airport is 5 kilometers away."))
        engine.onTtsCompleted()
        virtualTime.advanceTimeBy(300L)
        engine.pollCooldown()

        assertEquals(2, engine.history.size)
        val turn2 = engine.history[1]
        assertEquals(Language.ENGLISH, turn2.speakerLanguage)
        assertEquals(Language.VIETNAMESE, turn2.targetLanguage)
        assertEquals("Sân bay cách đây 5 km.", turn2.translatedText)
    }
}
