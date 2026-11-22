package com.github.jschbe.simplempdplayer.audio

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.github.jschbe.simplempdplayer.mpd.PlaybackState
import com.github.jschbe.simplempdplayer.mpd.PlayerAction
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/** Session facade, never a decoder. State and metadata come exclusively from confirmed MPD reads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MpdSessionPlayer(private val transport: MpdMediaTransport) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var confirmed = State.Builder().build()

    fun update(request: StreamRequest, metadata: MediaMetadata) {
        val playback = request.snapshot.status.playback
        // One current MPD item, no copied queue, stream URI, duration or seekable position.
        val item = MediaItem.Builder().setMediaId(request.server.id).setMediaMetadata(metadata).build()
        confirmed = State.Builder()
            .setAvailableCommands(commands(transport.available && playback != PlaybackState.UNKNOWN))
            .setPlaylist(listOf(MediaItemData.Builder(request.server.id).setMediaItem(item).build()))
            .setPlaybackState(if (playback == PlaybackState.UNKNOWN || playback == PlaybackState.STOPPED)
                Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(playback == PlaybackState.PLAYING, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs { 0L }
            .build()
        invalidateState()
    }

    override fun getState(): State = confirmed

    // Async command acknowledgement must not manufacture optimistic MPD state/track changes.
    override fun getPlaceholderState(suggestedPlaceholderState: State): State = confirmed

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        dispatch(if (playWhenReady) PlayerAction.Play else PlayerAction.Pause)

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        return dispatch(when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> PlayerAction.Next
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> PlayerAction.Previous
            else -> return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        })
    }

    private fun dispatch(action: PlayerAction): ListenableFuture<*> {
        val future = SettableFuture.create<Unit>()
        val job = transport.submit(action)
        if (job == null) future.set(Unit) else job.invokeOnCompletion { future.set(Unit) }
        return future
    }

    companion object {
        fun commands(transportAvailable: Boolean) = Player.Commands.Builder()
            .addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE)
            .apply {
                if (transportAvailable) addAll(Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }.build()
    }
}
