package com.translive.app.data

import android.content.Context
import android.util.Log
import com.translive.app.data.db.DialogueDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class DialogueStorageStats(
    val fileCount: Int = 0,
    val totalBytes: Long = 0L
) {
    val formattedSize: String
        get() {
            if (totalBytes <= 0) return "0 КБ"
            val kb = totalBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.1f ГБ", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f МБ", mb)
                else -> String.format(Locale.US, "%d КБ", kb.toInt().coerceAtLeast(1))
            }
        }
}

object DialogueStorageCalculator {

    private const val TAG = "DialogueStorageCalc"

    fun calculate(directory: File?): DialogueStorageStats {
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            return DialogueStorageStats(0, 0L)
        }

        var count = 0
        var bytes = 0L

        directory.walkTopDown().forEach { file ->
            if (file.isFile) {
                count++
                bytes += file.length()
            }
        }

        return DialogueStorageStats(fileCount = count, totalBytes = bytes)
    }

    fun deleteAll(directory: File?): Int {
        if (directory == null || !directory.exists()) return 0
        var deleted = 0
        directory.walkBottomUp().forEach { file ->
            if (file.isFile) {
                if (file.delete()) {
                    deleted++
                }
            }
        }
        return deleted
    }

    fun getDialoguesDirectory(context: Context): File {
        val externalDir = context.getExternalFilesDir("dialogues")
        val targetDir = externalDir ?: File(context.filesDir, "dialogues")
        if (!targetDir.exists()) targetDir.mkdirs()
        return targetDir
    }
}
