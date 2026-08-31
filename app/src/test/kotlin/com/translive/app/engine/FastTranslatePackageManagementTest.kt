package com.translive.app.engine

import com.translive.app.data.model.ComponentInstallStatus
import com.translive.app.data.model.Language
import com.translive.app.data.model.LanguagePack
import com.translive.app.data.model.PackComponent
import com.translive.app.data.model.PackComponentType
import com.translive.app.data.model.PackOverallStatus
import com.translive.app.data.model.TravelPacksCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM Unit Test Suite for Fast NMT Language Package Management.
 *
 * Verifies:
 * 1. Package Catalog Aggregation:
 *    - Language code mapping to ML Kit Translate codes or LLM fallback.
 *    - Chinese dialect consolidation (zh, zh-Hant, yue, nan -> zh).
 *    - Package sizing (30 MB per pack) and total/installed/missing storage aggregation.
 * 2. Bulk Download Logic:
 *    - Filtering of already downloaded packages and unsupported codes.
 *    - Monotonic progress increments across sequential/batched downloads.
 *    - Final state convergence where all requested supported packages are installed.
 *    - Edge cases: empty list, all already installed, all unsupported.
 * 3. Single Package Deletion:
 *    - Storage reclamation (30 MB reduction per pack).
 *    - Installed count decrements and missing storage re-evaluation.
 *    - Shared dialect model invalidation.
 *    - Graceful no-op handling for uninstalled packages.
 * 4. Zero-Emoji Compliance:
 *    - Strict Unicode range validation across all titles, descriptions, badges,
 *      component names, and language metadata.
 * 5. Pure JVM Isolation:
 *    - Zero Android framework imports (no Context, no android.util.Log, no Robolectric).
 */
class FastTranslatePackageManagementTest {

    companion object {
        const val FAST_PACKAGE_SIZE_BYTES = 30_000_000L // 30 MB per ML Kit pack
        const val FAST_PACKAGE_SIZE_MB = 30L

        /** Supported ML Kit NMT language target codes */
        val ML_KIT_SUPPORTED_CODES = setOf(
            "en", "zh", "ja", "ko", "fr", "de", "es", "pt", "it", "nl",
            "pl", "cs", "tr", "uk", "ru", "hi", "bn", "gu", "mr", "ta",
            "te", "ur", "fa", "he", "ar", "th", "vi", "id", "ms", "tl"
        )

        /** Languages not available in ML Kit NMT that require local LLM fallback */
        val LLM_FALLBACK_CODES = setOf("my", "km", "mn", "bo", "ug")
    }

    /** Pure JVM Domain Model for Downloadable Fast NMT Language Package */
    data class FastLanguagePackage(
        val modelLanguageCode: String,
        val languages: List<Language>,
        val sizeBytes: Long = FAST_PACKAGE_SIZE_BYTES,
        val fastSupported: Boolean
    ) {
        val sizeMb: Long get() = sizeBytes / (1000 * 1000)
    }

    /** Pure JVM Download Progress Event */
    data class DownloadProgressEvent(
        val completedPackages: Int,
        val totalPackages: Int,
        val progressFraction: Float,
        val currentPackageCode: String,
        val statusMessage: String
    )

