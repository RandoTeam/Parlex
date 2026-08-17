package com.translive.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.translive.app.i18n.AppLocale
import com.translive.app.ui.theme.TransLiveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var incomingTranslationText by mutableStateOf<String?>(null)

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
        incomingTranslationText = extractIncomingTranslationText(intent)

        setContent {
            TransLiveTheme {
                TransLiveNavHost(
                    incomingText = incomingTranslationText,
                    onIncomingTextConsumed = { incomingTranslationText = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingTranslationText = extractIncomingTranslationText(intent)
    }

    private fun extractIncomingTranslationText(intent: Intent?): String? {
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()?.trim()
        return text?.takeIf { it.isNotBlank() }
    }
}
