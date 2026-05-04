package com.translive.app.data

import android.content.Context
import android.content.SharedPreferences
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

    /** Get the file path of the currently active model */
    fun getActiveModelPath(): String? {
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
}