    /**
     * Pure JVM Fast Translation Package Manager Harness.
     * Simulates package registry, storage math, download queuing, and deletion.
     */
    class FastTranslatePackageManagerHarness(
        initialDownloadedCodes: Set<String> = emptySet()
    ) {
        private val _downloadedCodes = MutableStateFlow(initialDownloadedCodes.toSet())
        val downloadedCodes: StateFlow<Set<String>> = _downloadedCodes.asStateFlow()

        private val _isDownloading = MutableStateFlow(false)
        val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

        /** Maps Parlex Language enum code to ML Kit model code or null for LLM fallback */
        fun toMlKitLang(code: String): String? {
            return when (code) {
                "en" -> "en"
                "zh", "zh-Hant" -> "zh"
                "ja" -> "ja"
                "ko" -> "ko"
                "fr" -> "fr"
                "de" -> "de"
                "es" -> "es"
                "pt" -> "pt"
                "it" -> "it"
                "nl" -> "nl"
                "pl" -> "pl"
                "cs" -> "cs"
                "tr" -> "tr"
                "uk" -> "uk"
                "ru" -> "ru"
                "hi" -> "hi"
                "bn" -> "bn"
                "gu" -> "gu"
                "mr" -> "mr"
                "ta" -> "ta"
                "te" -> "te"
                "ur" -> "ur"
                "fa" -> "fa"
                "he" -> "he"
                "ar" -> "ar"
                "th" -> "th"
                "vi" -> "vi"
                "id" -> "id"
                "ms" -> "ms"
                "fil" -> "tl"
                // Dialects mapped to base Chinese model
                "yue", "nan" -> "zh"
                // Not in ML Kit NMT
                "my", "km", "mn", "bo", "ug" -> null
                else -> null
            }
        }

        /** Aggregates all downloadable packages grouped by shared ML Kit model */
        fun availableLanguagePackages(): List<FastLanguagePackage> =
            Language.allLanguages
                .groupBy { language -> toMlKitLang(language.code) ?: "llm:${language.code}" }
                .map { (modelCode, languages) ->
                    FastLanguagePackage(
                        modelLanguageCode = modelCode,
                        languages = languages,
                        sizeBytes = if (modelCode.startsWith("llm:")) 0L else FAST_PACKAGE_SIZE_BYTES,
                        fastSupported = !modelCode.startsWith("llm:")
                    )
                }
                .sortedBy { it.languages.first().displayName }

        fun getTotalCatalogSizeBytes(): Long =
            availableLanguagePackages()
                .filter { it.fastSupported }
                .sumOf { it.sizeBytes }

        fun getInstalledSizeBytes(): Long {
            val installed = _downloadedCodes.value
            return availableLanguagePackages()
                .filter { it.fastSupported && it.modelLanguageCode in installed }
                .sumOf { it.sizeBytes }
        }

        fun getMissingSizeBytes(): Long {
            val installed = _downloadedCodes.value
            return availableLanguagePackages()
                .filter { it.fastSupported && it.modelLanguageCode !in installed }
                .sumOf { it.sizeBytes }
        }

        /** Determines if a specific language pair is ready for fast NMT */
        fun isPairReady(sourceCode: String, targetCode: String): Boolean {
            if (sourceCode == targetCode) return true
            val srcModel = toMlKitLang(sourceCode) ?: return false
            val tgtModel = toMlKitLang(targetCode) ?: return false
            val installed = _downloadedCodes.value
            return srcModel in installed && tgtModel in installed
        }

        fun getMissingPackagesForPair(sourceCode: String, targetCode: String): Set<String> {
            val srcModel = toMlKitLang(sourceCode)
            val tgtModel = toMlKitLang(targetCode)
            val required = setOfNotNull(srcModel, tgtModel)
            return required - _downloadedCodes.value
        }

        /**
         * Bulk download logic:
         * 1. Filters unsupported codes (LLM fallbacks and invalid codes).
         * 2. Filters already downloaded codes.
         * 3. Emits monotonic progress callbacks.
         * 4. Updates installed set atomically.
         */
        suspend fun downloadLanguagePackages(
            requestedCodes: Collection<String>,
            onProgress: ((DownloadProgressEvent) -> Unit)? = null
        ): Boolean {
            // Resolve codes to ML Kit models and filter supported
            val supportedRequested = requestedCodes.mapNotNull { rawCode ->
                toMlKitLang(rawCode) ?: rawCode.takeIf { it in ML_KIT_SUPPORTED_CODES }
            }.toSet()

            if (supportedRequested.isEmpty()) {
                return false
            }

            val missing = (supportedRequested - _downloadedCodes.value).toList()
            if (missing.isEmpty()) {
                // Already downloaded
                onProgress?.invoke(
                    DownloadProgressEvent(
                        completedPackages = supportedRequested.size,
                        totalPackages = supportedRequested.size,
                        progressFraction = 1.0f,
                        currentPackageCode = "",
                        statusMessage = "All packages already installed"
                    )
                )
                return true
            }

            _isDownloading.value = true
            try {
                var completed = 0
                val total = missing.size

                for (code in missing) {
                    completed++
                    _downloadedCodes.update { it + code }
                    val fraction = completed.toFloat() / total.toFloat()
                    onProgress?.invoke(
                        DownloadProgressEvent(
                            completedPackages = completed,
                            totalPackages = total,
                            progressFraction = fraction,
                            currentPackageCode = code,
                            statusMessage = "Downloaded package $code ($completed/$total)"
                        )
                    )
                }
                return true
            } finally {
                _isDownloading.value = false
            }
        }

        /** Deletes a single package and reclaims storage */
        fun deleteLanguagePackage(modelLanguageCode: String): Boolean {
            val mlKitCode = toMlKitLang(modelLanguageCode) ?: modelLanguageCode
            if (mlKitCode !in _downloadedCodes.value) {
                return false
            }
            _downloadedCodes.update { it - mlKitCode }
            return true
        }
    }

