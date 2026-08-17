package com.translive.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.translive.app.ui.MainActivity

/** Small, user-controlled launcher for the existing one-shot screen capture. */
class ScreenTranslateOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var button: TextView? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        if (button == null) showButton()
        return START_STICKY
    }

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen translation", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.translive.app.R.mipmap.ic_launcher)
                .setContentTitle("Parlex")
                .setContentText("Screen translation button is active")
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun showButton() {
        val label = TextView(this).apply {
            text = "文"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(80, 70, 180))
            }
            setOnClickListener {
                startActivity(
                    Intent(this@ScreenTranslateOverlayService, MainActivity::class.java)
                        .setAction(ACTION_REQUEST_SCREEN_CAPTURE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            setOnLongClickListener { stopSelf(); true }
        }
        val params = WindowManager.LayoutParams(
            58,
            58,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 18
            y = 0
        }
        windowManager = getSystemService(WindowManager::class.java)
        windowManager?.addView(label, params)
        button = label
    }

    override fun onDestroy() {
        button?.let { runCatching { windowManager?.removeView(it) } }
        button = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.translive.app.action.REQUEST_SCREEN_CAPTURE"
        private const val CHANNEL_ID = "screen_translation_overlay"
        private const val NOTIFICATION_ID = 7102

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenTranslateOverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTranslateOverlayService::class.java))
        }
    }
}
