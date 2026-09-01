package com.translive.app.engine

import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.ModelVariant
import kotlinx.coroutines.Dispatchers
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
import java.util.Locale

/**
 * Pure JVM Unit Test Suite for Translation Mode Lifecycle & Policies.
 */
class TranslationModeLifecycleTest {

    data class TranslationStats(
        val promptTokens: Int = 0,
        val generatedTokens: Int = 0,
        val totalTimeMs: Long = 0,
        val tokensPerSecond: Float = 0f,
        val backend: String? = null,
        val hasNativeTokenMetrics: Boolean = false
    )

    data class TranslationEntryRecord(
        val sourceLanguage: String,
        val targetLanguage: String,
        val sourceText: String,
        val translatedText: String
    )

    data class TranslationUiState(
        val sourceLanguage: Language = Language.RUSSIAN,
        val isSourceAuto: Boolean = false,
        val targetLanguage: Language = Language.ENGLISH,
        val sourceText: String = "",
        val translatedText: String = "",
        val isTranslating: Boolean = false,
        val isModelLoaded: Boolean = false,
        val isModelLoading: Boolean = false,
        val activeModelName: String? = null,
        val error: String? = null,
        val llmImproveUnavailableReason: String? = null,
        val stats: TranslationStats? = null,
        val isFastResult: Boolean = false,
        val canImproveWithLlm: Boolean = false,
        val isImprovingWithLlm: Boolean = false,
        val fastTranslationText: String = "",
        val fastNmtMissing: Boolean = false,
        val targetTransliteration: String? = null
    )

    class FakeSettingsRepository {
        var translationPolicy: TranslationPolicy = TranslationPolicy.FAST_WITH_LLM_IMPROVE
        var showTransliteration: Boolean = false
        var threads: Int = 4
        var backend: String = "cpu"
    }

    class FakeModelRepository {
        var activeModelPath: String? = null
        var activeRuntime: ModelRuntime = ModelRuntime.GGUF
        var activeVariant: ModelVariant? = null
    }

    class FakeFastTranslateEngine {
        val downloadedPairs = mutableSetOf<String>()
        var simulateTranslationLatencyMs: Long = 10
        var translationCallCount = 0
        var shouldThrow = false

        fun activateDownloadedPair(from: String, to: String): Boolean {
            return downloadedPairs.contains("$from->$to") || downloadedPairs.contains("${from}_$to")
        }

        fun translate(text: String): String {
            if (shouldThrow) throw IllegalStateException("Fast NMT native crash")
            translationCallCount++
            return "FastNMT: $text"
        }
    }

    class FakeTranslationEngine {
        var isLoaded = false
        var loadModelCallCount = 0
        var unloadModelCallCount = 0
        var streamTranslateCallCount = 0
        var currentBackend: String? = "cpu"
        val inferenceMutex = Mutex()
        var simulatedTokens = listOf("LLM", " ", "translated", " ", "text")
        var shouldThrow = false

        fun loadModel(path: String, threads: Int, backend: String): Boolean {
            loadModelCallCount++
            isLoaded = true
            return true
        }

        fun unloadModel() {
            unloadModelCallCount++
            isLoaded = false
        }

        suspend fun translateStreaming(
            sourceText: String,
            source: Language,
            target: Language,
            onComplete: (promptTokens: Int, genTokens: Int) -> Unit
        ): List<String> {
            if (shouldThrow) throw IllegalStateException("LLM inference error")
            streamTranslateCallCount++
            onComplete(15, simulatedTokens.size)
            return simulatedTokens
        }
    }

    class FakeTransliterationEngine {
        fun transliterate(text: String, language: Language): String = "Translit($text)"
    }

    class FakeTranslationDao {
        val insertedEntries = mutableListOf<TranslationEntryRecord>()

        fun insertTranslation(entry: TranslationEntryRecord) {
            insertedEntries.add(entry)
        }
    }

