package com.translive.app.engine.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLlmJniTest {

    @Test
    fun testChunkTokens_emptySequence_returnsEmptyList() {
        val emptyTokens = emptyList<Int>()
        val chunks128 = CameraLlmChunker.chunkTokens(emptyTokens, 128)
        val chunks256 = CameraLlmChunker.chunkTokens(emptyTokens, 256)
        val chunks512 = CameraLlmChunker.chunkTokens(emptyTokens, 512)

        assertTrue(chunks128.isEmpty())
        assertTrue(chunks256.isEmpty())
        assertTrue(chunks512.isEmpty())
    }

    @Test
    fun testChunkTokens_exactMultiple_splitsEvenly() {
        val tokens512 = (1..512).toList()

        val chunks128 = CameraLlmChunker.chunkTokens(tokens512, 128)
        assertEquals(4, chunks128.size)
        assertEquals(128, chunks128[0].size)
        assertEquals(128, chunks128[1].size)
        assertEquals(128, chunks128[2].size)
        assertEquals(128, chunks128[3].size)
        assertEquals((1..128).toList(), chunks128[0])
        assertEquals((385..512).toList(), chunks128[3])

        val chunks256 = CameraLlmChunker.chunkTokens(tokens512, 256)
        assertEquals(2, chunks256.size)
        assertEquals(256, chunks256[0].size)
        assertEquals(256, chunks256[1].size)

        val chunks512 = CameraLlmChunker.chunkTokens(tokens512, 512)
        assertEquals(1, chunks512.size)
        assertEquals(512, chunks512[0].size)
    }

    @Test
    fun testChunkTokens_nonExactMultiple_handlesTrailingChunk() {
        val tokens300 = (1..300).toList()

        val chunks128 = CameraLlmChunker.chunkTokens(tokens300, 128)
        assertEquals(3, chunks128.size)
        assertEquals(128, chunks128[0].size)
        assertEquals(128, chunks128[1].size)
        assertEquals(44, chunks128[2].size)
        assertEquals(300, chunks128.flatten().size)

        val chunks256 = CameraLlmChunker.chunkTokens(tokens300, 256)
        assertEquals(2, chunks256.size)
        assertEquals(256, chunks256[0].size)
        assertEquals(44, chunks256[1].size)
    }

    @Test
    fun testChunkTokens_sequenceSmallerThanBatchSize_returnsSingleChunk() {
        val tokens50 = (1..50).toList()
        val chunks128 = CameraLlmChunker.chunkTokens(tokens50, 128)
        val chunks512 = CameraLlmChunker.chunkTokens(tokens50, 512)

        assertEquals(1, chunks128.size)
        assertEquals(50, chunks128[0].size)
        assertEquals(tokens50, chunks128[0])

        assertEquals(1, chunks512.size)
        assertEquals(50, chunks512[0].size)
    }

    @Test
    fun testChunkTokens_preservesElementOrderingAndValues() {
        val inputTokens = listOf(101, 2054, 2003, 1037, 3835, 102)
        val chunks = CameraLlmChunker.chunkTokens(inputTokens, 2)

        assertEquals(3, chunks.size)
        assertEquals(listOf(101, 2054), chunks[0])
        assertEquals(listOf(2003, 1037), chunks[1])
        assertEquals(listOf(3835, 102), chunks[2])
        assertEquals(inputTokens, chunks.flatten())
    }

    @Test
    fun testComputeChunkRanges_calculatesAccurateOffsets() {
        val ranges = CameraLlmChunker.computeChunkRanges(totalTokens = 350, batchSize = 128)

        assertEquals(3, ranges.size)
        assertEquals(0 until 128, ranges[0])
        assertEquals(128 until 256, ranges[1])
        assertEquals(256 until 350, ranges[2])
        assertEquals(128, ranges[0].count())
        assertEquals(128, ranges[1].count())
        assertEquals(94, ranges[2].count())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testChunkTokens_invalidBatchSize_throwsException() {
        CameraLlmChunker.chunkTokens(listOf(1, 2, 3), 0)
    }

    @Test
    fun testFormatIndexedOcrPrompt_generatesCorrectLineTags() {
        val lines = listOf("Vietnamese Pho", "Spring Rolls", "Iced Coffee")
        val prompt = CameraLlmPromptFormatter.formatIndexedOcrPrompt(
            lines = lines,
            sourceLangName = "English",
            targetLangName = "Russian"
        )

        assertTrue(prompt.contains("Translate the OCR lines from English to Russian"))
        assertTrue(prompt.contains("[0] Vietnamese Pho"))
        assertTrue(prompt.contains("[1] Spring Rolls"))
        assertTrue(prompt.contains("[2] Iced Coffee"))
        assertTrue(prompt.contains("Preserve every line ID exactly, for example [0] -> [0]"))
    }

    @Test
    fun testParseIndexedTranslations_exactMatchingTags_reconstructsLines() {
        val rawModelOutput = """
            [0] Вьетнамский суп Фо
            [1] Спринг-роллы
            [2] Кофе со льдом
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 3
        )

        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("Вьетнамский суп Фо", parsed[0])
        assertEquals("Спринг-роллы", parsed[1])
        assertEquals("Кофе со льдом", parsed[2])
    }

    @Test
    fun testParseIndexedTranslations_handlesArrowAndColonSeparators() {
        val rawModelOutput = """
            [0] -> Вьетнамский суп Фо
            [1]: Спринг-роллы
            [2] - Кофе со льдом
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 3
        )

        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("Вьетнамский суп Фо", parsed[0])
        assertEquals("Спринг-роллы", parsed[1])
        assertEquals("Кофе со льдом", parsed[2])
    }

    @Test
    fun testParseIndexedTranslations_handlesPrefixTagFormat() {
        val rawModelOutput = """
            [L0] Вход
            [L1] Выход
            [L2] Касса
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 3,
            tagPrefix = "L"
        )

        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("Вход", parsed[0])
        assertEquals("Выход", parsed[1])
        assertEquals("Касса", parsed[2])
    }

    @Test
    fun testParseIndexedTranslations_ignoresPreambleAndPostambleNoise() {
        val rawModelOutput = """
            Sure, here is the translation for the OCR lines:
            [0] Главное меню
            [1] Напитки и десерты
            Hope this helps! Let me know if you need anything else.
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 2
        )

        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("Главное меню", parsed[0])
        assertEquals("Напитки и десерты", parsed[1])
    }

    @Test
    fun testParseIndexedTranslations_outOfOrderTags_correctlyMapsToIndices() {
        val rawModelOutput = """
            [1] Второй пункт
            [0] Первый пункт
            [2] Третий пункт
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 3
        )

        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals("Первый пункт", parsed[0])
        assertEquals("Второй пункт", parsed[1])
        assertEquals("Третий пункт", parsed[2])
    }

    @Test
    fun testParseIndexedTranslations_missingTag_fallsBackToLineCountIfValid() {
        val rawModelOutput = """
            Главный вход
            Запасной выход
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 2
        )

        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("Главный вход", parsed[0])
        assertEquals("Запасной выход", parsed[1])
    }

    @Test
    fun testParseIndexedTranslations_incompleteOutput_returnsNull() {
        val rawModelOutput = """
            [0] Только одна строка
        """.trimIndent()

        val parsed = CameraLlmTagParser.parseIndexedTranslations(
            rawOutput = rawModelOutput,
            expectedLineCount = 3
        )

        assertNull(parsed)
    }

    @Test
    fun testStripTag_removesVariousTagPrefixes() {
        assertEquals("Test Line", CameraLlmTagParser.stripTag("[0] Test Line"))
        assertEquals("Test Line", CameraLlmTagParser.stripTag("[0]: Test Line"))
        assertEquals("Test Line", CameraLlmTagParser.stripTag("[0] -> Test Line"))
        assertEquals("Test Line", CameraLlmTagParser.stripTag("[0] - Test Line"))
        assertEquals("Test Line", CameraLlmTagParser.stripTag("[L0] Test Line", tagPrefix = "L"))
        assertEquals("Test Line", CameraLlmTagParser.stripTag("0. Test Line"))
    }

    @Test
    fun testCalculateAvailableGenerationTokens_standardBudget_returnsRequested() {
        val contextLimit = 1024
        val promptTokens = 200
        val requestedTokens = 512
        val reserve = 8

        val available = CameraLlmTokenBudget.calculateAvailableGenerationTokens(
            contextLimit = contextLimit,
            promptTokenCount = promptTokens,
            requestedTokens = requestedTokens,
            reserveTokens = reserve
        )

        assertEquals(512, available)
    }

    @Test
    fun testCalculateAvailableGenerationTokens_constrainedContext_clampsToRemaining() {
        val contextLimit = 1024
        val promptTokens = 800
        val requestedTokens = 512
        val reserve = 8

        val available = CameraLlmTokenBudget.calculateAvailableGenerationTokens(
            contextLimit = contextLimit,
            promptTokenCount = promptTokens,
            requestedTokens = requestedTokens,
            reserveTokens = reserve
        )

        assertEquals(216, available)
    }

    @Test
    fun testCalculateAvailableGenerationTokens_exactContextBoundary_returnsZero() {
        val contextLimit = 1024
        val promptTokens = 1016
        val requestedTokens = 100
        val reserve = 8

        val available = CameraLlmTokenBudget.calculateAvailableGenerationTokens(
            contextLimit = contextLimit,
            promptTokenCount = promptTokens,
            requestedTokens = requestedTokens,
            reserveTokens = reserve
        )

        assertEquals(0, available)
    }

    @Test
    fun testCalculateAvailableGenerationTokens_overflow_returnsZero() {
        val contextLimit = 1024
        val promptTokens = 1030
        val requestedTokens = 128
        val reserve = 8

        val available = CameraLlmTokenBudget.calculateAvailableGenerationTokens(
            contextLimit = contextLimit,
            promptTokenCount = promptTokens,
            requestedTokens = requestedTokens,
            reserveTokens = reserve
        )

        assertEquals(0, available)
    }

    @Test
    fun testIsContextOverflow_detectsExceededContextLimits() {
        val contextLimit = 1024
        val reserve = 8

        assertFalse(CameraLlmTokenBudget.isContextOverflow(contextLimit, 500, reserve))
        assertFalse(CameraLlmTokenBudget.isContextOverflow(contextLimit, 1015, reserve))
        assertTrue(CameraLlmTokenBudget.isContextOverflow(contextLimit, 1016, reserve))
        assertTrue(CameraLlmTokenBudget.isContextOverflow(contextLimit, 1024, reserve))
        assertTrue(CameraLlmTokenBudget.isContextOverflow(contextLimit, 1500, reserve))
    }

    @Test
    fun testEstimateTokenBudget_scalesWithTextLengthAndClampsToBounds() {
        assertEquals(256, CameraLlmTokenBudget.estimateTokenBudget(sourceTextLength = 10))
        assertEquals(600, CameraLlmTokenBudget.estimateTokenBudget(sourceTextLength = 200))
        assertEquals(2048, CameraLlmTokenBudget.estimateTokenBudget(sourceTextLength = 1000))
    }
}
