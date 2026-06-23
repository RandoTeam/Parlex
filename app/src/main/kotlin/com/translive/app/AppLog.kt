package com.translive.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.translive.app.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thin logging wrapper that strips debug-level logs in release builds.
 * Optionally writes logs to a file when file logging is enabled.
 * Usage: AppLog.d(TAG, "message") / AppLog.e(TAG, "message", exception)
 */
object AppLog {

    private const val MAX_LOG_SIZE = 1_000_000L // 1MB
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    private const val LOG_FILE_OLD = "app.log.old"

    private var fileLogWriter: FileLogWriter? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        fileLogWriter = FileLogWriter(context)
    }

    fun setFileLoggingEnabled(enabled: Boolean) {
        fileLogWriter?.setEnabled(enabled)
    }

    fun getFileLogContent(): String = fileLogWriter?.readLog() ?: ""

    fun clearFileLog() {
        fileLogWriter?.clearLog()
    }

    fun getLogFile(): File? = fileLogWriter?.logFile

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg)
            writeFile("D", tag, msg)
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writeFile("I", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        writeFile("W", tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        writeFile("E", tag, msg, tr)
    }

    private fun writeFile(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val ts = dateFormat.format(Date())
        val line = if (tr != null) "$ts $level/$tag: $msg - ${tr.message}" else "$ts $level/$tag: $msg"
        fileLogWriter?.write(line)
    }

    class FileLogWriter(context: Context) {
        private val logDir = File(context.filesDir, LOG_DIR)
        val logFile = File(logDir, LOG_FILE)
        private val oldLogFile = File(logDir, LOG_FILE_OLD)
        private val handlerThread = HandlerThread("FileLogWriter").also { it.start() }
        private val handler = Handler(handlerThread.looper)
        @Volatile private var enabled = false

        fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
            if (enabled) logDir.mkdirs()
        }

        fun write(line: String) {
            if (!enabled) return
            handler.post {
                try {
                    if (logFile.length() > MAX_LOG_SIZE) {
                        oldLogFile.delete()
                        logFile.renameTo(oldLogFile)
                    }
                    FileWriter(logFile, true).use { writer ->
                        writer.append(line)
                        writer.append('\n')
                    }
                } catch (_: Exception) { }
            }
        }

        fun readLog(): String {
            if (!logFile.exists()) return ""
            return try {
                val sb = StringBuilder()
                if (oldLogFile.exists()) sb.append(oldLogFile.readText()).append('\n')
                sb.append(logFile.readText())
                sb.toString()
            } catch (_: Exception) { "" }
        }

        fun clearLog() {
            handler.post {
                logFile.delete()
                oldLogFile.delete()
            }
        }
    }
}
