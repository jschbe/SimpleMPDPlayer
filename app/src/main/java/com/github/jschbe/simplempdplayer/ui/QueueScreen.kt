package com.github.jschbe.simplempdplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.mpd.ConnectionError
import com.github.jschbe.simplempdplayer.mpd.MpdSnapshot
import com.github.jschbe.simplempdplayer.mpd.QueueActionContext
import com.github.jschbe.simplempdplayer.mpd.QueueAvailability
import com.github.jschbe.simplempdplayer.mpd.PlayerAction

@Composable
internal fun QueueScreen(
    snapshot: MpdSnapshot?,
    serverId: String?,
    sessionId: Long?,
    enabled: Boolean,
    visible: Boolean,
    onAction: (PlayerAction.QueueAction, QueueActionContext) -> Unit,
    onRetry: (Long) -> Unit,
    commandError: ConnectionError? = null,
) {
    key(serverId) {
        val list = rememberLazyListState()
        var locatedCurrent by rememberSaveable { mutableStateOf(false) }
        val complete = snapshot?.queueAvailability == QueueAvailability.COMPLETE
        val queue = if (complete) snapshot?.queue.orEmpty() else emptyList()
        val canClear = sessionId != null && snapshot?.hasQueuedSongs == true
        val clearVersion = snapshot?.status?.queueVersion.takeIf { complete }
        // Dialogs are tied to a live session and are intentionally excluded from saved state.
        var confirmClear by remember(sessionId, clearVersion, canClear) { mutableStateOf(false) }
        val entriesEnabled = enabled && sessionId != null && complete
        fun submit(action: PlayerAction.QueueAction) {
            if (sessionId != null) {
                onAction(action, QueueActionContext(sessionId, clearVersion.takeIf { action == PlayerAction.Clear }))
            }
        }
        LaunchedEffect(visible, complete) {
            if (visible && complete && !locatedCurrent) {
                val index = queue.indexOfFirst { it.id == snapshot?.status?.songId }
                if (index >= 0) list.scrollToItem(index)
                locatedCurrent = true
            }
        }
        Column(Modifier.fillMaxSize().testTag("queue_page")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (complete) Text(stringResource(R.string.queue_count, queue.size), modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = enabled && canClear) { Text(stringResource(R.string.clear_queue)) }
            }
            commandError?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(it.label()) } }
            if (snapshot != null && !complete) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(when (snapshot.queueAvailability) {
                        QueueAvailability.LIMIT -> R.string.queue_limit
                        QueueAvailability.CHANGING -> R.string.queue_changing
                        QueueAvailability.COMPLETE -> R.string.queue_empty
                    }))
                    if (sessionId != null) TextButton(onClick = { onRetry(sessionId) }, enabled = enabled) {
                        Text(stringResource(R.string.retry))
                    }
                }
            } else if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (snapshot == null) R.string.queue_unavailable else R.string.queue_empty))
                }
            } else {
                LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("queue_list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(queue, key = { _, entry -> entry.id }) { index, entry ->
                        val current = entry.id == snapshot?.status?.songId
                        var menu by remember(sessionId, entry.id) { mutableStateOf(false) }
                        Surface(color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(
                                Modifier.fillMaxWidth().testTag("queue_entry_${entry.id}").semantics { selected = current },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f).testTag("queue_play_${entry.id}").clickable(enabled = entriesEnabled) { submit(PlayerAction.PlayId(entry.id)) }.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(entry.song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    if (entry.song.artists.isNotEmpty()) Text(entry.song.artists.joinToString(stringResource(R.string.metadata_separator)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    entry.song.duration?.let { TimeText(it) }
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = entriesEnabled, modifier = Modifier.testTag("queue_actions_${entry.id}")) {
                                        Icon(painterResource(R.drawable.ic_more), stringResource(R.string.queue_item_actions, entry.song.title))
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, onClick = { menu = false; submit(PlayerAction.MoveId(entry.id, -1)) }, enabled = entriesEnabled && index > 0)
                                        DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, onClick = { menu = false; submit(PlayerAction.MoveId(entry.id, 1)) }, enabled = entriesEnabled && index < queue.lastIndex)
                                        DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menu = false; submit(PlayerAction.DeleteId(entry.id)) }, enabled = entriesEnabled)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (confirmClear && canClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.clear_queue_confirmation)) },
                text = { Text(stringResource(if (complete) R.string.clear_queue_message else R.string.clear_queue_unavailable_message)) },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; submit(PlayerAction.Clear) }, enabled = enabled) { Text(stringResource(R.string.clear_queue)) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
