package com.translive.app.engine.dialogue

import com.translive.app.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM Unit Test Suite for Continuous Dialogue State Machine,
 * VAD Coordination, and AEC Echo Guard (Sub-phase D1.2).
 *
 * Rules:
 * - 100% pure JVM (zero Android context, AudioRecord, or Robolectric dependencies).
 * - Zero-emoji compliance in test fixtures, assertions, and method names.
 * - Deterministic virtual time stepping for AEC reverb drain window testing.
 */
class ContinuousDialogueVadTest {

    enum class DialogueLoopState {
        IDLE,
        START,
        LISTENING,
        SPEECH_DETECTED,
        RECOGNIZED,
        TRANSLATING,
        TTS_PLAYING,
        REVERB_DRAIN,
        STOPPED,
        ERROR
    }

    sealed class DialogueLoopEvent {
        object Start : DialogueLoopEvent()
        object Stop : DialogueLoopEvent()
        data class SetMute(val isMuted: Boolean) : DialogueLoopEvent()
        object VadSpeechStart : DialogueLoopEvent()
        data class VadSpeechEnd(val audioSamples: FloatArray = FloatArray(512)) : DialogueLoopEvent()
        data class SpeechRecognized(val text: String, val recognizedLang: String? = null) : DialogueLoopEvent()
        data class TranslationCompleted(val translatedText: String) : DialogueLoopEvent()
        object TtsStarted : DialogueLoopEvent()
        object TtsFinished : DialogueLoopEvent()
        object ReverbDrainExpired : DialogueLoopEvent()
        data class ErrorOccurred(val message: String) : DialogueLoopEvent()
    }

    data class DialogueTurnRecord(
        val turnIndex: Int,
        val speakerLanguage: Language,
        val targetLanguage: Language,
        val sourceText: String,
        val translatedText: String,
        val resolutionMethod: ResolutionMethod
    )

    class VirtualTimeProvider(private var currentTimeMs: Long = 0L) {
        fun now(): Long = currentTimeMs
        fun advanceTimeBy(deltaMs: Long) {
            currentTimeMs += deltaMs
        }
        fun setTime(timeMs: Long) {
            currentTimeMs = timeMs
        }
    }

