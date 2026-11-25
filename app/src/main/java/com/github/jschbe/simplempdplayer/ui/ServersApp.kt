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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.PasswordEdit
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.ThemeMode
import com.github.jschbe.simplempdplayer.mpd.ConnectionError
import com.github.jschbe.simplempdplayer.mpd.ConnectionState
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import com.github.jschbe.simplempdplayer.mpd.PlayerAction
import com.github.jschbe.simplempdplayer.mpd.QueueActionContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersApp(
    state: ServersUiState,
    onAdd: () -> Unit,
    onEdit: (ServerProfile) -> Unit,
    onDismissEditor: () -> Unit,
    onSave: (ServerProfile, PasswordEdit) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onReconnect: () -> Unit,
    onReload: () -> Unit,
    onGrantNetworkAccess: () -> Unit,
    onAction: (PlayerAction) -> Unit,
    onTest: (ServerProfile, PasswordEdit) -> Unit = { _, _ -> },
    onReorder: (List<String>) -> Unit = {},
    onTheme: (ThemeMode) -> Unit = {},
    onLoadBrowser: () -> Unit = {},
    onBrowse: (String, Long) -> Unit = { _, _ -> },
    onEnqueue: (String, Boolean, Long) -> Unit = { _, _, _ -> },
    onLocalListening: (Boolean) -> Unit = {},
    onSleepEnabled: (Boolean) -> Unit = {},
    onSleepMinutes: (Int) -> Boolean = { false },
    onSleepRetry: () -> Unit = {},
    onTrackInfoExpanded: (Boolean) -> Unit = {},
    language: AppLanguage = AppLanguage.SYSTEM,
    onLanguage: (AppLanguage) -> Unit = {},
    onQueueAction: (PlayerAction.QueueAction, QueueActionContext) -> Unit = { _, _ -> },
    onRetryQueue: (Long) -> Unit = {},
    canRequestNetworkAccess: Boolean = true,
    onOpenNetworkSettings: () -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showServers by rememberSaveable { mutableStateOf(false) }
    var initialNavigationResolved by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var serverMenu by remember { mutableStateOf(false) }
    var sleepTimer by rememberSaveable { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
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
                            Modifier.testTag("server_dropdown").clickable(enabled = state.settingsEditable && state.settings.servers.isNotEmpty()) { serverMenu = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(state.activeServer?.name ?: stringResource(R.string.select_server), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(painterResource(R.drawable.ic_expand), stringResource(R.string.select_server))
                        }
                        DropdownMenu(expanded = serverMenu && state.settingsEditable, onDismissRequest = { serverMenu = false }) {
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
                    if (showServers) IconButton(onClick = onAdd, enabled = state.settingsEditable) {
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
                            DropdownMenuItem(text = { Text(stringResource(R.string.language)) }, onClick = { languageDialog = true; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.about)) }, onClick = { about = true; menu = false })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showServers) {
                if (!state.networkAllowed && state.activeServer != null) {
                    ConnectionStatusRow(state.connection, true, onReconnect, onGrantNetworkAccess,
                        networkAllowed = false, canRequestNetworkAccess = canRequestNetworkAccess,
                        onOpenNetworkSettings = onOpenNetworkSettings)
                }
                ServerManagement(state, onEdit, onDelete, {
                    onSelect(it)
                    showServers = false
                    scope.launch { pager.scrollToPage(1) }
                }, onReload, onReorder)
            } else {
                ConnectionStatusRow(state.connection, state.activeServer != null, onReconnect, onGrantNetworkAccess,
                    onEditServer = state.activeServer?.takeIf { state.settingsEditable }?.let { server -> { onEdit(server) } },
                    networkAllowed = state.networkAllowed, canRequestNetworkAccess = canRequestNetworkAccess,
                    onOpenNetworkSettings = onOpenNetworkSettings)
                if (state.settings.readFailed && state.activeServer == null) {
                    Column(Modifier.padding(horizontal = 16.dp)) { SettingsReadError(state.loading, onReload) }
                }
                if (state.saveFailed) ErrorText(R.string.settings_save_failed)
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
                        0 -> BrowserScreen(state.browser, connected, controlsEnabled, pager.settledPage == 0, onBrowse, onEnqueue)
                        1 -> PlayerScreen(state, controlsEnabled, onAction, onLocalListening, onTrackInfoExpanded,
                            onOpenSleepTimer = { sleepTimer = true })
                        2 -> QueueScreen(
                            state.connection.snapshot, state.activeServer?.id,
                            state.connection.sessionId.takeIf { connected },
                            controlsEnabled, pager.settledPage == 2, onQueueAction, onRetryQueue,
                            commandError = state.connection.commandError.takeIf { state.connection.commandErrorFromQueue },
                        )
                    }
                }
            }
        }
    }
    state.editor?.let { editor ->
        key(editor.token) {
            ServerEditorDialog(editor, state, onDismissEditor, onSave, onTest, onReload,
                canRequestNetworkAccess, onGrantNetworkAccess, onOpenNetworkSettings)
        }
    }
    if (sleepTimer) {
        SleepTimerDialog(state.sleepTimer, state.activeServer?.name, !state.settingsEditable,
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
                            Modifier.fillMaxWidth().testTag("theme_${mode.name}").clickable(enabled = state.settingsEditable) { onTheme(mode) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.settings.theme == mode, onClick = { onTheme(mode) }, enabled = state.settingsEditable)
                            Text(stringResource(when (mode) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            }))
                        }
                    }
                    if (state.settings.readFailed) SettingsReadError(state.loading, onReload)
                    if (state.saveFailed) ErrorText(R.string.settings_save_failed)
                }
            },
            confirmButton = { TextButton(onClick = { appearance = false }) { Text(stringResource(R.string.done)) } },
        )
    }
    if (languageDialog) LanguageDialog(language, onLanguage) { languageDialog = false }
    if (about) AboutDialog { about = false }
}

