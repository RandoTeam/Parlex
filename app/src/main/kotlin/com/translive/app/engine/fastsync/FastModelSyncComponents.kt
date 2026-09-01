package com.translive.app.engine.fastsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Metadata manifest embedded inside .parlex-fast archive packages.
 */
data class ParlexFastManifest(
    val format: String = FORMAT_V1,
    val appVersion: String,
    val timestamp: Long,
    val languages: List<String>,
    val totalSizeBytes: Long
) {
    companion object {
        const val FORMAT_V1 = "parlex-fast-v1"
        const val MANIFEST_ENTRY_NAME = "manifest.json"

        fun fromJson(jsonStr: String): ParlexFastManifest? {
            return try {
                val formatMatch = Regex(""""format"\s*:\s*"([^"]+)"""").find(jsonStr) ?: return null
                val format = formatMatch.groupValues[1]
                if (format != FORMAT_V1) return null

                val appVersion = Regex(""""appVersion"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: "1.0.0"
                val timestamp = Regex(""""timestamp"\s*:\s*(\d+)""").find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val totalSizeBytes = Regex(""""totalSizeBytes"\s*:\s*(\d+)""").find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                val langsMatch = Regex(""""languages"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(jsonStr)
                val languages = mutableListOf<String>()
                if (langsMatch != null) {
                    val rawLangs = langsMatch.groupValues[1]
                    Regex(""""([^"]+)"""").findAll(rawLangs).forEach {
                        languages.add(it.groupValues[1])
                    }
                }

                ParlexFastManifest(
                    format = format,
                    appVersion = appVersion,
                    timestamp = timestamp,
                    languages = languages,
                    totalSizeBytes = totalSizeBytes
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        val langsFormatted = languages.joinToString(", ") { "\"$it\"" }
        return """
        {
          "format": "$format",
          "appVersion": "$appVersion",
          "timestamp": $timestamp,
          "totalSizeBytes": $totalSizeBytes,
          "languages": [$langsFormatted]
        }
        """.trimIndent()
    }
}

data class ArchiveValidationResult(
    val isValid: Boolean,
    val languages: List<String> = emptyList(),
    val errorMessage: String? = null
)

object FastModelSyncPacker {
    fun pack(
        modelsDir: File,
        manifest: ParlexFastManifest,
        outputZip: File
    ) {
        FileOutputStream(outputZip).use { fos ->
            packToStream(modelsDir, manifest, fos)
        }
    }

    fun packToStream(
        modelsDir: File,
        manifest: ParlexFastManifest,
        outputStream: OutputStream
    ) {
        ZipOutputStream(outputStream).use { zipOut ->
            // 1. Write manifest.json
            val manifestBytes = manifest.toJson().toByteArray(Charsets.UTF_8)
            zipOut.putNextEntry(ZipEntry(ParlexFastManifest.MANIFEST_ENTRY_NAME))
            zipOut.write(manifestBytes)
            zipOut.closeEntry()

            // 2. Write model folders for each included language
            for (lang in manifest.languages) {
                val langFolder = File(modelsDir, lang)
                if (langFolder.exists() && langFolder.isDirectory) {
                    addFolderToZip(langFolder, "$lang/", zipOut)
                }
            }
        }
    }

    private fun addFolderToZip(folder: File, parentPath: String, zipOut: ZipOutputStream) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                addFolderToZip(file, "$parentPath${file.name}/", zipOut)
            } else {
                zipOut.putNextEntry(ZipEntry("$parentPath${file.name}"))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }
}

object FastModelArchiveValidator {
    fun validate(zipFile: File): ArchiveValidationResult {
        if (!zipFile.exists() || zipFile.length() == 0L) {
            return ArchiveValidationResult(isValid = false, errorMessage = "File is empty or does not exist")
        }

        return try {
            ZipFile(zipFile).use { zf ->
                val manifestEntry = zf.getEntry(ParlexFastManifest.MANIFEST_ENTRY_NAME)
                    ?: return ArchiveValidationResult(isValid = false, errorMessage = "manifest.json missing in archive")

                val jsonContent = zf.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val manifest = ParlexFastManifest.fromJson(jsonContent)
                    ?: return ArchiveValidationResult(isValid = false, errorMessage = "Invalid manifest or unsupported version")

                ArchiveValidationResult(
                    isValid = true,
                    languages = manifest.languages
                )
            }
        } catch (e: Exception) {
            ArchiveValidationResult(isValid = false, errorMessage = "Corrupted zip archive: ${e.message}")
        }
    }

    fun unpackToDirectory(zipFile: File, destinationDir: File) {
        destinationDir.mkdirs()
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == ParlexFastManifest.MANIFEST_ENTRY_NAME) continue

                val destPath = File(destinationDir, entry.name)
                if (entry.isDirectory) {
                    destPath.mkdirs()
                } else {
                    destPath.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(destPath).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