    class TranslationModeHarness(
        val settings: FakeSettingsRepository = FakeSettingsRepository(),
        val modelRepository: FakeModelRepository = FakeModelRepository(),
        val fastTranslateEngine: FakeFastTranslateEngine = FakeFastTranslateEngine(),
        val engine: FakeTranslationEngine = FakeTranslationEngine(),
        val transliterationEngine: FakeTransliterationEngine = FakeTransliterationEngine(),
        val dao: FakeTranslationDao = FakeTranslationDao()
    ) {
        private val _uiState = MutableStateFlow(TranslationUiState())
        val uiState = _uiState.asStateFlow()

        fun setSourceText(text: String) {
            _uiState.update { it.copy(sourceText = text) }
        }

        fun setLanguages(source: Language, target: Language) {
            _uiState.update { it.copy(sourceLanguage = source, targetLanguage = target) }
        }

        fun loadModel() {
            if (_uiState.value.isModelLoaded || _uiState.value.isModelLoading) return

            when (settings.translationPolicy) {
                TranslationPolicy.FAST -> {
                    _uiState.update {
                        it.copy(
                            isModelLoading = false,
                            isModelLoaded = false,
                            error = null,
                            llmImproveUnavailableReason = null
                        )
                    }
                }
                TranslationPolicy.FAST_WITH_LLM_IMPROVE -> {
                    val path = modelRepository.activeModelPath
                    if (path == null) {
                        _uiState.update {
                            it.copy(
                                isModelLoading = false,
                                isModelLoaded = false,
                                error = null,
                                llmImproveUnavailableReason = "Улучшенный перевод недоступен без скачанной LLM-модели"
                            )
                        }
                        return
                    }
                    _uiState.update { it.copy(isModelLoading = true, error = null) }
                    val loaded = engine.loadModel(path, settings.threads, settings.backend)
                    _uiState.update {
                        it.copy(
                            isModelLoaded = loaded,
                            isModelLoading = false,
                            activeModelName = if (loaded) "Hy-MT-1.5B (CPU)" else null,
                            error = null,
                            llmImproveUnavailableReason = if (!loaded) "Улучшенный перевод недоступен: не удалось инициализировать LLM" else null
                        )
                    }
                }
                TranslationPolicy.LLM_ONLY -> {
                    _uiState.update { it.copy(isModelLoading = true, error = null, llmImproveUnavailableReason = null) }
                    val path = modelRepository.activeModelPath
                    if (path == null) {
                        _uiState.update {
                            it.copy(
                                isModelLoading = false,
                                isModelLoaded = false,
                                error = "No translation model selected"
                            )
                        }
                        return
                    }
                    val loaded = engine.loadModel(path, settings.threads, settings.backend)
                    _uiState.update {
                        it.copy(
                            isModelLoaded = loaded,
                            isModelLoading = false,
                            activeModelName = if (loaded) "Hy-MT-1.5B (CPU)" else null,
                            error = if (!loaded) "Failed to load model" else null
                        )
                    }
                }
            }
        }

        fun translate() {
            val state = _uiState.value
            if (state.sourceText.isBlank() || state.isTranslating) return

            when (settings.translationPolicy) {
                TranslationPolicy.FAST -> translateFast()
                TranslationPolicy.FAST_WITH_LLM_IMPROVE -> translateFastWithImproveOption()
                TranslationPolicy.LLM_ONLY -> translateWithLlm()
            }
        }

        private fun translateFast() {
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    isTranslating = true,
                    error = null,
                    stats = null,
                    translatedText = "",
                    isFastResult = false,
                    canImproveWithLlm = false,
                    isImprovingWithLlm = false,
                    fastTranslationText = "",
                    fastNmtMissing = false,
                    targetTransliteration = null
                )
            }

