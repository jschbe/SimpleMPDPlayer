package com.github.jschbe.simplempdplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.mpd.BrowserQueueMessage
import com.github.jschbe.simplempdplayer.mpd.BrowserState
import com.github.jschbe.simplempdplayer.mpd.LibraryEntry

@Composable
internal fun BrowserScreen(
    browser: BrowserState,
    connected: Boolean,
    controlsEnabled: Boolean,
    onBrowse: (String, Long) -> Unit,
    onEnqueue: (String, Boolean, Long) -> Unit,
) {
    // A new session/path invalidates open menus and confirmations, including identical URIs.
    key(browser.generation, browser.path, connected) {
        val list = rememberLazyListState()
        var actionsFor by remember { mutableStateOf<LibraryEntry?>(null) }
        var replaceWith by remember { mutableStateOf<LibraryEntry?>(null) }
        val enabled = controlsEnabled && !browser.loading
        Column(Modifier.fillMaxSize().testTag("browser_page")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (browser.path.isNotEmpty()) IconButton(
                    onClick = { onBrowse(browser.parent, browser.generation) }, enabled = connected,
                ) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_up)) }
                Text(
                    if (browser.path.isEmpty()) stringResource(R.string.library_root) else stringResource(R.string.library_path, browser.path),
                    modifier = Modifier.weight(1f).padding(8.dp).testTag("browser_path"),
                    style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            browser.queueMessage?.let { message ->
                Text(
                    stringResource(when (message) {
                        BrowserQueueMessage.APPENDED -> R.string.library_appended
                        BrowserQueueMessage.REPLACED -> R.string.library_replaced
                        BrowserQueueMessage.ADD_FAILED -> R.string.library_add_failed
                        BrowserQueueMessage.CLEAR_FAILED -> R.string.library_clear_failed
                        BrowserQueueMessage.REPLACE_UNCONFIRMED -> R.string.library_replace_unconfirmed
                        BrowserQueueMessage.REPLACE_PARTIAL -> R.string.library_replace_partial
                        BrowserQueueMessage.REFRESH_FAILED -> R.string.library_refresh_failed
                    }),
                    color = if (message in listOf(BrowserQueueMessage.APPENDED, BrowserQueueMessage.REPLACED)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("browser_feedback"),
                )
            }
            if (connected && !controlsEnabled) Text(stringResource(R.string.applying_command), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            when {
                !connected -> Text(stringResource(R.string.library_unavailable), Modifier.padding(16.dp))
                browser.loading || (!browser.loaded && browser.error == null) -> Row(
                    Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.library_loading))
                }
                browser.error != null -> Column(Modifier.padding(16.dp)) {
                    ErrorText(R.string.library_load_failed)
                    TextButton(onClick = { onBrowse(browser.path, browser.generation) }) { Text(stringResource(R.string.retry)) }
                }
                else -> {
                    if (browser.hasPlaylists) Text(stringResource(R.string.library_playlists_unsupported), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    if (browser.entries.isEmpty()) Text(stringResource(if (browser.hasPlaylists) R.string.library_no_music else R.string.library_empty), Modifier.padding(16.dp))
                    LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("browser_list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(browser.entries, key = { "${it::class.simpleName}:${it.uri}" }) { entry ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f).testTag("browse_${entry.uri}").clickable(enabled = enabled) {
                                        if (entry is LibraryEntry.Directory) onBrowse(entry.uri, browser.generation) else actionsFor = entry
                                    }.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    when (entry) {
                                        is LibraryEntry.Directory -> Text(stringResource(R.string.library_folder), style = MaterialTheme.typography.bodySmall)
                                        is LibraryEntry.Track -> {
                                            if (entry.song.artists.isNotEmpty()) Text(entry.song.artists.joinToString(stringResource(R.string.metadata_separator)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            entry.song.duration?.let { TimeText(it) }
                                        }
                                    }
                                }
                                Box {
                                    IconButton(onClick = { actionsFor = entry }, enabled = enabled, modifier = Modifier.testTag("library_actions_${entry.uri}")) {
                                        Icon(painterResource(R.drawable.ic_more), stringResource(R.string.library_actions, entry.title))
                                    }
                                    DropdownMenu(expanded = actionsFor == entry, onDismissRequest = { actionsFor = null }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.append_to_queue)) }, enabled = enabled, onClick = {
                                            actionsFor = null
                                            onEnqueue(entry.uri, false, browser.generation)
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.replace_queue)) }, enabled = enabled, onClick = {
                                            actionsFor = null
                                            replaceWith = entry
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        replaceWith?.let { entry ->
            AlertDialog(
                onDismissRequest = { replaceWith = null },
                title = { Text(stringResource(R.string.replace_queue_confirmation)) },
                text = { Text(stringResource(R.string.replace_queue_message, entry.title)) },
                confirmButton = { TextButton(enabled = enabled, onClick = {
                    replaceWith = null
                    onEnqueue(entry.uri, true, browser.generation)
                }) { Text(stringResource(R.string.replace_queue)) } },
                dismissButton = { TextButton(onClick = { replaceWith = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
