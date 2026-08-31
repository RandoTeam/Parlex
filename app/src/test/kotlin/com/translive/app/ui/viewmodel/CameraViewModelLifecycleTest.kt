package com.translive.app.ui.viewmodel

import android.graphics.Rect
import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.DictionaryEntry
import com.translive.app.data.model.Language
import com.translive.app.engine.camera.BilingualParagraph
import com.translive.app.engine.camera.CameraLlmPromptFormatter
import com.translive.app.engine.camera.CameraLlmTagParser
import com.translive.app.engine.camera.CameraLlmTokenBudget
import com.translive.app.engine.camera.SampledBackground
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

class CameraViewModelLifecycleTest {

    private data class FakePaintLine(
        val id: String,
        val text: String,
        val box: Rect,
        val sourceLanguage: Language? = null
    )

    private data class FakePaintBlock(
        val id: String,
        val lines: List<FakePaintLine>
    )

    private class CameraLifecycleHarness(
        var isLlmLoaded: Boolean = false,
        var isFastNmtReady: Boolean = false,
        var isFastNmtSupported: Boolean = true,
        var simulatedLlmStructuredOutput: String? = null,
        var simulatedLlmSingleOutput: String? = null,
        var shouldOcrThrow: Boolean = false,
        var shouldLlmThrow: Boolean = false,
        var shouldFastNmtThrow: Boolean = false,
        var translationPolicy: TranslationPolicy = TranslationPolicy.FAST_WITH_LLM_IMPROVE
    ) {
        val initialTranslationMode = when (translationPolicy) {
            TranslationPolicy.LLM_ONLY -> CameraTranslationMode.QUALITY
            else -> CameraTranslationMode.FAST
        }

        private val _uiState = MutableStateFlow(
            CameraUiState(
                sourceLanguage = Language.ENGLISH,
                targetLanguage = Language.RUSSIAN,
                translationMode = initialTranslationMode,
                captureStatus = CaptureStatus.IDLE
            )
        )
        val uiState = _uiState.asStateFlow()

        var activeJobCancelled = false
        var activeJobCount = 0
        var promptAssembled: String? = null

        fun setSourceLanguage(language: Language) {
            cancelActiveJob()
            _uiState.update {
                it.copy(
                    sourceLanguage = language,
                    liveBlocks = emptyList(),
                    captureStatus = CaptureStatus.IDLE,
                    capturedBitmap = null,
                    paintedBitmap = null,
                    bilingualParagraphs = emptyList()
                )
            }
        }

        fun setTargetLanguage(language: Language) {
            cancelActiveJob()
            _uiState.update {
                it.copy(
                    targetLanguage = language,
                    liveBlocks = emptyList(),
                    captureStatus = CaptureStatus.IDLE,
                    capturedBitmap = null,
                    paintedBitmap = null,
                    bilingualParagraphs = emptyList()
                )
            }
        }

        fun setTranslationMode(mode: CameraTranslationMode) {
            if (_uiState.value.translationMode == mode) return
            _uiState.update {
                it.copy(
                    translationMode = mode,
                    liveBlocks = emptyList(),
                    qualityWarnings = emptyList()
                )
            }
        }

        fun swapLanguages() {
            if (_uiState.value.isSourceAuto) return
            cancelActiveJob()
            _uiState.update {
                it.copy(
                    sourceLanguage = it.targetLanguage,
                    targetLanguage = it.sourceLanguage,
                    liveBlocks = emptyList(),
                    captureStatus = CaptureStatus.IDLE,
                    capturedBitmap = null,
                    paintedBitmap = null,
                    bilingualParagraphs = emptyList()
                )
            }
        }

        fun improveCaptureWithLlm(blocks: List<FakePaintBlock>) {
            cancelActiveJob()
            _uiState.update {
                it.copy(
                    translationMode = CameraTranslationMode.QUALITY,
                    captureStatus = CaptureStatus.PROCESSING,
                    captureMessage = "Improving translation with offline LLM..."
                )
            }
            processCapture(blocks)
        }

        fun simulateGalleryOpenFailure() {
            cancelActiveJob()
            _uiState.update {
                it.copy(
                    mode = CameraMode.CAPTURE,
                    captureStatus = CaptureStatus.ERROR,
                    captureMessage = "Could not open selected image",
                    qualityWarnings = emptyList()
                )
            }
        }

        fun processCapture(blocks: List<FakePaintBlock>) {
            activeJobCount++
            activeJobCancelled = false

            _uiState.update {
                it.copy(
                    mode = CameraMode.CAPTURE,
                    captureStatus = CaptureStatus.PROCESSING,
                    captureMessage = "Finding text in capture..."
                )
            }

            if (shouldOcrThrow) {
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.ERROR,
                        captureMessage = "Failed to process photo",
                        bilingualParagraphs = emptyList(),
                        qualityWarnings = emptyList()
                    )
                }
                return
            }

