package com.translive.app.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.translive.app.R
import com.translive.app.service.accessibility.ScreenAccessibilityService
import com.translive.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * System Quick Settings Tile for Screen Translation (Floating Button).
 * Allows users to toggle screen translation from any app via the notification shade.
 */
class ScreenTranslateTileService : TileService() {

    private var serviceScope: CoroutineScope? = null
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listeningJob = serviceScope?.launch {
            ScreenTranslateOverlayService.isServiceRunning.collect { isRunning ->
                updateTileState(isRunning)
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        serviceScope?.cancel()
        serviceScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // On Android 14+, use PendingIntent or startActivityAndCollapse
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        val isRunning = ScreenTranslateOverlayService.isServiceRunning.value
        if (isRunning) {
            ScreenTranslateOverlayService.stop(this)
            updateTileState(false)
        } else {
            if (ScreenAccessibilityService.isConnected()) {
                ScreenTranslateOverlayService.start(this)
                updateTileState(true)
                return
            }
            val intent = Intent(this, MainActivity::class.java).apply {
                action = ScreenTranslateOverlayService.ACTION_REQUEST_SCREEN_CAPTURE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState(isRunning: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.screen_translate_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) getString(R.string.screen_translate_active) else "Parlex"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_screen_translate)
        tile.updateTile()
    }
}
