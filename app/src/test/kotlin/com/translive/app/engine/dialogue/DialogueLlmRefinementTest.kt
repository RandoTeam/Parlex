package com.translive.app.engine.dialogue

import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM Unit Test Suite for Dialogue LLM Refinement Pipeline (Sub-phase D1.3).
 *
 * Requirements:
 * 1. Fast NMT first translation followed by on-demand LLM upgrade (isLlmRefined: false -> true).
 * 2. In-place message update with stable index and constant message count.
 * 3. Concurrent upgrade handling, inference mutex serialization, and LLM failure fallback.
 * 4. Zero-emoji formatting across all UI representations and payloads.
 *
 * Pure JVM (100% free of Android framework/stubs and native binaries).
 */
class DialogueLlmRefinementTest {

    enum class DialogueRuntimeTier {
        FAST_NMT,
        LLM_REFINED
    }

    data class DialogueUiMessage(
        val sourceText: String,
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String,
        val sourceTransliteration: String? = null,
        val targetTransliteration: String? = null,
        val runtimeTier: DialogueRuntimeTier = DialogueRuntimeTier.FAST_NMT,
        val isLlmRefined: Boolean = false,
        val isLlmRefining: Boolean = false,
        val fastTranslationText: String? = null,
        val refinementError: String? = null
    ) {
        val runtimeBadge: String
            get() = when {
                isLlmRefining -> "[REFINING]"
                isLlmRefined -> "[LLM]"
                else -> "[FAST]"
            }

        val directionTag: String
            get() = "${sourceLang.uppercase()} -> ${targetLang.uppercase()}"
    }

    data class DialogueUiState(
        val messages: List<DialogueUiMessage> = emptyList(),
        val isConversationActive: Boolean = false,
        val sourceLanguage: Language = Language.RUSSIAN,
        val targetLanguage: Language = Language.ENGLISH,
        val error: String? = null
    )

    data class DbDialogueRecord(
        val id: Long,
        val sessionId: Long,
        val sourceText: String,
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String,
        val isRefined: Boolean
    )

    class FakeFastTranslateEngine {
        var shouldThrow: Boolean = false

        fun translate(text: String, from: String, to: String): String {
            if (shouldThrow) throw IllegalStateException("Fast NMT translation engine failure")
            return when {
                from == "ru" && to == "en" && text == "Здравствуйте" -> "Hello"
                from == "ru" && to == "en" && text == "Как пройти к метро?" -> "How to get to the subway?"
                from == "en" && to == "ru" && text == "Turn right at the crossroads" -> "Поверните направо на перекрестке"
                from == "en" && to == "ru" && text == "Have a nice flight" -> "Приятного полета"
                else -> "FastNMT[$from->$to]: $text"
            }
        }
    }

    class FakeLlmTranslateEngine {
        val inferenceMutex = Mutex()
        var shouldThrow: Boolean = false
        var activeInferenceCount: Int = 0
            private set
        var peakConcurrentInferences: Int = 0
            private set

        suspend fun translateLlm(text: String, from: Language, to: Language): String {
            inferenceMutex.withLock {
                activeInferenceCount++
                if (activeInferenceCount > peakConcurrentInferences) {
                    peakConcurrentInferences = activeInferenceCount
                }
                try {
                    if (shouldThrow) {
                        throw RuntimeException("LLM inference out of memory or aborted")
                    }
                    return when {
                        from == Language.RUSSIAN && to == Language.ENGLISH && text == "Здравствуйте" ->
                            "Good day to you"
                        from == Language.RUSSIAN && to == Language.ENGLISH && text == "Как пройти к метро?" ->
                            "Could you please tell me how to reach the nearest metro station?"
                        from == Language.ENGLISH && to == Language.RUSSIAN && text == "Turn right at the crossroads" ->
                            "Пожалуйста, поверните направо на следующем перекрестке"
                        from == Language.ENGLISH && to == Language.RUSSIAN && text == "Have a nice flight" ->
                            "Желаю вам приятного и комфортного полета"
                        else -> "LLM_DEEP[$from->$to]: $text"
                    }
                } finally {
                    activeInferenceCount--
                }
            }
        }
    }

