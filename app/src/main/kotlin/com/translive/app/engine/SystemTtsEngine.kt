package com.translive.app.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * System TTS wrapper — uses Android's built-in TextToSpeech (Google TTS, etc.)
 * No model download needed. Inspired by RTranslator approach.
 */
@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SystemTtsEngine"
    }

    private var tts: TextToSpeech? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                _isReady.value = true
                Log.i(TAG, "System TTS initialized")
            } else {
                Log.e(TAG, "System TTS init failed: $status")
                _isReady.value = false
            }
        }
    }

    /**
     * Speak text in the given language. Blocks (suspends) until speech is done.
     */
    suspend fun speakAndWait(text: String, languageCode: String) {
        val engine = tts ?: return
        if (!_isReady.value) return

        // Set language
        val locale = Locale.forLanguageTag(languageCode)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language $languageCode not supported by system TTS, trying default")
        }

        val utteranceId = "dialogue_${System.currentTimeMillis()}"

        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(id: String?) {
                    _isSpeaking.value = false
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in API")
                override fun onError(id: String?) {
                    _isSpeaking.value = false
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(id: String?, errorCode: Int) {
                    Log.e(TAG, "TTS error: $errorCode")
                    _isSpeaking.value = false
                    if (cont.isActive) cont.resume(Unit)
                }
            })

            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            cont.invokeOnCancellation {
                engine.stop()
                _isSpeaking.value = false
            }
        }
    }

    /** Fire-and-forget speak (non-blocking). */
    fun speak(text: String, languageCode: String) {
        val engine = tts ?: return
        if (!_isReady.value) return

        val locale = Locale.forLanguageTag(languageCode)
        engine.setLanguage(locale)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speak_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        _isReady.value = false
    }
}