    private lateinit var manager: FastTranslatePackageManagerHarness

    @Before
    fun setUp() {
        manager = FastTranslatePackageManagerHarness()
    }

    // =========================================================================
    // 1. Package Catalog Aggregation Tests
    // =========================================================================

    @Test
    fun testPackageCatalog_allSupportedLanguagesMapToMlKitOrFallback() {
        val allLanguages = Language.allLanguages
        assertEquals(38, allLanguages.size) // 33 primary + 5 dialects

        for (language in allLanguages) {
            val mlKitCode = manager.toMlKitLang(language.code)
            if (language.code in LLM_FALLBACK_CODES) {
                assertNull(
                    "Language ${language.displayName} (${language.code}) must map to null for LLM fallback",
                    mlKitCode
                )
            } else {
                assertNotNull(
                    "Supported language ${language.displayName} (${language.code}) must map to ML Kit code",
                    mlKitCode
                )
                assertTrue(
                    "ML Kit code $mlKitCode for ${language.code} must be in known ML Kit supported list",
                    mlKitCode in ML_KIT_SUPPORTED_CODES
                )
            }
        }
    }

    @Test
    fun testPackageCatalog_chineseDialectsConsolidateToSharedModelPackage() {
        val zhPacks = listOf("zh", "zh-Hant", "yue", "nan").map { code ->
            manager.toMlKitLang(code)
        }
        // Simplified Chinese, Traditional Chinese, Cantonese, and Hokkien all map to "zh"
        assertTrue(zhPacks.all { it == "zh" })

        val packages = manager.availableLanguagePackages()
        val chinesePackage = packages.find { it.modelLanguageCode == "zh" }
        assertNotNull(chinesePackage)
        assertTrue(chinesePackage!!.fastSupported)
        assertEquals(4, chinesePackage.languages.size)
        val attachedCodes = chinesePackage.languages.map { it.code }.toSet()
        assertEquals(setOf("zh", "zh-Hant", "yue", "nan"), attachedCodes)
    }

    @Test
    fun testPackageCatalog_packageSizeCalculationAndAggregation() {
        val packages = manager.availableLanguagePackages()
        val supportedPackages = packages.filter { it.fastSupported }
        val fallbackPackages = packages.filter { !it.fastSupported }

        val expectedSupportedCount = 30 // 30 distinct ML Kit model packages
        assertEquals(expectedSupportedCount, supportedPackages.size)
        assertEquals(5, fallbackPackages.size)  // 5 LLM fallback languages

        // Each supported package is exactly 30 MB (30_000_000 bytes)
        for (pkg in supportedPackages) {
            assertEquals(FAST_PACKAGE_SIZE_BYTES, pkg.sizeBytes)
            assertEquals(FAST_PACKAGE_SIZE_MB, pkg.sizeMb)
        }

        // Fallback packages take 0 bytes of ML Kit package storage
        for (pkg in fallbackPackages) {
            assertEquals(0L, pkg.sizeBytes)
        }

        val expectedTotalBytes = expectedSupportedCount * FAST_PACKAGE_SIZE_BYTES // 900,000,000 bytes (~900 MB)
        assertEquals(expectedTotalBytes, manager.getTotalCatalogSizeBytes())
        assertEquals(0L, manager.getInstalledSizeBytes())
        assertEquals(expectedTotalBytes, manager.getMissingSizeBytes())
    }