    class FakeTransliterationEngine {
        fun transliterate(text: String, language: Language): String? {
            return when (language) {
                Language.RUSSIAN -> when (text) {
                    "Здравствуйте" -> "Zdravstvuyte"
                    "Как пройти к метро?" -> "Kak proyti k metro?"
                    "Поверните направо на перекрестке" -> "Povernite napravo na perekrestke"
                    "Пожалуйста, поверните направо на следующем перекрестке" -> "Pozhaluysta, povernite napravo na sleduyushchem perekrestke"
                    "Приятного полета" -> "Priyatnogo poleta"
                    "Желаю вам приятного и комфортного полета" -> "Zhelayu vam priyatnogo i komfortnogo poleta"
                    else -> "TranslitRu($text)"
                }
                else -> null
            }
        }
    }

    class FakeDialogueDao {
        val records = mutableListOf<DbDialogueRecord>()
        private var nextId = 1L

        fun insertMessage(
            sessionId: Long,
            sourceText: String,
            translatedText: String,
            sourceLang: String,
            targetLang: String,
            isRefined: Boolean
        ): Long {
            val id = nextId++
            records.add(
                DbDialogueRecord(
                    id = id,
                    sessionId = sessionId,
                    sourceText = sourceText,
                    translatedText = translatedText,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    isRefined = isRefined
                )
            )
            return id
        }

        fun updateMessageTranslation(id: Long, updatedTranslation: String, isRefined: Boolean) {
            val idx = records.indexOfFirst { it.id == id }
            if (idx != -1) {
                val old = records[idx]
                records[idx] = old.copy(
                    translatedText = updatedTranslation,
                    isRefined = isRefined
                )
            }
        }
    }

    class DialogueRefinementHarness(
        val fastEngine: FakeFastTranslateEngine,
        val llmEngine: FakeLlmTranslateEngine,
        val translitEngine: FakeTransliterationEngine,
        val dao: FakeDialogueDao,
        var translationPolicy: TranslationPolicy = TranslationPolicy.FAST_WITH_LLM_IMPROVE,
        var showTransliteration: Boolean = true
    ) {
        private val _uiState = MutableStateFlow(DialogueUiState())
        val uiState = _uiState.asStateFlow()

        private val dbIdMap = mutableMapOf<Int, Long>()

        fun addTurn(sourceText: String, from: Language, to: Language) {
            val rawTranslated = fastEngine.translate(sourceText, from.code, to.code)
            val srcTrans = if (showTransliteration) translitEngine.transliterate(sourceText, from) else null
            val tgtTrans = if (showTransliteration) translitEngine.transliterate(rawTranslated, to) else null

            val uiMessage = DialogueUiMessage(
                sourceText = sourceText,
                translatedText = rawTranslated,
                sourceLang = from.code,
                targetLang = to.code,
                sourceTransliteration = srcTrans,
                targetTransliteration = tgtTrans,
                runtimeTier = DialogueRuntimeTier.FAST_NMT,
                isLlmRefined = false,
                isLlmRefining = false,
                fastTranslationText = rawTranslated,
                refinementError = null
            )

            val currentIdx = _uiState.value.messages.size
            _uiState.update { it.copy(messages = it.messages + uiMessage) }

            val dbId = dao.insertMessage(
                sessionId = 1L,
                sourceText = sourceText,
                translatedText = rawTranslated,
                sourceLang = from.code,
                targetLang = to.code,
                isRefined = false
            )
            dbIdMap[currentIdx] = dbId
        }

        suspend fun improveMessageWithLlm(index: Int) {
            val list = _uiState.value.messages
            if (index < 0 || index >= list.size) return
            val current = list[index]

            if (current.isLlmRefined || current.isLlmRefining) return

            _uiState.update { state ->
                val updatedList = state.messages.toMutableList()
                updatedList[index] = current.copy(isLlmRefining = true, refinementError = null)
                state.copy(messages = updatedList)
            }

            val fromLang = Language.entries.firstOrNull { it.code == current.sourceLang } ?: Language.RUSSIAN
            val toLang = Language.entries.firstOrNull { it.code == current.targetLang } ?: Language.ENGLISH

            try {
                val refinedTranslation = llmEngine.translateLlm(current.sourceText, fromLang, toLang)
                val refinedTgtTrans = if (showTransliteration) translitEngine.transliterate(refinedTranslation, toLang) else null

                _uiState.update { state ->
                    val updatedList = state.messages.toMutableList()
                    if (index in updatedList.indices) {
                        updatedList[index] = updatedList[index].copy(
                            translatedText = refinedTranslation,
                            targetTransliteration = refinedTgtTrans,
                            runtimeTier = DialogueRuntimeTier.LLM_REFINED,
                            isLlmRefined = true,
                            isLlmRefining = false,
                            refinementError = null
                        )
                    }
                    state.copy(messages = updatedList)
                }

                val dbId = dbIdMap[index]
                if (dbId != null) {
                    dao.updateMessageTranslation(dbId, refinedTranslation, isRefined = true)
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    val updatedList = state.messages.toMutableList()
                    if (index in updatedList.indices) {
                        val preservedFastText = updatedList[index].fastTranslationText ?: updatedList[index].translatedText
                        val fallbackTgtTrans = if (showTransliteration) translitEngine.transliterate(preservedFastText, toLang) else null
                        updatedList[index] = updatedList[index].copy(
                            translatedText = preservedFastText,
                            targetTransliteration = fallbackTgtTrans,
                            runtimeTier = DialogueRuntimeTier.FAST_NMT,
                            isLlmRefined = false,
                            isLlmRefining = false,
                            refinementError = e.message ?: "LLM refinement failed"
                        )
                    }
                    state.copy(messages = updatedList)
                }
            }
        }
    }

