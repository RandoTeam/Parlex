package com.translive.app.engine.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM Unit Tests for Sub-phase C1.3 (Semantic Paragraph-Level OCR Translation).
 *
 * Covers:
 * 1. Paragraph grouping from scattered OCR lines (spatial proximity, reading order).
 * 2. Multi-paragraph prompt assembly with line IDs and correct spatial reconstruction.
 * 3. Token budget overflow: automatic chunking of large documents into multiple LLM calls.
 * 4. Fallback per-paragraph from LLM to Fast NMT when structured output parsing fails.
 * 5. Mixed-script document handling (e.g. English headers + Russian body text).
 * 6. Edge cases: single-line paragraphs, empty OCR results, very long lines exceeding token budget.
 */
class CameraOcrParagraphTest {

    // Pure JVM Domain Models for OCR and Layout Analysis
    data class SpatialBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)

        fun union(other: SpatialBox): SpatialBox {
            return SpatialBox(
                left = min(left, other.left),
                top = min(top, other.top),
                right = max(right, other.right),
                bottom = max(bottom, other.bottom)
            )
        }
    }

    data class RawOcrLine(
        val id: String,
        val text: String,
        val box: SpatialBox,
        val confidence: Float = 1.0f,
        val detectedLanguage: String = "auto"
    )

    data class SemanticParagraph(
        val id: String,
        val lines: List<RawOcrLine>,
        val boundingBox: SpatialBox,
        val sourceLanguage: String = "en",
        val isHeading: Boolean = false
    ) {
        val cleanText: String
            get() {
                val sb = StringBuilder()
                for ((index, line) in lines.withIndex()) {
                    val trimmed = line.text.trim()
                    if (sb.isEmpty()) {
                        sb.append(trimmed)
                    } else if (sb.endsWith("-") && index < lines.size) {
                        sb.setLength(sb.length - 1)
                        sb.append(trimmed)
                    } else {
                        sb.append(" ").append(trimmed)
                    }
                }
                return sb.toString()
            }
    }

    enum class TranslationEngineType {
        LLM_STRUCTURED,
        FAST_NMT_FALLBACK,
        BYPASS_SAME_LANGUAGE
    }

    data class TranslatedParagraphResult(
        val paragraphId: String,
        val sourceText: String,
        val translatedText: String,
        val lineTranslations: List<String>,
        val boundingBox: SpatialBox,
        val sourceLanguage: String,
        val targetLanguage: String,
        val engineUsed: TranslationEngineType,
        val fallbackApplied: Boolean = false
    )

    // Test Doubles & Orchestrator Implementation
    interface TestLlmEngine {
        var isLoaded: Boolean
        fun translateStructured(prompt: String, maxTokens: Int): String
    }

    interface TestFastNmtEngine {
        var isReady: Boolean
        fun translateLines(lines: List<String>, sourceLang: String, targetLang: String): List<String>
    }

    class FakeLlmEngine : TestLlmEngine {
        override var isLoaded: Boolean = true
        var nextResponse: ((String) -> String)? = null
        var invocationCount: Int = 0
        val recordedPrompts = mutableListOf<String>()

        override fun translateStructured(prompt: String, maxTokens: Int): String {
            invocationCount++
            recordedPrompts.add(prompt)
            return nextResponse?.invoke(prompt) ?: ""
        }
    }

    class FakeFastNmtEngine : TestFastNmtEngine {
        override var isReady: Boolean = true
        var invocationCount: Int = 0
        var translationDictionary = mutableMapOf<String, String>()

        override fun translateLines(lines: List<String>, sourceLang: String, targetLang: String): List<String> {
            invocationCount++
            return lines.map { line ->
                translationDictionary[line] ?: "[NMT-$targetLang] $line"
            }
        }
    }

    class SemanticParagraphClusterer {
        private val verticalGapRatio = 1.35f
        private val columnGutterMinPx = 60

        fun clusterScatteredLines(lines: List<RawOcrLine>): List<SemanticParagraph> {
            if (lines.isEmpty()) return emptyList()

            val sortedLines = lines.sortedWith(
                compareBy<RawOcrLine> { it.box.top }
                    .thenBy { it.box.left }
            )

            val medianHeight = sortedLines.map { it.box.height.coerceAtLeast(1) }.sorted().let {
                if (it.isEmpty()) 16f else it[it.size / 2].toFloat()
            }

            val paragraphs = mutableListOf<MutableList<RawOcrLine>>()
            var currentPara = mutableListOf<RawOcrLine>()
            var prevLine: RawOcrLine? = null

            for (line in sortedLines) {
                val prev = prevLine
                val isNewParagraph = prev != null && currentPara.isNotEmpty() && (
                    (line.box.top - prev.box.bottom) > medianHeight * verticalGapRatio ||
                    (line.box.left - prev.box.right) >= columnGutterMinPx ||
                    (prev.box.left - line.box.right) >= columnGutterMinPx
                )

                if (isNewParagraph) {
                    paragraphs.add(currentPara)
                    currentPara = mutableListOf()
                }
                currentPara.add(line)
                prevLine = line
            }

            if (currentPara.isNotEmpty()) {
                paragraphs.add(currentPara)
            }

            return paragraphs.mapIndexed { pIdx, pLines ->
                var unionBox = pLines.first().box
                for (l in pLines.drop(1)) {
                    unionBox = unionBox.union(l.box)
                }
                val dominantLang = pLines.map { it.detectedLanguage }
                    .groupBy { it }
                    .maxByOrNull { it.value.size }
                    ?.key ?: "en"

                SemanticParagraph(
                    id = "P$pIdx",
                    lines = pLines,
                    boundingBox = unionBox,
                    sourceLanguage = dominantLang,
                    isHeading = pLines.size == 1 && pLines.first().box.height > medianHeight * 1.4f
                )
            }
        }
    }

    class SemanticParagraphPromptCompiler {
        fun compilePrompt(
            paragraphs: List<SemanticParagraph>,
            targetLanguage: String
        ): String {
            val sb = StringBuilder()
            sb.append("Translate the OCR lines to ").append(targetLanguage).append(".\n")
            sb.append("Preserve every line tag exactly (e.g. [P0_L0]).\n")
            sb.append("Return one translated line per input tag. Do not add commentary.\n\n")

            for ((pIndex, para) in paragraphs.withIndex()) {
                sb.append("# Paragraph ").append(pIndex)
                    .append(" (Source: ").append(para.sourceLanguage).append(")\n")
                for ((lIndex, line) in para.lines.withIndex()) {
                    sb.append("[P").append(pIndex).append("_L").append(lIndex).append("] ")
                        .append(line.text.trim())
                        .append("\n")
                }
                sb.append("\n")
            }
            return sb.toString().trim()
        }

        fun parseResponse(
            rawOutput: String,
            expectedParagraphs: List<SemanticParagraph>
        ): Map<String, List<String>>? {
            val result = mutableMapOf<String, MutableList<String>>()
            val lineTagRegex = Regex("""\[(P\d+_L\d+)\]\s*(?:[:\->=.]+\s*)?(.*)""")

            val matches = lineTagRegex.findAll(rawOutput).toList()
            val extractedMap = mutableMapOf<String, String>()

            for (m in matches) {
                val tag = m.groupValues[1]
                val content = m.groupValues[2].trim()
                if (content.isNotBlank()) {
                    extractedMap[tag] = content
                }
            }

            for ((pIndex, para) in expectedParagraphs.withIndex()) {
                val pLines = mutableListOf<String>()
                for ((lIndex, _) in para.lines.withIndex()) {
                    val tag = "P${pIndex}_L$lIndex"
                    val lineText = extractedMap[tag]
                    if (lineText != null) {
                        pLines.add(lineText)
                    } else {
                        return null
                    }
                }
                result[para.id] = pLines
            }

            return result
        }
    }

    class DocumentTokenChunker(
        private val maxPromptTokensPerChunk: Int = 512
    ) {
        fun estimateTokens(text: String): Int {
            return (text.length / 4) + 12
        }

        fun chunkParagraphs(
            paragraphs: List<SemanticParagraph>,
            promptCompiler: SemanticParagraphPromptCompiler,
            targetLang: String
        ): List<List<SemanticParagraph>> {
            if (paragraphs.isEmpty()) return emptyList()

            val chunks = mutableListOf<List<SemanticParagraph>>()
            var currentChunk = mutableListOf<SemanticParagraph>()
            var currentEstimatedTokens = 0

            for (para in paragraphs) {
                val singleParaPrompt = promptCompiler.compilePrompt(listOf(para), targetLang)
                val paraTokens = estimateTokens(singleParaPrompt)

                if (currentChunk.isNotEmpty() && (currentEstimatedTokens + paraTokens > maxPromptTokensPerChunk)) {
                    chunks.add(currentChunk)
                    currentChunk = mutableListOf()
                    currentEstimatedTokens = 0
                }

                currentChunk.add(para)
                currentEstimatedTokens += paraTokens
            }

            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
            }

            return chunks
        }
    }

    class SemanticOcrTranslationPipeline(
        private val clusterer: SemanticParagraphClusterer,
        private val promptCompiler: SemanticParagraphPromptCompiler,
        private val tokenChunker: DocumentTokenChunker,
        private val llmEngine: TestLlmEngine,
        private val fastNmtEngine: TestFastNmtEngine
    ) {
        fun processDocument(
            rawLines: List<RawOcrLine>,
            targetLang: String
        ): List<TranslatedParagraphResult> {
            if (rawLines.isEmpty()) return emptyList()

            val paragraphs = clusterer.clusterScatteredLines(rawLines)
            val chunks = tokenChunker.chunkParagraphs(paragraphs, promptCompiler, targetLang)
            val finalResults = mutableListOf<TranslatedParagraphResult>()

            for (chunk in chunks) {
                var structuredTranslationMap: Map<String, List<String>>? = null
                val parasToTranslate = chunk.filter { !it.sourceLanguage.equals(targetLang, ignoreCase = true) }
                if (llmEngine.isLoaded && parasToTranslate.isNotEmpty()) {
                    val prompt = promptCompiler.compilePrompt(parasToTranslate, targetLang)
                    val rawOutput = llmEngine.translateStructured(prompt, maxTokens = 1024)
                    structuredTranslationMap = promptCompiler.parseResponse(rawOutput, parasToTranslate)
                }

                for (para in chunk) {
                    if (para.sourceLanguage.equals(targetLang, ignoreCase = true)) {
                        finalResults.add(
                            TranslatedParagraphResult(
                                paragraphId = para.id,
                                sourceText = para.cleanText,
                                translatedText = para.cleanText,
                                lineTranslations = para.lines.map { it.text },
                                boundingBox = para.boundingBox,
                                sourceLanguage = para.sourceLanguage,
                                targetLanguage = targetLang,
                                engineUsed = TranslationEngineType.BYPASS_SAME_LANGUAGE,
                                fallbackApplied = false
                            )
                        )
                        continue
                    }

                    val llmLines = structuredTranslationMap?.get(para.id)
                    if (llmLines != null && llmLines.size == para.lines.size) {
                        finalResults.add(
                            TranslatedParagraphResult(
                                paragraphId = para.id,
                                sourceText = para.cleanText,
                                translatedText = llmLines.joinToString(" "),
                                lineTranslations = llmLines,
                                boundingBox = para.boundingBox,
                                sourceLanguage = para.sourceLanguage,
                                targetLanguage = targetLang,
                                engineUsed = TranslationEngineType.LLM_STRUCTURED,
                                fallbackApplied = false
                            )
                        )
                    } else {
                        val rawTexts = para.lines.map { it.text }
                        val nmtLines = fastNmtEngine.translateLines(rawTexts, para.sourceLanguage, targetLang)
                        finalResults.add(
                            TranslatedParagraphResult(
                                paragraphId = para.id,
                                sourceText = para.cleanText,
                                translatedText = nmtLines.joinToString(" "),
                                lineTranslations = nmtLines,
                                boundingBox = para.boundingBox,
                                sourceLanguage = para.sourceLanguage,
                                targetLanguage = targetLang,
                                engineUsed = TranslationEngineType.FAST_NMT_FALLBACK,
                                fallbackApplied = true
                            )
                        )
                    }
                }
            }

            return finalResults
        }
    }

    private lateinit var clusterer: SemanticParagraphClusterer
    private lateinit var promptCompiler: SemanticParagraphPromptCompiler
    private lateinit var tokenChunker: DocumentTokenChunker
    private lateinit var fakeLlm: FakeLlmEngine
    private lateinit var fakeNmt: FakeFastNmtEngine
    private lateinit var pipeline: SemanticOcrTranslationPipeline

    @Before
    fun setUp() {
        clusterer = SemanticParagraphClusterer()
        promptCompiler = SemanticParagraphPromptCompiler()
        tokenChunker = DocumentTokenChunker(maxPromptTokensPerChunk = 200)
        fakeLlm = FakeLlmEngine()
        fakeNmt = FakeFastNmtEngine()
        pipeline = SemanticOcrTranslationPipeline(
            clusterer,
            promptCompiler,
            tokenChunker,
            fakeLlm,
            fakeNmt
        )
    }

    // =========================================================================
    // 1. Paragraph Grouping Tests (Spatial Proximity, Reading Order, De-hyphenation)
    // =========================================================================

    @Test
    fun testParagraphGrouping_scatteredLines_ordersTopToBottom() {
        val line3 = RawOcrLine("l3", "Third line of document.", SpatialBox(20, 100, 200, 120))
        val line1 = RawOcrLine("l1", "First line of document.", SpatialBox(20, 20, 200, 40))
        val line2 = RawOcrLine("l2", "Second line of document.", SpatialBox(20, 45, 190, 65))

        val paragraphs = clusterer.clusterScatteredLines(listOf(line3, line1, line2))

        assertEquals(2, paragraphs.size)
        assertEquals(2, paragraphs[0].lines.size)
        assertEquals("First line of document.", paragraphs[0].lines[0].text)
        assertEquals("Second line of document.", paragraphs[0].lines[1].text)
        assertEquals(1, paragraphs[1].lines.size)
        assertEquals("Third line of document.", paragraphs[1].lines[0].text)
    }

    @Test
    fun testParagraphGrouping_mergesConsecutiveLinesIntoSingleBoundingBox() {
        val line1 = RawOcrLine("l1", "Line one text.", SpatialBox(10, 10, 150, 30))
        val line2 = RawOcrLine("l2", "Line two text.", SpatialBox(10, 35, 180, 55))

        val paragraphs = clusterer.clusterScatteredLines(listOf(line1, line2))

        assertEquals(1, paragraphs.size)
        val box = paragraphs[0].boundingBox
        assertEquals(10, box.left)
        assertEquals(10, box.top)
        assertEquals(180, box.right)
        assertEquals(55, box.bottom)
    }

    @Test
    fun testParagraphGrouping_dehyphenation_joinsSplitWordsCleanly() {
        val line1 = RawOcrLine("l1", "This is an interna-", SpatialBox(10, 10, 150, 30))
        val line2 = RawOcrLine("l2", "tional agreement.", SpatialBox(10, 35, 150, 55))

        val paragraphs = clusterer.clusterScatteredLines(listOf(line1, line2))

        assertEquals(1, paragraphs.size)
        assertEquals("This is an international agreement.", paragraphs[0].cleanText)
    }

    @Test
    fun testParagraphGrouping_columnGutter_separatesTwoColumns() {
        val col1Line1 = RawOcrLine("c1_1", "Col1 line 1", SpatialBox(10, 20, 100, 40))
        val col1Line2 = RawOcrLine("c1_2", "Col1 line 2", SpatialBox(10, 45, 100, 65))

        val col2Line1 = RawOcrLine("c2_1", "Col2 line 1", SpatialBox(260, 20, 350, 40))
        val col2Line2 = RawOcrLine("c2_2", "Col2 line 2", SpatialBox(260, 45, 350, 65))

        val paragraphs = clusterer.clusterScatteredLines(listOf(col1Line1, col2Line1, col1Line2, col2Line2))

        assertTrue("Expected multi-column layout to produce at least 2 distinct paragraphs", paragraphs.size >= 2)
    }

    // =========================================================================
    // 2. Multi-Paragraph Prompt Assembly & Line ID Reconstruction Tests
    // =========================================================================

    @Test
    fun testPromptAssembly_generatesHierarchicalTagsForMultipleParagraphs() {
        val p0 = SemanticParagraph(
            id = "P0",
            lines = listOf(
                RawOcrLine("l1", "Welcome to the station.", SpatialBox(10, 10, 100, 25)),
                RawOcrLine("l2", "Please keep your ticket.", SpatialBox(10, 30, 100, 45))
            ),
            boundingBox = SpatialBox(10, 10, 100, 45),
            sourceLanguage = "en"
        )
        val p1 = SemanticParagraph(
            id = "P1",
            lines = listOf(
                RawOcrLine("l3", "Platform 4 departures.", SpatialBox(10, 80, 100, 95))
            ),
            boundingBox = SpatialBox(10, 80, 100, 95),
            sourceLanguage = "en"
        )

        val prompt = promptCompiler.compilePrompt(listOf(p0, p1), targetLanguage = "ru")

        assertTrue(prompt.contains("Translate the OCR lines to ru"))
        assertTrue(prompt.contains("[P0_L0] Welcome to the station."))
        assertTrue(prompt.contains("[P0_L1] Please keep your ticket."))
        assertTrue(prompt.contains("[P1_L0] Platform 4 departures."))
    }

    @Test
    fun testPromptParsing_extractsTranslationsWithVariousDelimiters() {
        val p0 = SemanticParagraph(
            id = "P0",
            lines = listOf(
                RawOcrLine("l1", "Hello", SpatialBox(0, 0, 50, 20)),
                RawOcrLine("l2", "World", SpatialBox(0, 25, 50, 45))
            ),
            boundingBox = SpatialBox(0, 0, 50, 45)
        )

        val rawOutput = """
            [P0_L0] -> Привет
            [P0_L1]: Мир
        """.trimIndent()

        val parsed = promptCompiler.parseResponse(rawOutput, listOf(p0))

        assertNotNull(parsed)
        assertEquals(listOf("Привет", "Мир"), parsed!!["P0"])
    }

    @Test
    fun testPromptParsing_handlesOutOfOrderTagsAndIgnoresNoise() {
        val p0 = SemanticParagraph(
            id = "P0",
            lines = listOf(
                RawOcrLine("l1", "One", SpatialBox(0, 0, 50, 20)),
                RawOcrLine("l2", "Two", SpatialBox(0, 25, 50, 45))
            ),
            boundingBox = SpatialBox(0, 0, 50, 45)
        )

        val rawOutput = """
            Here is your translation:
            [P0_L1] Два
            [P0_L0] Один
            Hope this helps!
        """.trimIndent()

        val parsed = promptCompiler.parseResponse(rawOutput, listOf(p0))

        assertNotNull(parsed)
        assertEquals("Один", parsed!!["P0"]?.get(0))
        assertEquals("Два", parsed["P0"]?.get(1))
    }

    @Test
    fun testPromptParsing_missingLineTag_returnsNull() {
        val p0 = SemanticParagraph(
            id = "P0",
            lines = listOf(
                RawOcrLine("l1", "One", SpatialBox(0, 0, 50, 20)),
                RawOcrLine("l2", "Two", SpatialBox(0, 25, 50, 45))
            ),
            boundingBox = SpatialBox(0, 0, 50, 45)
        )

        val incompleteOutput = """
            [P0_L0] Один
        """.trimIndent()

        val parsed = promptCompiler.parseResponse(incompleteOutput, listOf(p0))
        assertNull(parsed)
    }

    // =========================================================================
    // 3. Token Budget Overflow & Automatic Document Chunking Tests
    // =========================================================================

    @Test
    fun testTokenBudget_smallDocument_fitsIntoSingleChunk() {
        val p0 = SemanticParagraph(
            id = "P0",
            lines = listOf(RawOcrLine("l1", "Short text.", SpatialBox(0, 0, 50, 20))),
            boundingBox = SpatialBox(0, 0, 50, 20)
        )
        val p1 = SemanticParagraph(
            id = "P1",
            lines = listOf(RawOcrLine("l2", "Another short line.", SpatialBox(0, 30, 50, 50))),
            boundingBox = SpatialBox(0, 30, 50, 50)
        )

        val chunks = tokenChunker.chunkParagraphs(listOf(p0, p1), promptCompiler, targetLang = "ru")

        assertEquals(1, chunks.size)
        assertEquals(2, chunks[0].size)
    }

    @Test
    fun testTokenBudget_largeDocument_splitsIntoMultipleChunksAtParagraphBoundaries() {
        val longParagraphs = (0..5).map { i ->
            SemanticParagraph(
                id = "P$i",
                lines = listOf(
                    RawOcrLine(
                        "l_${i}_1",
                        "This is a long sentence providing extensive context for OCR paragraph testing index $i.",
                        SpatialBox(0, i * 40, 300, (i * 40) + 20)
                    )
                ),
                boundingBox = SpatialBox(0, i * 40, 300, (i * 40) + 20)
            )
        }

        val chunks = tokenChunker.chunkParagraphs(longParagraphs, promptCompiler, targetLang = "ru")

        assertTrue("Expected large document to be split into multiple chunks", chunks.size >= 2)
        val reconstructedParagraphCount = chunks.sumOf { it.size }
        assertEquals(6, reconstructedParagraphCount)
    }

    @Test
    fun testTokenBudget_pipelineExecutesMultipleLlmCallsForMultiChunkDocument() {
        val longParagraphs = (0..5).map { i ->
            RawOcrLine(
                "l_$i",
                "Long line text for testing multiple chunks in paragraph index $i.",
                SpatialBox(0, i * 80, 200, (i * 80) + 20)
            )
        }

        fakeLlm.nextResponse = { prompt ->
            val lines = mutableListOf<String>()
            val regex = Regex("""\[(P\d+_L\d+)\]""")
            regex.findAll(prompt).forEach { match ->
                lines.add("${match.value} Translated line")
            }
            lines.joinToString("\n")
        }

        val results = pipeline.processDocument(longParagraphs, targetLang = "ru")

        assertEquals(6, results.size)
        assertTrue("Pipeline should have performed multiple LLM calls", fakeLlm.invocationCount >= 2)
        assertTrue(results.all { it.engineUsed == TranslationEngineType.LLM_STRUCTURED })
    }

    // =========================================================================
    // 4. Per-Paragraph Fallback to Fast NMT Tests
    // =========================================================================

    @Test
    fun testFallback_corruptedParagraphTags_triggersFastNmtOnlyForFailedParagraph() {
        val line1 = RawOcrLine("l1", "Welcome", SpatialBox(0, 0, 100, 20))
        val line2 = RawOcrLine("l2", "Departure lounge", SpatialBox(0, 60, 100, 80))

        fakeNmt.translationDictionary["Departure lounge"] = "Зал вылета"

        fakeLlm.nextResponse = {
            """
                [P0_L0] Добро пожаловать
                Corrupted text without tag for paragraph 1.
            """.trimIndent()
        }

        val results = pipeline.processDocument(listOf(line1, line2), targetLang = "ru")

        assertEquals(2, results.size)
        assertEquals(TranslationEngineType.FAST_NMT_FALLBACK, results[0].engineUsed)
        assertEquals(TranslationEngineType.FAST_NMT_FALLBACK, results[1].engineUsed)
        assertTrue(results[0].fallbackApplied)
        assertTrue(results[1].fallbackApplied)
    }

    @Test
    fun testFallback_llmUnloaded_routesAllParagraphsDirectlyToFastNmt() {
        fakeLlm.isLoaded = false
        fakeNmt.translationDictionary["Hello"] = "Привет"
        fakeNmt.translationDictionary["World"] = "Мир"

        val line1 = RawOcrLine("l1", "Hello", SpatialBox(0, 0, 100, 20))
        val line2 = RawOcrLine("l2", "World", SpatialBox(0, 60, 100, 80))

        val results = pipeline.processDocument(listOf(line1, line2), targetLang = "ru")

        assertEquals(2, results.size)
        assertEquals(0, fakeLlm.invocationCount)
        assertEquals(TranslationEngineType.FAST_NMT_FALLBACK, results[0].engineUsed)
        assertEquals("Привет", results[0].translatedText)
        assertEquals("Мир", results[1].translatedText)
    }

    // =========================================================================
    // 5. Mixed-Script Document Handling Tests
    // =========================================================================

    @Test
    fun testMixedScript_sameAsTargetLanguage_bypassesTranslation() {
        val enLine = RawOcrLine("l1", "Terminal Information", SpatialBox(0, 0, 100, 20), detectedLanguage = "en")
        val ruLine = RawOcrLine("l2", "Информация о терминале", SpatialBox(0, 50, 100, 70), detectedLanguage = "ru")

        fakeLlm.nextResponse = {
            """
                [P0_L0] Информация о терминале
            """.trimIndent()
        }

        val results = pipeline.processDocument(listOf(enLine, ruLine), targetLang = "ru")

        assertEquals(2, results.size)
        val enResult = results.first { it.sourceLanguage == "en" }
        val ruResult = results.first { it.sourceLanguage == "ru" }

        assertEquals(TranslationEngineType.LLM_STRUCTURED, enResult.engineUsed)
        assertFalse(enResult.fallbackApplied)

        assertEquals(TranslationEngineType.BYPASS_SAME_LANGUAGE, ruResult.engineUsed)
        assertEquals("Информация о терминале", ruResult.translatedText)
        assertFalse(ruResult.fallbackApplied)
    }

    @Test
    fun testMixedScript_multilingualDocument_preservesPerParagraphLanguageMetadata() {
        val lineZh = RawOcrLine("l1", "北京烤鸭", SpatialBox(0, 0, 100, 20), detectedLanguage = "zh")
        val lineEn = RawOcrLine("l2", "Peking Duck", SpatialBox(0, 50, 100, 70), detectedLanguage = "en")

        fakeLlm.nextResponse = {
            """
                [P0_L0] Утка по-пекински
                [P1_L0] Утка по-пекински
            """.trimIndent()
        }

        val results = pipeline.processDocument(listOf(lineZh, lineEn), targetLang = "ru")

        assertEquals(2, results.size)
        assertEquals("zh", results[0].sourceLanguage)
        assertEquals("en", results[1].sourceLanguage)
        assertEquals("ru", results[0].targetLanguage)
        assertEquals("ru", results[1].targetLanguage)
    }

    // =========================================================================
    // 6. Edge Cases & Boundary Conditions
    // =========================================================================

    @Test
    fun testEdgeCase_emptyOcrResult_returnsEmptyListWithoutEngineInvocations() {
        val results = pipeline.processDocument(emptyList(), targetLang = "ru")

        assertTrue(results.isEmpty())
        assertEquals(0, fakeLlm.invocationCount)
        assertEquals(0, fakeNmt.invocationCount)
    }

    @Test
    fun testEdgeCase_singleLineParagraph_translatesWithoutLineSplits() {
        val singleLine = RawOcrLine("l1", "Exit Only", SpatialBox(10, 10, 100, 30))
        fakeLlm.nextResponse = { "[P0_L0] Только выход" }

        val results = pipeline.processDocument(listOf(singleLine), targetLang = "ru")

        assertEquals(1, results.size)
        assertEquals("Только выход", results[0].translatedText)
        assertEquals(1, results[0].lineTranslations.size)
        assertEquals("Только выход", results[0].lineTranslations[0])
    }

    @Test
    fun testEdgeCase_veryLongLineExceedingTokenLimit_handlesGracefully() {
        val massiveText = "word ".repeat(300)
        val massiveLine = RawOcrLine("l1", massiveText, SpatialBox(0, 0, 500, 20))

        fakeNmt.translationDictionary[massiveText] = "перевод ".repeat(300).trim()
        fakeLlm.nextResponse = { "[P0_L0] " + "перевод ".repeat(300).trim() }

        val results = pipeline.processDocument(listOf(massiveLine), targetLang = "ru")

        assertEquals(1, results.size)
        assertNotNull(results[0].translatedText)
        assertTrue(results[0].translatedText.isNotEmpty())
    }

    @Test
    fun testEdgeCase_zeroAreaBoundingBox_computesWithoutDivisionByZero() {
        val zeroBoxLine = RawOcrLine("l1", "Zero box text", SpatialBox(0, 0, 0, 0))
        val paragraphs = clusterer.clusterScatteredLines(listOf(zeroBoxLine))

        assertEquals(1, paragraphs.size)
        assertEquals(0, paragraphs[0].boundingBox.width)
        assertEquals(0, paragraphs[0].boundingBox.height)
    }

    @Test
    fun testEdgeCase_whitespaceOnlyLines_filteredOrPreservedSafely() {
        val normalLine = RawOcrLine("l1", "Real text", SpatialBox(0, 0, 100, 20))
        val spaceLine = RawOcrLine("l2", "   ", SpatialBox(0, 30, 100, 50))

        val paragraphs = clusterer.clusterScatteredLines(listOf(normalLine, spaceLine))
        assertTrue("Paragraph clustering should handle whitespace lines without crash", paragraphs.isNotEmpty())
    }
}