@Composable
internal fun ConnectionStatusRow(
    connection: ConnectionState,
    hasServer: Boolean,
    onReconnect: () -> Unit,
    onGrantNetworkAccess: () -> Unit,
    onEditServer: (() -> Unit)? = null,
    networkAllowed: Boolean = true,
    canRequestNetworkAccess: Boolean = true,
    onOpenNetworkSettings: () -> Unit = {},
) {
    val permissionMissing = hasServer && !networkAllowed
    val permissionRequired = permissionMissing || connection.error == ConnectionError.PERMISSION_REQUIRED
    val settingsRequired = permissionMissing && !canRequestNetworkAccess
    val busy = !permissionRequired && !connection.retriesExhausted &&
        (connection.recovering || connection.status == ConnectionStatus.CONNECTING)
    val failed = permissionRequired || connection.retriesExhausted || (!busy &&
        (connection.status == ConnectionStatus.FAILED || connection.error != null))
    val accessLabel = connection.error?.accessStatusLabel()
    val label = when {
        settingsRequired -> R.string.local_network_settings_hint
        permissionMissing -> R.string.local_network_access_required
        accessLabel != null && failed -> accessLabel
        failed -> R.string.connection_failed
        connection.waitingForNetwork -> R.string.connection_waiting_network
        connection.recovering -> R.string.reconnecting
        else -> connection.status.label()
    }
    val stale = stringResource(R.string.stale_data)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).testTag("connection_status_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f).semantics {
                if (connection.snapshot != null && !connection.livePlayerStatus) stateDescription = stale
            })
        if (permissionRequired || (failed && hasServer)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                val edit = onEditServer?.takeIf { accessLabel != null }
                TextButton(onClick = when {
                    settingsRequired -> onOpenNetworkSettings
                    permissionRequired -> onGrantNetworkAccess
                    else -> edit ?: onReconnect
                }) {
                    Text(stringResource(when {
                        settingsRequired -> R.string.open_app_settings
                        permissionRequired -> R.string.allow_local_network
                        edit != null -> R.string.edit_server
                        else -> R.string.reconnect
                    }))
                }
            }
        } else if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp).testTag("connection_progress"), strokeWidth = 2.dp)
        }
    }
}
