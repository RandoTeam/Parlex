package com.translive.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class TtsState { IDLE, LOADING, SPEAKING, ERROR }

@Singleton
class TtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var speakJob: Job? = null

    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    val modelDir: File get() = File(context.filesDir, "tts/kokoro-multi-lang-v1_1")

    fun isModelDownloaded(): Boolean {
        val dir = modelDir
        return dir.exists() &&
                File(dir, "model.onnx").exists() &&
                File(dir, "voices.bin").exists() &&
                File(dir, "tokens.txt").exists()
    }

    fun loadModel(): Boolean {
        if (tts != null) return true
        if (!isModelDownloaded()) return false

        return try {
            _state.value = TtsState.LOADING
            val dir = modelDir.absolutePath

            val kokoroConfig = OfflineTtsKokoroModelConfig(
                model = "$dir/model.onnx",
                voices = "$dir/voices.bin",
                tokens = "$dir/tokens.txt",
                dataDir = "$dir/espeak-ng-data",
                lexicon = "$dir/lexicon-us-en.txt,$dir/lexicon-zh.txt"
            )
            val modelConfig = OfflineTtsModelConfig(
                kokoro = kokoroConfig,
                numThreads = 2,
                debug = false
            )
            val config = OfflineTtsConfig(model = modelConfig)
            tts = OfflineTts(config = config)
            _isModelReady.value = true
            _state.value = TtsState.IDLE
            android.util.Log.i("TtsEngine", "Kokoro TTS loaded successfully")
            true
        } catch (e: Exception) {
            android.util.Log.e("TtsEngine", "Failed to load TTS model: ${e.message}", e)
            _state.value = TtsState.ERROR
            _isModelReady.value = false
            false
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("TtsEngine", "Native library error: ${e.message}", e)
            _state.value = TtsState.ERROR
            _isModelReady.value = false
            false
        }
    }

    fun speak(text: String, speakerId: Int = 0, speed: Float = 1.0f) {
        val engine = tts ?: return
        stop()

        speakJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                _state.value = TtsState.SPEAKING
                val genConfig = GenerationConfig(
                    sid = speakerId,
                    speed = speed
                )

                val audio = engine.generateWithConfigAndCallback(
                    text = text,
                    config = genConfig,
                    callback = { samples ->
                        if (isActive) 1 else 0
                    }
                )

                if (isActive && audio.samples.isNotEmpty()) {
                    playAudio(audio.samples, audio.sampleRate)
                }
            } catch (_: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                _state.value = TtsState.ERROR
            } finally {
                if (_state.value == TtsState.SPEAKING) {
                    _state.value = TtsState.IDLE
                }
            }
        }
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        audioTrack?.apply {
            try {
                stop()
                release()
            } catch (_: Exception) {}
        }
        audioTrack = null
        _state.value = TtsState.IDLE
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.apply {
            play()
            write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            stop()
            release()
        }
        audioTrack = null
        _state.value = TtsState.IDLE
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
        _isModelReady.value = false
    }
}
