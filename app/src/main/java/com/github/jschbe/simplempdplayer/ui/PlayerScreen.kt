package com.github.jschbe.simplempdplayer.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.audio.StreamPhase
import com.github.jschbe.simplempdplayer.audio.StreamError
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.mpd.PlaybackState
import com.github.jschbe.simplempdplayer.mpd.PlayerAction
import com.github.jschbe.simplempdplayer.mpd.MpdStatus
import com.github.jschbe.simplempdplayer.mpd.RepeatMode
import com.github.jschbe.simplempdplayer.mpd.SingleMode

@Composable
internal fun PlayerScreen(state: ServersUiState, enabled: Boolean, onAction: (PlayerAction) -> Unit, onLocalListening: (Boolean) -> Unit = {}, onTrackInfoExpanded: (Boolean) -> Unit = {}, onOpenSleepTimer: () -> Unit = {}) {
    val snapshot = state.connection.snapshot
    val status = snapshot?.status
    val song = snapshot?.currentSong
    val hasQueue = snapshot?.hasQueuedSongs == true
    val duration = status?.duration
    var draggedPosition by remember(state.activeServer?.id, status?.songId) { mutableStateOf<Float?>(null) }
    var draggedVolume by remember(state.activeServer?.id, status?.volume) { mutableStateOf<Float?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) { draggedPosition = null; draggedVolume = null }
    }
    Column(
        Modifier.fillMaxSize().testTag("player_page").verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val expanded = state.settings.trackInfoExpanded
        val expandLabel = stringResource(if (expanded) R.string.track_info_collapse else R.string.track_info_expand)
        val expandedDescription = stringResource(if (expanded) R.string.track_info_expanded else R.string.track_info_collapsed)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("track_info_toggle")
            .semantics { stateDescription = expandedDescription }
            .clickable(enabled = state.settingsEditable,
                role = Role.Button, onClickLabel = expandLabel) { onTrackInfoExpanded(!expanded) },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song?.title ?: stringResource(if (snapshot != null && !hasQueue) R.string.queue_empty else R.string.no_current_song),
                    style = if (expanded) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                    maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("track_title"))
                if (expanded) {
                    if (!song?.artists.isNullOrEmpty()) Text(song.artists.joinToString(stringResource(R.string.metadata_separator)), style = MaterialTheme.typography.titleMedium)
                    if (!song?.albums.isNullOrEmpty()) Text(song.albums.joinToString(stringResource(R.string.metadata_separator)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Icon(painterResource(R.drawable.ic_expand), null, Modifier.padding(12.dp).rotate(if (expanded) 180f else 0f))
        }
        Row(Modifier.fillMaxWidth().testTag("player_status_row"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (status?.playback) {
                PlaybackState.PLAYING -> R.string.playing
                PlaybackState.PAUSED -> R.string.paused
                PlaybackState.STOPPED -> R.string.stopped
                else -> R.string.playback_unknown
            }), Modifier.weight(1f).testTag("mpd_playback_status"), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start)
            val timerLabel = stringResource(R.string.sleep_timer)
            Box(Modifier.weight(1.5f).heightIn(min = 48.dp).testTag("sleep_compact_status")
                .semantics { contentDescription = timerLabel }
                .clickable(interactionSource = null, indication = null,
                    role = Role.Button, onClickLabel = timerLabel, onClick = onOpenSleepTimer),
                contentAlignment = Alignment.CenterEnd) {
                SleepRemaining(state.sleepTimer, Modifier.fillMaxWidth(), compact = true)
            }
        }
        if (state.sleepTimer.settings.record?.status == com.github.jschbe.simplempdplayer.timer.TimerStatus.FAILED ||
            state.sleepTimer.operationError != null) Text(stringResource(R.string.timer_player_error), style = MaterialTheme.typography.bodySmall)
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
        key(state.activeServer?.id) { PlaybackOptions(status, enabled, onAction) }
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
        state.connection.commandError?.takeUnless { state.connection.commandErrorFromQueue }?.let { ErrorText(it.label()) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackOptions(status: MpdStatus?, enabled: Boolean, onAction: (PlayerAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    FlowRow(Modifier.fillMaxWidth().testTag("playback_options"), maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val randomLabel = stringResource(R.string.random)
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(randomLabel, Modifier.testTag("random_label"), style = MaterialTheme.typography.bodyLarge)
            Switch(status?.random == true, { onAction(PlayerAction.Random(it)) }, enabled = enabled,
                modifier = Modifier.testTag("random_switch").semantics { contentDescription = randomLabel })
        }
        Box(Modifier.align(Alignment.CenterVertically)) {
            val modeLabel = stringResource(status?.repeatMode?.label() ?: when {
                status?.single == SingleMode.ONESHOT -> R.string.repeat_oneshot
                status?.repeat == false && status.single == SingleMode.ON -> R.string.repeat_stop_after_track
                else -> R.string.repeat_unknown
            })
            TextButton(onClick = { expanded = true }, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current,
                    disabledContentColor = LocalContentColor.current),
                modifier = Modifier.width(IntrinsicSize.Max).testTag("repeat_dropdown").semantics { stateDescription = modeLabel }) {
                Text(stringResource(R.string.repeat, modeLabel), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge)
                Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                RepeatMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(stringResource(mode.label())) },
                        modifier = Modifier.testTag("repeat_option_${mode.name}").semantics { selected = status?.repeatMode == mode },
                        onClick = { expanded = false; onAction(PlayerAction.Repeat(mode)) })
                }
            }
        }
    }
}

@StringRes
private fun RepeatMode.label(): Int = when (this) {
    RepeatMode.NONE -> R.string.repeat_none
    RepeatMode.QUEUE -> R.string.repeat_queue
    RepeatMode.SINGLE -> R.string.repeat_single
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
    var info by rememberSaveable { mutableStateOf(false) }
    if (info) AlertDialog(onDismissRequest = { info = false },
        title = { Text(stringResource(R.string.listen_on_phone)) },
        text = { Text(stringResource(R.string.stream_info)) },
        confirmButton = { TextButton(onClick = { info = false }) { Text(stringResource(R.string.done)) } })
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.listen_on_phone), Modifier.weight(1f, fill = false),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { info = true }, modifier = Modifier.testTag("local_listening_info")) {
                        Icon(painterResource(R.drawable.ic_info), stringResource(R.string.stream_info_button))
                    }
                }
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
            if (audio.phase == StreamPhase.ERROR && state.connection.status == ConnectionStatus.CONNECTED) {
                TextButton(onClick = { onChange(true) }) { Text(stringResource(R.string.stream_retry)) }
            }
        }
    }
}
