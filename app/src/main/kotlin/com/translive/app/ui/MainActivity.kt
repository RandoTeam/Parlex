package com.translive.app.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.translive.app.i18n.AppLocale
import com.translive.app.service.LiveScreenTranslateService
import com.translive.app.service.ScreenCaptureService
import com.translive.app.service.ScreenTranslateOverlayService
import com.translive.app.ui.theme.TransLiveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var incomingTranslationText by mutableStateOf<String?>(null)
    private var incomingImageUri by mutableStateOf<Uri?>(null)
    private var isOverlayCaptureRequest: Boolean = false
    private var isLiveTranslateRequest: Boolean = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultData = result.data ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val isLive = isLiveTranslateRequest
            val fromOverlay = isOverlayCaptureRequest
            isLiveTranslateRequest = false
            isOverlayCaptureRequest = false

            if (isLive) {
                LiveScreenTranslateService.start(this, result.resultCode, resultData)
                moveTaskToBack(true)
            } else {
                startForegroundService(
                    ScreenCaptureService.newCaptureIntent(this, result.resultCode, resultData, fromOverlay)
                )
                if (fromOverlay) {
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("parlex_settings", Context.MODE_PRIVATE)
        val languageCode = prefs.getString("app_language", AppLocale.SYSTEM) ?: AppLocale.SYSTEM
        super.attachBaseContext(AppLocale.localizedContext(newBase, languageCode))
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("parlex_settings", Context.MODE_PRIVATE)
        val languageCode = prefs.getString("app_language", AppLocale.SYSTEM) ?: AppLocale.SYSTEM
        AppLocale.applyRuntimeLanguage(this, languageCode)
        enableEdgeToEdge()
        applyIncomingIntent(intent)

        setContent {
            TransLiveTheme {
                TransLiveNavHost(
                    incomingText = incomingTranslationText,
                    incomingImageUri = incomingImageUri,
                    onIncomingTextConsumed = { incomingTranslationText = null },
                    onIncomingImageConsumed = { incomingImageUri = null },
                onRequestScreenCapture = ::requestScreenCapture
                , onStartScreenOverlay = ::startScreenOverlay
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingIntent(intent)
    }

    private fun applyIncomingIntent(intent: Intent?) {
        if (intent?.action == ScreenTranslateOverlayService.ACTION_REQUEST_LIVE_TRANSLATE) {
            isLiveTranslateRequest = true
            requestScreenCapture()
            return
        }
        if (intent?.action == ScreenTranslateOverlayService.ACTION_REQUEST_SCREEN_CAPTURE) {
            isOverlayCaptureRequest = true
            requestScreenCapture()
            return
        }
        if (intent?.action == ScreenCaptureService.ACTION_CAPTURE_COMPLETE) {
            incomingTranslationText = null
            incomingImageUri = intent.data
        } else {
            incomingTranslationText = extractIncomingTranslationText(intent)
            incomingImageUri = extractIncomingImageUri(intent)
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startScreenOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        ScreenTranslateOverlayService.start(this)
    }

    private fun extractIncomingTranslationText(intent: Intent?): String? {
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()?.trim()
        return text?.takeIf { it.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    private fun extractIncomingImageUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else {
            null
        }
}
