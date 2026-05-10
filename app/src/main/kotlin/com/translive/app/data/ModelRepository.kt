package com.translive.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.translive.app.data.model.ModelVariant
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
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

    /** Get the currently active model ID */
    fun getActiveModelId(): String? = prefs.getString("active_model_id", null)

    /** Set the active model */
    fun setActiveModelId(id: String) {
        prefs.edit().putString("active_model_id", id).apply()
    }

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
        return ModelVariant.ALL.sumOf { variant ->
            val file = File(modelsDir, variant.filename)
            if (file.exists()) file.length() else 0L
        }
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
     * Import a GGUF model from a SAF URI.
     * Validates GGUF magic bytes, copies the file with progress reporting.
     * @return the filename of the imported model, or null on failure.
     */
    fun importModelFromUri(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        val resolver = context.contentResolver

        // Get filename from URI
        val filename = resolveFilename(uri) ?: "imported_model.gguf"
        if (!filename.endsWith(".gguf", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Файл должен иметь расширение .gguf"))
        }

        val inputStream = resolver.openInputStream(uri)
            ?: return Result.failure(IllegalStateException("Не удалось открыть файл"))

        return try {
            // Read first 4 bytes to validate GGUF magic
            val magic = ByteArray(4)
            val read = inputStream.read(magic)
            if (read < 4 || magic[0] != 0x47.toByte() || magic[1] != 0x47.toByte() ||
                magic[2] != 0x55.toByte() || magic[3] != 0x46.toByte()) {
                inputStream.close()
                return Result.failure(IllegalArgumentException("Файл не является GGUF моделью"))
            }

            // Get total size for progress
            val fileDescriptor = resolver.openFileDescriptor(uri, "r")
            val totalSize = fileDescriptor?.statSize ?: -1L
            fileDescriptor?.close()

            // Copy to models directory
            val destFile = File(modelsDir, filename)
            destFile.outputStream().buffered().use { out ->
                // Write the magic bytes we already read
                out.write(magic)
                var copied = 4L
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    copied += bytesRead
                    if (totalSize > 0) {
                        onProgress(copied.toFloat() / totalSize)
                    }
                }
            }

            inputStream.close()
            Result.success(filename)
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
