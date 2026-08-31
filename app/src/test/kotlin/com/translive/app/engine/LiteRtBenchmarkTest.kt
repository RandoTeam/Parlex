package com.translive.app.engine

import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.engine.benchmark.BenchmarkSample
import com.translive.app.engine.benchmark.BenchmarkTarget
import com.translive.app.engine.benchmark.LanguageEvaluationSuite
import com.translive.app.engine.benchmark.MultiLanguageEvaluator
import com.translive.app.engine.benchmark.SampleCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiteRtBenchmarkTest {

    private lateinit var evaluator: MultiLanguageEvaluator

    @Before
    fun setUp() {
        evaluator = MultiLanguageEvaluator()
    }

    @Test
    fun `test sentence BLEU exact match returns 1_0`() {
        val reference = "The quick brown fox jumps over the lazy dog"
        val hypothesis = "The quick brown fox jumps over the lazy dog"
        val bleu = evaluator.computeSentenceBleu(reference, hypothesis)
        assertEquals(1.0, bleu, 0.001)
    }

    @Test
    fun `test sentence BLEU partial match returns valid score`() {
        val reference = "В аэропорту необходимо предъявить паспорт и посадочный талон."
        val hypothesis = "В аэропорту нужно показать паспорт и посадочный талон."
        val bleu = evaluator.computeSentenceBleu(reference, hypothesis)
        assertTrue("BLEU should be between 0.3 and 0.95 for close synonym translation", bleu in 0.3..0.95)
    }

    @Test
    fun `test line ID preservation correctly detects missing tags`() {
        val expected = listOf("[L1]", "[L2]", "[L3]")
        val completeOutput = "[L1] Вход\n[L2] Выход\n[L3] Касса"
        val partialOutput = "[L1] Вход\nВыход\n[L3] Касса"

        assertEquals(1.0, evaluator.calculateLineIdPreservation(expected, completeOutput), 0.001)
        assertEquals(0.666, evaluator.calculateLineIdPreservation(expected, partialOutput), 0.01)
    }

    @Test
    fun `test control tag leakage triggers quality flag`() {
        val sample = BenchmarkSample(
            id = "s1",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.RUSSIAN,
            sourceText = "Hello",
            referenceTranslation = "Привет"
        )
        val leakedOutput = "<src>en</src><dst>ru</dst><text>Привет</text>"
        val quality = evaluator.evaluate(sample, leakedOutput)

        assertTrue("Should detect tag leakage", quality.hasTagLeakage)
        assertFalse("Should fail language integrity due to leaked tags", quality.languageIntegrityPass)
    }

    @Test
    fun `test cyclic repetition loop detection`() {
        val sample = BenchmarkSample(
            id = "s2",
            category = SampleCategory.SHORT_SENTENCE,
            sourceLang = Language.ENGLISH,
            targetLang = Language.FRENCH,
            sourceText = "Good morning",
            referenceTranslation = "Bonjour"
        )
        val loopOutput = "Bonjour le monde Bonjour le monde Bonjour le monde Bonjour le monde"
        val quality = evaluator.evaluate(sample, loopOutput)

        assertTrue("Should detect repetition loop", quality.hasRepetitionLoop)
        assertTrue("Should mark as hallucinated", quality.hasHallucination)
    }

    @Test
    fun `test delegate status model captures fallback reason correctly`() {
        val status = LiteRtBackendStatus(
            requested = "gpu",
            active = "cpu",
            fallbackReason = "Out of memory: requested 1.34 GB over 1.0 GB allocation limit"
        )

        assertEquals("gpu", status.requested)
        assertEquals("cpu", status.active)
        assertEquals("Out of memory: requested 1.34 GB over 1.0 GB allocation limit", status.fallbackReason)
    }

    @Test
    fun `test language evaluation suite generates valid test battery`() {
        val battery = LanguageEvaluationSuite.createStandardBattery()
        assertTrue("Battery must not be empty", battery.isNotEmpty())
        assertEquals(22, battery.size)

        val ocrSamples = battery.filter { it.isStructuredOcr }
        assertEquals(2, ocrSamples.size)
        for (sample in ocrSamples) {
            assertTrue(sample.expectedLineIds.isNotEmpty())
            assertTrue(sample.sourceText.contains(sample.expectedLineIds.first()))
        }
    }
}
