package com.github.jschbe.simplempdplayer.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.mpd.PlaybackState
import com.github.jschbe.simplempdplayer.mpd.PlayerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

/** One manually authorized HTTP receiver with generation-bound MPD system transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalStreamService : MediaSessionService() {
    private val owner get() = application as PlayerApplication
    private val stream get() = owner.localStream
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var sessionPlayer: MpdSessionPlayer? = null
    private var token = -1L
    private var remotePlaying = false
    private var watchdog: Job? = null
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        owner.streamBackend.attach(this)
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                stream.fail(token, StreamError.START_FAILED)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val request = stream.request.value
        if (request == null || intent?.getLongExtra(GENERATION, -1) != request.generation) {
            // No resumption, sticky start or controller may authorize a new stream.
            if (player == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (token != request.generation) {
            releasePlayback()
            token = request.generation
            try {
                startPlayback(request)
            } catch (_: SecurityException) {
                stream.fail(token, StreamError.PERMISSION)
            } catch (_: IllegalStateException) {
                stream.fail(token, StreamError.START_FAILED)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(request: StreamRequest) {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(false)
        // A dropped stream is terminal; only the user can initiate another attempt.
        // MPD metadata is authoritative; do not extract embedded MP3 artwork/ID3.
        val sources = DefaultMediaSourceFactory(http,
            DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA))
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
            })
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (token == request.generation) publishState()
            }
            override fun onPlayerError(error: PlaybackException) {
                val failure = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> StreamError.TIMEOUT
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> StreamError.FORMAT
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> StreamError.PERMISSION
                    else -> StreamError.UNAVAILABLE
                }
                // Release outside the player's event dispatch; the token rejects old failures.
                val failedToken = request.generation
                Handler(Looper.getMainLooper()).post { stream.fail(failedToken, failure) }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                    val stoppedToken = request.generation
                    Handler(Looper.getMainLooper()).post {
                        if (stream.request.value?.generation == stoppedToken) stream.disable()
                    }
                }
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val transport = MpdMediaTransport(owner.repository, stream, request)
        val facade = MpdSessionPlayer(transport)
        sessionPlayer = facade
        updateMetadata(request)
        session = MediaSession.Builder(this, facade)
            .setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
                    val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                    if (key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && key.keyCode != KeyEvent.KEYCODE_HEADSETHOOK) return false
                    // Preserve toggle intent until the repository reads fresh MPD status. Other
                    // standard media commands still use the player facade, never ExoPlayer.
                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 && transport.available) {
                        transport.submit(PlayerAction.PlayPause)
                    }
                    return true
                }

                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    // Standard player commands reach the MPD facade, including platform/media keys.
                    // Never allow seeking, Stop, decoder/item control, queues or custom commands.
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(SessionCommands.EMPTY)
                        .setAvailablePlayerCommands(MpdSessionPlayer.commands(true))
                        .build()
                }
            }).build()
        addSession(requireNotNull(session))
        remotePlaying = request.snapshot.status.playback == PlaybackState.PLAYING
        val item = MediaItem.Builder().setMediaId(request.server.id).setUri(request.uri.toASCIIString()).build()
        exo.setMediaItem(item)
        updateMetadata(request)
        exo.playWhenReady = remotePlaying
        exo.prepare()
        observer = scope.launch {
            combine(stream.request, owner.repository.state) { current, _ -> current }.collect { current ->
                if (current?.generation == token) {
                    updateMetadata(current)
                    val playing = current.snapshot.status.playback == PlaybackState.PLAYING
                    if (playing != remotePlaying) {
                        remotePlaying = playing
                        if (playing) {
                            // Rejoin the live origin rather than playing buffered silence from a pause.
                            exo.stop()
                            exo.prepare()
                            exo.play()
                        } else exo.pause()
                    }
                    publishState()
                }
            }
        }
    }

    private fun updateMetadata(request: StreamRequest) {
        val song = request.snapshot.currentSong
        val status = getString(when (request.snapshot.status.playback) {
            PlaybackState.PLAYING -> R.string.playing
            PlaybackState.PAUSED -> R.string.paused
            PlaybackState.STOPPED -> R.string.stopped
            PlaybackState.UNKNOWN -> R.string.playback_unknown
        })
        val updated = MediaMetadata.Builder()
            .setTitle(song?.title ?: request.server.name)
            .setArtist(song?.artists.orEmpty().joinToString(getString(R.string.metadata_separator)))
            .setAlbumTitle(song?.albums.orEmpty().joinToString(getString(R.string.metadata_separator)))
            .setSubtitle(getString(R.string.stream_notification_status, status))
            .setIsBrowsable(false).setIsPlayable(true).build()
        sessionPlayer?.update(request, updated)
    }

    private fun publishState() {
        val exo = player ?: return
        if (exo.playerError != null) return
        val phase = when {
            exo.playbackState == Player.STATE_ENDED -> {
                val endedToken = token
                Handler(Looper.getMainLooper()).post { stream.fail(endedToken, StreamError.UNAVAILABLE) }
                return
            }
            exo.playbackState == Player.STATE_BUFFERING -> StreamPhase.BUFFERING
            exo.playbackState == Player.STATE_IDLE -> StreamPhase.CONNECTING
            !remotePlaying -> StreamPhase.WAITING_FOR_MPD
            exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> StreamPhase.INTERRUPTED
            exo.isPlaying -> StreamPhase.ACTIVE
            else -> StreamPhase.INTERRUPTED
        }
        stream.report(token, phase)
        if (phase == StreamPhase.BUFFERING || phase == StreamPhase.CONNECTING) {
            if (watchdog == null) {
                val waitingToken = token
                watchdog = scope.launch { delay(20_000); stream.fail(waitingToken, StreamError.TIMEOUT) }
            }
        } else {
            watchdog?.cancel()
            watchdog = null
        }
    }

    // A controller never creates the player, even when the service is explicitly bound.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { stream.request.value?.generation == token }

    /** Called synchronously before switching profiles. Safe to repeat from onDestroy. */
    fun releasePlayback() {
        observer?.cancel(); observer = null
        watchdog?.cancel(); watchdog = null
        session?.let { removeSession(it); it.release() }; session = null
        player?.release(); player = null
        sessionPlayer?.release(); sessionPlayer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player?.isPlaying != true) {
            stream.serviceDestroyed(token)
            stopSelf()
        }
    }

    override fun onDestroy() {
        owner.streamBackend.detach(this)
        releasePlayback()
        scope.cancel()
        stream.serviceDestroyed(token)
        super.onDestroy()
    }

    companion object { const val GENERATION = "stream_generation" }
}
