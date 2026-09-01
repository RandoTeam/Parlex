package com.translive.app.engine.dialogue

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import com.translive.app.data.model.DialogueMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AudioPlaybackState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val activeTurnId: Long? = null,
    val audioFilePath: String? = null
)

@Singleton
class DialogueAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var turnsList: List<DialogueMessage> = emptyList()

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    fun loadSessionAudio(filePath: String?, messages: List<DialogueMessage>) {
        turnsList = messages
        releasePlayer()

        if (filePath.isNullOrBlank()) {
            _playbackState.value = AudioPlaybackState(isReady = false)
            return
        }

        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Audio file does not exist or empty: $filePath")
            _playbackState.value = AudioPlaybackState(isReady = false)
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    _playbackState.value = AudioPlaybackState(
                        isReady = true,
                        isPlaying = false,
                        currentPositionMs = 0L,
                        durationMs = mp.duration.toLong(),
                        playbackSpeed = _playbackState.value.playbackSpeed,
                        audioFilePath = filePath
                    )
                }
                setOnCompletionListener {
                    stopProgressTracker()
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        currentPositionMs = 0L,
                        activeTurnId = null
                    )
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dialogue audio: $filePath", e)
            _playbackState.value = AudioPlaybackState(isReady = false)
        }
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (!_playbackState.value.isReady) return

        try {
            applyPlaybackSpeed(_playbackState.value.playbackSpeed)
            player.start()
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
            startProgressTracker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio playback", e)
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                player.pause()
            }
            stopProgressTracker()
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause audio playback", e)
        }
    }

    fun togglePlayPause() {
        if (_playbackState.value.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        val clamped = positionMs.coerceIn(0L, _playbackState.value.durationMs)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(clamped.toInt())
            }
            updateActiveTurn(clamped)
            _playbackState.value = _playbackState.value.copy(currentPositionMs = clamped)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seek audio position", e)
        }
    }

    fun cyclePlaybackSpeed() {
        val nextSpeed = when (_playbackState.value.playbackSpeed) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        setPlaybackSpeed(nextSpeed)
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
        applyPlaybackSpeed(speed)
    }

    fun seekToTurn(message: DialogueMessage) {
        seekTo(message.audioStartTimeMs)
        if (!_playbackState.value.isPlaying) {
            play()
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val player = mediaPlayer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = speed
                player.playbackParams = params
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set playback speed $speed", e)
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val currentMs = player.currentPosition.toLong()
                        updateActiveTurn(currentMs)
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = currentMs,
                            isPlaying = true
                        )
                    }
                }
                delay(50L)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateActiveTurn(currentPositionMs: Long) {
        val activeTurn = turnsList.firstOrNull { msg ->
            currentPositionMs >= msg.audioStartTimeMs &&
                currentPositionMs <= (msg.audioStartTimeMs + msg.audioDurationMs.coerceAtLeast(1000L))
        }
        _playbackState.value = _playbackState.value.copy(activeTurnId = activeTurn?.id)
    }

    fun release() {
        releasePlayer()
        scope.cancel()
    }

    private fun releasePlayer() {
        stopProgressTracker()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "DialogueAudioPlayer"
    }
}
