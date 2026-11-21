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
import com.github.jschbe.simplempdplayer.mpd.MpdSnapshot
import com.github.jschbe.simplempdplayer.mpd.PlayerAction

@Composable
internal fun QueueScreen(snapshot: MpdSnapshot?, serverId: String?, enabled: Boolean, visible: Boolean, onAction: (PlayerAction) -> Unit) {
    key(serverId) {
        val list = rememberLazyListState()
        var locatedCurrent by rememberSaveable { mutableStateOf(false) }
        var confirmClear by rememberSaveable { mutableStateOf(false) }
        val queue = snapshot?.queue.orEmpty()
        LaunchedEffect(visible, snapshot != null) {
            if (visible && snapshot != null && !locatedCurrent) {
                val index = queue.indexOfFirst { it.id == snapshot.status.songId }
                if (index >= 0) list.scrollToItem(index)
                locatedCurrent = true
            }
        }
        Column(Modifier.fillMaxSize().testTag("queue_page")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue_count, queue.size), modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = enabled && queue.isNotEmpty()) { Text(stringResource(R.string.clear_queue)) }
            }
            if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (snapshot == null) R.string.queue_unavailable else R.string.queue_empty))
                }
            } else {
                LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("queue_list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(queue, key = { _, entry -> entry.id }) { index, entry ->
                        val current = entry.id == snapshot?.status?.songId
                        var menu by remember(entry.id) { mutableStateOf(false) }
                        Surface(color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(
                                Modifier.fillMaxWidth().testTag("queue_entry_${entry.id}").semantics { selected = current },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f).testTag("queue_play_${entry.id}").clickable(enabled = enabled) { onAction(PlayerAction.PlayId(entry.id)) }.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(entry.song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    if (entry.song.artists.isNotEmpty()) Text(entry.song.artists.joinToString(stringResource(R.string.metadata_separator)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    entry.song.duration?.let { TimeText(it) }
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("queue_actions_${entry.id}")) {
                                        Icon(painterResource(R.drawable.ic_more), stringResource(R.string.queue_item_actions, entry.song.title))
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, onClick = { menu = false; onAction(PlayerAction.MoveId(entry.id, -1)) }, enabled = enabled && index > 0)
                                        DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, onClick = { menu = false; onAction(PlayerAction.MoveId(entry.id, 1)) }, enabled = enabled && index < queue.lastIndex)
                                        DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menu = false; onAction(PlayerAction.DeleteId(entry.id)) }, enabled = enabled)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.clear_queue_confirmation)) },
                text = { Text(stringResource(R.string.clear_queue_message)) },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; onAction(PlayerAction.Clear) }, enabled = enabled) { Text(stringResource(R.string.clear_queue)) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