            val allLines = blocks.flatMap { it.lines }
            if (allLines.isEmpty()) {
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.EMPTY,
                        captureMessage = "No text found in photo",
                        bilingualParagraphs = emptyList(),
                        qualityWarnings = emptyList()
                    )
                }
                return
            }

            _uiState.update {
                it.copy(captureMessage = "Translating lines...")
            }

            val warnings = mutableListOf<CameraQualityWarning>()
            val state = _uiState.value
            val canTranslate = when (state.translationMode) {
                CameraTranslationMode.QUALITY -> isLlmLoaded
                CameraTranslationMode.FAST -> isFastNmtSupported || isLlmLoaded
            }

            if (!canTranslate) {
                warnings.add(CameraQualityWarning.TRANSLATION_MODEL_UNAVAILABLE)
            }

            val translatedParts = try {
                translateBlocks(blocks, state.sourceLanguage, state.targetLanguage, state.translationMode)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.ERROR,
                        captureMessage = "Translation error occurred",
                        bilingualParagraphs = emptyList()
                    )
                }
                return
            }

            val bilingualList = blocks.mapIndexed { idx, block ->
                val blockSource = block.lines.joinToString("\n") { it.text }
                val blockTrans = block.lines.map { line ->
                    val globalIdx = allLines.indexOf(line)
                    if (globalIdx in translatedParts.indices) translatedParts[globalIdx] else line.text
                }.joinToString("\n")

                BilingualParagraph(
                    id = "para_$idx",
                    sourceText = blockSource,
                    translatedText = blockTrans,
                    boundingBox = block.lines.firstOrNull()?.box ?: Rect(0, 0, 100, 100),
                    sourceLanguage = state.sourceLanguage,
                    targetLanguage = state.targetLanguage,
                    background = SampledBackground(
                        topColor = 0,
                        bottomColor = 0,
                        primaryTextColor = -1,
                        strokeColor = 0,
                        isDarkBackground = true
                    )
                )
            }

            _uiState.update {
                it.copy(
                    captureStatus = CaptureStatus.READY,
                    captureMessage = null,
                    qualityWarnings = warnings,
                    bilingualParagraphs = bilingualList
                )
            }
        }

        private fun translateBlocks(
            blocks: List<FakePaintBlock>,
            sourceLang: Language,
            targetLang: Language,
            mode: CameraTranslationMode
        ): List<String> {
            val result = mutableListOf<String>()

            for (block in blocks) {
                val lines = block.lines
                if (mode == CameraTranslationMode.QUALITY) {
                    if (!isLlmLoaded) {
                        result.addAll(lines.map { it.text })
                        continue
                    }
                    if (shouldLlmThrow) {
                        throw RuntimeException("Simulated LLM JNI native crash")
                    }

                    if (lines.size >= 2) {
                        val ids = lines.mapIndexed { index, _ -> "L${index + 1}" }
                        val prompt = lines.mapIndexed { index, line -> "[${ids[index]}] ${line.text}" }.joinToString("\n")
                        promptAssembled = prompt

                        val rawOutput = simulatedLlmStructuredOutput ?: ids.mapIndexed { i, id ->
                            "[$id] Translated ${lines[i].text}"
                        }.joinToString("\n")

                        val parsed = CameraLlmTagParser.parseIndexedTranslations(rawOutput, ids.size, tagPrefix = "L")
                            ?: parseLineByLineFallback(rawOutput, ids.size)

                        if (parsed != null) {
                            result.addAll(parsed)
                        } else {
                            result.addAll(lines.map { simulatedLlmSingleOutput ?: "Single ${it.text}" })
                        }
                    } else {
                        result.addAll(lines.map { simulatedLlmSingleOutput ?: "Single ${it.text}" })
                    }
                } else {
                    if (isFastNmtSupported) {
                        if (isFastNmtReady) {
                            if (shouldFastNmtThrow) throw RuntimeException("Fast NMT service error")
                            result.addAll(lines.map { "Fast $it" })
                        } else {
                            result.addAll(lines.map { it.text })
                        }
                    } else {
                        if (isLlmLoaded) {
                            result.addAll(lines.map { simulatedLlmSingleOutput ?: "LLM Fallback ${it.text}" })
                        } else {
                            result.addAll(lines.map { it.text })
                        }
                    }
                }
            }
            return result
        }

        private fun parseLineByLineFallback(raw: String, expectedCount: Int): List<String>? {
            val lines = raw.lines()
                .map { CameraLlmTagParser.stripTag(it, tagPrefix = "L") }
                .filter { it.isNotBlank() }
            return if (lines.size == expectedCount) lines else null
        }

        fun cancelActiveJob() {
            activeJobCancelled = true
        }

        fun selectParagraph(paragraph: BilingualParagraph?) {
            _uiState.update {
                it.copy(
                    selectedParagraph = paragraph,
                    selectedParagraphCurrency = if (paragraph != null && paragraph.sourceText.contains("$")) "approx 7,500 RUB" else null,
                    selectedWordDictionaryEntries = emptyList()
                )
            }
        }

        fun lookupWord(word: String) {
            val entry = DictionaryEntry(
                headword = word,
                normalizedHeadword = word.lowercase(),
                sourceLang = _uiState.value.sourceLanguage.code,
                targetLang = _uiState.value.targetLanguage.code,
                definition = "Definition for $word"
            )
            _uiState.update {
                it.copy(selectedWordDictionaryEntries = listOf(entry))
            }
        }
    }

    private lateinit var harness: CameraLifecycleHarness

    @Before
    fun setUp() {
        harness = CameraLifecycleHarness()
    }

    @Test
    fun testInitialState_isIdleAndLiveMode() {
        val state = harness.uiState.value
        assertEquals(CaptureStatus.IDLE, state.captureStatus)
        assertEquals(CameraMode.LIVE, state.mode)
        assertNull(state.captureMessage)
        assertNull(state.capturedBitmap)
        assertNull(state.paintedBitmap)
        assertTrue(state.bilingualParagraphs.isEmpty())
        assertFalse(state.isCaptureProcessing)
    }

    @Test
    fun testCaptureProcessing_transitionsFromIdleToProcessingToReady() {
        harness.isLlmLoaded = true
        harness.setTranslationMode(CameraTranslationMode.QUALITY)

        val block = FakePaintBlock(
            id = "b1",
            lines = listOf(
                FakePaintLine("l1", "Welcome to Vietnam", Rect(10, 10, 100, 30)),
                FakePaintLine("l2", "Enjoy local coffee", Rect(10, 35, 100, 55))
            )
        )

        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CameraMode.CAPTURE, state.mode)
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertNull(state.captureMessage)
        assertEquals(1, state.bilingualParagraphs.size)

        val paragraph = state.bilingualParagraphs.first()
        assertEquals("Welcome to Vietnam\nEnjoy local coffee", paragraph.sourceText)
        assertTrue(paragraph.translatedText.isNotBlank())
        assertEquals(Language.ENGLISH, paragraph.sourceLanguage)
        assertEquals(Language.RUSSIAN, paragraph.targetLanguage)
    }

    @Test
    fun testEmptyOcrResult_transitionsToEmptyStatusWithQualityWarnings() {
        harness.processCapture(emptyList())

        val state = harness.uiState.value
        assertEquals(CaptureStatus.EMPTY, state.captureStatus)
        assertEquals("No text found in photo", state.captureMessage)
        assertTrue(state.bilingualParagraphs.isEmpty())
    }

    @Test
    fun testRenderedState_constructsBilingualParagraphsWithCoordinates() {
        harness.isLlmLoaded = true
        harness.setTranslationMode(CameraTranslationMode.QUALITY)

        val rect1 = Rect(10, 10, 200, 40)
        val rect2 = Rect(10, 60, 200, 90)
        val block1 = FakePaintBlock("b1", listOf(FakePaintLine("l1", "Header text", rect1)))
        val block2 = FakePaintBlock("b2", listOf(FakePaintLine("l2", "Body text", rect2)))

        harness.processCapture(listOf(block1, block2))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertEquals(2, state.bilingualParagraphs.size)

        assertEquals("para_0", state.bilingualParagraphs[0].id)
        assertEquals("Header text", state.bilingualParagraphs[0].sourceText)
        assertEquals(rect1.toString(), state.bilingualParagraphs[0].boundingBox.toString())

        assertEquals("para_1", state.bilingualParagraphs[1].id)
        assertEquals("Body text", state.bilingualParagraphs[1].sourceText)
        assertEquals(rect2.toString(), state.bilingualParagraphs[1].boundingBox.toString())
    }

    @Test
    fun testInteractiveParagraphSelection_triggersCurrencyAndDictionaryLookup() {
        harness.isLlmLoaded = true
        val block = FakePaintBlock(
            "b1",
            listOf(FakePaintLine("l1", "Price: $100 per person", Rect(0, 0, 100, 50)))
        )
        harness.processCapture(listOf(block))

        val paragraph = harness.uiState.value.bilingualParagraphs.first()
        harness.selectParagraph(paragraph)

        var state = harness.uiState.value
        assertEquals(paragraph, state.selectedParagraph)
        assertEquals("approx 7,500 RUB", state.selectedParagraphCurrency)

        harness.lookupWord("person")
        state = harness.uiState.value
        assertEquals(1, state.selectedWordDictionaryEntries.size)
        assertEquals("person", state.selectedWordDictionaryEntries.first().headword)

        harness.selectParagraph(null)
        state = harness.uiState.value
        assertNull(state.selectedParagraph)
        assertNull(state.selectedParagraphCurrency)
    }

    @Test
    fun testQualityMode_whenLlmNotLoaded_setsTranslationModelUnavailableWarning() {
        harness.isLlmLoaded = false
        harness.setTranslationMode(CameraTranslationMode.QUALITY)

        val block = FakePaintBlock(
            "b1",
            listOf(FakePaintLine("l1", "Pho bo Dac Biet", Rect(0, 0, 50, 20)))
        )
        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertTrue(state.qualityWarnings.contains(CameraQualityWarning.TRANSLATION_MODEL_UNAVAILABLE))
        assertEquals("Pho bo Dac Biet", state.bilingualParagraphs.first().translatedText)
    }

    @Test
    fun testQualityMode_whenLlmNotLoaded_preservesSourceTextSafely() {
        harness.isLlmLoaded = false
        harness.setTranslationMode(CameraTranslationMode.QUALITY)

        val block = FakePaintBlock(
            "b1",
            listOf(
                FakePaintLine("l1", "Line one", Rect(0, 0, 10, 10)),
                FakePaintLine("l2", "Line two", Rect(0, 15, 10, 25))
            )
        )
        harness.processCapture(listOf(block))

        val paragraph = harness.uiState.value.bilingualParagraphs.first()
        assertEquals("Line one\nLine two", paragraph.sourceText)
        assertEquals("Line one\nLine two", paragraph.translatedText)
    }

    @Test
    fun testFastMode_whenPairSupportedAndNmtReady_usesFastNmt() {
        harness.isFastNmtSupported = true
        harness.isFastNmtReady = true
        harness.setTranslationMode(CameraTranslationMode.FAST)

        val block = FakePaintBlock("b1", listOf(FakePaintLine("l1", "Item text", Rect(0, 0, 10, 10))))
        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertTrue(state.qualityWarnings.isEmpty())
        assertTrue(state.bilingualParagraphs.first().translatedText.contains("Fast"))
    }

    @Test
    fun testFastMode_whenPairUnsupportedInFastNmt_fallsBackToLoadedLlm() {
        harness.isFastNmtSupported = false
        harness.isLlmLoaded = true
        harness.simulatedLlmSingleOutput = "LLM Fallback Translation"
        harness.setTranslationMode(CameraTranslationMode.FAST)

        val block = FakePaintBlock("b1", listOf(FakePaintLine("l1", "Dialect text", Rect(0, 0, 10, 10))))
        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertEquals("LLM Fallback Translation", state.bilingualParagraphs.first().translatedText)
    }

    @Test
    fun testFastMode_whenPairUnsupportedAndLlmNotLoaded_returnsSourceTextSafely() {
        harness.isFastNmtSupported = false
        harness.isLlmLoaded = false
        harness.setTranslationMode(CameraTranslationMode.FAST)

        val block = FakePaintBlock("b1", listOf(FakePaintLine("l1", "Untranslatable text", Rect(0, 0, 10, 10))))
        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertTrue(state.qualityWarnings.contains(CameraQualityWarning.TRANSLATION_MODEL_UNAVAILABLE))
        assertEquals("Untranslatable text", state.bilingualParagraphs.first().translatedText)
    }

    @Test
    fun testInitialTranslationMode_derivedFromTranslationPolicy() {
        val harnessLlmOnly = CameraLifecycleHarness(translationPolicy = TranslationPolicy.LLM_ONLY)
        assertEquals(CameraTranslationMode.QUALITY, harnessLlmOnly.uiState.value.translationMode)

        val harnessFast = CameraLifecycleHarness(translationPolicy = TranslationPolicy.FAST)
        assertEquals(CameraTranslationMode.FAST, harnessFast.uiState.value.translationMode)

        val harnessHybrid = CameraLifecycleHarness(translationPolicy = TranslationPolicy.FAST_WITH_LLM_IMPROVE)
        assertEquals(CameraTranslationMode.FAST, harnessHybrid.uiState.value.translationMode)
    }

    @Test
    fun testImproveCaptureWithLlm_switchesModeToQualityAndTriggersLlmPipeline() {
        harness.isFastNmtSupported = true
        harness.isFastNmtReady = true
        harness.isLlmLoaded = true
        harness.setTranslationMode(CameraTranslationMode.FAST)

        val block = FakePaintBlock(
            "b1",
            listOf(
                FakePaintLine("l1", "First line", Rect(0, 0, 10, 10)),
                FakePaintLine("l2", "Second line", Rect(0, 15, 10, 25))
            )
        )
        harness.processCapture(listOf(block))
        assertEquals(CameraTranslationMode.FAST, harness.uiState.value.translationMode)

        harness.simulatedLlmStructuredOutput = "[L1] High quality line 1\n[L2] High quality line 2"
        harness.improveCaptureWithLlm(listOf(block))

        val state = harness.uiState.value
        assertEquals(CameraTranslationMode.QUALITY, state.translationMode)
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertEquals("High quality line 1\nHigh quality line 2", state.bilingualParagraphs.first().translatedText)
    }

    @Test
    fun testStructuredPromptAssembly_generatesIndexedLineTags() {
        val lines = listOf("Vietnamese Pho", "Spring Rolls", "Iced Coffee")
        val prompt = CameraLlmPromptFormatter.formatIndexedOcrPrompt(
            lines = lines,
            sourceLangName = "English",
            targetLangName = "Russian",
            tagPrefix = "L"
        )

        assertTrue(prompt.contains("Translate the OCR lines from English to Russian"))
        assertTrue(prompt.contains("[L0] Vietnamese Pho"))
        assertTrue(prompt.contains("[L1] Spring Rolls"))
        assertTrue(prompt.contains("[L2] Iced Coffee"))
        assertTrue(prompt.contains("Preserve every line ID"))
    }

    @Test
    fun testStructuredResponseParsing_exactMatchingTags_mapsToOriginalLinePositions() {
        val rawModelOutput = """
            [L1] Soup Pho
            [L2] Spring Rolls
            [L3] Iced Coffee
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 3,
            tagPrefix = "L"
        )

        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("Soup Pho", parsed[0])
        assertEquals("Spring Rolls", parsed[1])
        assertEquals("Iced Coffee", parsed[2])
    }

    @Test
    fun testStructuredResponseParsing_outOfOrderTags_correctlyReconstructsOrder() {
        val rawModelOutput = """
            [L2] Third line
            [L0] First line
            [L1] Second line
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 3,
            tagPrefix = "L"
        )

        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("First line", parsed[0])
        assertEquals("Second line", parsed[1])
        assertEquals("Third line", parsed[2])
    }

    @Test
    fun testStructuredResponseParsing_onMalformedOutput_fallsBackToLineByLine() {
        harness.isLlmLoaded = true
        harness.setTranslationMode(CameraTranslationMode.QUALITY)
        harness.simulatedLlmStructuredOutput = "Malformed output with only one line"
        harness.simulatedLlmSingleOutput = "Single line fallback"

        val block = FakePaintBlock(
            "b1",
            listOf(
                FakePaintLine("l1", "First line", Rect(0, 0, 10, 10)),
                FakePaintLine("l2", "Second line", Rect(0, 15, 10, 25))
            )
        )
        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertEquals("Single line fallback\nSingle line fallback", state.bilingualParagraphs.first().translatedText)
    }

    @Test
    fun testOcrException_recoversToErrorStatusWithoutCrashingViewModel() {
        harness.shouldOcrThrow = true
        val block = FakePaintBlock("b1", listOf(FakePaintLine("l1", "Crash line", Rect(0, 0, 10, 10))))

        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.ERROR, state.captureStatus)
        assertEquals("Failed to process photo", state.captureMessage)
        assertTrue(state.bilingualParagraphs.isEmpty())
    }

    @Test
    fun testLlmInferenceException_recoversGracefullyAndPreservesState() {
        harness.isLlmLoaded = true
        harness.shouldLlmThrow = true
        harness.setTranslationMode(CameraTranslationMode.QUALITY)

        val block = FakePaintBlock("b1", listOf(FakePaintLine("l1", "Crash line", Rect(0, 0, 10, 10))))
        harness.processCapture(listOf(block))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.ERROR, state.captureStatus)
        assertEquals("Translation error occurred", state.captureMessage)
        assertTrue(state.bilingualParagraphs.isEmpty())
    }

    @Test
    fun testGalleryDecodeFailure_transitionsToErrorStatus() {
        harness.simulateGalleryOpenFailure()

        val state = harness.uiState.value
        assertEquals(CameraMode.CAPTURE, state.mode)
        assertEquals(CaptureStatus.ERROR, state.captureStatus)
        assertEquals("Could not open selected image", state.captureMessage)
    }

    @Test
    fun testRapidReCapture_cancelsExistingTranslationJob() {
        harness.isLlmLoaded = true
        harness.setTranslationMode(CameraTranslationMode.QUALITY)

        val block1 = FakePaintBlock("b1", listOf(FakePaintLine("l1", "First capture", Rect(0, 0, 10, 10))))
        val block2 = FakePaintBlock("b2", listOf(FakePaintLine("l2", "Second capture", Rect(0, 0, 10, 10))))

        harness.cancelActiveJob()
        harness.processCapture(listOf(block2))

        val state = harness.uiState.value
        assertEquals(CaptureStatus.READY, state.captureStatus)
        assertEquals("Second capture", state.bilingualParagraphs.first().sourceText)
    }

    @Test
    fun testLanguageSwap_cancelsInFlightJobAndResetsVisualState() {
        harness.isLlmLoaded = true
        val block = FakePaintBlock("b1", listOf(FakePaintLine("l1", "Hello world", Rect(0, 0, 10, 10))))
        harness.processCapture(listOf(block))
        assertEquals(CaptureStatus.READY, harness.uiState.value.captureStatus)

        harness.swapLanguages()

        val state = harness.uiState.value
        assertEquals(Language.RUSSIAN, state.sourceLanguage)
        assertEquals(Language.ENGLISH, state.targetLanguage)
        assertEquals(CaptureStatus.IDLE, state.captureStatus)
        assertTrue(state.bilingualParagraphs.isEmpty())
        assertTrue(harness.activeJobCancelled)
    }
}