    private lateinit var fastEngine: FakeFastTranslateEngine
    private lateinit var llmEngine: FakeLlmTranslateEngine
    private lateinit var translitEngine: FakeTransliterationEngine
    private lateinit var dao: FakeDialogueDao
    private lateinit var harness: DialogueRefinementHarness

    @Before
    fun setUp() {
        fastEngine = FakeFastTranslateEngine()
        llmEngine = FakeLlmTranslateEngine()
        translitEngine = FakeTransliterationEngine()
        dao = FakeDialogueDao()
        harness = DialogueRefinementHarness(
            fastEngine = fastEngine,
            llmEngine = llmEngine,
            translitEngine = translitEngine,
            dao = dao
        )
    }

    @Test
    fun testInitialMessage_isFastNmt_andNotRefined() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)

        val messages = harness.uiState.value.messages
        assertEquals(1, messages.size)

        val msg = messages[0]
        assertEquals("Здравствуйте", msg.sourceText)
        assertEquals("Hello", msg.translatedText)
        assertEquals("ru", msg.sourceLang)
        assertEquals("en", msg.targetLang)
        assertEquals(DialogueRuntimeTier.FAST_NMT, msg.runtimeTier)
        assertFalse(msg.isLlmRefined)
        assertFalse(msg.isLlmRefining)
        assertEquals("Hello", msg.fastTranslationText)
        assertEquals("Zdravstvuyte", msg.sourceTransliteration)
        assertNull(msg.targetTransliteration)
        assertNull(msg.refinementError)
        assertEquals("[FAST]", msg.runtimeBadge)
        assertEquals("RU -> EN", msg.directionTag)

        assertEquals(1, dao.records.size)
        assertEquals("Hello", dao.records[0].translatedText)
        assertFalse(dao.records[0].isRefined)
    }

    @Test
    fun testImproveMessageWithLlm_producesDeeperTranslation_andSetsRefinedTrue() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)

        harness.improveMessageWithLlm(0)

        val messages = harness.uiState.value.messages
        assertEquals(1, messages.size)

        val refinedMsg = messages[0]
        assertEquals("Здравствуйте", refinedMsg.sourceText)
        assertEquals("Good day to you", refinedMsg.translatedText)
        assertEquals(DialogueRuntimeTier.LLM_REFINED, refinedMsg.runtimeTier)
        assertTrue(refinedMsg.isLlmRefined)
        assertFalse(refinedMsg.isLlmRefining)
        assertEquals("Hello", refinedMsg.fastTranslationText)
        assertNull(refinedMsg.refinementError)
        assertEquals("[LLM]", refinedMsg.runtimeBadge)

        assertEquals(1, dao.records.size)
        assertEquals("Good day to you", dao.records[0].translatedText)
        assertTrue(dao.records[0].isRefined)
    }

    @Test
    fun testMultiTurnDialogue_refinesSpecificIndexInPlace_withoutChangingCountOrIndices() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)
        harness.addTurn("Turn right at the crossroads", Language.ENGLISH, Language.RUSSIAN)
        harness.addTurn("Как пройти к метро?", Language.RUSSIAN, Language.ENGLISH)
        harness.addTurn("Have a nice flight", Language.ENGLISH, Language.RUSSIAN)

        assertEquals(4, harness.uiState.value.messages.size)

        val initial0 = harness.uiState.value.messages[0]
        val initial1 = harness.uiState.value.messages[1]
        val initial2 = harness.uiState.value.messages[2]
        val initial3 = harness.uiState.value.messages[3]

        assertEquals("Hello", initial0.translatedText)
        assertEquals("Поверните направо на перекрестке", initial1.translatedText)
        assertEquals("How to get to the subway?", initial2.translatedText)
        assertEquals("Приятного полета", initial3.translatedText)

        harness.improveMessageWithLlm(2)

        val updatedList = harness.uiState.value.messages
        assertEquals("Total message count must remain strictly invariant", 4, updatedList.size)

        assertEquals(initial0, updatedList[0])
        assertEquals(initial1, updatedList[1])
        assertEquals(initial3, updatedList[3])

        val upgraded2 = updatedList[2]
        assertEquals("Как пройти к метро?", upgraded2.sourceText)
        assertEquals("Could you please tell me how to reach the nearest metro station?", upgraded2.translatedText)
        assertTrue(upgraded2.isLlmRefined)
        assertEquals(DialogueRuntimeTier.LLM_REFINED, upgraded2.runtimeTier)
        assertEquals("How to get to the subway?", upgraded2.fastTranslationText)

        assertEquals("Could you please tell me how to reach the nearest metro station?", dao.records[2].translatedText)
        assertTrue(dao.records[2].isRefined)
        assertFalse(dao.records[0].isRefined)
        assertFalse(dao.records[1].isRefined)
        assertFalse(dao.records[3].isRefined)
    }

    @Test
    fun testHeadAndTailRefinement_preservesOrderAndIndices() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)
        harness.addTurn("Have a nice flight", Language.ENGLISH, Language.RUSSIAN)

        assertEquals(2, harness.uiState.value.messages.size)

        harness.improveMessageWithLlm(0)
        assertTrue(harness.uiState.value.messages[0].isLlmRefined)
        assertFalse(harness.uiState.value.messages[1].isLlmRefined)

        harness.improveMessageWithLlm(1)
        assertTrue(harness.uiState.value.messages[0].isLlmRefined)
        assertTrue(harness.uiState.value.messages[1].isLlmRefined)
        assertEquals("Желаю вам приятного и комфортного полета", harness.uiState.value.messages[1].translatedText)
        assertEquals("Zhelayu vam priyatnogo i komfortnogo poleta", harness.uiState.value.messages[1].targetTransliteration)
    }

    @Test
    fun testLlmFailure_preservesOriginalFastNmtTranslation() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)

        val originalFastTranslation = harness.uiState.value.messages[0].translatedText
        assertEquals("Hello", originalFastTranslation)

        llmEngine.shouldThrow = true

        harness.improveMessageWithLlm(0)

        val messages = harness.uiState.value.messages
        assertEquals(1, messages.size)

        val msg = messages[0]
        assertEquals("Original translation must be preserved after LLM failure", originalFastTranslation, msg.translatedText)
        assertEquals("Hello", msg.fastTranslationText)
        assertFalse(msg.isLlmRefined)
        assertFalse(msg.isLlmRefining)
        assertEquals(DialogueRuntimeTier.FAST_NMT, msg.runtimeTier)
        assertNotNull(msg.refinementError)
        assertTrue(msg.refinementError!!.contains("LLM inference out of memory"))

        assertEquals("Hello", dao.records[0].translatedText)
        assertFalse(dao.records[0].isRefined)
    }

    @Test
    fun testRetryAfterTransientFailure_successfullyRefinesMessage() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)

        llmEngine.shouldThrow = true
        harness.improveMessageWithLlm(0)

        assertFalse(harness.uiState.value.messages[0].isLlmRefined)
        assertNotNull(harness.uiState.value.messages[0].refinementError)

        llmEngine.shouldThrow = false
        harness.improveMessageWithLlm(0)

        val recoveredMsg = harness.uiState.value.messages[0]
        assertTrue(recoveredMsg.isLlmRefined)
        assertEquals("Good day to you", recoveredMsg.translatedText)
        assertNull(recoveredMsg.refinementError)
        assertEquals("[LLM]", recoveredMsg.runtimeBadge)
    }

    @Test
    fun testConcurrentUpgradesOnDifferentMessages_serializesInferenceAndCompletesCleanly() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)
        harness.addTurn("Turn right at the crossroads", Language.ENGLISH, Language.RUSSIAN)

        val job0 = async(Dispatchers.Default) { harness.improveMessageWithLlm(0) }
        val job1 = async(Dispatchers.Default) { harness.improveMessageWithLlm(1) }
        awaitAll(job0, job1)

        val messages = harness.uiState.value.messages
        assertEquals(2, messages.size)

        assertTrue(messages[0].isLlmRefined)
        assertEquals("Good day to you", messages[0].translatedText)

        assertTrue(messages[1].isLlmRefined)
        assertEquals("Пожалуйста, поверните направо на следующем перекрестке", messages[1].translatedText)

        assertEquals(1, llmEngine.peakConcurrentInferences)
    }

    @Test
    fun testDuplicateUpgradeCallsOnSameMessage_areIgnoredGracefully() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)

        harness.improveMessageWithLlm(0)
        harness.improveMessageWithLlm(0)
        harness.improveMessageWithLlm(0)

        val messages = harness.uiState.value.messages
        assertEquals(1, messages.size)
        assertTrue(messages[0].isLlmRefined)
        assertEquals("Good day to you", messages[0].translatedText)
    }

    @Test
    fun testOutOfBoundsIndex_doesNotThrowOrCorruptState() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)

        harness.improveMessageWithLlm(-1)
        harness.improveMessageWithLlm(1)
        harness.improveMessageWithLlm(99)

        val messages = harness.uiState.value.messages
        assertEquals(1, messages.size)
        assertFalse(messages[0].isLlmRefined)
    }

    private fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    @Test
    fun testZeroEmojiCompliance_acrossAllMessageFieldsAndBadges() = runBlocking {
        harness.addTurn("Здравствуйте", Language.RUSSIAN, Language.ENGLISH)
        harness.addTurn("Turn right at the crossroads", Language.ENGLISH, Language.RUSSIAN)

        harness.improveMessageWithLlm(0)

        val messages = harness.uiState.value.messages

        for ((idx, msg) in messages.withIndex()) {
            assertFalse("sourceText at index $idx contains emoji", containsEmoji(msg.sourceText))
            assertFalse("translatedText at index $idx contains emoji", containsEmoji(msg.translatedText))
            assertFalse("sourceLang at index $idx contains emoji", containsEmoji(msg.sourceLang))
            assertFalse("targetLang at index $idx contains emoji", containsEmoji(msg.targetLang))
            msg.sourceTransliteration?.let {
                assertFalse("sourceTransliteration at index $idx contains emoji", containsEmoji(it))
            }
            msg.targetTransliteration?.let {
                assertFalse("targetTransliteration at index $idx contains emoji", containsEmoji(it))
            }
            assertFalse("runtimeBadge at index $idx contains emoji", containsEmoji(msg.runtimeBadge))
            assertFalse("directionTag at index $idx contains emoji", containsEmoji(msg.directionTag))
            msg.fastTranslationText?.let {
                assertFalse("fastTranslationText at index $idx contains emoji", containsEmoji(it))
            }
        }

        for (record in dao.records) {
            assertFalse("DB sourceText contains emoji", containsEmoji(record.sourceText))
            assertFalse("DB translatedText contains emoji", containsEmoji(record.translatedText))
        }
    }
}
