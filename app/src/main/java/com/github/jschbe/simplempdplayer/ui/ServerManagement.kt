package com.github.jschbe.simplempdplayer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.ServerEditor
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.ProfileField
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.mpd.ConnectionError
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun ServerManagement(
    state: ServersUiState,
    onEdit: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onReload: () -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = !state.loading && !state.saving && !state.settings.readFailed
    val list = rememberLazyListState()
    var order by remember { mutableStateOf(state.settings.servers) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedTop by remember { mutableFloatStateOf(0f) }
    var draggedHeight by remember { mutableIntStateOf(0) }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(state.settings.servers, state.saveFailed) { if (draggedId == null) order = state.settings.servers }

    fun moveAtPointer(direction: Float) {
        val id = draggedId ?: return
        val center = draggedTop + draggedHeight / 2f
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != id && center >= it.offset && center <= it.offset + it.size
        } ?: return
        val from = order.indexOfFirst { it.id == id }
        val to = order.indexOfFirst { it.id == target.key }
        if (from >= 0 && to >= 0 && ((direction > 0 && to > from) || (direction < 0 && to < from))) {
            order = order.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
    LaunchedEffect(draggedId) {
        if (draggedId != null) while (isActive && draggedId != null) {
            val layout = list.layoutInfo
            val amount = when {
                draggedTop < layout.viewportStartOffset + edge -> -16f
                draggedTop + draggedHeight > layout.viewportEndOffset - edge -> 16f
                else -> 0f
            }
            if (amount != 0f && list.scrollBy(amount) != 0f) moveAtPointer(amount)
            delay(16)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (state.settings.recovered) ErrorText(R.string.settings_recovered)
        if (state.settings.readFailed) {
            ErrorText(R.string.settings_read_failed)
            TextButton(onClick = onReload) { Text(stringResource(R.string.retry)) }
        }
        if (state.saveFailed) ErrorText(R.string.settings_save_failed)
        if (!state.loading && order.isEmpty()) Text(stringResource(R.string.no_servers), Modifier.padding(16.dp))
        LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("server_list"), contentPadding = PaddingValues(8.dp)) {
            items(order, key = { it.id }) { server ->
                val dragging = draggedId == server.id
                val rowOffset = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == server.id }?.offset ?: 0
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("server_row_${server.id}").zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) draggedTop - rowOffset else 0f },
                    color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_drag), stringResource(R.string.reorder_server, server.name),
                            modifier = Modifier.size(48.dp).testTag("drag_server_${server.id}")
                                .pointerInput(server.id, enabled) {
                                    if (enabled) detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == server.id }
                                            if (info != null) { draggedId = server.id; draggedTop = info.offset.toFloat(); draggedHeight = info.size }
                                        },
                                        onDragEnd = { draggedId = null; onReorder(order.map { it.id }) },
                                        onDragCancel = { draggedId = null; order = state.settings.servers },
                                        onDrag = { change, amount -> change.consume(); draggedTop += amount.y; moveAtPointer(amount.y) },
                                    )
                                }.padding(12.dp),
                        )
                        Column(Modifier.weight(1f).clickable(enabled = enabled && draggedId == null) { onSelect(server) }.padding(8.dp)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.server_endpoint, server.host, server.mpdPort), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (server.id == state.settings.selectedId) Text(stringResource(R.string.active_server), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { onEdit(server) }, enabled = enabled && draggedId == null, modifier = Modifier.testTag("edit_server_${server.id}")) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit))
                        }
                        IconButton(onClick = { deleteId = server.id }, enabled = enabled && draggedId == null, modifier = Modifier.testTag("delete_server_${server.id}")) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
    state.settings.servers.find { it.id == deleteId }?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.delete_server)) },
            text = { Text(stringResource(R.string.delete_server_confirmation, server.name)) },
            confirmButton = { TextButton(onClick = { deleteId = null; onDelete(server) }, enabled = enabled) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun ServerEditorDialog(
    editor: ServerEditor,
    saving: Boolean,
    saveFailed: Boolean,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit,
    onTest: (ServerProfile) -> Unit,
) {
    val server = editor.server
    val busy = saving || editor.testing
    var name by rememberSaveable(server?.id) { mutableStateOf(server?.name.orEmpty()) }
    var host by rememberSaveable(server?.id) { mutableStateOf(server?.host.orEmpty()) }
    var mpdPort by rememberSaveable(server?.id) { mutableStateOf((server?.mpdPort ?: 6600).toString()) }
    var streamPort by rememberSaveable(server?.id) { mutableStateOf((server?.streamPort ?: 8000).toString()) }
    var submitted by rememberSaveable(server?.id) { mutableStateOf(false) }
    val input = ServerInput(name, host, mpdPort, streamPort)
    val errors = if (submitted) input.errors() else emptySet()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (server == null) R.string.add_server else R.string.edit_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTextField(name, { name = it }, R.string.server_name, ProfileField.NAME in errors, R.string.invalid_name, !busy)
                ProfileTextField(host, { host = it }, R.string.server_host, ProfileField.HOST in errors, R.string.invalid_host, !busy, placeholder = R.string.host_placeholder)
                ProfileTextField(mpdPort, { mpdPort = it }, R.string.mpd_port, ProfileField.MPD_PORT in errors, R.string.invalid_port, !busy, true)
                ProfileTextField(streamPort, { streamPort = it }, R.string.http_stream_port, ProfileField.STREAM_PORT in errors, R.string.invalid_port, !busy, true)
                if (saveFailed) ErrorText(R.string.settings_save_failed)
                editor.testError?.let { ErrorText(it.label()) }
                if (editor.testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(stringResource(R.string.testing))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    submitted = true
                    val profile = if (server == null) input.toProfile() else input.toProfile(server.id)
                    profile?.let(onTest)
                }, enabled = !busy) { Text(stringResource(R.string.test_connection)) }
                TextButton(onClick = {
                    submitted = true
                    val profile = if (server == null) input.toProfile() else input.toProfile(server.id)
                    profile?.let(onSave)
                }, enabled = !busy) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ProfileTextField(
    value: String,
    onChange: (String) -> Unit,
    @StringRes label: Int,
    error: Boolean,
    @StringRes errorText: Int,
    enabled: Boolean,
    numeric: Boolean = false,
    @StringRes placeholder: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        singleLine = true,
        enabled = enabled,
        isError = error,
        supportingText = if (error) ({ Text(stringResource(errorText)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ErrorText(@StringRes text: Int) {
    Text(stringResource(text), color = MaterialTheme.colorScheme.error)
}

@StringRes
internal fun ConnectionStatus.label(): Int = when (this) {
    ConnectionStatus.DISCONNECTED -> R.string.disconnected
    ConnectionStatus.CONNECTING -> R.string.connecting
    ConnectionStatus.CONNECTED -> R.string.connected
    ConnectionStatus.FAILED -> R.string.connection_failed
}

@StringRes
internal fun ConnectionError.label(): Int = when (this) {
    ConnectionError.REFUSED -> R.string.connection_refused
    ConnectionError.UNREACHABLE -> R.string.host_not_reachable
    ConnectionError.TIMEOUT -> R.string.connection_timed_out
    ConnectionError.INVALID_RESPONSE -> R.string.invalid_mpd_response
    ConnectionError.LOST -> R.string.connection_lost
    ConnectionError.MPD_REJECTED -> R.string.mpd_rejected
    ConnectionError.NETWORK -> R.string.network_error
    ConnectionError.PERMISSION_REQUIRED -> R.string.local_network_permission_required
}

@StringRes
internal fun com.github.jschbe.simplempdplayer.mpd.ConnectionFailureSource.label(): Int = when (this) {
    com.github.jschbe.simplempdplayer.mpd.ConnectionFailureSource.CONNECT -> R.string.connection_source_connect
    com.github.jschbe.simplempdplayer.mpd.ConnectionFailureSource.CONTROL -> R.string.connection_source_control
    com.github.jschbe.simplempdplayer.mpd.ConnectionFailureSource.IDLE -> R.string.connection_source_idle
}
