package com.translive.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import com.translive.app.data.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class LiveBoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top

    fun offset(dx: Int, dy: Int): LiveBoundingBox =
        LiveBoundingBox(left + dx, top + dy, right + dx, bottom + dy)

    fun calculateIoU(other: LiveBoundingBox): Float {
        val intersectLeft = max(left, other.left)
        val intersectTop = max(top, other.top)
        val intersectRight = min(right, other.right)
        val intersectBottom = min(bottom, other.bottom)

        if (intersectLeft < intersectRight && intersectTop < intersectBottom) {
            val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
            val area1 = width() * height()
            val area2 = other.width() * other.height()
            val unionArea = area1 + area2 - intersectionArea
            return if (unionArea > 0) intersectionArea.toFloat() / unionArea else 0f
        }
        return 0f
    }

    companion object {
        fun fromRect(rect: Rect): LiveBoundingBox =
            LiveBoundingBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}

data class LiveTextBlock(
    val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val bounds: LiveBoundingBox,
    val translatedText: String? = null,
    val transliteration: String? = null,
    val detectedLanguage: Language? = null
)

data class LiveTranslationFrame(
    val blocks: List<LiveTextBlock>,
    val timestampMs: Long,
    val sourceLang: String,
    val targetLang: String,
    val processingTimeMs: Long
)

private data class TranslationCacheKey(
    val sourceLang: String,
    val targetLang: String,
    val text: String
)

data class CachedTranslation(
    val translatedText: String,
    val transliteration: String? = null
)

class LiveTranslationCache(private val maxSize: Int = 1000) {
    private val cache = object : LinkedHashMap<TranslationCacheKey, CachedTranslation>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationCacheKey, CachedTranslation>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(sourceLang: String, targetLang: String, text: String): CachedTranslation? {
        return cache[TranslationCacheKey(sourceLang, targetLang, text)]
    }

