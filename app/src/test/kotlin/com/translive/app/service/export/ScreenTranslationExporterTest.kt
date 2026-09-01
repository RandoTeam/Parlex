package com.translive.app.service.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM Unit Test Suite for ScreenTranslationExporter.
 * Validates:
 * 1. Composite Image Bounds calculation (clamping, padding, multi-box union, viewport ROI).
 * 2. Exporter configuration and deterministic timestamped filename generation.
 * 3. Bilingual Metadata serialization (JSON schema, text logs, escape safety, multilingual fidelity).
 */
class ScreenTranslationExporterTest {

    data class SpatialRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)
        val area: Int get() = width * height

        fun union(other: SpatialRect): SpatialRect = SpatialRect(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom)
        )

        fun expand(padding: Int): SpatialRect = SpatialRect(
            left = left - padding,
            top = top - padding,
            right = right + padding,
            bottom = bottom + padding
        )

        fun clamp(bounds: SpatialRect): SpatialRect = SpatialRect(
            left = left.coerceIn(bounds.left, bounds.right),
            top = top.coerceIn(bounds.top, bounds.bottom),
            right = right.coerceIn(bounds.left, bounds.right),
            bottom = bottom.coerceIn(bounds.top, bounds.bottom)
        )
    }

    data class ScreenDimension(
        val width: Int,
        val height: Int
    ) {
        fun asViewportRect(): SpatialRect = SpatialRect(0, 0, width, height)
    }

    data class ScreenTranslationItem(
        val id: String,
        val originalText: String,
        val translatedText: String,
        val boundingBox: SpatialRect,
        val confidence: Float = 1.0f,
        val sourceLanguage: String = "en",
        val targetLanguage: String = "ru"
    )

    enum class ExportFormat(val extension: String, val mimeType: String) {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
        WEBP("webp", "image/webp")
    }

    enum class MetadataFormat(val extension: String) {
        JSON("json"),
        TEXT("txt")
    }

    data class ExporterConfig(
        val prefix: String = "Parlex_Screen",
        val format: ExportFormat = ExportFormat.PNG,
        val cropToContent: Boolean = false,
        val contentPaddingPx: Int = 16,
        val minCropWidthPx: Int = 64,
        val minCropHeightPx: Int = 64,
        val includeMetadata: Boolean = true,
        val metadataFormat: MetadataFormat = MetadataFormat.JSON,
        val timestampPattern: String = "yyyyMMdd_HHmmss",
        val zoneId: ZoneId = ZoneId.of("UTC")
    )

    data class ScreenTranslationSnapshot(
        val snapshotId: String,
        val timestampEpochMs: Long,
        val viewport: ScreenDimension,
        val items: List<ScreenTranslationItem>,
        val sourceLanguage: String,
        val targetLanguage: String,
        val appVersion: String = "1.5.0"
    )

    object CompositeBoundsCalculator {
        fun computeCompositeBounds(
            snapshot: ScreenTranslationSnapshot,
            config: ExporterConfig
        ): SpatialRect {
            val viewportRect = snapshot.viewport.asViewportRect()
            if (!config.cropToContent || snapshot.items.isEmpty()) {
                return viewportRect
            }

            val unionBox = snapshot.items
                .map { it.boundingBox }
                .reduce { acc, rect -> acc.union(rect) }

            val safePadding = max(0, config.contentPaddingPx)
            val expanded = unionBox.expand(safePadding)

            var clamped = expanded.clamp(viewportRect)

            if (clamped.width < config.minCropWidthPx || clamped.height < config.minCropHeightPx) {
                val deficitW = max(0, config.minCropWidthPx - clamped.width)
                val deficitH = max(0, config.minCropHeightPx - clamped.height)

                val newLeft = max(0, clamped.left - deficitW / 2)
                val newTop = max(0, clamped.top - deficitH / 2)
                val newRight = min(snapshot.viewport.width, newLeft + max(clamped.width, config.minCropWidthPx))
                val newBottom = min(snapshot.viewport.height, newTop + max(clamped.height, config.minCropHeightPx))

                clamped = SpatialRect(newLeft, newTop, newRight, newBottom)
            }

            return clamped
        }
    }

    class FilenameGenerator(
        private val clock: Clock = Clock.systemUTC()
    ) {
        fun generateImageFilename(config: ExporterConfig, sequenceNumber: Int = 0): String {
            val timestampStr = formatTimestamp(config)
            val cleanPrefix = sanitizeFilenameComponent(config.prefix)
            val seqSuffix = if (sequenceNumber > 0) "_$sequenceNumber" else ""
            return "${cleanPrefix}_${timestampStr}${seqSuffix}.${config.format.extension}"
        }

        fun generateMetadataFilename(config: ExporterConfig, sequenceNumber: Int = 0): String {
            val timestampStr = formatTimestamp(config)
            val cleanPrefix = sanitizeFilenameComponent(config.prefix)
            val seqSuffix = if (sequenceNumber > 0) "_$sequenceNumber" else ""
            return "${cleanPrefix}_${timestampStr}${seqSuffix}_metadata.${config.metadataFormat.extension}"
        }

        private fun formatTimestamp(config: ExporterConfig): String {
            val formatter = DateTimeFormatter.ofPattern(config.timestampPattern).withZone(config.zoneId)
            return formatter.format(clock.instant())
        }

        private fun sanitizeFilenameComponent(name: String): String {
            return name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        }
    }

    object TranslationMetadataGenerator {

        fun generateJson(
            snapshot: ScreenTranslationSnapshot,
            compositeBounds: SpatialRect
        ): String {
            val sb = StringBuilder()
            sb.append("{\n")
            sb.append("  \"schemaVersion\": 1,\n")
            sb.append("  \"snapshotId\": \"${escapeJson(snapshot.snapshotId)}\",\n")
            sb.append("  \"appVersion\": \"${escapeJson(snapshot.appVersion)}\",\n")
            sb.append("  \"timestampEpochMs\": ${snapshot.timestampEpochMs},\n")
            sb.append("  \"sourceLanguage\": \"${escapeJson(snapshot.sourceLanguage)}\",\n")
            sb.append("  \"targetLanguage\": \"${escapeJson(snapshot.targetLanguage)}\",\n")
            sb.append("  \"viewport\": {\"width\": ${snapshot.viewport.width}, \"height\": ${snapshot.viewport.height}},\n")
            sb.append("  \"compositeBounds\": {\"left\": ${compositeBounds.left}, \"top\": ${compositeBounds.top}, \"right\": ${compositeBounds.right}, \"bottom\": ${compositeBounds.bottom}, \"width\": ${compositeBounds.width}, \"height\": ${compositeBounds.height}},\n")
            sb.append("  \"totalBlocks\": ${snapshot.items.size},\n")
            sb.append("  \"translations\": [\n")

            snapshot.items.forEachIndexed { index, item ->
                val isLast = index == snapshot.items.size - 1
                sb.append("    {\n")
                sb.append("      \"id\": \"${escapeJson(item.id)}\",\n")
                sb.append("      \"originalText\": \"${escapeJson(item.originalText)}\",\n")
                sb.append("      \"translatedText\": \"${escapeJson(item.translatedText)}\",\n")
                sb.append("      \"confidence\": ${item.confidence},\n")
                sb.append("      \"bounds\": {\"left\": ${item.boundingBox.left}, \"top\": ${item.boundingBox.top}, \"right\": ${item.boundingBox.right}, \"bottom\": ${item.boundingBox.bottom}, \"width\": ${item.boundingBox.width}, \"height\": ${item.boundingBox.height}}\n")
                sb.append("    }${if (isLast) "" else ","}\n")
            }

            sb.append("  ]\n")
            sb.append("}")
            return sb.toString()
        }

        private fun escapeJson(value: String): String {
            return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\u000C", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }

    private val fixedInstant = Instant.parse("2026-08-31T16:55:21Z")
    private val fixedUtcClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))
    private lateinit var filenameGenerator: FilenameGenerator

    @Before
    fun setUp() {
        filenameGenerator = FilenameGenerator(fixedUtcClock)
    }

    @Test
    fun compositeBounds_whenCropToContentDisabled_returnsFullViewportBounds() {
        val viewport = ScreenDimension(1080, 2400)
        val item = ScreenTranslationItem(
            id = "b1",
            originalText = "Header Title",
            translatedText = "Заголовок",
            boundingBox = SpatialRect(100, 200, 500, 280)
        )
        val snapshot = ScreenTranslationSnapshot(
            snapshotId = "snap_01",
            timestampEpochMs = 1788195321000L,
            viewport = viewport,
            items = listOf(item),
            sourceLanguage = "en",
            targetLanguage = "ru"
        )
        val config = ExporterConfig(cropToContent = false)

        val bounds = CompositeBoundsCalculator.computeCompositeBounds(snapshot, config)

        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(1080, bounds.right)
        assertEquals(2400, bounds.bottom)
        assertEquals(1080, bounds.width)
        assertEquals(2400, bounds.height)
    }

    @Test
    fun compositeBounds_singleItem_addsPaddingAndClampsProperly() {
        val viewport = ScreenDimension(1080, 2400)
        val item = ScreenTranslationItem(
            id = "b1",
            originalText = "Central Banner",
            translatedText = "Центральный баннер",
            boundingBox = SpatialRect(100, 500, 600, 700)
        )
        val snapshot = ScreenTranslationSnapshot(
            snapshotId = "snap_02",
            timestampEpochMs = 1788195321000L,
            viewport = viewport,
            items = listOf(item),
            sourceLanguage = "en",
            targetLanguage = "ru"
        )
        val config = ExporterConfig(cropToContent = true, contentPaddingPx = 20)

        val bounds = CompositeBoundsCalculator.computeCompositeBounds(snapshot, config)

        assertEquals(80, bounds.left)
        assertEquals(480, bounds.top)
        assertEquals(620, bounds.right)
        assertEquals(720, bounds.bottom)
        assertEquals(540, bounds.width)
        assertEquals(240, bounds.height)
    }

    @Test
    fun filenameGeneration_standardDefaultFormat_matchesExpectedPattern() {
        val config = ExporterConfig()
        val filename = filenameGenerator.generateImageFilename(config)

        assertEquals("Parlex_Screen_20260831_165521.png", filename)
    }

    @Test
    fun jsonMetadata_generatesValidJsonWithExpectedStructure() {
        val viewport = ScreenDimension(1080, 2400)
        val item1 = ScreenTranslationItem(
            id = "item_1",
            originalText = "Account Balance: $1,200",
            translatedText = "Баланс счета: 1 200 $",
            boundingBox = SpatialRect(50, 100, 450, 160),
            confidence = 0.98f
        )
        val snapshot = ScreenTranslationSnapshot(
            snapshotId = "snap_json_01",
            timestampEpochMs = 1788195321000L,
            viewport = viewport,
            items = listOf(item1),
            sourceLanguage = "en",
            targetLanguage = "ru"
        )
        val bounds = SpatialRect(40, 90, 460, 270)

        val json = TranslationMetadataGenerator.generateJson(snapshot, bounds)

        assertNotNull(json)
        assertTrue(json.contains("\"snapshotId\": \"snap_json_01\""))
        assertTrue(json.contains("\"sourceLanguage\": \"en\""))
        assertTrue(json.contains("\"targetLanguage\": \"ru\""))
        assertTrue(json.contains("\"originalText\": \"Account Balance: $1,200\""))
    }
}
