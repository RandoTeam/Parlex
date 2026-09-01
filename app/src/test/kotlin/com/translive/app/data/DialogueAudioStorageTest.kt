package com.translive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DialogueAudioStorageTest {

    @Test
    fun formatStorageSize_calculatesBytesCorrectly() {
        assertEquals("0 КБ", DialogueStorageStats(0, 0L).formattedSize)
        assertEquals("500 КБ", DialogueStorageStats(1, 500 * 1024L).formattedSize)
        assertEquals("2.5 МБ", DialogueStorageStats(5, (2.5 * 1024 * 1024).toLong()).formattedSize)
        assertEquals("1.2 ГБ", DialogueStorageStats(20, (1.2 * 1024 * 1024 * 1024).toLong()).formattedSize)
    }

    @Test
    fun computeDirectoryStats_computesFileCountAndBytes() {
        val tempDir = File.createTempFile("test_dialogues", "").apply {
            delete()
            mkdirs()
        }
        try {
            val file1 = File(tempDir, "dialogue_1.m4a").apply { writeBytes(ByteArray(1024)) }
            val file2 = File(tempDir, "dialogue_2.wav").apply { writeBytes(ByteArray(2048)) }
            val subDir = File(tempDir, "sub").apply { mkdirs() }
            val file3 = File(subDir, "dialogue_3.m4a").apply { writeBytes(ByteArray(4096)) }

            val stats = DialogueStorageCalculator.calculate(tempDir)
            assertEquals(3, stats.fileCount)
            assertEquals(7168L, stats.totalBytes)
            assertEquals("7 КБ", stats.formattedSize)

            val deletedCount = DialogueStorageCalculator.deleteAll(tempDir)
            assertEquals(3, deletedCount)

            val statsAfter = DialogueStorageCalculator.calculate(tempDir)
            assertEquals(0, statsAfter.fileCount)
            assertEquals(0L, statsAfter.totalBytes)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