    class ContinuousDialogueVadController(
        val sessionPair: DialogueSessionPair,
        private val arbiter: DialogueLanguageArbiter,
        private val timeProvider: VirtualTimeProvider,
        val reverbDrainDurationMs: Long = 300L
    ) {
        var state: DialogueLoopState = DialogueLoopState.IDLE
            private set

        var isMuted: Boolean = false
            private set

        var lastError: String? = null
            private set

        private var activeTurnContext = DialogueTurnContext.EMPTY
        private var currentSpokenText: String? = null
        private var currentResolvedResult: DialogueArbitrationResult? = null
        private var currentTranslatedText: String? = null

        private var reverbDrainStartTimeMs: Long = 0L
        private val history = mutableListOf<DialogueTurnRecord>()
        val turnHistory: List<DialogueTurnRecord> get() = history.toList()

        val stateTransitions = mutableListOf<DialogueLoopState>()

        init {
            recordState(DialogueLoopState.IDLE)
        }

        private fun recordState(newState: DialogueLoopState) {
            state = newState
            stateTransitions.add(newState)
        }

        fun isAecGuardActive(): Boolean {
            if (state == DialogueLoopState.TTS_PLAYING) return true
            if (state == DialogueLoopState.REVERB_DRAIN) {
                val elapsed = timeProvider.now() - reverbDrainStartTimeMs
                return elapsed < reverbDrainDurationMs
            }
            return false
        }

        fun onEvent(event: DialogueLoopEvent) {
            when (event) {
                is DialogueLoopEvent.Start -> handleStart()
                is DialogueLoopEvent.Stop -> handleStop()
                is DialogueLoopEvent.SetMute -> handleMute(event.isMuted)
                is DialogueLoopEvent.VadSpeechStart -> handleVadSpeechStart()
                is DialogueLoopEvent.VadSpeechEnd -> handleVadSpeechEnd()
                is DialogueLoopEvent.SpeechRecognized -> handleSpeechRecognized(event.text)
                is DialogueLoopEvent.TranslationCompleted -> handleTranslationCompleted(event.translatedText)
                is DialogueLoopEvent.TtsStarted -> handleTtsStarted()
                is DialogueLoopEvent.TtsFinished -> handleTtsFinished()
                is DialogueLoopEvent.ReverbDrainExpired -> handleReverbDrainExpired()
                is DialogueLoopEvent.ErrorOccurred -> handleError(event.message)
            }
        }

        private fun handleStart() {
            if (state == DialogueLoopState.IDLE || state == DialogueLoopState.STOPPED) {
                recordState(DialogueLoopState.START)
                recordState(DialogueLoopState.LISTENING)
            }
        }

        private fun handleStop() {
            recordState(DialogueLoopState.STOPPED)
            currentSpokenText = null
            currentResolvedResult = null
            currentTranslatedText = null
        }

        private fun handleMute(muted: Boolean) {
            isMuted = muted
            if (isMuted && state == DialogueLoopState.SPEECH_DETECTED) {
                recordState(DialogueLoopState.LISTENING)
            }
        }

        private fun handleVadSpeechStart() {
            if (isMuted) return
            if (isAecGuardActive()) return
            if (state == DialogueLoopState.LISTENING) {
                recordState(DialogueLoopState.SPEECH_DETECTED)
            }
        }

        private fun handleVadSpeechEnd() {
            if (isMuted) return
            if (isAecGuardActive()) return
        }

        private fun handleSpeechRecognized(text: String) {
            if (isMuted || isAecGuardActive()) {
                if (state == DialogueLoopState.SPEECH_DETECTED) {
                    recordState(DialogueLoopState.LISTENING)
                }
                return
            }

            if (state != DialogueLoopState.SPEECH_DETECTED && state != DialogueLoopState.LISTENING) {
                return
            }

            val clean = text.trim()
            if (clean.isBlank()) {
                recordState(DialogueLoopState.LISTENING)
                return
            }

            currentSpokenText = clean
            recordState(DialogueLoopState.RECOGNIZED)

            val resolution = arbiter.arbitrate(clean, sessionPair, activeTurnContext)
            currentResolvedResult = resolution

            recordState(DialogueLoopState.TRANSLATING)
        }

        private fun handleTranslationCompleted(translatedText: String) {
            if (state != DialogueLoopState.TRANSLATING) return
            currentTranslatedText = translatedText
            recordState(DialogueLoopState.TTS_PLAYING)
        }

        private fun handleTtsStarted() {
            if (state != DialogueLoopState.TTS_PLAYING) {
                recordState(DialogueLoopState.TTS_PLAYING)
            }
        }

        private fun handleTtsFinished() {
            if (state != DialogueLoopState.TTS_PLAYING) return

            val res = currentResolvedResult
            if (res != null && currentSpokenText != null && currentTranslatedText != null) {
                val turnRecord = DialogueTurnRecord(
                    turnIndex = activeTurnContext.turnIndex + 1,
                    speakerLanguage = res.resolvedLanguage,
                    targetLanguage = res.targetLanguage,
                    sourceText = currentSpokenText!!,
                    translatedText = currentTranslatedText!!,
                    resolutionMethod = res.resolutionMethod
                )
                history.add(turnRecord)

                activeTurnContext = DialogueTurnContext(
                    previousLanguage = res.resolvedLanguage,
                    isSameSpeaker = false,
                    turnIndex = turnRecord.turnIndex
                )
            }

            reverbDrainStartTimeMs = timeProvider.now()
            recordState(DialogueLoopState.REVERB_DRAIN)
        }

        private fun handleReverbDrainExpired() {
            if (state != DialogueLoopState.REVERB_DRAIN) return
            val elapsed = timeProvider.now() - reverbDrainStartTimeMs
            if (elapsed >= reverbDrainDurationMs) {
                currentSpokenText = null
                currentResolvedResult = null
                currentTranslatedText = null
                recordState(DialogueLoopState.LISTENING)
            }
        }

        private fun handleError(message: String) {
            lastError = message
            recordState(DialogueLoopState.ERROR)
        }

        fun pollReverbDrain() {
            if (state == DialogueLoopState.REVERB_DRAIN) {
                val elapsed = timeProvider.now() - reverbDrainStartTimeMs
                if (elapsed >= reverbDrainDurationMs) {
                    handleReverbDrainExpired()
                }
            }
        }
    }

