package com.translive.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
// Uses android system icons for notification
import com.translive.app.engine.DownloadState
import com.translive.app.engine.ModelDownloadManager
import com.translive.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground service that keeps downloads alive when the user navigates away
 * or minimizes the app. Shows a persistent notification with progress.
 *
 * Lifecycle:
 * - Started by ModelDownloadManager when a download begins
 * - Stops itself when all downloads finish
 */
@AndroidEntryPoint
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_CANCEL = "com.translive.app.CANCEL_DOWNLOAD"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }

    @Inject lateinit var downloadManager: ModelDownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Подготовка загрузки…", 0))

        // Observe download states and update notification
        observeJob = scope.launch {
            downloadManager.activeDownloads.collect { downloads ->
                if (downloads.isEmpty()) {
                    // All done — stop service
                    stopSelf()
                    return@collect
                }

                // Find the most active download for notification
                val active = downloads.values.filterIsInstance<DownloadState.Downloading>()
                if (active.isNotEmpty()) {
                    val total = active.sumOf { it.totalBytes }
                    val done = active.sumOf { it.bytesDownloaded }
                    val progress = if (total > 0) (done * 100 / total).toInt() else 0
                    val names = downloads.keys.joinToString(", ")
                    updateNotification("Загрузка: $names", progress)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            downloadManager.cancelAll()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Загрузка моделей",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о загрузке моделей перевода"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
        builder.setContentTitle("Parlex")
        builder.setContentText(text)
        builder.setProgress(100, progress, progress == 0)
        builder.setOngoing(true)
        builder.setContentIntent(openPi)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", cancelPi)
        builder.setSilent(true)
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }
}
