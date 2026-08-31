package com.translive.app.engine.benchmark

import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime

/**
 * Execution target configuration for a benchmark run.
 */
data class BenchmarkTarget(
    val modelId: String,
    val modelPath: String,
    val runtime: ModelRuntime,
    val requestedBackend: String,
    val threadCount: Int = 4
)

/**
 * Evaluation sample category.
 */
enum class SampleCategory {
    SHORT_SENTENCE,
    PARAGRAPH,
    DIALOGUE_TURN,
    OCR_STRUCTURED,
    DIALECT_STRESS,
    MIXED_SCRIPT
}

/**
 * Single evaluation prompt item.
 */
data class BenchmarkSample(
    val id: String,
    val category: SampleCategory,
    val sourceLang: Language,
    val targetLang: Language,
    val sourceText: String,
    val referenceTranslation: String,
    val isStructuredOcr: Boolean = false,
    val expectedLineIds: List<String> = emptyList()
)

/**
 * Low-level performance telemetry for a single benchmark iteration.
 */
data class IterationMetrics(
    val sampleId: String,
    val coldLoadDurationMs: Long? = null,
    val timeToFirstTokenMs: Long,
    val totalInferenceDurationMs: Long,
    val generationDurationMs: Long,
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokensPerSecond: Double,
    val peakNativeHeapBytes: Long,
    val pssBytes: Long,
    val memoryDeltaBytes: Long,
    val requestedBackend: String,
    val activeBackend: String,
    val isFallbackActive: Boolean,
    val fallbackReason: String? = null,
    val outputText: String,
    val error: String? = null
)

/**
 * Translation quality and integrity evaluation results.
 */
data class QualityMetrics(
    val sampleId: String,
    val sentenceBleu: Double,
    val lengthRatio: Double,
    val lineIdPreservationRatio: Double,
    val hasHallucination: Boolean,
    val hasRepetitionLoop: Boolean,
    val hasTagLeakage: Boolean,
    val languageIntegrityPass: Boolean,
    val qualityNotes: List<String> = emptyList()
)

/**
 * Summary per sample category.
 */
data class CategorySummary(
    val count: Int,
    val avgTtftMs: Double,
    val avgTokensPerSecond: Double,
    val avgBleu: Double
)

/**
 * Summary per language.
 */
data class LanguageSummary(
    val language: Language,
    val sampleCount: Int,
    val avgBleu: Double,
    val avgTokensPerSecond: Double,
    val zeroHallucinationPass: Boolean
)

/**
 * Consolidated aggregate benchmark report for a model runtime/backend configuration.
 */
data class BenchmarkSummaryReport(
    val target: BenchmarkTarget,
    val totalSamples: Int,
    val successfulSamples: Int,
    val failedSamples: Int,
    val avgColdLoadMs: Long,
    val avgTtftMs: Double,
    val p95TtftMs: Double,
    val avgTokensPerSecond: Double,
    val p95TokensPerSecond: Double,
    val avgGenerationDurationMs: Double,
    val peakNativeMemoryMb: Double,
    val avgSentenceBleu: Double,
    val ocrLineIdAccuracy: Double,
    val hallucinationRate: Double,
    val backendVerifiedGpu: Boolean,
    val fallbackCount: Int,
    val perCategoryResults: Map<SampleCategory, CategorySummary> = emptyMap(),
    val perLanguageResults: Map<Language, LanguageSummary> = emptyMap()
)
