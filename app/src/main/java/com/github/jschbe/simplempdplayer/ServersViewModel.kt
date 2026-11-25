package com.github.jschbe.simplempdplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jschbe.simplempdplayer.audio.LocalStreamController
import com.github.jschbe.simplempdplayer.audio.StreamState
import com.github.jschbe.simplempdplayer.timer.MAX_SLEEP_MINUTES
import com.github.jschbe.simplempdplayer.timer.SleepTimerController
import com.github.jschbe.simplempdplayer.timer.SleepTimerState
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.CredentialException
import com.github.jschbe.simplempdplayer.data.CredentialFailure
import com.github.jschbe.simplempdplayer.data.PasswordEdit
import com.github.jschbe.simplempdplayer.data.DuplicateServerNameException
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
    val passwordSaveFailed: Boolean = false,
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
    val browser: BrowserState = BrowserState(),
    val localStream: StreamState = StreamState(),
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val sessionServer: ServerProfile? = null,
    val networkAllowed: Boolean = true,
) {
    val settingsEditable: Boolean get() = !loading && !saving && !settings.readFailed

    val activeServer: ServerProfile?
        get() = settings.servers.find { it.id == settings.selectedId }
            ?: sessionServer.takeIf { loading || settings.readFailed }
}

class ServersViewModel(
    private val store: ServerSettingsStore,
    connectionFactory: () -> MpdConnection = { MpdClient() },
    private val networkPermission: () -> Boolean = { true },
    private val sharedRepository: MpdRepository? = null,
    private val localStream: LocalStreamController? = null,
    private val sleepTimer: SleepTimerController? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) : ViewModel() {
    private val repository = sharedRepository ?: MpdRepository(viewModelScope, connectionFactory)
    private val mutableState = MutableStateFlow(
        ServersUiState(sessionServer = repository.activeServer, networkAllowed = networkPermission())
    )
    val state = mutableState.asStateFlow()
    private val mutableNotices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 4)
    val notices = mutableNotices.asSharedFlow()
    private var settingsJob: Job? = null
    private var testJob: Job? = null
    private var foregroundCheckJob: Job? = null
    private var desiredNetworkAllowed = repository.state.value.let {
        // A policy-blocked selection has no socket failure source.
        it.error != ConnectionError.PERMISSION_REQUIRED || it.failureSource != null
    }
    private var desiredEndpoint = repository.activeServer?.endpoint()
    private var writingSettings = false
    private var wasEmpty: Boolean? = null
    private var editorToken = 0L
    private var noticeToken = 0L
    private var pendingConnectionNotice: Long? = null

    init {
        localStream?.let { stream ->
            viewModelScope.launch { stream.state.collect { audio -> mutableState.update { it.copy(localStream = audio) } } }
        }
        sleepTimer?.let { timer ->
            viewModelScope.launch { timer.state.collect { value -> mutableState.update { it.copy(sleepTimer = value) } } }
            viewModelScope.launch {
                timer.state.map { it.active }.distinctUntilChanged().collectLatest { active ->
                    if (active) while (isActive) { timer.tick(); delay(1_000) }
                }
            }
        }
        observeSettings()
        viewModelScope.launch {
            repository.browser.collect { browser -> mutableState.update { it.copy(browser = browser) } }
        }
        viewModelScope.launch {
            repository.state.collectLatest { connection ->
                val snapshot = connection.snapshot
                mutableState.update {
                    val position = when {
                        snapshot == null -> null
                        connection.livePlayerStatus -> snapshot.positionAt(nanoTime())
                        connection.playerConfirmedAtMillis != null && snapshot !== it.connection.snapshot ->
                            snapshot.status.elapsed
                        else -> it.positionSeconds ?: snapshot.status.elapsed
                    }
                    it.copy(connection = connection, positionSeconds = position, sessionServer = repository.activeServer)
                }
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
                if (connection.livePlayerStatus && snapshot?.status?.playback == PlaybackState.PLAYING) {
                    while (isActive) {
                        delay(500)
                        // The collector may be rescheduled after a network/freshness transition.
                        if (repository.state.value !== connection) break
                        mutableState.update { it.copy(positionSeconds = snapshot.positionAt(nanoTime())) }
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

    fun reloadSettings() {
        val current = state.value
        if (current.settings.readFailed && !current.loading && !current.saving) observeSettings()
    }

    private fun observeSettings() {
        settingsJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        settingsJob = viewModelScope.launch {
            store.settings.collect { settings ->
                val recovered = state.value.settings.readFailed && !settings.readFailed
                if (applySettings(settings) && !writingSettings) synchronizeConnection()
                if (recovered) refreshSleepTimer()
            }
        }
    }

    private fun applySettings(settings: ServerSettings): Boolean {
        if (settings.readFailed) {
            mutableState.update { it.copy(settings = it.settings.copy(readFailed = true), loading = false) }
            return false
        }
        mutableState.update { it.copy(settings = settings, loading = false) }
        val empty = settings.servers.isEmpty()
        val enteredEmpty = empty && wasEmpty != true
        wasEmpty = empty
        if (enteredEmpty && mutableState.value.editor == null) openEditor()
        return true
    }

    private fun synchronizeConnection() {
        if (!state.value.settingsEditable) return
        val selected = mutableState.value.activeServer
        if (desiredEndpoint != selected?.endpoint() ||
            (selected != null && desiredNetworkAllowed != state.value.networkAllowed)) connect(selected)
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

    fun test(server: ServerProfile, password: PasswordEdit = PasswordEdit.Keep) {
        val editor = mutableState.value.editor ?: return
        if (!server.isValid() || editor.testing || mutableState.value.saving) return
        invalidateNotices()
        val token = noticeToken
        mutableState.update { it.copy(editor = editor.copy(testing = true, testError = null, passwordSaveFailed = false)) }
        testJob = viewModelScope.launch {
            val error = try {
                if (!networkPermission()) ConnectionError.PERMISSION_REQUIRED else when (password) {
                    PasswordEdit.Keep -> repository.testConnection(store.testProfile(server, editor.server))
                    PasswordEdit.Remove -> repository.testConnection(server.copy(credentialRevision = null))
                    is PasswordEdit.Replace -> repository.testConnection(server) { password.password }
                }
            } catch (failure: CredentialException) {
                failure.connectionError()
            }
            if (token == noticeToken && mutableState.value.editor?.token == editor.token) {
                mutableState.update { it.copy(editor = it.editor?.copy(testing = false, testError = error)) }
                mutableNotices.tryEmit(UiNotice(token, if (error == null) NoticeMessage.MPD_AVAILABLE else NoticeMessage.MPD_UNAVAILABLE))
            }
        }
    }

    fun save(server: ServerProfile, password: PasswordEdit = PasswordEdit.Keep) {
        if (!server.isValid() || mutableState.value.editor?.testing == true) return
        writeSettings(afterSave = server.id) { store.saveAndSelect(server, password) }
    }

    fun select(server: ServerProfile) = writeSettings { store.select(server.id) }
    fun delete(server: ServerProfile) = writeSettings { store.delete(server.id) }
    fun reorder(ids: List<String>) = writeSettings(invalidate = false) { store.reorder(ids) }
    fun setTrackInfoExpanded(expanded: Boolean) = writeSettings(invalidate = false) { store.setTrackInfoExpanded(expanded) }
    fun setTheme(mode: ThemeMode) = writeSettings(invalidate = false) { store.setTheme(mode) }

    private fun writeSettings(
        afterSave: String? = null,
        invalidate: Boolean = true,
        action: suspend () -> Unit,
    ) {
        if (!state.value.settingsEditable) return
        if (invalidate) invalidateNotices()
        val token = noticeToken
        writingSettings = true
        mutableState.update { it.copy(saving = true, saveFailed = false,
            editor = it.editor?.copy(passwordSaveFailed = false)) }
        viewModelScope.launch {
            try {
                action()
                val saved = if (refreshWrittenSettings() && afterSave != null) {
                    state.value.settings.servers.find { it.id == afterSave }
                } else null
                if (saved != null) {
                    mutableState.update { it.copy(editor = null) }
                    // The observer is suppressed during this atomic write, including same-endpoint edits.
                    connect(saved, notifyToken = token)
                }
            } catch (_: DuplicateServerNameException) {
                // Refresh the editor's name validation if another write won the race to save.
                refreshWrittenSettings()
            } catch (failure: CredentialException) {
                mutableState.update { current ->
                    current.copy(editor = current.editor?.let {
                        if (failure.failure == CredentialFailure.SAVE) it.copy(passwordSaveFailed = true)
                        else it.copy(testError = failure.connectionError())
                    })
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

    private suspend fun refreshWrittenSettings(): Boolean {
        if (state.value.settings.readFailed) return false
        val settings = store.settings.first()
        return !state.value.settings.readFailed && applySettings(settings)
    }

    fun reconnect() {
        foregroundCheckJob?.cancel()
        invalidateNotices()
        val current = state.value
        val server = if (current.loading || current.settings.readFailed) repository.activeServer else current.activeServer
        if (server == null) return
        if (repository.activeServer == server && networkPermission()) repository.reconnect()
        else connect(server)
    }

    fun noteDeparture() = repository.noteDeparture()

    fun onForeground(canReturn: Boolean = false) {
        refreshNetworkPermission()
        refreshAfterReturn(canReturn && repository.consumeReturn())
    }

    fun onVisibleReturn(): Boolean {
        if (!repository.consumeReturn()) return false
        refreshAfterReturn(rearmRecovery = true)
        return true
    }

    private fun refreshAfterReturn(rearmRecovery: Boolean) {
        repository.invalidatePlayerStatus()
        if (rearmRecovery) repository.resumeExhaustedRecovery()
        requestConnectionCheck(ConnectionCheckTrigger.FOREGROUND)
        refreshSleepTimer()
    }

    fun onWindowFocusGained(canReturn: Boolean = false) {
        refreshNetworkPermission()
        if (!canReturn || !onVisibleReturn()) requestConnectionCheck(ConnectionCheckTrigger.WINDOW_FOCUS)
    }

    private fun requestConnectionCheck(trigger: ConnectionCheckTrigger) {
        if (foregroundCheckJob?.isActive == true) return
        val endpoint = mutableState.value.activeServer?.endpoint() ?: repository.activeServer?.endpoint() ?: return
        foregroundCheckJob = viewModelScope.launch {
            // Merge resume/unlock/focus bursts, including checks that would otherwise finish quickly.
            delay(300)
            val current = state.first { !it.loading }
            if (!current.saving && current.activeServer?.endpoint() == endpoint &&
                repository.activeServer?.endpoint() == endpoint) repository.checkConnection(trigger)
        }
    }

    fun setSleepEnabled(enabled: Boolean) {
        if (!state.value.settingsEditable || sleepTimer?.state?.value?.editable != true) return
        viewModelScope.launch {
            sleepTimer.setEnabled(enabled)
            if (enabled) confirmWaitingTimerPlayback()
        }
    }

    fun setSleepMinutes(minutes: Int): Boolean {
        if (minutes !in 1..MAX_SLEEP_MINUTES || !state.value.settingsEditable ||
            sleepTimer?.state?.value?.editable != true) return false
        viewModelScope.launch {
            sleepTimer.setMinutes(minutes)
            confirmWaitingTimerPlayback()
        }
        return true
    }

    private fun confirmWaitingTimerPlayback() {
        val timer = sleepTimer?.state?.value ?: return
        if (timer.waiting && timer.settings.record?.matches(repository.activeServer) == true) repository.confirmTimerPlayback()
    }
    fun refreshSleepTimer() { sleepTimer?.retry() }

    fun setLocalListening(enabled: Boolean) {
        if (enabled) {
            if (networkPermission() && !state.value.saving) localStream?.enable(state.value.activeServer, repository.state.value)
        } else localStream?.disable()
    }

    fun perform(action: PlayerAction) = repository.perform(action)
    fun performQueue(action: PlayerAction.QueueAction, context: QueueActionContext) =
        repository.performQueue(action, context)
    fun retryQueue(sessionId: Long) = repository.retryQueue(sessionId)

    fun loadBrowser() = repository.loadBrowser()
    fun browse(path: String, generation: Long) = repository.browse(path, generation)
    fun enqueue(uri: String, replace: Boolean, generation: Long) = repository.enqueue(uri, replace, generation)

    fun refreshNetworkPermission() {
        val allowed = networkPermission()
        if (state.value.networkAllowed == allowed) return
        updateNetworkPermission(allowed)
        // A successful Save connects its own committed selection. A grant during the write
        // must not start the previous selection, while a revoked grant closes it immediately.
        if (allowed && writingSettings) return
        invalidateNotices()
        connect(state.value.activeServer)
    }

    private fun updateNetworkPermission(allowed: Boolean) {
        mutableState.update { current ->
            val editor = current.editor
            current.copy(networkAllowed = allowed, editor =
                if (allowed && !current.networkAllowed && editor?.testError == ConnectionError.PERMISSION_REQUIRED) {
                    editor.copy(testError = null)
                } else editor)
        }
    }

    private fun connect(server: ServerProfile?, notifyToken: Long? = null) {
        foregroundCheckJob?.cancel()
        localStream?.disable()
        desiredEndpoint = server?.endpoint()
        pendingConnectionNotice = notifyToken
        desiredNetworkAllowed = networkPermission()
        updateNetworkPermission(desiredNetworkAllowed)
        repository.connect(server, desiredNetworkAllowed)
    }

    override fun onCleared() {
        testJob?.cancel()
        if (sharedRepository == null) repository.close()
    }

    private fun ServerProfile.endpoint() = listOf(id, host, mpdPort.toString(), streamPort.toString(), credentialRevision)
}
