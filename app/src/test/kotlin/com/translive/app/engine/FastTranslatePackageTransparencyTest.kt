package com.translive.app.engine

import com.translive.app.data.model.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FastTranslatePackageTransparencyTest {

    companion object {
        const val FAST_PACKAGE_SIZE_BYTES = 30_000_000L

        val ML_KIT_SUPPORTED_CODES = listOf(
            "en", "zh", "ja", "ko", "fr", "de", "es", "pt", "it", "nl",
            "pl", "cs", "tr", "uk", "ru", "hi", "bn", "gu", "mr", "ta",
            "te", "ur", "fa", "he", "ar", "th", "vi", "id", "ms", "tl"
        )
    }

    data class FastPackageItemUiState(
        val modelLanguageCode: String,
        val displayName: String,
        val isDownloaded: Boolean = false,
        val isDownloading: Boolean = false,
        val isQueued: Boolean = false
    ) {
        init {
            require(!(isDownloading && isQueued)) {
                "Package $modelLanguageCode cannot have both isDownloading=true and isQueued=true"
            }
            if (isDownloaded) {
                require(!isDownloading && !isQueued) {
                    "Downloaded package $modelLanguageCode cannot be downloading or queued"
                }
            }
        }
    }

    data class BulkDownloadUiState(
        val isBulkDownloading: Boolean = false,
        val packages: List<FastPackageItemUiState> = emptyList(),
        val activeDownloadingCode: String? = null,
        val currentBatchIndex: Int = 0,
        val totalBatchCount: Int = 0
    ) {
        val downloadedCount: Int get() = packages.count { it.isDownloaded }
        val queuedCount: Int get() = packages.count { it.isQueued }
        val activeCount: Int get() = packages.count { it.isDownloading }
    }

    object BulkDownloadHeaderFormatter {
        fun formatActiveStatus(
            languageCode: String,
            displayName: String,
            currentIndex: Int,
            totalCount: Int
        ): String {
            val upperCode = languageCode.uppercase()
            return "Загрузка: [$upperCode] $displayName ($currentIndex / $totalCount)"
        }

        fun formatFromState(state: BulkDownloadUiState): String {
            if (!state.isBulkDownloading || state.activeDownloadingCode == null) {
                return "Готово: ${state.downloadedCount} / ${state.packages.size}"
            }
            val activeItem = state.packages.find { it.modelLanguageCode == state.activeDownloadingCode }
            val displayName = activeItem?.displayName ?: state.activeDownloadingCode.uppercase()
            return formatActiveStatus(
                languageCode = state.activeDownloadingCode,
                displayName = displayName,
                currentIndex = state.currentBatchIndex,
                totalCount = state.totalBatchCount
            )
        }
    }

    class FastTranslateTransparencyHarness(
        initialInstalledCodes: Set<String> = emptySet(),
        catalogCodes: List<String> = ML_KIT_SUPPORTED_CODES
    ) {
        private val _state = MutableStateFlow(
            BulkDownloadUiState(
                packages = catalogCodes.map { code ->
                    val lang = Language.fromCode(if (code == "tl") "fil" else code)
                    FastPackageItemUiState(
                        modelLanguageCode = code,
                        displayName = lang?.displayName ?: code.uppercase(),
                        isDownloaded = code in initialInstalledCodes
                    )
                }
            )
        )
        val state: StateFlow<BulkDownloadUiState> = _state.asStateFlow()

        @Volatile
        private var isCancelled = false
        private var currentDownloadJob: Job? = null

        fun startBulkDownload(
            scope: CoroutineScope,
            targetCodes: List<String> = ML_KIT_SUPPORTED_CODES,
            onStepDownloading: (suspend (stepIndex: Int, code: String, stateSnapshot: BulkDownloadUiState) -> Unit)? = null,
            onStepCompleted: (suspend (stepIndex: Int, code: String, stateSnapshot: BulkDownloadUiState) -> Unit)? = null
        ): Job {
            isCancelled = false
            val job = scope.launch(Dispatchers.Unconfined) {
                val currentPacks = _state.value.packages
                val missingCodes = targetCodes.filter { code ->
                    currentPacks.find { it.modelLanguageCode == code }?.isDownloaded == false
                }

                if (missingCodes.isEmpty()) return@launch

                _state.update { old ->
                    old.copy(
                        isBulkDownloading = true,
                        totalBatchCount = missingCodes.size,
                        packages = old.packages.map { item ->
                            if (item.modelLanguageCode in missingCodes) {
                                item.copy(isQueued = true, isDownloading = false)
                            } else {
                                item
                            }
                        }
                    )
                }

                try {
                    for ((index, code) in missingCodes.withIndex()) {
                        if (isCancelled) throw CancellationException("Download cancelled")
                        val stepIndex = index + 1

                        _state.update { old ->
                            old.copy(
                                activeDownloadingCode = code,
                                currentBatchIndex = stepIndex,
                                packages = old.packages.map { item ->
                                    when (item.modelLanguageCode) {
                                        code -> item.copy(isDownloading = true, isQueued = false)
                                        else -> item
                                    }
                                }
                            )
                        }

                        onStepDownloading?.invoke(stepIndex, code, _state.value)
                        if (isCancelled) {
                            throw CancellationException("Download cancelled")
                        }

                        _state.update { old ->
                            old.copy(
                                packages = old.packages.map { item ->
                                    if (item.modelLanguageCode == code) {
                                        item.copy(isDownloaded = true, isDownloading = false, isQueued = false)
                                    } else {
                                        item
                                    }
                                }
                            )
                        }

                        onStepCompleted?.invoke(stepIndex, code, _state.value)
                    }

                    _state.update { old ->
                        old.copy(
                            isBulkDownloading = false,
                            activeDownloadingCode = null
                        )
                    }
                } catch (e: CancellationException) {
                    _state.update { old ->
                        old.copy(
                            isBulkDownloading = false,
                            activeDownloadingCode = null,
                            packages = old.packages.map { item ->
                                if (item.isDownloading || item.isQueued) {
                                    item.copy(isDownloading = false, isQueued = false)
                                } else {
                                    item
                                }
                            }
                        )
                    }
                    throw e
                }
            }
            currentDownloadJob = job
            return job
        }

        fun cancelBulkDownload() {
            isCancelled = true
            currentDownloadJob?.cancel()
        }
    }

    private lateinit var harness: FastTranslateTransparencyHarness

    @Before
    fun setUp() {
        harness = FastTranslateTransparencyHarness()
    }

    @Test
    fun testActiveVsQueuedStates_duringSequentialBulkDownload_stepByStepTransitions() = runBlocking {
        val recordedSnapshots = mutableListOf<Pair<Int, BulkDownloadUiState>>()

        val job = harness.startBulkDownload(
            scope = this,
            targetCodes = ML_KIT_SUPPORTED_CODES,
            onStepDownloading = { stepIndex, code, snapshot ->
                recordedSnapshots.add(stepIndex to snapshot)

                assertEquals("Exactly 1 package must be downloading", 1, snapshot.activeCount)
                assertEquals("Active downloading code matches", code, snapshot.activeDownloadingCode)

                val activeItem = snapshot.packages.find { it.modelLanguageCode == code }
                assertNotNull("Active item must exist", activeItem)
                assertTrue("Active item must have isDownloading=true", activeItem!!.isDownloading)
                assertFalse("Active item must have isQueued=false", activeItem.isQueued)
                assertFalse("Active item must have isDownloaded=false", activeItem.isDownloaded)

                val downloadedItems = snapshot.packages.filter { it.isDownloaded }
                assertEquals(stepIndex - 1, downloadedItems.size)
                for (item in downloadedItems) {
                    assertFalse(item.isDownloading)
                    assertFalse(item.isQueued)
                }

                val queuedItems = snapshot.packages.filter { it.isQueued }
                assertEquals(ML_KIT_SUPPORTED_CODES.size - stepIndex, queuedItems.size)
                for (item in queuedItems) {
                    assertFalse(item.isDownloading)
                    assertFalse(item.isDownloaded)
                }
            }
        )

        job.join()

        assertEquals(30, recordedSnapshots.size)

        val finalState = harness.state.value
        assertFalse("Bulk downloading must be false when complete", finalState.isBulkDownloading)
        assertNull("Active downloading code must be null", finalState.activeDownloadingCode)
        assertEquals("All 30 packages must be downloaded", 30, finalState.downloadedCount)
        assertEquals("0 packages queued", 0, finalState.queuedCount)
        assertEquals("0 packages downloading", 0, finalState.activeCount)
    }

    @Test
    fun testActiveVsQueuedStates_singleDownloadingInvariantAcrossEntireBatch() = runBlocking {
        val testBatch = listOf("en", "fr", "de", "es", "ja")
        harness = FastTranslateTransparencyHarness(catalogCodes = testBatch)

        val job = harness.startBulkDownload(
            scope = this,
            targetCodes = testBatch,
            onStepDownloading = { _, code, snapshot ->
                val downloadingPackages = snapshot.packages.filter { it.isDownloading }
                assertEquals(1, downloadingPackages.size)
                assertEquals(code, downloadingPackages.first().modelLanguageCode)

                val nonDownloading = snapshot.packages.filter { it.modelLanguageCode != code }
                assertTrue(nonDownloading.none { it.isDownloading })
            }
        )
        job.join()
    }

    @Test
    fun testActiveVsQueuedStates_preInstalledPackagesRemainDownloadedAndNotQueued() = runBlocking {
        harness = FastTranslateTransparencyHarness(
            initialInstalledCodes = setOf("en", "de"),
            catalogCodes = listOf("en", "fr", "de", "es")
        )

        val initial = harness.state.value
        assertEquals(2, initial.downloadedCount)
        assertTrue(initial.packages.find { it.modelLanguageCode == "en" }!!.isDownloaded)
        assertTrue(initial.packages.find { it.modelLanguageCode == "de" }!!.isDownloaded)

        val downloadedSteps = mutableListOf<String>()
        val job = harness.startBulkDownload(
            scope = this,
            targetCodes = listOf("en", "fr", "de", "es"),
            onStepDownloading = { _, code, snapshot ->
                downloadedSteps.add(code)
                val en = snapshot.packages.find { it.modelLanguageCode == "en" }!!
                val de = snapshot.packages.find { it.modelLanguageCode == "de" }!!
                assertTrue("Pre-installed 'en' must stay downloaded", en.isDownloaded)
                assertFalse("Pre-installed 'en' must not be downloading", en.isDownloading)
                assertFalse("Pre-installed 'en' must not be queued", en.isQueued)
                assertTrue("Pre-installed 'de' must stay downloaded", de.isDownloaded)
            }
        )
        job.join()

        assertEquals(listOf("fr", "es"), downloadedSteps)
        assertEquals(4, harness.state.value.downloadedCount)
    }

    @Test
    fun testCancellation_midwayThroughBatch_abortsRemainingAndCleansActiveDownloadingState() = runBlocking {
        val testBatch = listOf("en", "zh", "ja", "ko", "fr", "de")
        harness = FastTranslateTransparencyHarness(catalogCodes = testBatch)

        val cancelAtStep = 3
        var cancelledAtCode: String? = null

        val job = harness.startBulkDownload(
            scope = this,
            targetCodes = testBatch,
            onStepDownloading = { step, code, _ ->
                if (step == cancelAtStep) {
                    cancelledAtCode = code
                    harness.cancelBulkDownload()
                }
            }
        )

        job.join()

        assertEquals("ja", cancelledAtCode)

        val stateAfterCancel = harness.state.value

        assertFalse("isBulkDownloading must be false after cancellation", stateAfterCancel.isBulkDownloading)
        assertNull("activeDownloadingCode must be null after cancellation", stateAfterCancel.activeDownloadingCode)
        assertEquals("0 packages downloading after cancel", 0, stateAfterCancel.activeCount)
        assertEquals("0 packages queued after cancel", 0, stateAfterCancel.queuedCount)

        val en = stateAfterCancel.packages.find { it.modelLanguageCode == "en" }!!
        val zh = stateAfterCancel.packages.find { it.modelLanguageCode == "zh" }!!
        assertTrue("Pre-cancellation 'en' must be downloaded", en.isDownloaded)
        assertTrue("Pre-cancellation 'zh' must be downloaded", zh.isDownloaded)

        val ja = stateAfterCancel.packages.find { it.modelLanguageCode == "ja" }!!
        assertFalse("Interrupted 'ja' must not be marked downloaded", ja.isDownloaded)
        assertFalse("Interrupted 'ja' must not be stuck in downloading state", ja.isDownloading)
        assertFalse("Interrupted 'ja' must not be queued", ja.isQueued)

        val remaining = listOf("ko", "fr", "de")
        for (code in remaining) {
            val item = stateAfterCancel.packages.find { it.modelLanguageCode == code }!!
            assertFalse("Subsequent pack '$code' must not be downloaded", item.isDownloaded)
            assertFalse("Subsequent pack '$code' must not be downloading", item.isDownloading)
            assertFalse("Subsequent pack '$code' must not be queued", item.isQueued)
        }
    }

    @Test
    fun testHeaderStatus_exactFormatForGermanAtIndex11Of30() {
        val statusText = BulkDownloadHeaderFormatter.formatActiveStatus(
            languageCode = "de",
            displayName = "German",
            currentIndex = 11,
            totalCount = 30
        )

        assertEquals("Загрузка: [DE] German (11 / 30)", statusText)
        assertFalse("Status text must not contain emoji", containsEmoji(statusText))
    }

    @Test
    fun testHeaderStatus_formatsCorrectlyAcrossAllSupportedLanguagesAndIndices() {
        val totalCount = ML_KIT_SUPPORTED_CODES.size
        for ((index, code) in ML_KIT_SUPPORTED_CODES.withIndex()) {
            val currentIndex = index + 1
            val lang = Language.fromCode(if (code == "tl") "fil" else code)
            val displayName = lang?.displayName ?: code.uppercase()

            val formatted = BulkDownloadHeaderFormatter.formatActiveStatus(
                languageCode = code,
                displayName = displayName,
                currentIndex = currentIndex,
                totalCount = totalCount
            )

            val expectedCodeUpper = code.uppercase()
            val expectedString = "Загрузка: [$expectedCodeUpper] $displayName ($currentIndex / $totalCount)"

            assertEquals("Header status text mismatch for $code", expectedString, formatted)
            assertFalse("Header status text for $code must contain zero emoji", containsEmoji(formatted))
        }
    }

    @Test
    fun testHeaderStatus_zeroEmojiComplianceStrictUnicodeCheck() {
        val sampleCodes = listOf("de", "zh", "ja", "ru", "ar", "hi", "vi", "fr", "es")
        for (code in sampleCodes) {
            val lang = Language.fromCode(code)!!
            val statusText = BulkDownloadHeaderFormatter.formatActiveStatus(
                languageCode = code,
                displayName = lang.displayName,
                currentIndex = 5,
                totalCount = 20
            )

            assertFalse("Formatted text '$statusText' must contain no emoji", containsEmoji(statusText))
            assertFalse("Formatted text must not contain flag symbol", statusText.contains(lang.flag))
        }
    }

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
}
