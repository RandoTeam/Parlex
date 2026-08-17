package com.translive.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** PP-OCR detector + recognizer orchestration over the MNN tensor bridge. */
@Singleton
class PpOcrMnnEngine @Inject constructor(
    private val runtime: OcrMnnRuntime
) {
    data class Config(
        val backend: Int = 1,
        val detectorThreshold: Float = 0.3f,
        val minComponentArea: Int = 3,
        val recognizerThreshold: Float = 0.0f
    )

    fun recognize(
        bitmap: Bitmap,
        detectorPath: String,
        recognizerPath: String,
        dictionary: List<String>,
        config: Config = Config()
    ): OcrResult {
        val startedAt = SystemClock.elapsedRealtime()
        val detectorHandle = runtime.loadModel(detectorPath, config.backend)
        val recognizerHandle = runtime.loadModel(recognizerPath, config.backend)
        if (detectorHandle == 0L || recognizerHandle == 0L) {
            runtime.releaseModel(detectorHandle)
            runtime.releaseModel(recognizerHandle)
            return OcrResult(
                emptyList(), bitmap.width, bitmap.height,
                diagnostics = OcrDiagnostics("MNN unavailable/model load failed", elapsed(startedAt))
            )
        }
        return try {
            val detectorInput = PpOcrPreprocessor.detector(bitmap)
            val detectorOutput = runtime.runFloat(detectorHandle, detectorInput.values, detectorInput.shape)
            val detectorShape = runtime.outputShape(detectorHandle)
            if (detectorOutput == null || detectorShape == null) {
                emptyResult(bitmap, startedAt, "MNN detector output unavailable")
            } else {
                val map = detectorMap(detectorOutput, detectorShape)
                val mapWidth = detectorShape.takeLast(1).firstOrNull() ?: 0
                val mapHeight = detectorShape.takeLast(2).firstOrNull() ?: 0
                val detected = if (map != null && mapWidth > 0 && mapHeight > 0) {
                    PpOcrPostProcessor.thresholdMap(
                        map, mapWidth, mapHeight,
                        config.detectorThreshold, config.minComponentArea
                    )
                } else emptyList()
                val lines = detected.mapNotNull { quad ->
                    val originalQuad = quad.points.map { point ->
                        android.graphics.PointF(
                            point.x / mapWidth * bitmap.width,
                            point.y / mapHeight * bitmap.height
                        )
                    }
                    val recInput = PpOcrPreprocessor.recognition(bitmap, originalQuad)
                    val logits = runtime.runFloat(recognizerHandle, recInput.values, recInput.shape)
                        ?: return@mapNotNull null
                    val shape = runtime.outputShape(recognizerHandle) ?: return@mapNotNull null
                    val time = if (shape.size >= 3) shape[shape.size - 2] else shape.firstOrNull() ?: 0
                    val classes = shape.lastOrNull() ?: 0
                    // CTC class 0 is blank; every remaining class must have a
                    // dictionary entry. Never silently truncate an incompatible
                    // language package and return plausible-looking garbage.
                    if (time <= 0 || classes <= 1 || classes != dictionary.size + 1) {
                        return@mapNotNull null
                    }
                    val (text, score) = PpOcrPostProcessor.decodeCtc(
                        logits, time, classes, dictionary, config.recognizerThreshold
                    )
                    if (text.isBlank()) return@mapNotNull null
                    val left = originalQuad.minOf { it.x }.toInt()
                    val top = originalQuad.minOf { it.y }.toInt()
                    val right = originalQuad.maxOf { it.x }.toInt()
                    val bottom = originalQuad.maxOf { it.y }.toInt()
                    OcrLine(text, Rect(left, top, right, bottom)) to score
                }
                val ocrLines = lines.map { it.first }
                val block = if (ocrLines.isEmpty()) emptyList() else listOf(
                    OcrBlock(
                        ocrLines.joinToString(" ") { it.text },
                        union(ocrLines.map { it.boundingBox }),
                        ocrLines
                    )
                )
                OcrResult(
                    block, bitmap.width, bitmap.height,
                    diagnostics = OcrDiagnostics("MNN ${backendName(config.backend)}", elapsed(startedAt))
                )
            }
        } finally {
            runtime.releaseModel(detectorHandle)
            runtime.releaseModel(recognizerHandle)
        }
    }

    private fun detectorMap(values: FloatArray, shape: IntArray): FloatArray? {
        if (shape.size < 2) return null
        val expected = shape.takeLast(2).fold(1) { a, b -> a * b }
        return values.takeIf { it.size >= expected }?.copyOf(expected)
    }

    private fun emptyResult(bitmap: Bitmap, startedAt: Long, backend: String) =
        OcrResult(emptyList(), bitmap.width, bitmap.height, diagnostics = OcrDiagnostics(backend, elapsed(startedAt)))

    private fun union(rects: List<Rect>): Rect = Rect(
        rects.minOf { it.left }, rects.minOf { it.top },
        rects.maxOf { it.right }, rects.maxOf { it.bottom }
    )

    private fun backendName(value: Int) = when (value) {
        2 -> "Vulkan"
        1 -> "OpenCL"
        else -> "CPU"
    }

    private fun elapsed(startedAt: Long) = SystemClock.elapsedRealtime() - startedAt
}