    @Synchronized
    fun put(sourceLang: String, targetLang: String, text: String, translation: String, transliteration: String? = null) {
        cache[TranslationCacheKey(sourceLang, targetLang, text)] = CachedTranslation(translation, transliteration)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}

/**
 * Incremental OCR and Fast NMT translation pipeline with spatial diffing,
 * per-block multi-language auto-detection, zero-key currency augmentation, and LRU caching.
 */
@Singleton
class LiveTranslationPipeline @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val fastTranslateEngine: FastTranslateEngine,
    private val transliterationEngine: TransliterationEngine,
    private val languageDetectionEngine: LanguageDetectionEngine,
    private val currencyAugmentor: CurrencyAugmentor
) {

    val cache = LiveTranslationCache(1000)
    private var previousBlocks: List<LiveTextBlock> = emptyList()

    /**
     * Incrementally processes a keyframe image.
     */
    suspend fun processKeyframe(
        bitmap: Bitmap,
        sourceLanguage: Language,
        targetLanguage: Language,
        showTransliteration: Boolean = true
    ): LiveTranslationFrame = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 1. Run OCR
        val ocrResult = ocrEngine.recognize(bitmap, sourceLanguage.code)
        val extractedBlocks = ocrResult.blocks.flatMap { block ->
            block.lines.map { line ->
                LiveTextBlock(
                    rawText = line.text.trim(),
                    bounds = LiveBoundingBox.fromRect(line.boundingBox)
                )
            }
        }.filter { it.rawText.isNotBlank() }

        if (extractedBlocks.isEmpty()) {
            previousBlocks = emptyList()
            return@withContext LiveTranslationFrame(
                blocks = emptyList(),
                timestampMs = System.currentTimeMillis(),
                sourceLang = sourceLanguage.code,
                targetLang = targetLanguage.code,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 2. Spatial & text diffing against previous frame
        val (retainedBlocks, newOrModifiedBlocks) = diffFrames(previousBlocks, extractedBlocks)

        // 3. Batch translation & caching for new or modified blocks
        val translatedNewBlocks = if (newOrModifiedBlocks.isNotEmpty()) {
            translateAndCacheBlocks(
                blocks = newOrModifiedBlocks,
                defaultSourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                showTransliteration = showTransliteration
            )
        } else {
            emptyList()
        }

        // 4. Merge results
        val finalBlocks = retainedBlocks + translatedNewBlocks
        previousBlocks = finalBlocks

        val totalElapsed = System.currentTimeMillis() - startTime
        return@withContext LiveTranslationFrame(
            blocks = finalBlocks,
            timestampMs = System.currentTimeMillis(),
            sourceLang = sourceLanguage.code,
            targetLang = targetLanguage.code,
            processingTimeMs = totalElapsed
        )
    }

    private suspend fun translateAndCacheBlocks(
        blocks: List<LiveTextBlock>,
        defaultSourceLanguage: Language,
        targetLanguage: Language,
        showTransliteration: Boolean
    ): List<LiveTextBlock> = withContext(Dispatchers.IO) {
        val resolvedBlocks = mutableListOf<LiveTextBlock>()

        // 1. Detect language per block if source is AUTO or fallback
        val blocksWithLang = blocks.map { block ->
            val detected = if (defaultSourceLanguage.code.equals("auto", ignoreCase = true)) {
                languageDetectionEngine.detect(block.rawText) ?: Language.ENGLISH
            } else {
                defaultSourceLanguage
            }
            block.copy(detectedLanguage = detected)
        }

        // 2. Group by effective source language for batch translation
        val groupedByLang = blocksWithLang.groupBy { it.detectedLanguage ?: defaultSourceLanguage }

        for ((srcLang, group) in groupedByLang) {
            val pendingBlocks = mutableListOf<LiveTextBlock>()
            val pendingTexts = mutableListOf<String>()

            for (block in group) {
                val cached = cache.get(srcLang.code, targetLanguage.code, block.rawText)
                if (cached != null) {
                    resolvedBlocks.add(
                        block.copy(
                            translatedText = cached.translatedText,
                            transliteration = cached.transliteration
                        )
                    )
                } else if (srcLang == targetLanguage) {
                    // Already in target language, augment currency only
                    val augmented = currencyAugmentor.augment(block.rawText, srcLang)
                    cache.put(srcLang.code, targetLanguage.code, block.rawText, augmented, null)
                    resolvedBlocks.add(block.copy(translatedText = augmented))
                } else {
                    pendingBlocks.add(block)
                    pendingTexts.add(block.rawText)
                }
            }

            if (pendingTexts.isNotEmpty()) {
                fastTranslateEngine.activateDownloadedPair(srcLang.code, targetLanguage.code)
                val freshTranslations = fastTranslateEngine.translateLines(pendingTexts)

                pendingBlocks.forEachIndexed { index, block ->
                    val rawTranslated = freshTranslations.getOrElse(index) { block.rawText }
                    // Apply live currency converter augmentation (e.g. $50 -> 50 $ (≈ 4,580 ₽))
                    val augmentedTranslated = currencyAugmentor.augment(rawTranslated, srcLang)

                    val transliteration = if (showTransliteration) {
                        transliterationEngine.transliterate(augmentedTranslated, targetLanguage)
                    } else null

                    cache.put(srcLang.code, targetLanguage.code, block.rawText, augmentedTranslated, transliteration)

                    resolvedBlocks.add(
                        block.copy(
                            translatedText = augmentedTranslated,
                            transliteration = transliteration
                        )
                    )
                }
            }
        }

        resolvedBlocks
    }

    fun reset() {
        previousBlocks = emptyList()
        cache.clear()
    }

    companion object {
        fun diffFrames(
            oldBlocks: List<LiveTextBlock>,
            newBlocks: List<LiveTextBlock>
        ): Pair<List<LiveTextBlock>, List<LiveTextBlock>> {
            if (oldBlocks.isEmpty()) {
                return Pair(emptyList(), newBlocks)
            }

            // Estimate vertical scroll delta using exact text matches
            val yOffsets = mutableListOf<Int>()
            for (newBlock in newBlocks) {
                val match = oldBlocks.find { it.rawText == newBlock.rawText }
                if (match != null) {
                    yOffsets.add(newBlock.bounds.top - match.bounds.top)
                }
            }
            val medianYOffset = if (yOffsets.isNotEmpty()) {
                val sorted = yOffsets.sorted()
                sorted[sorted.size / 2]
            } else {
                0
            }

            val retainedBlocks = mutableListOf<LiveTextBlock>()
            val newOrModifiedBlocks = mutableListOf<LiveTextBlock>()

            for (newBlock in newBlocks) {
                val matchedOld = oldBlocks.find { oldBlock ->
                    val shiftedOldBounds = oldBlock.bounds.offset(0, medianYOffset)
                    val iou = shiftedOldBounds.calculateIoU(newBlock.bounds)
                    (iou > 0.5f || oldBlock.rawText == newBlock.rawText) && oldBlock.rawText == newBlock.rawText
                }

                if (matchedOld != null && matchedOld.translatedText != null) {
                    // Retain translation with new spatial bounds
                    retainedBlocks.add(
                        newBlock.copy(
                            id = matchedOld.id,
                            translatedText = matchedOld.translatedText,
                            transliteration = matchedOld.transliteration
                        )
                    )
                } else {
                    newOrModifiedBlocks.add(newBlock)
                }
            }

            return Pair(retainedBlocks, newOrModifiedBlocks)
        }
    }
}