    private lateinit var arbiter: DialogueLanguageArbiter
    private lateinit var virtualTime: VirtualTimeProvider
    private lateinit var controller: ContinuousDialogueVadController
    private val pairRuEn = DialogueSessionPair(Language.RUSSIAN, Language.ENGLISH)
    private val pairViEn = DialogueSessionPair(Language.VIETNAMESE, Language.ENGLISH)

    @Before
    fun setUp() {
        arbiter = DialogueLanguageArbiter()
        virtualTime = VirtualTimeProvider(currentTimeMs = 1000L)
        controller = ContinuousDialogueVadController(
            sessionPair = pairRuEn,
            arbiter = arbiter,
            timeProvider = virtualTime,
            reverbDrainDurationMs = 300L
        )
    }

    // =========================================================================
    // 1. Continuous Dialogue State Machine Lifecycle Tests
    // =========================================================================

    @Test
    fun testFullContinuousCycle_StateSequenceIntegrity() {
        assertEquals(DialogueLoopState.IDLE, controller.state)

        controller.onEvent(DialogueLoopEvent.Start)
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.SPEECH_DETECTED, controller.state)

        val utterance = "Здравствуйте! Где находится музей?"
        controller.onEvent(DialogueLoopEvent.SpeechRecognized(utterance))
        assertEquals(DialogueLoopState.TRANSLATING, controller.state)

        val translation = "Hello! Where is the museum?"
        controller.onEvent(DialogueLoopEvent.TranslationCompleted(translation))
        assertEquals(DialogueLoopState.TTS_PLAYING, controller.state)

        controller.onEvent(DialogueLoopEvent.TtsFinished)
        assertEquals(DialogueLoopState.REVERB_DRAIN, controller.state)

