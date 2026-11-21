package com.github.jschbe.simplempdplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.ServerSettings
import com.github.jschbe.simplempdplayer.data.ServerSettingsStore
import com.github.jschbe.simplempdplayer.data.ThemeMode
import com.github.jschbe.simplempdplayer.mpd.*
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Token distinguishes successive dialogs, including two Add dialogs without a profile ID. */
data class ServerEditor(
    val server: ServerProfile? = null,
    val token: Long = 0,
    val testing: Boolean = false,
    val testError: ConnectionError? = null,
)

enum class NoticeMessage { MPD_AVAILABLE, MPD_UNAVAILABLE, CONNECTED, CONNECTION_FAILED }
data class UiNotice(val token: Long, val message: NoticeMessage)

data class ServersUiState(
    val settings: ServerSettings = ServerSettings(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val connection: ConnectionState = ConnectionState(),
    val editor: ServerEditor? = null,
    val positionSeconds: Double? = null,
) {
    val activeServer: ServerProfile?
        get() = settings.servers.find { it.id == settings.selectedId }
}

class ServersViewModel(
    private val store: ServerSettingsStore,
    connectionFactory: () -> MpdConnection = { MpdClient() },
    private val networkPermission: () -> Boolean = { true },
) : ViewModel() {
    private val repository = MpdRepository(viewModelScope, connectionFactory)
    private val mutableState = MutableStateFlow(ServersUiState())
    val state = mutableState.asStateFlow()
    private val mutableNotices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 4)
    val notices = mutableNotices.asSharedFlow()
    private var settingsJob: Job? = null
    private var testJob: Job? = null
    private var networkAllowed = networkPermission()
    private var desiredEndpoint: Triple<String, String, Int>? = null
    private var writingSettings = false
    private var wasEmpty: Boolean? = null
    private var editorToken = 0L
    private var noticeToken = 0L
    private var pendingConnectionNotice: Long? = null

    init {
        observeSettings()
        viewModelScope.launch {
            repository.state.collectLatest { connection ->
                val snapshot = connection.snapshot
                mutableState.update { it.copy(connection = connection, positionSeconds = snapshot?.status?.elapsed) }
                pendingConnectionNotice?.let { token ->
                    val message = when (connection.status) {
                        ConnectionStatus.CONNECTED -> NoticeMessage.CONNECTED
                        ConnectionStatus.FAILED -> NoticeMessage.CONNECTION_FAILED
                        else -> null
                    }
                    if (message != null) {
                        pendingConnectionNotice = null
                        if (token == noticeToken) mutableNotices.tryEmit(UiNotice(token, message))
                    }
                }
                if (connection.status == ConnectionStatus.CONNECTED && snapshot != null) {
                    mutableState.update { it.copy(positionSeconds = snapshot.positionAt(System.nanoTime())) }
                    if (snapshot.status.playback == PlaybackState.PLAYING) {
                        while (isActive) {
                            delay(500)
                            mutableState.update { it.copy(positionSeconds = snapshot.positionAt(System.nanoTime())) }
                        }
                    }
                }
            }
        }
    }

    fun isCurrentNotice(notice: UiNotice) = notice.token == noticeToken

    private fun invalidateNotices() {
        noticeToken++
        pendingConnectionNotice = null
        testJob?.cancel()
        testJob = null
        mutableState.update { it.copy(editor = it.editor?.copy(testing = false)) }
    }

    fun reloadSettings() = observeSettings()

    private fun observeSettings() {
        settingsJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        settingsJob = viewModelScope.launch {
            store.settings.collect { settings ->
                applySettings(settings)
                if (!writingSettings) synchronizeConnection()
            }
        }
    }

    private fun applySettings(settings: ServerSettings) {
        mutableState.update { it.copy(settings = settings, loading = false) }
        if (!settings.readFailed) {
            val empty = settings.servers.isEmpty()
            val enteredEmpty = empty && wasEmpty != true
            wasEmpty = empty
            if (enteredEmpty && mutableState.value.editor == null) openEditor()
        }
    }

    private fun synchronizeConnection() {
        val selected = mutableState.value.activeServer
        if (desiredEndpoint != selected?.endpoint()) connect(selected)
    }

    fun openEditor(server: ServerProfile? = null) {
        invalidateNotices()
        testJob?.cancel()
        mutableState.update { it.copy(editor = ServerEditor(server, ++editorToken), saveFailed = false) }
    }

    fun dismissEditor() {
        if (!mutableState.value.saving) {
            invalidateNotices()
            testJob?.cancel()
            mutableState.update { it.copy(editor = null) }
        }
    }

    fun test(server: ServerProfile) {
        val editor = mutableState.value.editor ?: return
        if (!server.isValid() || editor.testing || mutableState.value.saving) return
        invalidateNotices()
        val token = noticeToken
        mutableState.update { it.copy(editor = editor.copy(testing = true, testError = null)) }
        testJob = viewModelScope.launch {
            val error = if (!networkPermission()) ConnectionError.PERMISSION_REQUIRED else repository.testConnection(server)
            if (token == noticeToken && mutableState.value.editor?.token == editor.token) {
                mutableState.update { it.copy(editor = it.editor?.copy(testing = false, testError = error)) }
                mutableNotices.tryEmit(UiNotice(token, if (error == null) NoticeMessage.MPD_AVAILABLE else NoticeMessage.MPD_UNAVAILABLE))
            }
        }
    }

    fun save(server: ServerProfile) {
        if (!server.isValid() || mutableState.value.editor?.testing == true) return
        writeSettings(afterSave = server) { store.saveAndSelect(server) }
    }

    fun select(server: ServerProfile) = writeSettings { store.select(server.id) }
    fun delete(server: ServerProfile) = writeSettings { store.delete(server.id) }
    fun reorder(ids: List<String>) = writeSettings(invalidate = false) { store.reorder(ids) }
    fun setTheme(mode: ThemeMode) = writeSettings(invalidate = false) { store.setTheme(mode) }

    private fun writeSettings(
        afterSave: ServerProfile? = null,
        invalidate: Boolean = true,
        action: suspend () -> Unit,
    ) {
        if (mutableState.value.saving || mutableState.value.loading || mutableState.value.settings.readFailed) return
        if (invalidate) invalidateNotices()
        val token = noticeToken
        writingSettings = true
        mutableState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                action()
                applySettings(store.settings.first())
                if (afterSave != null) {
                    mutableState.update { it.copy(editor = null) }
                    // The observer is suppressed during this atomic write, including same-endpoint edits.
                    connect(afterSave, notifyToken = token)
                }
            } catch (_: IOException) {
                mutableState.update { it.copy(saveFailed = true) }
            } finally {
                writingSettings = false
                mutableState.update { it.copy(saving = false) }
                synchronizeConnection()
            }
        }
    }

    fun reconnect() {
        invalidateNotices()
        connect(mutableState.value.activeServer)
    }

    fun perform(action: PlayerAction) = repository.perform(action)

    fun refreshNetworkPermission() {
        val allowed = networkPermission()
        if (networkAllowed != allowed) {
            networkAllowed = allowed
            reconnect()
        }
    }

    private fun connect(server: ServerProfile?, notifyToken: Long? = null) {
        desiredEndpoint = server?.endpoint()
        pendingConnectionNotice = notifyToken
        networkAllowed = networkPermission()
        repository.connect(server, networkAllowed)
    }

    override fun onCleared() {
        testJob?.cancel()
        repository.close()
    }

    private fun ServerProfile.endpoint() = Triple(id, host, mpdPort)
}