            try {
                val activated = fastTranslateEngine.activateDownloadedPair(
                    state.sourceLanguage.code, state.targetLanguage.code
                )
                if (!activated) {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            fastNmtMissing = true,
                            error = "Install fast translation packages for this language pair in Models"
                        )
                    }
                    return
                }

                val result = fastTranslateEngine.translate(state.sourceText)
                val tgtTrans = if (settings.showTransliteration) {
                    transliterationEngine.transliterate(result, state.targetLanguage)
                } else null

                _uiState.update {
                    it.copy(
                        translatedText = result,
                        targetTransliteration = tgtTrans,
                        isTranslating = false,
                        isFastResult = true,
                        canImproveWithLlm = false,
                        stats = TranslationStats(totalTimeMs = 12)
                    )
                }

                dao.insertTranslation(
                    TranslationEntryRecord(
                        sourceLanguage = state.sourceLanguage.code,
                        targetLanguage = state.targetLanguage.code,
                        sourceText = state.sourceText,
                        translatedText = result
                    )
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTranslating = false, error = "Fast translation error: ${e.message}")
                }
            }
        }

        private fun translateFastWithImproveOption() {
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    isTranslating = true,
                    error = null,
                    stats = null,
                    translatedText = "",
                    isFastResult = false,
                    canImproveWithLlm = false,
                    isImprovingWithLlm = false,
                    fastTranslationText = "",
                    fastNmtMissing = false
                )
            }

            try {
                val activated = fastTranslateEngine.activateDownloadedPair(
                    state.sourceLanguage.code, state.targetLanguage.code
                )
                if (activated) {
                    val fastResult = fastTranslateEngine.translate(state.sourceText)
                    val tgtTrans = if (settings.showTransliteration) {
                        transliterationEngine.transliterate(fastResult, state.targetLanguage)
                    } else null

                    _uiState.update {
                        it.copy(
                            translatedText = fastResult,
                            targetTransliteration = tgtTrans,
                            isTranslating = false,
                            isFastResult = true,
                            canImproveWithLlm = true,
                            fastTranslationText = fastResult,
                            stats = TranslationStats(totalTimeMs = 15)
                        )
                    }

                    dao.insertTranslation(
                        TranslationEntryRecord(
                            sourceLanguage = state.sourceLanguage.code,
                            targetLanguage = state.targetLanguage.code,
                            sourceText = state.sourceText,
                            translatedText = fastResult
                        )
                    )
                } else {
                    translateWithLlm()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTranslating = false, error = "Translation error: ${e.message}")
                }
            }
        }

        fun improveWithLlm() {
            val state = _uiState.value
            if (state.isImprovingWithLlm || state.sourceText.isBlank()) return

            _uiState.update { it.copy(isImprovingWithLlm = true, canImproveWithLlm = false, error = null) }

            if (!engine.isLoaded) {
                loadModel()
                if (!_uiState.value.isModelLoaded) {
                    _uiState.update {
                        it.copy(
                            isImprovingWithLlm = false,
                            canImproveWithLlm = true,
                            error = it.error ?: "No translation model selected"
                        )
                    }
                    return
                }
            }

            try {
                var promptTok = 0
                var genTok = 0
                val tokens = runBlocking {
                    engine.inferenceMutex.withLock {
                        engine.translateStreaming(
                            sourceText = state.sourceText,
                            source = state.sourceLanguage,
                            target = state.targetLanguage,
                            onComplete = { p, g ->
                                promptTok = p
                                genTok = g
                            }
                        )
                    }
                }

                val result = tokens.joinToString("").trim()
                val tgtTrans = if (settings.showTransliteration) {
                    transliterationEngine.transliterate(result, state.targetLanguage)
                } else null

                _uiState.update {
                    it.copy(
                        translatedText = result,
                        targetTransliteration = tgtTrans,
                        isImprovingWithLlm = false,
                        isFastResult = false,
                        canImproveWithLlm = false,
                        stats = TranslationStats(
                            promptTokens = promptTok,
                            generatedTokens = genTok,
                            totalTimeMs = 150,
                            tokensPerSecond = 25.0f,
                            backend = engine.currentBackend,
                            hasNativeTokenMetrics = true
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImprovingWithLlm = false,
                        canImproveWithLlm = true,
                        error = "LLM improvement error: ${e.message}"
                    )
                }
            }
        }

        private fun translateWithLlm() {
            val state = _uiState.value
            if (state.sourceText.isBlank() || state.isTranslating) return

            if (!engine.isLoaded) {
                _uiState.update { it.copy(isModelLoaded = false) }
                loadModel()
                if (!_uiState.value.isModelLoaded) {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            error = it.error ?: "No translation model selected"
                        )
                    }
                    return
                }
            }

            _uiState.update {
                it.copy(
                    isTranslating = true,
                    error = null,
                    stats = null,
                    translatedText = "",
                    isFastResult = false,
                    canImproveWithLlm = false,
                    isImprovingWithLlm = false,
                    fastTranslationText = "",
                    fastNmtMissing = false
                )
            }

            try {
                var promptTok = 0
                var genTok = 0
                val tokens = runBlocking {
                    engine.inferenceMutex.withLock {
                        engine.translateStreaming(
                            sourceText = state.sourceText,
                            source = state.sourceLanguage,
                            target = state.targetLanguage,
                            onComplete = { p, g ->
                                promptTok = p
                                genTok = g
                            }
                        )
                    }
                }

                val result = tokens.joinToString("").trim()
                val tgtTrans = if (settings.showTransliteration) {
                    transliterationEngine.transliterate(result, state.targetLanguage)
                } else null

                _uiState.update {
                    it.copy(
                        translatedText = result,
                        targetTransliteration = tgtTrans,
                        isTranslating = false,
                        isFastResult = false,
                        canImproveWithLlm = false,
                        stats = TranslationStats(
                            promptTokens = promptTok,
                            generatedTokens = genTok,
                            totalTimeMs = 200,
                            tokensPerSecond = 20.0f,
                            backend = engine.currentBackend,
                            hasNativeTokenMetrics = true
                        )
                    )
                }

                dao.insertTranslation(
                    TranslationEntryRecord(
                        sourceLanguage = state.sourceLanguage.code,
                        targetLanguage = state.targetLanguage.code,
                        sourceText = state.sourceText,
                        translatedText = result
                    )
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTranslating = false, error = "LLM translation error: ${e.message}")
                }
            }
        }
    }

    object TranslationBadgeFormatter {
        fun formatProvenanceBadge(isFast: Boolean, isLlmRefining: Boolean = false): String {
            return when {
                isLlmRefining -> "NMT (улучшение...)"
                isFast -> "NMT"
                else -> "LLM"
            }
        }

        fun formatPolicyLabel(policy: TranslationPolicy): String = when (policy) {
            TranslationPolicy.FAST -> "Fast"
            TranslationPolicy.FAST_WITH_LLM_IMPROVE -> "Fast + Improve"
            TranslationPolicy.LLM_ONLY -> "LLM Direct"
        }

        fun formatPolicyDescription(policy: TranslationPolicy): String = when (policy) {
            TranslationPolicy.FAST -> "Offline compact translation only"
            TranslationPolicy.FAST_WITH_LLM_IMPROVE -> "Fast translation with on-demand LLM refinement"
            TranslationPolicy.LLM_ONLY -> "High quality translation directly via on-device LLM"
        }

        fun formatLanguageDirectionTag(source: Language, target: Language): String =
            "[${source.code.uppercase(Locale.ROOT)} -> ${target.code.uppercase(Locale.ROOT)}]"
    }

    private fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (
                cp in 0x1F300..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x1F1E6..0x1F1FF ||
                cp in 0x1F900..0x1F9FF ||
                cp in 0x1F600..0x1F64F ||
                cp in 0x1F680..0x1F6FF
            ) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    private lateinit var harness: TranslationModeHarness

    @Before
    fun setUp() {
        harness = TranslationModeHarness()
        harness.fastTranslateEngine.downloadedPairs.add("ru->en")
        harness.fastTranslateEngine.downloadedPairs.add("en->ru")
    }

    @Test
    fun testFastOnly_translatesSuccessfullyWithoutLlmModel() {
        harness.settings.translationPolicy = TranslationPolicy.FAST
        harness.modelRepository.activeModelPath = null
        harness.setLanguages(Language.RUSSIAN, Language.ENGLISH)
        harness.setSourceText("Привет, мир!")

        harness.loadModel()

        assertNull(harness.uiState.value.error)
        assertNull(harness.uiState.value.llmImproveUnavailableReason)
        assertFalse(harness.uiState.value.isModelLoaded)

        harness.translate()

        val state = harness.uiState.value
        assertEquals("FastNMT: Привет, мир!", state.translatedText)
        assertTrue(state.isFastResult)
        assertFalse(state.canImproveWithLlm)
        assertFalse(state.isImprovingWithLlm)
        assertNull(state.error)
        assertFalse(state.fastNmtMissing)
        assertFalse(state.isModelLoaded)

        assertEquals(0, harness.engine.loadModelCallCount)
        assertEquals(0, harness.engine.streamTranslateCallCount)
        assertEquals(1, harness.fastTranslateEngine.translationCallCount)
    }

    @Test
    fun testFastOnly_neverTriggersLlmLoadOrInference() {
        harness.settings.translationPolicy = TranslationPolicy.FAST
        harness.modelRepository.activeModelPath = "/sdcard/models/hy_mt_1.5b.gguf"
        harness.setLanguages(Language.RUSSIAN, Language.ENGLISH)
        harness.setSourceText("Короткий текст")

        harness.loadModel()
        harness.translate()

        assertEquals(0, harness.engine.loadModelCallCount)
        assertEquals(0, harness.engine.streamTranslateCallCount)
        assertEquals("FastNMT: Короткий текст", harness.uiState.value.translatedText)
        assertTrue(harness.uiState.value.isFastResult)
        assertFalse(harness.uiState.value.canImproveWithLlm)
    }

    @Test
    fun testFastImprove_worksSeamlesslyWithoutDownloadedLlm() {
        harness.settings.translationPolicy = TranslationPolicy.FAST_WITH_LLM_IMPROVE
        harness.modelRepository.activeModelPath = null
        harness.setLanguages(Language.RUSSIAN, Language.ENGLISH)
        harness.setSourceText("Перевод без скачанной LLM")

        harness.loadModel()

        assertNull(harness.uiState.value.error)
        assertEquals("Улучшенный перевод недоступен без скачанной LLM-модели", harness.uiState.value.llmImproveUnavailableReason)

        harness.translate()

        val state = harness.uiState.value
        assertEquals("FastNMT: Перевод без скачанной LLM", state.translatedText)
        assertEquals("FastNMT: Перевод без скачанной LLM", state.fastTranslationText)
        assertTrue(state.isFastResult)
        assertTrue(state.canImproveWithLlm)
        assertFalse(state.isImprovingWithLlm)
        assertNull(state.error)

        assertEquals(1, harness.fastTranslateEngine.translationCallCount)
        assertEquals(0, harness.engine.loadModelCallCount)
    }

    @Test
    fun testFastImprove_whenLlmDownloaded_subsequentImproveWithLlmUpgradesResult() {
        harness.settings.translationPolicy = TranslationPolicy.FAST_WITH_LLM_IMPROVE
        harness.modelRepository.activeModelPath = "/sdcard/models/hy_mt.gguf"
        harness.setLanguages(Language.RUSSIAN, Language.ENGLISH)
        harness.setSourceText("Хороший день для прогулки")

        harness.loadModel()
        assertTrue(harness.uiState.value.isModelLoaded)
        assertNull(harness.uiState.value.llmImproveUnavailableReason)

        harness.translate()

        assertTrue(harness.uiState.value.isFastResult)
        assertTrue(harness.uiState.value.canImproveWithLlm)

        harness.improveWithLlm()

        val upgradedState = harness.uiState.value
        assertEquals("LLM translated text", upgradedState.translatedText)
        assertFalse(upgradedState.isFastResult)
        assertFalse(upgradedState.canImproveWithLlm)
        assertFalse(upgradedState.isImprovingWithLlm)
        assertNotNull(upgradedState.stats)
        assertEquals(1, harness.engine.loadModelCallCount)
        assertEquals(1, harness.engine.streamTranslateCallCount)
    }

    @Test
    fun testLlmDirect_requiresLlmAndSetsErrorIfMissing() {
        harness.settings.translationPolicy = TranslationPolicy.LLM_ONLY
        harness.modelRepository.activeModelPath = null
        harness.setLanguages(Language.RUSSIAN, Language.ENGLISH)
        harness.setSourceText("Сложное предложение для LLM")

        harness.loadModel()

        assertNotNull(harness.uiState.value.error)
        assertEquals("No translation model selected", harness.uiState.value.error)
        assertNull(harness.uiState.value.llmImproveUnavailableReason)

        harness.translate()

        val state = harness.uiState.value
        assertEquals("", state.translatedText)
        assertFalse(state.isTranslating)
        assertFalse(state.isModelLoaded)

        assertEquals(0, harness.fastTranslateEngine.translationCallCount)
        assertEquals(0, harness.engine.streamTranslateCallCount)
    }

    @Test
    fun testZeroEmojiCompliance_acrossAllTranslationPoliciesAndBadges() {
        for (policy in TranslationPolicy.entries) {
            val label = TranslationBadgeFormatter.formatPolicyLabel(policy)
            val desc = TranslationBadgeFormatter.formatPolicyDescription(policy)

            assertFalse("Policy label '$label' must not contain emoji", containsEmoji(label))
            assertFalse("Policy description '$desc' must not contain emoji", containsEmoji(desc))
        }

        val fastBadge = TranslationBadgeFormatter.formatProvenanceBadge(isFast = true, isLlmRefining = false)
        val llmBadge = TranslationBadgeFormatter.formatProvenanceBadge(isFast = false, isLlmRefining = false)
        val refiningBadge = TranslationBadgeFormatter.formatProvenanceBadge(isFast = false, isLlmRefining = true)

        assertEquals("NMT", fastBadge)
        assertEquals("LLM", llmBadge)
        assertEquals("NMT (улучшение...)", refiningBadge)

        assertFalse(containsEmoji(fastBadge))
        assertFalse(containsEmoji(llmBadge))
        assertFalse(containsEmoji(refiningBadge))
    }
}
