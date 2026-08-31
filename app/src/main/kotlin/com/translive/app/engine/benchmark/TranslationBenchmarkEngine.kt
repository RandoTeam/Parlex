package com.translive.app.engine.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.TranslationEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class TranslationBenchmarkEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ggufEngine: TranslationEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val evaluator: MultiLanguageEvaluator
) {
    /**
     * Executes an end-to-end benchmark run across a full evaluation suite.
     */
    suspend fun runSuite(
        target: BenchmarkTarget,
        samples: List<BenchmarkSample>,
        warmupIterations: Int = 1,
        onProgress: (Int, Int, IterationMetrics?) -> Unit = { _, _, _ -> }
    ): BenchmarkSummaryReport = withContext(Dispatchers.Default) {
        gcAndTrimMemory()
        val coldLoadStart = SystemClock.elapsedRealtime()
        val loadSuccess = loadTargetModel(target)
        val coldLoadMs = SystemClock.elapsedRealtime() - coldLoadStart

        if (!loadSuccess) {
            return@withContext buildFailureReport(target, samples.size, "Failed to load model on $target")
        }

        // Warm-up phase
        if (samples.isNotEmpty()) {
            val warmupSample = samples.first()
            for (i in 0 until warmupIterations) {
                runInferenceIteration(target, warmupSample)
            }
        }

        // Execution phase
        val iterationResults = mutableListOf<IterationMetrics>()
        val qualityResults = mutableListOf<QualityMetrics>()

        samples.forEachIndexed { index, sample ->
            val metrics = runInferenceIteration(target, sample)
            val quality = evaluator.evaluate(sample, metrics.outputText)

            iterationResults.add(metrics)
            qualityResults.add(quality)
            onProgress(index + 1, samples.size, metrics)
        }

        // Unload and cleanup
        unloadTargetModel(target)
        gcAndTrimMemory()

        // Aggregate summary
        aggregateReport(target, coldLoadMs, iterationResults, qualityResults, samples)
    }

    private suspend fun runInferenceIteration(
        target: BenchmarkTarget,
        sample: BenchmarkSample
    ): IterationMetrics {
        val startNano = System.nanoTime()
        var firstTokenNano: Long? = null
        val tokenCollector = StringBuilder()
        var promptTokenCount = 0
        var generatedTokenCount = 0
        var caughtError: String? = null

        val heapBefore = Debug.getNativeHeapAllocatedSize()

        try {
            when (target.runtime) {
                ModelRuntime.GGUF -> {
                    ggufEngine.translateStreaming(
                        sourceText = sample.sourceText,
                        source = sample.sourceLang,
                        target = sample.targetLang,
                        onComplete = { streamResult ->
                            promptTokenCount = streamResult.promptTokens
                            generatedTokenCount = streamResult.generatedTokens
                        }
                    ).collect { token ->
                        if (firstTokenNano == null) {
                            firstTokenNano = System.nanoTime()
                        }
                        tokenCollector.append(token)
                    }
                }
                ModelRuntime.LITERT_LM -> {
                    liteRtEngine.translateStreaming(
                        sourceText = sample.sourceText,
                        source = sample.sourceLang,
                        target = sample.targetLang
                    ).collect { chunk ->
                        if (firstTokenNano == null) {
                            firstTokenNano = System.nanoTime()
                        }
                        tokenCollector.append(chunk)
                    }
                }
                else -> throw IllegalArgumentException("Unsupported benchmark runtime: ${target.runtime}")
            }
        } catch (e: Exception) {
            caughtError = e.message ?: e.javaClass.simpleName
        }

        val endNano = System.nanoTime()
        val heapAfter = Debug.getNativeHeapAllocatedSize()

        val totalDurationMs = (endNano - startNano) / 1_000_000
        val ft = firstTokenNano
        val ttftMs = if (ft != null) {
            (ft - startNano) / 1_000_000
        } else {
            totalDurationMs
        }
        val genDurationMs = max(1L, totalDurationMs - ttftMs)

        val output = tokenCollector.toString().trim()
        val estimatedGeneratedTokens = if (generatedTokenCount > 0) {
            generatedTokenCount
        } else {
            estimateTokens(output)
        }

        val tokensPerSec = if (genDurationMs > 0) {
            (estimatedGeneratedTokens.toDouble() / (genDurationMs.toDouble() / 1000.0))
        } else 0.0

        val (activeBackend, fallbackActive, fallbackReason) = getBackendStatus(target)

        return IterationMetrics(
            sampleId = sample.id,
            timeToFirstTokenMs = ttftMs,
            totalInferenceDurationMs = totalDurationMs,
            generationDurationMs = genDurationMs,
            promptTokens = promptTokenCount,
            generatedTokens = estimatedGeneratedTokens,
            tokensPerSecond = tokensPerSec,
            peakNativeHeapBytes = heapAfter,
            pssBytes = getProcessPssBytes(),
            memoryDeltaBytes = heapAfter - heapBefore,
            requestedBackend = target.requestedBackend,
            activeBackend = activeBackend,
            isFallbackActive = fallbackActive,
            fallbackReason = fallbackReason,
            outputText = output,
            error = caughtError
        )
    }

    private fun loadTargetModel(target: BenchmarkTarget): Boolean {
        return when (target.runtime) {
            ModelRuntime.GGUF -> ggufEngine.loadModel(
                modelPath = target.modelPath,
                nThreads = target.threadCount,
                backend = target.requestedBackend
            )
            ModelRuntime.LITERT_LM -> liteRtEngine.loadModel(
                modelPath = target.modelPath,
                backendSetting = target.requestedBackend,
                threads = target.threadCount
            )
            else -> false
        }
    }

    private fun unloadTargetModel(target: BenchmarkTarget) {
        when (target.runtime) {
            ModelRuntime.GGUF -> ggufEngine.unloadModel()
            ModelRuntime.LITERT_LM -> liteRtEngine.unloadModel()
            else -> {}
        }
    }

    private fun getBackendStatus(target: BenchmarkTarget): Triple<String, Boolean, String?> {
        return when (target.runtime) {
            ModelRuntime.GGUF -> {
                val backend = ggufEngine.currentBackend ?: "unknown"
                val isFallback = target.requestedBackend == "gpu" && backend == "cpu"
                Triple(backend, isFallback, if (isFallback) "GGUF GPU delegate not acquired" else null)
            }
            ModelRuntime.LITERT_LM -> {
                val status = liteRtEngine.currentBackendStatus
                Triple(status.active ?: "none", status.fallbackReason != null, status.fallbackReason)
            }
            else -> Triple("none", false, null)
        }
    }

    private fun getProcessPssBytes(): Long {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return memInfo.totalPss * 1024L
    }

    private fun gcAndTrimMemory() {
        System.gc()
        System.runFinalization()
        System.gc()
    }

    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val cjkCount = text.count { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF }
        val nonCjk = text.filterNot { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF }
        val words = nonCjk.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        return (cjkCount + (words * 1.3).toInt()).coerceAtLeast(1)
    }

    private fun aggregateReport(
        target: BenchmarkTarget,
        coldLoadMs: Long,
        iterations: List<IterationMetrics>,
        qualities: List<QualityMetrics>,
        samples: List<BenchmarkSample>
    ): BenchmarkSummaryReport {
        val valid = iterations.filter { it.error == null }
        val avgTtft = if (valid.isNotEmpty()) valid.map { it.timeToFirstTokenMs }.average() else 0.0
        val p95Ttft = calculatePercentile(valid.map { it.timeToFirstTokenMs.toDouble() }, 95.0)
        val avgTokSec = if (valid.isNotEmpty()) valid.map { it.tokensPerSecond }.average() else 0.0
        val p95TokSec = calculatePercentile(valid.map { it.tokensPerSecond }, 95.0)
        val avgGenMs = if (valid.isNotEmpty()) valid.map { it.generationDurationMs.toDouble() }.average() else 0.0
        val peakMemMb = (valid.maxOfOrNull { it.peakNativeHeapBytes } ?: 0L) / (1024.0 * 1024.0)

        val avgBleu = if (qualities.isNotEmpty()) qualities.map { it.sentenceBleu }.average() else 0.0
        val structuredQualities = qualities.zip(samples).filter { it.second.isStructuredOcr }
        val ocrLineAcc = if (structuredQualities.isNotEmpty()) {
            structuredQualities.map { it.first.lineIdPreservationRatio }.average()
        } else {
            1.0
        }
        val hallucinationCount = qualities.count { it.hasHallucination || it.hasRepetitionLoop || it.hasTagLeakage }
        val fallbackCount = iterations.count { it.isFallbackActive }

        return BenchmarkSummaryReport(
            target = target,
            totalSamples = samples.size,
            successfulSamples = valid.size,
            failedSamples = samples.size - valid.size,
            avgColdLoadMs = coldLoadMs,
            avgTtftMs = avgTtft,
            p95TtftMs = p95Ttft,
            avgTokensPerSecond = avgTokSec,
            p95TokensPerSecond = p95TokSec,
            avgGenerationDurationMs = avgGenMs,
            peakNativeMemoryMb = peakMemMb,
            avgSentenceBleu = avgBleu,
            ocrLineIdAccuracy = ocrLineAcc,
            hallucinationRate = if (samples.isNotEmpty()) hallucinationCount.toDouble() / samples.size else 0.0,
            backendVerifiedGpu = target.requestedBackend == "gpu" && fallbackCount == 0,
            fallbackCount = fallbackCount,
            perCategoryResults = emptyMap(),
            perLanguageResults = emptyMap()
        )
    }

    private fun calculatePercentile(values: List<Double>, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = ((percentile / 100.0) * (sorted.size - 1)).toInt()
        return sorted[index.coerceIn(0, sorted.size - 1)]
    }

    private fun buildFailureReport(target: BenchmarkTarget, sampleCount: Int, reason: String) =
        BenchmarkSummaryReport(
            target = target,
            totalSamples = sampleCount,
            successfulSamples = 0,
            failedSamples = sampleCount,
            avgColdLoadMs = -1L,
            avgTtftMs = 0.0,
            p95TtftMs = 0.0,
            avgTokensPerSecond = 0.0,
            p95TokensPerSecond = 0.0,
            avgGenerationDurationMs = 0.0,
            peakNativeMemoryMb = 0.0,
            avgSentenceBleu = 0.0,
            ocrLineIdAccuracy = 0.0,
            hallucinationRate = 1.0,
            backendVerifiedGpu = false,
            fallbackCount = sampleCount,
            perCategoryResults = emptyMap(),
            perLanguageResults = emptyMap()
        )
}