    @Test
    fun testPackageCatalog_travelPacksComponentSizeAndSharedAssetDeduplication() {
        val travelPacks = TravelPacksCatalog.createDefaultTravelPacks()
        assertEquals(7, travelPacks.size)

        for (pack in travelPacks) {
            val nmtComponents = pack.components.filter { it.type == PackComponentType.NMT_TRANSLATE }
            assertEquals(2, nmtComponents.size) // Source and target NMT packs
            for (comp in nmtComponents) {
                assertEquals(30_000_000L, comp.sizeBytes)
            }

            assertTrue(pack.totalSizeBytes > 60_000_000L) // NMT (60MB) + OCR + Dict + STT (39MB)
        }

        // Test deduplication across packs
        val packRuEn = travelPacks.find { it.id == "pack_ru_en" }!!
        val packViEn = travelPacks.find { it.id == "pack_vi_en" }!!

        val sharedTargetComponentId = "nmt_en"
        assertTrue(packRuEn.components.any { it.id == sharedTargetComponentId })
        assertTrue(packViEn.components.any { it.id == sharedTargetComponentId })
    }

    // =========================================================================
    // 2. Bulk Download Logic Tests
    // =========================================================================

    @Test
    fun testBulkDownload_filtersAlreadyDownloadedAndUnsupported() = runBlocking {
        // Pre-install English and Russian
        manager = FastTranslatePackageManagerHarness(initialDownloadedCodes = setOf("en", "ru"))
        assertEquals(2, manager.downloadedCodes.value.size)
        assertEquals(60_000_000L, manager.getInstalledSizeBytes())

        val requested = listOf("en", "ru", "fr", "de", "my", "km", "invalid_code")
        val progressEvents = mutableListOf<DownloadProgressEvent>()

        val success = manager.downloadLanguagePackages(requested) { event ->
            progressEvents.add(event)
        }

        assertTrue(success)
        // Only missing supported packages ("fr", "de") should have been downloaded
        assertEquals(2, progressEvents.size)
        assertEquals(listOf("fr", "de"), progressEvents.map { it.currentPackageCode })
        assertEquals(setOf("en", "ru", "fr", "de"), manager.downloadedCodes.value)
        assertEquals(120_000_000L, manager.getInstalledSizeBytes())
    }

    @Test
    fun testBulkDownload_progressIncrementsMonotonicallyAndCompletes() = runBlocking {
        val requested = listOf("ja", "ko", "es", "it")
        val progressEvents = mutableListOf<DownloadProgressEvent>()

        val success = manager.downloadLanguagePackages(requested) { event ->
            progressEvents.add(event)
        }

        assertTrue(success)
        assertEquals(4, progressEvents.size)

        var lastProgress = 0f
        for ((idx, event) in progressEvents.withIndex()) {
            assertEquals(idx + 1, event.completedPackages)
            assertEquals(4, event.totalPackages)
            assertTrue("Progress fraction must be strictly increasing", event.progressFraction > lastProgress)
            assertTrue("Progress fraction must not exceed 1.0", event.progressFraction <= 1.0f)
            lastProgress = event.progressFraction
        }
        assertEquals(1.0f, progressEvents.last().progressFraction, 0.0001f)
    }

    @Test
    fun testBulkDownload_finalStateHasAllSupportedPackagesDownloaded() = runBlocking {
        val supportedCodes = ML_KIT_SUPPORTED_CODES.toList().map { if (it == "tl") "fil" else it }
        val success = manager.downloadLanguagePackages(supportedCodes)

        assertTrue(success)
        assertEquals(30, manager.downloadedCodes.value.size)
        assertEquals(manager.getTotalCatalogSizeBytes(), manager.getInstalledSizeBytes())
        assertEquals(0L, manager.getMissingSizeBytes())

        // Validate bidirectional pair readiness across all supported pairs
        assertTrue(manager.isPairReady("ru", "en"))
        assertTrue(manager.isPairReady("en", "ja"))
        assertTrue(manager.isPairReady("zh-Hant", "fr"))
        assertTrue(manager.isPairReady("yue", "es"))
    }

    @Test
    fun testBulkDownload_edgeCases_emptyListAndAlreadyDownloaded() = runBlocking {
        // Edge Case 1: Empty list
        val emptySuccess = manager.downloadLanguagePackages(emptyList())
        assertFalse("Empty request should return false", emptySuccess)

        // Edge Case 2: Only unsupported fallback codes
        val unsupportedSuccess = manager.downloadLanguagePackages(listOf("my", "km", "bo"))
        assertFalse("Unsupported request should return false", unsupportedSuccess)

        // Edge Case 3: All requested already downloaded
        manager = FastTranslatePackageManagerHarness(initialDownloadedCodes = setOf("en", "es"))
        var alreadyDownloadedCallbackCalled = false
        val alreadySuccess = manager.downloadLanguagePackages(listOf("en", "es")) { event ->
            alreadyDownloadedCallbackCalled = true
            assertEquals(1.0f, event.progressFraction, 0.0001f)
        }
        assertTrue(alreadySuccess)
        assertTrue(alreadyDownloadedCallbackCalled)
    }

