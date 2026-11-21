package com.github.jschbe.simplempdplayer.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.audio.StreamPhase
import com.github.jschbe.simplempdplayer.audio.StreamError
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.mpd.PlaybackState
import com.github.jschbe.simplempdplayer.mpd.PlayerAction

@Composable
internal fun PlayerScreen(state: ServersUiState, enabled: Boolean, onAction: (PlayerAction) -> Unit, onLocalListening: (Boolean) -> Unit = {}) {
    val snapshot = state.connection.snapshot
    val status = snapshot?.status
    val song = snapshot?.currentSong
    val hasQueue = !snapshot?.queue.isNullOrEmpty()
    val duration = status?.duration
    var draggedPosition by remember(state.settings.selectedId, status?.songId) { mutableStateOf<Float?>(null) }
    var draggedVolume by remember(state.settings.selectedId, status?.volume) { mutableStateOf<Float?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) { draggedPosition = null; draggedVolume = null }
    }
    Column(
        Modifier.fillMaxSize().testTag("player_page").verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (song != null) {
            Text(song.title, style = MaterialTheme.typography.headlineMedium)
            if (song.artists.isNotEmpty()) Text(song.artists.joinToString(stringResource(R.string.metadata_separator)), style = MaterialTheme.typography.titleMedium)
            if (song.albums.isNotEmpty()) Text(song.albums.joinToString(stringResource(R.string.metadata_separator)), style = MaterialTheme.typography.bodyMedium)
        } else if (snapshot != null && !hasQueue) {
            Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.headlineSmall)
        } else {
            Text(stringResource(R.string.no_current_song), style = MaterialTheme.typography.headlineSmall)
        }
        Text(stringResource(when (status?.playback) {
            PlaybackState.PLAYING -> R.string.playing
            PlaybackState.PAUSED -> R.string.paused
            PlaybackState.STOPPED -> R.string.stopped
            else -> R.string.playback_unknown
        }))
        LocalListening(state, onLocalListening)
        if (duration != null && state.positionSeconds != null) {
            val position = draggedPosition?.toDouble() ?: state.positionSeconds
            Slider(
                value = position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { draggedPosition = it },
                onValueChangeFinished = {
                    draggedPosition?.let { onAction(PlayerAction.Seek(it.toDouble(), status.songId)) }
                    draggedPosition = null
                },
                valueRange = 0f..duration.toFloat(),
                enabled = enabled && hasQueue && status.playback != PlaybackState.STOPPED && status.playback != PlaybackState.UNKNOWN,
                modifier = Modifier.testTag("seek_slider"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TimeText(position)
                TimeText(duration)
            }
        } else if (state.positionSeconds != null) {
            TimeText(state.positionSeconds)
        } else {
            Text(stringResource(R.string.duration_unknown), style = MaterialTheme.typography.bodySmall)
        }
        if (duration == null && song?.duration != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.total_duration), style = MaterialTheme.typography.bodySmall)
                TimeText(song.duration)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PlaybackButton(R.drawable.ic_previous, R.string.previous, enabled && hasQueue) { onAction(PlayerAction.Previous) }
            val playing = status?.playback == PlaybackState.PLAYING
            FilledIconButton(onClick = { onAction(PlayerAction.PlayPause) }, enabled = enabled && hasQueue && status?.playback != PlaybackState.UNKNOWN, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), stringResource(if (playing) R.string.pause else R.string.play), modifier = Modifier.size(32.dp))
            }
            PlaybackButton(R.drawable.ic_stop, R.string.stop, enabled && hasQueue) { onAction(PlayerAction.Stop) }
            PlaybackButton(R.drawable.ic_next, R.string.next, enabled && hasQueue) { onAction(PlayerAction.Next) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.random))
                Switch(checked = status?.random == true, onCheckedChange = { onAction(PlayerAction.Random(it)) }, enabled = enabled, modifier = Modifier.testTag("random_switch"))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.repeat))
                Switch(checked = status?.repeat == true, onCheckedChange = { onAction(PlayerAction.Repeat(it)) }, enabled = enabled, modifier = Modifier.testTag("repeat_switch"))
            }
        }
        status?.volume?.let { volume ->
            Text(stringResource(R.string.mpd_volume, draggedVolume?.toInt() ?: volume))
            Slider(
                value = draggedVolume ?: volume.toFloat(),
                onValueChange = { draggedVolume = it },
                onValueChangeFinished = {
                    draggedVolume?.let { onAction(PlayerAction.Volume(it.toInt().coerceIn(0, 100))) }
                    draggedVolume = null
                },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.testTag("volume_slider"),
            )
        }
        if (state.connection.commandPending) Text(stringResource(R.string.applying_command), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PlaybackButton(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled) { Icon(painterResource(icon), stringResource(label)) }
}

@Composable
internal fun TimeText(seconds: Double) {
    val total = seconds.toLong().coerceAtLeast(0)
    Text(stringResource(R.string.time_minutes_seconds, total / 60, total % 60), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LocalListening(state: ServersUiState, onChange: (Boolean) -> Unit) {
    val audio = state.localStream
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.listen_on_phone), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = audio.enabled,
                    onCheckedChange = onChange,
                    enabled = audio.enabled || (state.connection.status == ConnectionStatus.CONNECTED && !state.saving),
                    modifier = Modifier.testTag("local_listening_switch"),
                )
            }
            Text(stringResource(when (audio.phase) {
                StreamPhase.OFF -> R.string.stream_off
                StreamPhase.CONNECTING -> R.string.stream_connecting
                StreamPhase.BUFFERING -> R.string.stream_buffering
                StreamPhase.ACTIVE -> R.string.stream_active
                StreamPhase.WAITING_FOR_MPD -> R.string.stream_waiting
                StreamPhase.INTERRUPTED -> R.string.stream_interrupted
                StreamPhase.ERROR -> when (audio.error) {
                    StreamError.TIMEOUT -> R.string.stream_error_timeout
                    StreamError.FORMAT -> R.string.stream_error_format
                    StreamError.PERMISSION -> R.string.stream_error_permission
                    StreamError.CONNECTION_LOST -> R.string.stream_error_connection
                    StreamError.START_FAILED -> R.string.stream_error_start
                    else -> R.string.stream_error_unavailable
                }
            }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("local_listening_status"))
            Text(stringResource(R.string.stream_volume_hint), style = MaterialTheme.typography.bodySmall)
            if (audio.phase == StreamPhase.ERROR && state.connection.status == ConnectionStatus.CONNECTED) {
                TextButton(onClick = { onChange(true) }) { Text(stringResource(R.string.stream_retry)) }
            }
        }
    }
}