        virtualTime.advanceTimeBy(150L)
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.REVERB_DRAIN, controller.state)

        virtualTime.advanceTimeBy(150L)
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        val expectedTrajectory = listOf(
            DialogueLoopState.IDLE,
            DialogueLoopState.START,
            DialogueLoopState.LISTENING,
            DialogueLoopState.SPEECH_DETECTED,
            DialogueLoopState.RECOGNIZED,
            DialogueLoopState.TRANSLATING,
            DialogueLoopState.TTS_PLAYING,
            DialogueLoopState.REVERB_DRAIN,
            DialogueLoopState.LISTENING
        )
        assertEquals(expectedTrajectory, controller.stateTransitions)
    }

    @Test
    fun testEmptyOrBlankSpeech_ReturnsToListeningWithoutTranslating() {
        controller.onEvent(DialogueLoopEvent.Start)
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.SPEECH_DETECTED, controller.state)

        controller.onEvent(DialogueLoopEvent.SpeechRecognized("   "))
        assertEquals(DialogueLoopState.LISTENING, controller.state)
        assertEquals(0, controller.turnHistory.size)
    }

    // =========================================================================
    // 2. AEC Guard & 300ms Reverb Drain Window Tests
    // =========================================================================

    @Test
    fun testAecGuard_DropsVadSpeechDuringTtsPlayback() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        controller.onEvent(DialogueLoopEvent.SpeechRecognized("How much is this ticket?"))
        controller.onEvent(DialogueLoopEvent.TranslationCompleted("Сколько стоит этот билет?"))

        assertEquals(DialogueLoopState.TTS_PLAYING, controller.state)
        assertTrue(controller.isAecGuardActive())

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.TTS_PLAYING, controller.state)

        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Сколько стоит этот билет?"))
        assertEquals(DialogueLoopState.TTS_PLAYING, controller.state)
    }

    @Test
    fun testAecGuard_DropsSpeechDuring300msReverbDrainWindow() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Good morning!"))
        controller.onEvent(DialogueLoopEvent.TranslationCompleted("Доброе утро!"))
        controller.onEvent(DialogueLoopEvent.TtsFinished)

        assertEquals(DialogueLoopState.REVERB_DRAIN, controller.state)
        assertTrue(controller.isAecGuardActive())

        virtualTime.advanceTimeBy(50L)
        assertTrue(controller.isAecGuardActive())
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.REVERB_DRAIN, controller.state)

        virtualTime.advanceTimeBy(130L)
        assertTrue(controller.isAecGuardActive())
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.REVERB_DRAIN, controller.state)

        virtualTime.advanceTimeBy(119L)
        assertTrue(controller.isAecGuardActive())
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.REVERB_DRAIN, controller.state)

        virtualTime.advanceTimeBy(1L)
        assertFalse(controller.isAecGuardActive())
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.SPEECH_DETECTED, controller.state)
    }

    @Test
    fun testAecGuard_AcceptsSpeechImmediatelyAfterDrainCooldownExpires() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Thank you very much."))
        controller.onEvent(DialogueLoopEvent.TranslationCompleted("Большое спасибо."))
        controller.onEvent(DialogueLoopEvent.TtsFinished)

        virtualTime.advanceTimeBy(350L)
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.LISTENING, controller.state)
        assertFalse(controller.isAecGuardActive())

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.SPEECH_DETECTED, controller.state)
        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Пожалуйста! Удачного дня!"))
        assertEquals(DialogueLoopState.TRANSLATING, controller.state)
    }

    // =========================================================================
    // 3. Arbiter Integration in Continuous Conversational Loop
    // =========================================================================

    @Test
    fun testArbiterIntegration_MultiTurnBidirectionalAlternation() {
        controller.onEvent(DialogueLoopEvent.Start)

        // Turn 1: Speaker A speaks Russian
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        val turn1Ru = "Извините, во сколько отправляется поезд?"
        controller.onEvent(DialogueLoopEvent.SpeechRecognized(turn1Ru))

        assertEquals(DialogueLoopState.TRANSLATING, controller.state)
        val turn1EnTrans = "Excuse me, what time does the train depart?"
        controller.onEvent(DialogueLoopEvent.TranslationCompleted(turn1EnTrans))
        controller.onEvent(DialogueLoopEvent.TtsFinished)

        virtualTime.advanceTimeBy(300L)
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        assertEquals(1, controller.turnHistory.size)
        val record1 = controller.turnHistory[0]
        assertEquals(1, record1.turnIndex)
        assertEquals(Language.RUSSIAN, record1.speakerLanguage)
        assertEquals(Language.ENGLISH, record1.targetLanguage)
        assertEquals(turn1Ru, record1.sourceText)
        assertEquals(turn1EnTrans, record1.translatedText)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, record1.resolutionMethod)

        // Turn 2: Speaker B speaks English
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        val turn2En = "The next train leaves at quarter past five from platform two."
        controller.onEvent(DialogueLoopEvent.SpeechRecognized(turn2En))

        assertEquals(DialogueLoopState.TRANSLATING, controller.state)
        val turn2RuTrans = "Следующий поезд отправляется в четверть шестого со второй платформы."
        controller.onEvent(DialogueLoopEvent.TranslationCompleted(turn2RuTrans))
        controller.onEvent(DialogueLoopEvent.TtsFinished)

        virtualTime.advanceTimeBy(300L)
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        assertEquals(2, controller.turnHistory.size)
        val record2 = controller.turnHistory[1]
        assertEquals(2, record2.turnIndex)
        assertEquals(Language.ENGLISH, record2.speakerLanguage)
        assertEquals(Language.RUSSIAN, record2.targetLanguage)
        assertEquals(turn2En, record2.sourceText)
        assertEquals(turn2RuTrans, record2.translatedText)
        assertEquals(ResolutionMethod.SCRIPT_DISPARITY, record2.resolutionMethod)

        // Turn 3: Speaker A responds in Russian
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        val turn3Ru = "Спасибо большое за помощь!"
        controller.onEvent(DialogueLoopEvent.SpeechRecognized(turn3Ru))

        assertEquals(DialogueLoopState.TRANSLATING, controller.state)
        val turn3EnTrans = "Thank you very much for your help!"
        controller.onEvent(DialogueLoopEvent.TranslationCompleted(turn3EnTrans))
        controller.onEvent(DialogueLoopEvent.TtsFinished)

        virtualTime.advanceTimeBy(300L)
        controller.pollReverbDrain()

        assertEquals(3, controller.turnHistory.size)
        val record3 = controller.turnHistory[2]
        assertEquals(3, record3.turnIndex)
        assertEquals(Language.RUSSIAN, record3.speakerLanguage)
        assertEquals(Language.ENGLISH, record3.targetLanguage)
    }

    @Test
    fun testArbiterIntegration_VietnameseAndEnglishDiacriticPair() {
        val viController = ContinuousDialogueVadController(
            sessionPair = pairViEn,
            arbiter = arbiter,
            timeProvider = virtualTime,
            reverbDrainDurationMs = 300L
        )
        viController.onEvent(DialogueLoopEvent.Start)

        // Turn 1: Vietnamese speaker
        viController.onEvent(DialogueLoopEvent.VadSpeechStart)
        val viUtterance = "Xin lỗi, cho tôi hỏi đường đến sân bay Nội Bài."
        viController.onEvent(DialogueLoopEvent.SpeechRecognized(viUtterance))

        assertEquals(DialogueLoopState.TRANSLATING, viController.state)
        val enTranslation = "Excuse me, could you tell me the way to Noi Bai airport?"
        viController.onEvent(DialogueLoopEvent.TranslationCompleted(enTranslation))
        viController.onEvent(DialogueLoopEvent.TtsFinished)

        virtualTime.advanceTimeBy(300L)
        viController.pollReverbDrain()

        assertEquals(1, viController.turnHistory.size)
        val rec1 = viController.turnHistory[0]
        assertEquals(Language.VIETNAMESE, rec1.speakerLanguage)
        assertEquals(Language.ENGLISH, rec1.targetLanguage)
        assertEquals(ResolutionMethod.DIACRITIC_FEATURE, rec1.resolutionMethod)

        // Turn 2: English speaker response
        viController.onEvent(DialogueLoopEvent.VadSpeechStart)
        val enUtterance = "You should take bus number 86 right across the street."
        viController.onEvent(DialogueLoopEvent.SpeechRecognized(enUtterance))

        assertEquals(DialogueLoopState.TRANSLATING, viController.state)
        val viTranslation = "Bạn nên bắt xe buýt số 86 ngay đối diện bên kia đường."
        viController.onEvent(DialogueLoopEvent.TranslationCompleted(viTranslation))
        viController.onEvent(DialogueLoopEvent.TtsFinished)

        virtualTime.advanceTimeBy(300L)
        viController.pollReverbDrain()

        assertEquals(2, viController.turnHistory.size)
        val rec2 = viController.turnHistory[1]
        assertEquals(Language.ENGLISH, rec2.speakerLanguage)
        assertEquals(Language.VIETNAMESE, rec2.targetLanguage)
    }

    // =========================================================================
    // 4. User Interruption & Mute Tests
    // =========================================================================

    @Test
    fun testUserInterruption_StopDuringListening() {
        controller.onEvent(DialogueLoopEvent.Start)
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        controller.onEvent(DialogueLoopEvent.Stop)
        assertEquals(DialogueLoopState.STOPPED, controller.state)

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.STOPPED, controller.state)
    }

    @Test
    fun testUserInterruption_StopDuringTranslating() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Where is the gate?"))
        assertEquals(DialogueLoopState.TRANSLATING, controller.state)

        controller.onEvent(DialogueLoopEvent.Stop)
        assertEquals(DialogueLoopState.STOPPED, controller.state)

        controller.onEvent(DialogueLoopEvent.TranslationCompleted("Где выход на посадку?"))
        assertEquals(DialogueLoopState.STOPPED, controller.state)
        assertEquals(0, controller.turnHistory.size)
    }

    @Test
    fun testUserInterruption_StopDuringTtsPlaying() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Hello"))
        controller.onEvent(DialogueLoopEvent.TranslationCompleted("Здравствуйте"))
        assertEquals(DialogueLoopState.TTS_PLAYING, controller.state)

        controller.onEvent(DialogueLoopEvent.Stop)
        assertEquals(DialogueLoopState.STOPPED, controller.state)

        virtualTime.advanceTimeBy(500L)
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.STOPPED, controller.state)
    }

    @Test
    fun testUserInterruption_StopDuringReverbDrain() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Hello"))
        controller.onEvent(DialogueLoopEvent.TranslationCompleted("Здравствуйте"))
        controller.onEvent(DialogueLoopEvent.TtsFinished)
        assertEquals(DialogueLoopState.REVERB_DRAIN, controller.state)

        controller.onEvent(DialogueLoopEvent.Stop)
        assertEquals(DialogueLoopState.STOPPED, controller.state)

        virtualTime.advanceTimeBy(300L)
        controller.pollReverbDrain()
        assertEquals(DialogueLoopState.STOPPED, controller.state)
    }

    @Test
    fun testUserMute_HaltsMicrophoneCaptureCleanly() {
        controller.onEvent(DialogueLoopEvent.Start)
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        controller.onEvent(DialogueLoopEvent.SetMute(isMuted = true))
        assertTrue(controller.isMuted)

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        controller.onEvent(DialogueLoopEvent.SpeechRecognized("Ignored text while muted"))
        assertEquals(DialogueLoopState.LISTENING, controller.state)

        controller.onEvent(DialogueLoopEvent.SetMute(isMuted = false))
        assertFalse(controller.isMuted)

        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.SPEECH_DETECTED, controller.state)
    }

    @Test
    fun testUserMute_DuringSpeechDetected_ResetsToListening() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.VadSpeechStart)
        assertEquals(DialogueLoopState.SPEECH_DETECTED, controller.state)

        controller.onEvent(DialogueLoopEvent.SetMute(isMuted = true))
        assertEquals(DialogueLoopState.LISTENING, controller.state)
        assertTrue(controller.isMuted)
    }

    // =========================================================================
    // 5. Error Recovery & Robustness Tests
    // =========================================================================

    @Test
    fun testErrorRecovery_StateTransitionToErrorAndCleanRestart() {
        controller.onEvent(DialogueLoopEvent.Start)
        controller.onEvent(DialogueLoopEvent.ErrorOccurred("STT model stream corrupted"))

        assertEquals(DialogueLoopState.ERROR, controller.state)
        assertEquals("STT model stream corrupted", controller.lastError)

        controller.onEvent(DialogueLoopEvent.Stop)
        assertEquals(DialogueLoopState.STOPPED, controller.state)

        controller.onEvent(DialogueLoopEvent.Start)
        assertEquals(DialogueLoopState.LISTENING, controller.state)
    }
}
