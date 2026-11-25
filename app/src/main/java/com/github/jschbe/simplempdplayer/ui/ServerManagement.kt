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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.ServerEditor
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.ProfileField
import com.github.jschbe.simplempdplayer.data.PasswordEdit
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.hasDuplicateName
import com.github.jschbe.simplempdplayer.data.validMpdPassword
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
    val enabled = state.settingsEditable
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
        if (state.settings.readFailed) SettingsReadError(state.loading, onReload)
        if (state.saveFailed) ErrorText(R.string.settings_save_failed)
        if (!state.loading && !state.settings.readFailed && order.isEmpty()) {
            Text(stringResource(R.string.no_servers), Modifier.padding(16.dp))
        }
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
    state: ServersUiState,
    onDismiss: () -> Unit,
    onSave: (ServerProfile, PasswordEdit) -> Unit,
    onTest: (ServerProfile, PasswordEdit) -> Unit,
    onReload: () -> Unit,
    canRequestNetworkAccess: Boolean,
    onGrantNetworkAccess: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
) {
    val server = editor.server
    val saving = state.saving
    val busy = saving || editor.testing
    var name by rememberSaveable(server?.id) { mutableStateOf(server?.name.orEmpty()) }
    var host by rememberSaveable(server?.id) { mutableStateOf(server?.host.orEmpty()) }
    var mpdPort by rememberSaveable(server?.id) { mutableStateOf((server?.mpdPort ?: 6600).toString()) }
    var streamPort by rememberSaveable(server?.id) { mutableStateOf((server?.streamPort ?: 8000).toString()) }
    var submitted by rememberSaveable(server?.id) { mutableStateOf(false) }
    val input = ServerInput(name, host, mpdPort, streamPort)
    val inputErrors = input.errors()
    val errors = if (submitted) inputErrors else emptySet()
    val duplicateName = state.settings.servers.hasDuplicateName(name, server?.id)
    val invalidName = ProfileField.NAME in inputErrors || duplicateName
    val hasPassword = server?.credentialRevision != null
    var choice by remember { mutableStateOf(if (hasPassword) PasswordChoice.KEEP else PasswordChoice.REPLACE) }
    val draft = remember(choice) { TextFieldState() }
    val missingReplacement = hasPassword && choice == PasswordChoice.REPLACE && draft.text.isEmpty()
    val invalidDraft = draft.text.isNotEmpty() && !validMpdPassword(draft.text)
    val needsReentry = server != null && hasPassword && choice == PasswordChoice.KEEP &&
        input.toProfile(server.id)?.sameControlEndpoint(server) == false
    val passwordError = when {
        needsReentry -> R.string.password_reentry
        invalidDraft || (submitted && missingReplacement) -> R.string.password_invalid
        else -> null
    }
    fun submit(action: (ServerProfile, PasswordEdit) -> Unit) {
        submitted = true
        val candidate = (if (server == null) input.toProfile() else input.toProfile(server.id)) ?: return
        if (needsReentry || missingReplacement || invalidDraft) return
        val edit = when (choice) {
            PasswordChoice.KEEP -> PasswordEdit.Keep
            PasswordChoice.REMOVE -> PasswordEdit.Remove
            PasswordChoice.REPLACE -> if (draft.text.isEmpty()) PasswordEdit.Keep else PasswordEdit.Replace(draft.text.toString())
        }
        action(candidate, edit)
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (server == null) R.string.add_server else R.string.edit_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.networkAllowed) {
                    Text(stringResource(R.string.local_network_access_explanation))
                    Text(stringResource(R.string.local_network_settings_hint), style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        if (canRequestNetworkAccess) {
                            TextButton(onClick = onGrantNetworkAccess, enabled = !busy) {
                                Text(stringResource(R.string.allow_local_network))
                            }
                        }
                        TextButton(onClick = onOpenNetworkSettings, enabled = !busy) {
                            Text(stringResource(R.string.open_app_settings))
                        }
                    }
                }
                ProfileTextField(name, { name = it }, R.string.server_name,
                    ProfileField.NAME in errors || duplicateName,
                    if (duplicateName) R.string.duplicate_server_name else R.string.invalid_name, !busy)
                ProfileTextField(host, { host = it }, R.string.server_host, ProfileField.HOST in errors, R.string.invalid_host, !busy, placeholder = R.string.host_placeholder)
                ProfileTextField(mpdPort, { mpdPort = it }, R.string.mpd_port, ProfileField.MPD_PORT in errors, R.string.invalid_port, !busy, true)
                ProfileTextField(streamPort, { streamPort = it }, R.string.http_stream_port, ProfileField.STREAM_PORT in errors, R.string.invalid_port, !busy, true)
                if (hasPassword) {
                    Column(Modifier.selectableGroup()) {
                        PasswordChoice.entries.forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("password_${option.name.lowercase()}")
                                    .selectable(choice == option, enabled = !busy, role = Role.RadioButton) { choice = option },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = choice == option, onClick = null, enabled = !busy)
                                Text(stringResource(option.label))
                            }
                        }
                    }
                }
                if (choice == PasswordChoice.REPLACE) {
                    OutlinedSecureTextField(
                        state = draft,
                        modifier = Modifier.fillMaxWidth().testTag("mpd_password").semantics {
                            // Compose's editable and input semantics otherwise retain the raw draft.
                            val masked = AnnotatedString("•".repeat(draft.text.length))
                            editableText = masked
                            inputText = masked
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.mpd_password)) },
                        textObfuscationMode = TextObfuscationMode.Hidden,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = passwordError != null,
                    )
                }
                passwordError?.let { ErrorText(it) }
                Text(stringResource(R.string.password_backup_hint), style = MaterialTheme.typography.bodySmall)
                if (state.settings.readFailed) SettingsReadError(state.loading, onReload)
                if (state.saveFailed) ErrorText(R.string.settings_save_failed)
                if (editor.passwordSaveFailed) ErrorText(R.string.password_save_failed)
                val activeError = state.connection.error.takeIf { server != null && server.id == state.activeServer?.id }
                (editor.testError ?: activeError?.takeIf { it.accessStatusLabel() != null })
                    ?.takeUnless { !state.networkAllowed && it == ConnectionError.PERMISSION_REQUIRED }
                    ?.let { ErrorText(it.label()) }
                if (editor.testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(stringResource(R.string.testing))
                    }
                }
            }
        },
        confirmButton = {
            FlowRow {
                TextButton(onClick = { submit(onTest) }, enabled = !busy && !needsReentry && !invalidDraft) {
                    Text(stringResource(R.string.test_connection))
                }
                TextButton(onClick = { submit(onSave) },
                    enabled = state.settingsEditable && !busy && !invalidName && !needsReentry && !invalidDraft) {
                    Text(stringResource(R.string.save))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}

private enum class PasswordChoice(@StringRes val label: Int) {
    KEEP(R.string.password_keep),
    REPLACE(R.string.password_replace),
    REMOVE(R.string.password_remove),
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

@Composable
internal fun SettingsReadError(loading: Boolean, onReload: () -> Unit) {
    ErrorText(R.string.settings_read_failed)
    TextButton(onClick = onReload, enabled = !loading) { Text(stringResource(R.string.retry)) }
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
    ConnectionError.RESPONSE_LIMIT -> R.string.mpd_response_limit
    ConnectionError.QUEUE_CHANGED -> R.string.queue_changed_action
    ConnectionError.LOST -> R.string.connection_lost
    ConnectionError.MPD_REJECTED -> R.string.mpd_rejected
    ConnectionError.NETWORK -> R.string.network_error
    ConnectionError.PERMISSION_REQUIRED -> R.string.local_network_permission_required
    ConnectionError.PASSWORD_REJECTED -> R.string.password_rejected_detail
    ConnectionError.MPD_PERMISSION -> R.string.mpd_access_denied_detail
    ConnectionError.CREDENTIAL_MISSING -> R.string.password_missing_detail
    ConnectionError.CREDENTIAL_UNREADABLE -> R.string.password_unreadable_detail
    ConnectionError.PASSWORD_REENTRY -> R.string.password_reentry
    ConnectionError.INVALID_PASSWORD -> R.string.password_invalid
}

@StringRes
internal fun ConnectionError.accessStatusLabel(): Int? = when (this) {
    ConnectionError.CREDENTIAL_MISSING, ConnectionError.CREDENTIAL_UNREADABLE -> R.string.password_needed_status
    ConnectionError.PASSWORD_REJECTED -> R.string.password_rejected_status
    ConnectionError.MPD_PERMISSION -> R.string.mpd_access_denied_status
    else -> null
}
