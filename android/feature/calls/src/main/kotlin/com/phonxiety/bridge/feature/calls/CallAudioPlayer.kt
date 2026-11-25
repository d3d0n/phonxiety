package com.phonxiety.bridge.feature.calls

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The playback position a seek bar needs; null duration until the file is open. */
data class PlaybackState(
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val ended: Boolean = false
)

/** Recording playback is the one place the app uses a media library. */
interface CallAudioPlayer {
    val state: StateFlow<PlaybackState>

    fun prepare(file: File)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun release()
}

@Singleton
class ExoCallAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) : CallAudioPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    override fun prepare(file: File) {
        release()
        val exo = ExoPlayer.Builder(context).build()
        player = exo
        exo.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.update {
                        it.copy(
                            prepared = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED,
                            ended = playbackState == Player.STATE_ENDED,
                            durationMs = exo.duration.takeIf { it > 0 } ?: it.durationMs
                        )
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(playing = isPlaying) }
                }
            }
        )
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        exo.prepare()
        startTicker()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
        _state.update { it.copy(positionMs = positionMs, ended = false) }
    }

    override fun release() {
        ticker?.cancel()
        ticker = null
        player?.release()
        player = null
        _state.value = PlaybackState()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                player?.let { exo ->
                    _state.update {
                        it.copy(
                            positionMs = exo.currentPosition,
                            durationMs = exo.duration.takeIf { duration -> duration > 0 } ?: it.durationMs
                        )
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CallAudioModule {
    @Binds
    @Singleton
    abstract fun audioPlayer(impl: ExoCallAudioPlayer): CallAudioPlayer
}
