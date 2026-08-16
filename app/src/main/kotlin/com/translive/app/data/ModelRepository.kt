package com.translive.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.translive.app.data.model.ModelCatalog
import com.translive.app.data.model.ModelFamily
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.ModelVariant
import com.translive.app.data.model.TranslationProfile
import com.translive.app.data.model.TranslationProfiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class ImportedModel(
        val variant: ModelVariant,
        val recognizedCatalogVariant: Boolean,
        val integrityVerified: Boolean,
        val alreadyPresent: Boolean
    )
    private val prefs: SharedPreferences =
        context.getSharedPreferences("parlex_models", Context.MODE_PRIVATE)

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    /** Check if a model variant is downloaded */
    fun isDownloaded(variant: ModelVariant): Boolean {
        val file = File(modelsDir, variant.filename)
        return file.exists() && file.length() > 0
    }

    /** Get the file path for a downloaded model */
    fun getModelPath(variant: ModelVariant): String? {
        val file = File(modelsDir, variant.filename)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /** Get the download destination file */
    fun getDownloadFile(variant: ModelVariant): File = File(modelsDir, variant.filename)

    /** Get the currently active model ID, with migration from legacy non-namespaced IDs */
    fun getActiveModelId(): String? {
        val raw = prefs.getString("active_model_id", null) ?: return null

        // TranslateGemma LiteRT was removed from the downloadable catalog because
        // its Android GPU artifact is not verified. Preserve an already selected
        // local file as an external model instead of silently losing it.
        val retiredLiteRtFilename = when (raw) {
            "translate_gemma_litert_beta:int4" -> "translategemma-4b-it-int4-generic.litertlm"
            "translate_gemma_litert_beta:dynamic_int8" -> "translategemma-4b-it-dynamic_int8-generic.litertlm"
            else -> null
        }
        if (retiredLiteRtFilename != null) {
            val retiredFile = File(modelsDir, retiredLiteRtFilename)
            if (retiredFile.exists() && retiredFile.length() > 0) {
                val migrated = "custom:$retiredLiteRtFilename"
                prefs.edit().putString("active_model_id", migrated).apply()
                return migrated
            }
            prefs.edit().remove("active_model_id").apply()
            return null
        }
        // Migrate legacy IDs: "q4_k_m" → "hy_mt:q4_k_m"
        if (!raw.contains(":") && !raw.startsWith("custom")) {
            val migrated = "hy_mt:$raw"
            prefs.edit().putString("active_model_id", migrated).apply()
            return migrated
        }
        return raw
    }

    /** Set the active model */
    fun setActiveModelId(id: String) {
        prefs.edit().putString("active_model_id", id).apply()
    }

    /** Get the [ModelFamily] of the currently active model */
    fun getActiveFamily(): ModelFamily? {
        val variant = getActiveVariant() ?: return null
        return ModelFamily.familyOf(variant)
    }

    fun getActiveRuntime(): ModelRuntime {
        val variant = getActiveVariant()
        if (variant != null) return variant.runtime

        val id = getActiveModelId() ?: return ModelRuntime.GGUF
        if (id.startsWith("custom:") && id.endsWith(".litertlm", ignoreCase = true)) {
            return ModelRuntime.LITERT_LM
        }
        return ModelRuntime.GGUF
    }

    /** Inference contract for the selected model. */
    fun getActiveTranslationProfile(): TranslationProfile =
        TranslationProfiles.forModel(getActiveFamily(), getActiveRuntime())

    /** Get the active model variant */
    fun getActiveVariant(): ModelVariant? {
        val id = getActiveModelId() ?: return null
        return ModelVariant.findById(id)
    }

    /** Get the file path of the currently active model (supports known + imported) */
    fun getActiveModelPath(): String? {
        val id = getActiveModelId() ?: return null
        if (id.startsWith("custom:")) {
            val filename = id.removePrefix("custom:")
            val file = File(modelsDir, filename)
            return if (file.exists() && file.length() > 0) file.absolutePath else null
        }
        val variant = getActiveVariant() ?: return null
        return getModelPath(variant)
    }

    /** Delete a downloaded model */
    fun deleteModel(variant: ModelVariant): Boolean {
        val file = File(modelsDir, variant.filename)
        if (getActiveModelId() == variant.id) {
            prefs.edit().remove("active_model_id").apply()
        }
        return file.delete()
    }

    /** Get total size of all downloaded models */
    fun getTotalDownloadedSize(): Long {
        return modelsDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sumOf { it.length() }
            ?: 0L
    }

    /** Get available storage space */
    fun getAvailableSpace(): Long = modelsDir.usableSpace

    /**
     * Export a model file to a SAF URI (user-chosen location).
     */
    fun exportModel(
        variant: ModelVariant,
        destUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> {
        val sourceFile = File(modelsDir, variant.filename)
        if (!sourceFile.exists()) {
            return Result.failure(IllegalStateException("Файл модели не найден"))
        }

        val resolver = context.contentResolver
        val outputStream = resolver.openOutputStream(destUri)
            ?: return Result.failure(IllegalStateException("Не удалось открыть файл для записи"))

        return try {
            val totalSize = sourceFile.length()
            sourceFile.inputStream().buffered().use { input ->
                outputStream.buffered().use { output ->
                    var copied = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copied += bytesRead
                        if (totalSize > 0) {
                            onProgress(copied.toFloat() / totalSize)
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Import a GGUF or LiteRT-LM model from a SAF URI.
     * Validates known magic bytes, copies the file with progress reporting.
     * Known catalog models are recognized by SHA-256 when a catalog checksum is
     * available (LiteRT). Unknown files stay separate as external models.
     */
    fun importModelFromUri(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result<ImportedModel> {
        val resolver = context.contentResolver

        val inputFilename = sanitizeFilename(resolveFilename(uri) ?: "imported_model.gguf")
        val isGguf = inputFilename.endsWith(".gguf", ignoreCase = true)
        val isLiteRtLm = inputFilename.endsWith(".litertlm", ignoreCase = true)
        if (!isGguf && !isLiteRtLm) {
            return Result.failure(IllegalArgumentException("Файл должен иметь расширение .gguf или .litertlm"))
        }

        val inputStream = resolver.openInputStream(uri)
            ?: return Result.failure(IllegalStateException("Не удалось открыть файл"))

        return try {
            val magicSize = if (isLiteRtLm) 8 else 4
            val magic = ByteArray(magicSize)
            val read = inputStream.read(magic)
            val validGguf = isGguf &&
                read >= 4 &&
                magic[0] == 0x47.toByte() &&
                magic[1] == 0x47.toByte() &&
                magic[2] == 0x55.toByte() &&
                magic[3] == 0x46.toByte()
            val validLiteRtLm = isLiteRtLm &&
                read >= 8 &&
                magic.copyOfRange(0, 8).contentEquals("LITERTLM".toByteArray())
            if (!validGguf && !validLiteRtLm) {
                inputStream.close()
                return Result.failure(IllegalArgumentException("Файл не является поддерживаемой моделью"))
            }

            // Get total size for progress
            val fileDescriptor = resolver.openFileDescriptor(uri, "r")
            val totalSize = fileDescriptor?.statSize ?: -1L
            fileDescriptor?.close()

            // Copy to an isolated temporary file first. The destination is only
            // chosen after its digest is known, so a cancelled import cannot
            // overwrite a usable model.
            val tempFile = File(modelsDir, ".import-${System.currentTimeMillis()}-$inputFilename.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            tempFile.outputStream().buffered().use { out ->
                // Write the magic bytes we already read
                out.write(magic)
                digest.update(magic)
                var copied = magicSize.toLong()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    copied += bytesRead
                    if (totalSize > 0) {
                        onProgress(copied.toFloat() / totalSize)
                    }
                }
            }

            inputStream.close()

            val actualSha = digest.digest().toHex()
            val knownByChecksum = ModelVariant.ALL.find { variant ->
                variant.sha256?.equals(actualSha, ignoreCase = true) == true
            }
            val knownByNameAndSize = ModelVariant.ALL.find { variant ->
                variant.sha256 == null &&
                    variant.filename.equals(inputFilename, ignoreCase = true) &&
                    variant.sizeBytes == tempFile.length()
            }
            val known = knownByChecksum ?: knownByNameAndSize
            val canonicalFilename = known?.filename ?: uniqueExternalFilename(inputFilename)
            val destination = File(modelsDir, canonicalFilename)

            if (destination.exists()) {
                if (knownByChecksum != null && sha256(destination).equals(actualSha, ignoreCase = true)) {
                    tempFile.delete()
                    return Result.success(ImportedModel(knownByChecksum, true, true, alreadyPresent = true))
                }
                if (known == null) {
                    tempFile.renameTo(File(modelsDir, uniqueExternalFilename(inputFilename)))
                } else {
                    tempFile.delete()
                    return Result.failure(IllegalStateException("A different model already uses the catalog filename"))
                }
            } else if (!tempFile.renameTo(destination)) {
                return Result.failure(IllegalStateException("Could not finalize imported model"))
            }

            val storedFile = if (known == null && destination.exists()) destination else File(modelsDir, canonicalFilename)
            val externalVariant = ModelVariant(
                id = "custom:${storedFile.name}",
                quantName = "External",
                displayName = storedFile.name,
                description = "External ${if (isLiteRtLm) "LiteRT-LM" else "GGUF"} model — compatibility checked on activation",
                sizeBytes = storedFile.length(),
                ramEstimateMb = 0,
                downloadUrl = "",
                filename = storedFile.name,
                runtime = if (isLiteRtLm) ModelRuntime.LITERT_LM else ModelRuntime.GGUF
            )
            Result.success(
                ImportedModel(
                    variant = known ?: externalVariant,
                    recognizedCatalogVariant = known != null,
                    integrityVerified = knownByChecksum != null,
                    alreadyPresent = false
                )
            )
        } catch (e: Exception) {
            inputStream.close()
            Result.failure(e)
        }
    }

    private fun resolveFilename(uri: Uri): String? {
        // Try content resolver query first
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        // Fallback to URI last path segment
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    fun getExternalModels(): List<ModelVariant> = modelsDir.listFiles()
        ?.filter { file ->
            file.isFile && !file.name.endsWith(".tmp") &&
                ModelVariant.ALL.none { it.filename.equals(file.name, ignoreCase = true) } &&
                (file.name.endsWith(".gguf", ignoreCase = true) || file.name.endsWith(".litertlm", ignoreCase = true))
        }
        ?.sortedBy { it.name.lowercase() }
        ?.map { file ->
            ModelVariant(
                id = "custom:${file.name}",
                quantName = "External",
                displayName = file.name,
                description = "External ${if (file.name.endsWith(".litertlm", true)) "LiteRT-LM" else "GGUF"} model",
                sizeBytes = file.length(),
                ramEstimateMb = 0,
                downloadUrl = "",
                filename = file.name,
                runtime = if (file.name.endsWith(".litertlm", true)) ModelRuntime.LITERT_LM else ModelRuntime.GGUF
            )
        }
        ?: emptyList()

    private fun uniqueExternalFilename(filename: String): String {
        val base = filename.substringBeforeLast('.', filename)
        val extension = filename.substringAfterLast('.', "")
        var index = 1
        var candidate = "external-$base.${extension}"
        while (File(modelsDir, candidate).exists()) {
            candidate = "external-$base-$index.${extension}"
            index++
        }
        return candidate
    }

    private fun sanitizeFilename(filename: String): String =
        filename.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Set active model by filename (for imported models) */
    fun setActiveByFilename(filename: String) {
        // Check if it matches a known variant
        val known = ModelVariant.ALL.find { it.filename == filename }
        if (known != null) {
            setActiveModelId(known.id)
        } else {
            // Store filename as custom active ID
            prefs.edit().putString("active_model_id", "custom:$filename").apply()
        }
    }

}
