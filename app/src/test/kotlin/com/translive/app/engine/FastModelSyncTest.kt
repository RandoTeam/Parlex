package com.translive.app.engine

import com.translive.app.engine.fastsync.FastModelArchiveValidator
import com.translive.app.engine.fastsync.FastModelSyncPacker
import com.translive.app.engine.fastsync.ParlexFastManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure JVM unit test suite verifying Sub-Phase M1:
 * Export and Import of Fast Translation (.parlex-fast) archive packages.
 */
class FastModelSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // SECTION 1: Manifest Serialization & Parsing
    // =========================================================================

    @Test
    fun manifest_serializationAndParsing_preservesMetadata() {
        val original = ParlexFastManifest(
            format = "parlex-fast-v1",
            appVersion = "1.5.0",
            timestamp = 1788194000000L,
            languages = listOf("ru", "en", "zh", "de", "vi"),
            totalSizeBytes = 150_000_000L
        )

        val json = original.toJson()
        val parsed = ParlexFastManifest.fromJson(json)

        assertNotNull(parsed)
        assertEquals("parlex-fast-v1", parsed!!.format)
        assertEquals("1.5.0", parsed.appVersion)
        assertEquals(5, parsed.languages.size)
        assertTrue(parsed.languages.contains("vi"))
        assertEquals(150_000_000L, parsed.totalSizeBytes)
    }

    // =========================================================================
    // SECTION 2: Packaging and Unpacking Round-Trip
    // =========================================================================

    @Test
    fun packer_createsValidZipArchiveWithManifestAndModels() {
        val sourceDir = tempFolder.newFolder("mlkit_models")
        val ruDir = File(sourceDir, "ru").apply { mkdirs() }
        File(ruDir, "model.tflite").writeBytes(byteArrayOf(1, 2, 3, 4))
        val enDir = File(sourceDir, "en").apply { mkdirs() }
        File(enDir, "model.tflite").writeBytes(byteArrayOf(5, 6, 7, 8))

        val zipFile = File(tempFolder.root, "backup.parlex-fast")
        val manifest = ParlexFastManifest(
            format = "parlex-fast-v1",
            appVersion = "1.5.0",
            timestamp = System.currentTimeMillis(),
            languages = listOf("ru", "en"),
            totalSizeBytes = 8L
        )

        FastModelSyncPacker.pack(
            modelsDir = sourceDir,
            manifest = manifest,
            outputZip = zipFile
        )

        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)

        // Validate archive using validator
        val validationResult = FastModelArchiveValidator.validate(zipFile)
        assertTrue(validationResult.isValid)
        assertEquals(listOf("ru", "en"), validationResult.languages)
    }

    // =========================================================================
    // SECTION 3: Corrupted / Invalid Archive Rejection
    // =========================================================================

    @Test
    fun validator_rejectsArchiveWithoutManifest() {
        val invalidZip = File(tempFolder.root, "invalid.zip")
        ZipOutputStream(FileOutputStream(invalidZip)).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("ru/model.tflite"))
            zipOut.write(byteArrayOf(1, 2, 3))
            zipOut.closeEntry()
        }

        val result = FastModelArchiveValidator.validate(invalidZip)
        assertFalse("Archive without manifest.json must be rejected", result.isValid)
    }

    @Test
    fun validator_rejectsUnsupportedFormatVersion() {
        val invalidZip = File(tempFolder.root, "unsupported.zip")
        ZipOutputStream(FileOutputStream(invalidZip)).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write("""{"format": "legacy-v0", "languages": ["en"]}""".toByteArray())
            zipOut.closeEntry()
        }

        val result = FastModelArchiveValidator.validate(invalidZip)
        assertFalse("Unsupported format version must be rejected", result.isValid)
    }
}