    // =========================================================================
    // 3. Single Package Deletion Tests
    // =========================================================================

    @Test
    fun testSinglePackageDeletion_reducesInstalledCountAndReclaimsStorage() = runBlocking {
        // Install 5 packages (150 MB)
        manager.downloadLanguagePackages(listOf("en", "ru", "fr", "de", "ja"))
        assertEquals(5, manager.downloadedCodes.value.size)
        assertEquals(150_000_000L, manager.getInstalledSizeBytes())

        // Delete French ("fr")
        val deleted = manager.deleteLanguagePackage("fr")
        assertTrue(deleted)

        assertEquals(4, manager.downloadedCodes.value.size)
        assertEquals(120_000_000L, manager.getInstalledSizeBytes())
        assertEquals(manager.getTotalCatalogSizeBytes() - 120_000_000L, manager.getMissingSizeBytes())
        assertFalse(manager.downloadedCodes.value.contains("fr"))

        // Pair involving French now requires French package
        assertFalse(manager.isPairReady("fr", "en"))
        assertEquals(setOf("fr"), manager.getMissingPackagesForPair("fr", "en"))
    }

    @Test
    fun testSinglePackageDeletion_sharedDialectModelImpact() = runBlocking {
        // Install Chinese model via Cantonese ("yue")
        manager.downloadLanguagePackages(listOf("yue", "en"))
        assertTrue(manager.isPairReady("yue", "en"))
        assertTrue(manager.isPairReady("zh", "en"))
        assertTrue(manager.isPairReady("zh-Hant", "en"))
        assertTrue(manager.isPairReady("nan", "en"))

        // Deleting traditional Chinese ("zh-Hant") deletes the underlying "zh" model
        val deleted = manager.deleteLanguagePackage("zh-Hant")
        assertTrue(deleted)

        assertFalse(manager.downloadedCodes.value.contains("zh"))
        assertFalse(manager.isPairReady("zh", "en"))
        assertFalse(manager.isPairReady("yue", "en"))
        assertFalse(manager.isPairReady("nan", "en"))
    }

    @Test
    fun testSinglePackageDeletion_nonInstalledPackageHandledGracefully() {
        val deleted = manager.deleteLanguagePackage("non_existent_code")
        assertFalse("Deleting non-installed package must return false", deleted)
        assertEquals(0, manager.downloadedCodes.value.size)
        assertEquals(0L, manager.getInstalledSizeBytes())
    }

    // =========================================================================
    // 4. Zero-Emoji Compliance Tests
    // =========================================================================

    /**
     * Strict Unicode Emoji and Pictograph Detector.
     * Validates that strings contain zero emoji, symbols, or regional indicator characters.
     */
    private fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (
                cp in 0x1F300..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x1F1E6..0x1F1FF
            ) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    @Test
    fun testZeroEmojiCompliance_acrossAllCatalogTitlesDescriptionsAndBadges() {
        val travelPacks = TravelPacksCatalog.createDefaultTravelPacks()

        for (pack in travelPacks) {
            assertFalse(
                "LanguagePack title '${pack.title}' must not contain emoji",
                containsEmoji(pack.title)
            )
            assertFalse(
                "LanguagePack description '${pack.description}' must not contain emoji",
                containsEmoji(pack.description)
            )
            for (comp in pack.components) {
                assertFalse(
                    "PackComponent name '${comp.name}' must not contain emoji",
                    containsEmoji(comp.name)
                )
            }
        }

        // Validate all Language display names and native names
        for (language in Language.allLanguages) {
            assertFalse(
                "Language displayName '${language.displayName}' must not contain emoji",
                containsEmoji(language.displayName)
            )
            assertFalse(
                "Language nativeName '${language.nativeName}' must not contain emoji",
                containsEmoji(language.nativeName)
            )
        }

        // Validate package catalog titles and generated descriptions
        val packages = manager.availableLanguagePackages()
        for (pkg in packages) {
            assertFalse(
                "Model code '${pkg.modelLanguageCode}' must not contain emoji",
                containsEmoji(pkg.modelLanguageCode)
            )
        }
    }
}
