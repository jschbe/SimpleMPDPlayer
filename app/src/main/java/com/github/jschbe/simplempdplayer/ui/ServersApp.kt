package com.github.jschbe.simplempdplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.ThemeMode
import com.github.jschbe.simplempdplayer.mpd.ConnectionError
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import com.github.jschbe.simplempdplayer.mpd.PlayerAction
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersApp(
    state: ServersUiState,
    onAdd: () -> Unit,
    onEdit: (ServerProfile) -> Unit,
    onDismissEditor: () -> Unit,
    onSave: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onReconnect: () -> Unit,
    onReload: () -> Unit,
    onGrantNetworkAccess: () -> Unit,
    onAction: (PlayerAction) -> Unit,
    onTest: (ServerProfile) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    onTheme: (ThemeMode) -> Unit = {},
    onLoadBrowser: () -> Unit = {},
    onBrowse: (String, Long) -> Unit = { _, _ -> },
    onEnqueue: (String, Boolean, Long) -> Unit = { _, _, _ -> },
    onLocalListening: (Boolean) -> Unit = {},
    onSleepEnabled: (Boolean) -> Unit = {},
    onSleepMinutes: (Int) -> Unit = {},
    onSleepRetry: () -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showServers by rememberSaveable { mutableStateOf(false) }
    var initialNavigationResolved by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var serverMenu by remember { mutableStateOf(false) }
    var sleepTimer by rememberSaveable { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        if (!state.loading && !initialNavigationResolved) {
            showServers = state.activeServer == null
            initialNavigationResolved = true
        }
    }
    BackHandler(enabled = showServers) { showServers = false }
    val connected = state.connection.status == ConnectionStatus.CONNECTED && !state.connection.waitingForNetwork
    val controlsEnabled = connected && !state.connection.commandPending
    LaunchedEffect(showServers, pager.settledPage, state.connection.status, state.browser.generation) {
        if (!showServers && pager.settledPage == 0 && connected) onLoadBrowser()
    }
    BackHandler(enabled = !showServers && pager.currentPage == 0 && state.browser.path.isNotEmpty() && connected) {
        onBrowse(state.browser.parent, state.browser.generation)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showServers) Text(stringResource(R.string.servers)) else Box {
                        Row(
                            Modifier.testTag("server_dropdown").clickable(enabled = !state.loading && !state.saving && state.settings.servers.isNotEmpty()) { serverMenu = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(state.activeServer?.name ?: stringResource(R.string.select_server), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(painterResource(R.drawable.ic_expand), stringResource(R.string.select_server))
                        }
                        DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                            state.settings.servers.forEach { server ->
                                DropdownMenuItem(
                                    text = { Text(server.name) },
                                    trailingIcon = { if (server.id == state.settings.selectedId) Icon(painterResource(R.drawable.ic_check), stringResource(R.string.active_server)) },
                                    onClick = { serverMenu = false; onSelect(server) },
                                    modifier = Modifier.testTag("select_server_${server.id}"),
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (showServers) IconButton(onClick = { showServers = false }) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    if (showServers) IconButton(onClick = onAdd, enabled = !state.loading && !state.saving && !state.settings.readFailed) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_server))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.settings))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.servers)) }, onClick = { showServers = true; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sleep_timer)) }, onClick = { sleepTimer = true; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.appearance)) }, onClick = { appearance = true; menu = false })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val connection = state.connection
                val label = when {
                    connection.waitingForNetwork -> stringResource(R.string.connection_waiting_network)
                    connection.recovering -> stringResource(R.string.connection_recovering, connection.retryAttempt)
                    else -> stringResource(connection.status.label())
                }
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (state.loading || connection.status == ConnectionStatus.CONNECTING || connection.recovering) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else if ((!connected || connection.error != null || connection.retriesExhausted) && state.activeServer != null) {
                    TextButton(onClick = onReconnect) { Text(stringResource(R.string.reconnect)) }
                }
            }
            if (state.connection.retriesExhausted) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { ErrorText(R.string.connection_retries_exhausted) }
            }
            state.connection.failureSource?.takeIf { state.connection.error != null }?.let {
                Text(stringResource(it.label()), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            }
            (state.connection.error ?: state.connection.commandError)?.let {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { ErrorText(it.label()) }
            }
            if (state.connection.error == ConnectionError.PERMISSION_REQUIRED) {
                TextButton(onClick = onGrantNetworkAccess, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.allow_local_network))
                }
            }
            if ((!connected || (state.connection.failureSource == com.github.jschbe.simplempdplayer.mpd.ConnectionFailureSource.IDLE &&
                    !state.connection.idleConnected)) && state.connection.snapshot != null) {
                Text(stringResource(R.string.stale_data), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            }
            if (!showServers && state.saveFailed) ErrorText(R.string.settings_save_failed)
            if (showServers) {
                ServerManagement(state, onEdit, onDelete, {
                    onSelect(it)
                    showServers = false
                    scope.launch { pager.scrollToPage(1) }
                }, onReload, onReorder)
            } else {
                PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                    listOf(R.string.browser, R.string.player, R.string.queue).forEachIndexed { index, title ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            text = { Text(stringResource(title)) },
                            modifier = Modifier.testTag("tab_$index"),
                        )
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).testTag("main_pager")) { page ->
                    when (page) {
                        0 -> BrowserScreen(state.browser, connected, controlsEnabled, onBrowse, onEnqueue)
                        1 -> PlayerScreen(state, controlsEnabled, onAction, onLocalListening)
                        2 -> QueueScreen(
                            state.connection.snapshot, state.settings.selectedId,
                            controlsEnabled, pager.settledPage == 2, onAction,
                        )
                    }
                }
            }
        }
    }
    state.editor?.let { editor ->
        key(editor.token) { ServerEditorDialog(editor, state.saving, state.saveFailed, onDismissEditor, onSave, onTest) }
    }
    if (sleepTimer) {
        SleepTimerDialog(state.sleepTimer, state.activeServer?.name, state.saving,
            onSleepEnabled, onSleepMinutes, onSleepRetry, { sleepTimer = false })
    }
    if (appearance) {
        AlertDialog(
            onDismissRequest = { appearance = false },
            title = { Text(stringResource(R.string.appearance)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().testTag("theme_${mode.name}").clickable(enabled = !state.saving) { onTheme(mode) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.settings.theme == mode, onClick = { onTheme(mode) }, enabled = !state.saving)
                            Text(stringResource(when (mode) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            }))
                        }
                    }
                    if (state.saveFailed) ErrorText(R.string.settings_save_failed)
                }
            },
            confirmButton = { TextButton(onClick = { appearance = false }) { Text(stringResource(R.string.done)) } },
        )
    }
}
