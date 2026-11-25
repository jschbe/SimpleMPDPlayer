package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.CredentialException
import com.github.jschbe.simplempdplayer.data.NoMpdCredentials
import com.github.jschbe.simplempdplayer.timer.TimerError
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
enum class ConnectionFailureSource { CONNECT, CONTROL, IDLE }
enum class ConnectionCheckTrigger { FOREGROUND, WINDOW_FOCUS, NETWORK, QUEUE_RETRY, TIMER }
enum class ConnectionError {
    REFUSED, UNREACHABLE, TIMEOUT, INVALID_RESPONSE, LOST, MPD_REJECTED, NETWORK, PERMISSION_REQUIRED,
    RESPONSE_LIMIT, QUEUE_CHANGED,
    PASSWORD_REJECTED, MPD_PERMISSION, CREDENTIAL_MISSING, CREDENTIAL_UNREADABLE, PASSWORD_REENTRY, INVALID_PASSWORD,
}

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val error: ConnectionError? = null,
    val snapshot: MpdSnapshot? = null,
    val commandPending: Boolean = false,
    val commandError: ConnectionError? = null,
    val failureSource: ConnectionFailureSource? = null,
    val recovering: Boolean = false,
    val retryAttempt: Int = 0,
    val retriesExhausted: Boolean = false,
    val waitingForNetwork: Boolean = false,
    val idleConnected: Boolean = false,
    val sessionId: Long? = null,
    val playerConfirmedAtMillis: Long? = null,
    val commandErrorFromQueue: Boolean = false,
) {
    private val confirmedPlayerStatus: Boolean
        get() = status == ConnectionStatus.CONNECTED && !waitingForNetwork &&
            snapshot != null && playerConfirmedAtMillis != null

    val livePlayerStatus: Boolean get() = confirmedPlayerStatus && idleConnected

    fun hasConfirmedPlayerStatus(nowMillis: Long): Boolean =
        confirmedPlayerStatus && (idleConnected || nowMillis - requireNotNull(playerConfirmedAtMillis) in 0..5_000)
}

/** Main-scope owner of two technical connections to exactly one active server. */
class MpdRepository(
    private val scope: CoroutineScope,
    private val connectionFactory: () -> MpdConnection = { MpdClient() },
    private val pingIntervalMillis: Long = 15_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val diagnostics: (String) -> Unit = {},
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val resolvePassword: suspend (ServerProfile) -> String? = NoMpdCredentials::resolve,
) {
    private class Session(val command: MpdConnection, var events: MpdConnection?, val work: Job) {
        val operations = Mutex()
        var browserRequest = 0L
        var lastUnstableQueueRead: Long? = null
        var closed = false
        var listener: Job? = null
        var heartbeat: Job? = null
        var healthCheck: Job? = null
        var healthCheckEpoch = -1L
        fun close() {
            closed = true
            command.close()
            events?.close()
            work.cancel()
        }
    }

    private val mutableState = MutableStateFlow(ConnectionState())
    val state = mutableState.asStateFlow()
    private val mutableBrowser = MutableStateFlow(BrowserState())
    val browser = mutableBrowser.asStateFlow()
    var activeServer: ServerProfile? = null
        private set
    private var session: Session? = null
    private var recoveryJob: Job? = null
    private var generation = 0L
    private var departureGeneration: Long? = null
    private var playerStatusEpoch = 0L
    private var nextSessionId = 0L
    // Keep the first recovery readback small after an unfinished range closed the control socket.
    private var queueReadsSuspended = false
    private var limitedCurrentSong: MpdStatus? = null
    private var retryAttempt = 0
    private val networkAvailable = MutableStateFlow(true)

    /** Probe only greeting/ping; never replace the active command/event session. */
    suspend fun testConnection(
        server: ServerProfile,
        password: suspend (ServerProfile) -> String? = resolvePassword,
    ): ConnectionError? {
        val probe = connectionFactory()
        return try {
            probe.openMpd(server, password)
            readStatus(probe)
            null
        } catch (error: IOException) {
            classify(error)
        } catch (_: SecurityException) {
            ConnectionError.PERMISSION_REQUIRED
        } finally {
            probe.close()
        }
    }

    fun connect(server: ServerProfile?, networkAllowed: Boolean = true) {
        close()
        if (server == null) return
        activeServer = server
        diagnostics("selected generation=$generation")
        if (!networkAllowed) {
            mutableState.value = ConnectionState(ConnectionStatus.FAILED, ConnectionError.PERMISSION_REQUIRED)
            return
        }
        if (!networkAvailable.value) {
            mutableState.value = ConnectionState(ConnectionStatus.FAILED, recovering = true, waitingForNetwork = true)
            scheduleRecovery()
        } else {
            startConnection()
        }
    }

    internal fun invalidateCredential(old: ServerProfile) {
        val active = activeServer ?: return
        if (active.id == old.id && active.credentialRevision == old.credentialRevision &&
            active.sameControlEndpoint(old)
        ) close()
    }

    private fun startConnection() {
        val token = generation
        val work = scope.launch(start = CoroutineStart.LAZY) { openControl(token) }
        recoveryJob = work
        work.invokeOnCompletion { if (generation == token && recoveryJob === work) {
            recoveryJob = null
            if (state.value.recovering) scheduleRecovery()
        } }
        work.start()
    }

    /** Manual retry resets the budget without revoking same-server local listening. */
    fun reconnect() {
        if (activeServer == null) return
        cancelConnections()
        mutableBrowser.value = BrowserState(generation = browser.value.generation + 1)
        mutableState.update { it.copy(status = ConnectionStatus.CONNECTING, error = null, commandPending = false,
            commandError = null, recovering = true, retryAttempt = 0, retriesExhausted = false,
            waitingForNetwork = !networkAvailable.value, idleConnected = false, sessionId = null,
            playerConfirmedAtMillis = null) }
        if (networkAvailable.value) startConnection() else scheduleRecovery()
    }

    private suspend fun openControl(token: Long) {
        val server = activeServer ?: return
        if (token != generation) return
        val current = Session(connectionFactory(), connectionFactory(), SupervisorJob(scope.coroutineContext[Job]))
        session = current
        mutableState.update { it.copy(status = ConnectionStatus.CONNECTING, commandPending = false, sessionId = ++nextSessionId) }
        try {
            current.command.openMpd(server, resolvePassword)
            current.command.ping()
            current.operations.withLock { refresh(current, forceQueue = true) }
            if (!isCurrent(current)) return
            current.heartbeat = scope.launch(current.work) {
                try {
                    while (isActive) {
                        delay(pingIntervalMillis)
                        if (networkAvailable.value) current.operations.withLock { current.command.ping() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IOException) { fail(current, classify(error)) }
                catch (_: SecurityException) { fail(current, ConnectionError.PERMISSION_REQUIRED) }
            }
            openIdle(current, forceQueue = false)
        } catch (cancelled: CancellationException) {
            current.close()
            throw cancelled
        } catch (error: IOException) {
            fail(current, classify(error), ConnectionFailureSource.CONNECT)
        } catch (_: SecurityException) {
            fail(current, ConnectionError.PERMISSION_REQUIRED, ConnectionFailureSource.CONNECT)
        }
    }

    private suspend fun openIdle(current: Session, forceQueue: Boolean = true) {
        if (!isCurrent(current)) return
        val server = activeServer ?: return
        val events = current.events ?: connectionFactory().also { current.events = it }
        try {
            events.openMpd(server, resolvePassword)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { idleFailed(current, events, classify(error)); return }
        catch (_: SecurityException) { idleFailed(current, events, ConnectionError.PERMISSION_REQUIRED); return }
        if (!isCurrent(current) || current.events !== events) return
        // Subscribe before readback so changes around recovery cannot fall into an idle gap.
        current.listener = scope.launch(current.work) {
            while (isActive && isCurrent(current) && current.events === events) {
                val changes = try {
                    events.idle().also { if (it.isEmpty()) throw InvalidMpdResponse() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IOException) { idleFailed(current, events, classify(error)); return@launch }
                catch (_: SecurityException) { idleFailed(current, events, ConnectionError.PERMISSION_REQUIRED); return@launch }
                try {
                    if ("player" in changes) invalidatePlayerStatus()
                    current.operations.withLock { refresh(current) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IOException) { fail(current, classify(error)); return@launch }
                catch (_: SecurityException) { fail(current, ConnectionError.PERMISSION_REQUIRED); return@launch }
            }
        }
        try {
            current.operations.withLock { refresh(current, forceQueue = forceQueue) }
            if (!isCurrent(current) || current.events !== events) return
            retryAttempt = 0
            mutableState.update { it.copy(error = null, recovering = false, retryAttempt = 0,
                retriesExhausted = false, waitingForNetwork = !networkAvailable.value, idleConnected = true) }
            diagnostics("restored generation=$generation idle=true")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { fail(current, classify(error)) }
        catch (_: SecurityException) { fail(current, ConnectionError.PERMISSION_REQUIRED) }
    }

    private fun idleFailed(current: Session, events: MpdConnection, error: ConnectionError) {
        if (!isCurrent(current) || current.events !== events) return
        diagnostics("failure generation=$generation source=IDLE category=$error attempt=$retryAttempt")
        events.close()
        current.events = null
        playerStatusEpoch++
        mutableState.update { it.copy(error = error, failureSource = ConnectionFailureSource.IDLE,
            idleConnected = false, recovering = retryable(error), waitingForNetwork = !networkAvailable.value,
            playerConfirmedAtMillis = null) }
        scheduleRecovery()
    }

    private fun retryable(error: ConnectionError) = error in setOf(ConnectionError.REFUSED,
        ConnectionError.UNREACHABLE, ConnectionError.TIMEOUT, ConnectionError.LOST, ConnectionError.NETWORK,
        ConnectionError.RESPONSE_LIMIT)

    /** One bounded repair loop for both full reconnects and isolated event-socket repairs. */
    private fun scheduleRecovery() {
        if (recoveryJob?.isActive == true || !state.value.recovering || activeServer == null) return
        val token = generation
        recoveryJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (token == generation && state.value.recovering && retryAttempt < 5) {
                    networkAvailable.first { it }
                    delay(1_000L shl retryAttempt)
                    if (!networkAvailable.value) continue
                    retryAttempt++
                    diagnostics("retry generation=$generation attempt=$retryAttempt idleOnly=${session?.let(::isCurrent) == true}")
                    mutableState.update { it.copy(retryAttempt = retryAttempt, waitingForNetwork = false) }
                    val current = session
                    if (current != null && isCurrent(current)) openIdle(current) else openControl(token)
                }
                if (token == generation && state.value.recovering && retryAttempt == 5) {
                    diagnostics("exhausted generation=$generation attempt=$retryAttempt")
                    mutableState.update { it.copy(recovering = false, retriesExhausted = true, waitingForNetwork = !networkAvailable.value) }
                }
            } finally {
                if (token == generation) recoveryJob = null
            }
        }.also { it.start() }
    }

    internal fun noteDeparture() {
        if (departureGeneration == null) departureGeneration = generation
    }

    internal fun consumeReturn(): Boolean {
        val departure = departureGeneration ?: return false
        departureGeneration = null
        return departure == generation
    }

    /** Called once for a qualified return, never for an ordinary window focus or network event. */
    internal fun resumeExhaustedRecovery() {
        val current = state.value
        val error = current.error ?: return
        val repairNeeded = current.status == ConnectionStatus.FAILED ||
            (current.failureSource == ConnectionFailureSource.IDLE && !current.idleConnected)
        if (activeServer == null || !repairNeeded || !current.retriesExhausted || current.recovering ||
            recoveryJob?.isActive == true || !retryable(error) || error == ConnectionError.RESPONSE_LIMIT
        ) return
        retryAttempt = 0
        diagnostics("rearm generation=$generation source=${current.failureSource} offline=${!networkAvailable.value}")
        mutableState.update { it.copy(recovering = true, retriesExhausted = false, retryAttempt = 0) }
        scheduleRecovery()
    }

    internal fun invalidatePlayerStatus() {
        // An already-started read cannot attest to state after this transition.
        playerStatusEpoch++
        mutableState.update { it.copy(playerConfirmedAtMillis = null) }
    }

    fun confirmTimerPlayback() {
        if (!state.value.hasConfirmedPlayerStatus(elapsedMillis())) checkConnection(ConnectionCheckTrigger.TIMER)
    }

    /** Connectivity callbacks are hints; LAN-only networks do not need Internet validation. */
    fun networkChanged(available: Boolean) {
        val changed = networkAvailable.value != available
        if (changed) playerStatusEpoch++
        networkAvailable.value = available
        mutableState.update {
            it.copy(waitingForNetwork = activeServer != null && !available,
                sessionId = if (changed && it.sessionId != null) ++nextSessionId else it.sessionId,
                playerConfirmedAtMillis = it.playerConfirmedAtMillis.takeUnless { changed })
        }
        if (changed && available) checkConnection(ConnectionCheckTrigger.NETWORK)
    }

    /** One foreground/network-return health check, serialized with commands and other checks. */
    fun checkConnection(trigger: ConnectionCheckTrigger = ConnectionCheckTrigger.FOREGROUND) {
        if (activeServer != null) diagnostics("check generation=$generation trigger=$trigger status=${state.value.status} " +
            "recovering=${state.value.recovering} exhausted=${state.value.retriesExhausted} offline=${!networkAvailable.value}")
        val current = session ?: return
        val epoch = playerStatusEpoch
        if (!isCurrent(current) || state.value.status != ConnectionStatus.CONNECTED || !networkAvailable.value ||
            (current.healthCheck?.isActive == true && current.healthCheckEpoch == epoch)
        ) return
        current.healthCheckEpoch = epoch
        current.healthCheck = scope.launch(current.work) {
            try {
                current.operations.withLock {
                    if (!isCurrent(current) || !networkAvailable.value || current.healthCheckEpoch != epoch) return@withLock
                    current.command.ping()
                    if (trigger == ConnectionCheckTrigger.QUEUE_RETRY) queueReadsSuspended = false
                    refresh(current, forceQueue = trigger != ConnectionCheckTrigger.TIMER)
                    if (isCurrent(current)) diagnostics("checked generation=$generation trigger=$trigger idle=${state.value.idleConnected}")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) { fail(current, classify(error)) }
            catch (_: SecurityException) { fail(current, ConnectionError.PERMISSION_REQUIRED) }
        }
    }

    /** The optional authorization is rechecked after waiting for IO/locks, before mutation. */
    fun perform(action: PlayerAction, authorized: () -> Boolean = { true }): Job? =
        if (action is PlayerAction.QueueAction) null else performAuthorized(action, null, authorized)

    fun performQueue(action: PlayerAction.QueueAction, context: QueueActionContext): Job? =
        performAuthorized(action, context) { state.value.sessionId == context.sessionId && networkAvailable.value }

    fun retryQueue(sessionId: Long) {
        if (state.value.sessionId != sessionId || state.value.commandPending) return
        checkConnection(ConnectionCheckTrigger.QUEUE_RETRY)
    }

    private fun performAuthorized(
        action: PlayerAction,
        queueContext: QueueActionContext?,
        authorized: () -> Boolean,
    ): Job? {
        val current = session ?: return null
        if (!isCurrent(current) || state.value.status != ConnectionStatus.CONNECTED ||
            state.value.commandPending || !networkAvailable.value || !authorized()
        ) return null
        mutableState.update { it.copy(commandPending = true, commandError = null, commandErrorFromQueue = queueContext != null) }
        return scope.launch(current.work) {
            try {
                current.operations.withLock {
                    if (!isCurrent(current) || !authorized()) return@withLock
                    try {
                        val stillAuthorized = { isCurrent(current) && authorized() }
                        if (action is PlayerAction.QueueAction && queueContext != null) {
                            executeQueueAction(current.command, action, queueContext, stillAuthorized)
                        } else {
                            executeAction(current.command, action, stillAuthorized)
                        }
                    } catch (error: MpdAckException) {
                        if (session === current) mutableState.update { it.copy(commandError = classify(error)) }
                    }
                    // The server remains authoritative, including after a rejected queue operation.
                    refresh(current)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                fail(current, classify(error))
            } catch (_: SecurityException) {
                fail(current, ConnectionError.PERMISSION_REQUIRED)
            } finally {
                if (session === current) mutableState.update { it.copy(commandPending = false) }
            }
        }
    }

    fun loadBrowser() {
        val value = browser.value
        if (!value.loaded && !value.loading && value.error == null && !value.listingLimited) {
            browse(value.path, value.generation)
        }
    }

    fun browse(path: String, generation: Long) {
        val current = session ?: return
        val previous = browser.value
        if (!isCurrent(current) || state.value.status != ConnectionStatus.CONNECTED || generation != previous.generation) return
        // Accept only the current directory, an ancestor or a directory from the current listing.
        if (path != previous.path && path.isNotEmpty() && !previous.path.startsWith("$path/") &&
            previous.entries.none { it is LibraryEntry.Directory && it.uri == path }
        ) return
        val request = ++current.browserRequest
        mutableBrowser.value = BrowserState(generation = generation, path = path, loading = true, queueMessage = previous.queueMessage)
        scope.launch(current.work) {
            try {
                current.operations.withLock {
                    if (!isCurrent(current) || request != current.browserRequest) return@withLock
                    val fields = if (path.isEmpty()) current.command.execute("lsinfo") else current.command.execute("lsinfo", path)
                    currentCoroutineContext().ensureActive()
                    val listing = MpdBrowserData.directory(fields)
                    if (isCurrent(current) && request == current.browserRequest) {
                        mutableBrowser.update { it.copy(entries = listing.entries, hasPlaylists = listing.hasPlaylists, loading = false, loaded = true) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MpdResponseLimit) {
                if (isCurrent(current) && request == current.browserRequest) {
                    mutableBrowser.update { it.copy(listingLimited = true) }
                }
                // There may still be unread fields and the terminator on this socket.
                fail(current, ConnectionError.RESPONSE_LIMIT)
            } catch (error: MpdAckException) {
                if (isCurrent(current) && request == current.browserRequest) {
                    mutableBrowser.update { it.copy(loading = false, error = classify(error)) }
                }
            } catch (error: IOException) {
                fail(current, classify(error))
            } catch (_: SecurityException) {
                fail(current, ConnectionError.PERMISSION_REQUIRED)
            }
        }
    }

    /** clear/add/readback share the same lock as every other app command and idle refresh. */
    fun enqueue(uri: String, replace: Boolean, generation: Long) {
        val current = session ?: return
        if (!isCurrent(current) || state.value.status != ConnectionStatus.CONNECTED || state.value.commandPending ||
            browser.value.generation != generation || browser.value.entries.none { it.uri == uri }
        ) return
        mutableState.update { it.copy(commandPending = true, commandError = null) }
        mutableBrowser.update { it.copy(queueMessage = null) }
        scope.launch(current.work) {
            var clearStarted = false
            var cleared = false
            var added = false
            var rejection: BrowserQueueMessage? = null
            fun failedMessage() = when {
                rejection != null -> rejection!!
                added -> BrowserQueueMessage.REFRESH_FAILED
                cleared -> BrowserQueueMessage.REPLACE_PARTIAL
                clearStarted -> BrowserQueueMessage.REPLACE_UNCONFIRMED
                else -> BrowserQueueMessage.ADD_FAILED
            }
            fun message(value: BrowserQueueMessage) {
                if (session === current) mutableBrowser.update { it.copy(queueMessage = value) }
            }
            try {
                current.operations.withLock {
                    if (!isCurrent(current)) return@withLock
                    try {
                        if (replace) {
                            clearStarted = true
                            current.command.execute("clear")
                            cleared = true
                            currentCoroutineContext().ensureActive()
                            if (!isCurrent(current)) return@withLock
                            queueReadsSuspended = false
                        }
                        current.command.execute("add", uri)
                        added = true
                        currentCoroutineContext().ensureActive()
                    } catch (error: MpdAckException) {
                        val accessDenied = error.code == ACK_PASSWORD || error.code == ACK_PERMISSION
                        if (accessDenied && isCurrent(current)) mutableState.update {
                            it.copy(commandError = classify(error), commandErrorFromQueue = false)
                        }
                        rejection = if (replace && !cleared) BrowserQueueMessage.CLEAR_FAILED else failedMessage()
                        message(rejection!!)
                        refresh(current)
                        return@withLock
                    }
                    refresh(current)
                    if (isCurrent(current)) message(if (replace) BrowserQueueMessage.REPLACED else BrowserQueueMessage.APPENDED)
                }
            } catch (cancelled: CancellationException) {
                // A switch discards notices; a failed idle connection may interrupt a partial write.
                message(failedMessage())
                throw cancelled
            } catch (error: IOException) {
                message(failedMessage())
                fail(current, classify(error))
            } catch (_: SecurityException) {
                message(failedMessage())
                fail(current, ConnectionError.PERMISSION_REQUIRED)
            } finally {
                if (session === current) mutableState.update { it.copy(commandPending = false) }
            }
        }
    }

    private fun isCurrent(current: Session) = session === current && !current.closed

    private suspend fun executeAction(client: MpdConnection, action: PlayerAction, authorized: () -> Boolean) {
        when (action) {
            PlayerAction.PlayPause, PlayerAction.Play, PlayerAction.Pause -> {
                val playback = MpdData.status(client.execute("status")).playback
                // A disabled receiver or switched session must not mutate after a delayed read.
                if (!authorized()) return
                when (playback) {
                    PlaybackState.PLAYING -> if (action != PlayerAction.Play) client.execute("pause", "1")
                    PlaybackState.PAUSED -> if (action != PlayerAction.Pause) client.execute("pause", "0")
                    PlaybackState.STOPPED -> if (action != PlayerAction.Pause) client.execute("play")
                    PlaybackState.UNKNOWN -> throw InvalidMpdResponse()
                }
            }
            PlayerAction.Stop -> client.execute("stop")
            PlayerAction.Previous -> client.execute("previous")
            PlayerAction.Next -> client.execute("next")
            is PlayerAction.QueueAction -> error("Queue action requires a session context")
            is PlayerAction.Random -> client.execute("random", if (action.enabled) "1" else "0")
            is PlayerAction.Repeat -> {
                // Fresh status avoids relying on an idle snapshot that may already be stale.
                // The caller holds operations across this entire sequence and its readback.
                val status = MpdData.status(client.execute("status"))
                val repeat = action.mode != RepeatMode.NONE
                val single = if (action.mode == RepeatMode.SINGLE) SingleMode.ON else SingleMode.OFF
                if (!authorized()) return
                if (status.repeat != repeat) client.execute("repeat", if (repeat) "1" else "0")
                currentCoroutineContext().ensureActive()
                if (!authorized()) return
                if (status.single != single) client.execute("single", if (single == SingleMode.ON) "1" else "0")
            }
            is PlayerAction.Volume -> {
                if (action.value in 0..100 && state.value.snapshot?.status?.volume != null) {
                    client.execute("setvol", action.value.toString())
                }
            }
            is PlayerAction.Seek -> {
                val status = MpdData.status(client.execute("status"))
                if (action.seconds.isFinite() && action.seconds >= 0 && status.duration != null &&
                    status.songId == action.songId && status.playback != PlaybackState.STOPPED
                ) {
                    client.execute("seekcur", action.seconds.coerceAtMost(status.duration).toString())
                }
            }
        }
    }

    private suspend fun executeQueueAction(
        client: MpdConnection,
        action: PlayerAction.QueueAction,
        context: QueueActionContext,
        authorized: () -> Boolean,
    ) {
        val snapshot = state.value.snapshot ?: return
        if (action == PlayerAction.Clear) {
            if (!snapshot.hasQueuedSongs) return
            val version = context.clearVersion
            if (version == null && snapshot.queueAvailability == QueueAvailability.COMPLETE) return
            if (version != null && readStatus(client).queueVersion != version) {
                if (authorized()) mutableState.update { it.copy(commandError = ConnectionError.QUEUE_CHANGED) }
                return
            }
            if (authorized()) {
                client.execute("clear")
                currentCoroutineContext().ensureActive()
                if (authorized()) queueReadsSuspended = false
            }
            return
        }
        if (snapshot.queueAvailability != QueueAvailability.COMPLETE) return
        val id = when (action) {
            is PlayerAction.PlayId -> action.id
            is PlayerAction.DeleteId -> action.id
            is PlayerAction.MoveId -> action.id
            PlayerAction.Clear -> return
        }
        val index = snapshot.queue.indexOfFirst { it.id == id }
        if (index == -1) return
        when (action) {
            is PlayerAction.PlayId -> client.execute("playid", id.toString())
            is PlayerAction.DeleteId -> client.execute("deleteid", id.toString())
            is PlayerAction.MoveId -> {
                val target = index + action.offset
                if (action.offset !in setOf(-1, 1) || target !in snapshot.queue.indices) return
                if (!readStatus(client).sameQueue(snapshot.status)) {
                    if (authorized()) mutableState.update { it.copy(commandError = ConnectionError.QUEUE_CHANGED) }
                    return
                }
                if (authorized()) client.execute("moveid", id.toString(), target.toString())
            }
            PlayerAction.Clear -> Unit
        }
    }

    private data class QueueRead(
        val status: MpdStatus,
        val availability: QueueAvailability,
        val entries: List<QueueEntry> = emptyList(),
    )

    private fun MpdStatus.sameQueue(other: MpdStatus): Boolean =
        queueVersion == other.queueVersion && queueLength == other.queueLength

    private suspend fun readStatus(client: MpdConnection): MpdStatus =
        MpdData.status(client.execute("status")).also {
            if (it.queueVersion == null || it.queueLength == null) throw InvalidMpdResponse()
        }

    private suspend fun readQueue(
        current: Session,
        before: MpdStatus,
        deadline: Long,
    ): QueueRead {
        val client = current.command
        val length = checkNotNull(before.queueLength)
        if (length == 0) {
            queueReadsSuspended = false
            return QueueRead(before, QueueAvailability.COMPLETE)
        }
        if (length > MAX_QUEUE_SONGS || queueReadsSuspended) return QueueRead(before, QueueAvailability.LIMIT)
        val entries = ArrayList<QueueEntry>(length)
        val ids = HashSet<Long>()
        var confirmed = before
        var characters = 0
        var fieldCount = 0
        for (start in 0 until length step QUEUE_BATCH_SONGS) {
            currentCoroutineContext().ensureActive()
            if (nanoTime() - deadline >= 0) return QueueRead(confirmed, QueueAvailability.LIMIT)
            val end = minOf(start + QUEUE_BATCH_SONGS, length)
            val fields = try {
                client.execute("playlistinfo", "$start:$end")
            } catch (limit: MpdResponseLimit) {
                if (isCurrent(current)) queueReadsSuspended = true
                throw limit
            } catch (ack: MpdAckException) {
                // MPD can reject a range whose start moved beyond the end of a shrinking queue.
                if (ack.code != 2 || ack.command != "playlistinfo") throw ack
                currentCoroutineContext().ensureActive()
                val after = readStatus(client)
                if (before.sameQueue(after)) throw ack
                return QueueRead(after, QueueAvailability.CHANGING)
            }
            currentCoroutineContext().ensureActive()
            if (nanoTime() - deadline >= 0) return QueueRead(confirmed, QueueAvailability.LIMIT)
            confirmed = readStatus(client)
            if (!before.sameQueue(confirmed)) return QueueRead(confirmed, QueueAvailability.CHANGING)
            val batch = MpdData.queue(fields, start)
            if (batch.size != end - start || batch.any { !ids.add(it.id) }) throw InvalidMpdResponse()
            fieldCount += fields.size
            characters += fields.sumOf { (key, value) -> key.length + value.length + 3 }
            if (characters > MAX_QUEUE_CHARACTERS || fieldCount > MAX_QUEUE_FIELDS ||
                nanoTime() - deadline >= 0
            ) return QueueRead(confirmed, QueueAvailability.LIMIT)
            entries.addAll(batch)
        }
        return QueueRead(confirmed, QueueAvailability.COMPLETE, entries)
    }

    private suspend fun readCurrentSong(current: Session, status: MpdStatus): MpdSong? {
        if (limitedCurrentSong?.let { it.songId == status.songId } == true) return null
        limitedCurrentSong = null
        return try {
            MpdData.currentSong(current.command.execute("currentsong"))
        } catch (limit: MpdResponseLimit) {
            if (isCurrent(current)) limitedCurrentSong = status
            throw limit
        }
    }

    /** All callers hold this session's operations lock, so command + refresh cannot interleave. */
    private suspend fun refresh(current: Session, forceQueue: Boolean = false) {
        if (!isCurrent(current)) return
        val client = current.command
        val epoch = playerStatusEpoch
        val startedOnline = networkAvailable.value
        val deadline = nanoTime() + QUEUE_LOAD_NANOS
        var before = readStatus(client)
        val old = state.value.snapshot
        val deferUnstableRead = !forceQueue &&
            current.lastUnstableQueueRead?.let { nanoTime() - it < QUEUE_RETRY_NANOS } == true
        var checkedQueue: QueueRead? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            if (!isCurrent(current)) return
            val queue = checkedQueue ?: when {
                !forceQueue && old != null && old.queueAvailability != QueueAvailability.CHANGING &&
                    before.sameQueue(old.status) -> QueueRead(before, old.queueAvailability, old.queue)
                deferUnstableRead && before.queueLength != 0 -> QueueRead(before, QueueAvailability.CHANGING)
                else -> readQueue(current, before, deadline)
            }
            val canRetry = attempt < 2 && nanoTime() - deadline < 0
            if (queue.availability == QueueAvailability.CHANGING && !queue.status.sameQueue(before) && canRetry) {
                before = queue.status
                return@repeat
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(current)) return
            val song = readCurrentSong(current, queue.status)
            currentCoroutineContext().ensureActive()
            val after = readStatus(client)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(current)) return
            val sameSong = queue.status.songId == after.songId
            val sameQueue = queue.status.sameQueue(after)
            val songMatches = sameSong && (limitedCurrentSong != null || song?.id == after.songId)
            if ((!songMatches || !sameQueue) && canRetry && nanoTime() - deadline < 0) {
                checkedQueue = queue.copy(status = after).takeIf { sameQueue }
                before = after
                return@repeat
            }
            if (sameSong && sameQueue && !songMatches) throw InvalidMpdResponse()

            val availability = when {
                after.queueLength == 0 -> QueueAvailability.COMPLETE
                sameQueue -> queue.availability
                else -> QueueAvailability.CHANGING
            }
            if (after.queueLength == 0) queueReadsSuspended = false
            if (!deferUnstableRead) {
                current.lastUnstableQueueRead = nanoTime().takeIf { availability == QueueAvailability.CHANGING }
            }
            val snapshot = MpdSnapshot(after, song.takeIf { songMatches },
                queue.entries.takeIf { sameQueue && availability == QueueAvailability.COMPLETE }.orEmpty(),
                nanoTime(), availability)
            mutableState.update { it.copy(status = ConnectionStatus.CONNECTED,
                error = if (it.failureSource == ConnectionFailureSource.IDLE && !it.idleConnected) it.error else null,
                snapshot = snapshot,
                playerConfirmedAtMillis = elapsedMillis().takeIf {
                    startedOnline && networkAvailable.value && epoch == playerStatusEpoch
                }) }
            return
        }
    }

    private fun fail(current: Session, error: ConnectionError, source: ConnectionFailureSource = ConnectionFailureSource.CONTROL) {
        if (!isCurrent(current)) return
        diagnostics("failure generation=$generation source=$source category=$error attempt=$retryAttempt")
        current.close()
        mutableState.update { it.copy(status = ConnectionStatus.FAILED, error = error, commandPending = false,
            failureSource = source, idleConnected = false, recovering = retryable(error), sessionId = null,
            playerConfirmedAtMillis = null, waitingForNetwork = !networkAvailable.value) }
        mutableBrowser.update { it.copy(generation = it.generation + 1, entries = emptyList(), loaded = false, loading = false, error = null) }
        scheduleRecovery()
    }

    private fun cancelConnections() {
        ++generation
        recoveryJob?.cancel()
        recoveryJob = null
        retryAttempt = 0
        val previous = session
        session = null
        previous?.close()
    }

    fun close() {
        cancelConnections()
        activeServer = null
        queueReadsSuspended = false
        limitedCurrentSong = null
        mutableState.value = ConnectionState()
        mutableBrowser.value = BrowserState(generation = browser.value.generation + 1)
    }

    private fun classify(error: IOException): ConnectionError = when (error) {
        is InvalidMpdResponse -> ConnectionError.INVALID_RESPONSE
        is MpdResponseLimit -> ConnectionError.RESPONSE_LIMIT
        is MpdAckException -> when (error.code) {
            ACK_PASSWORD -> ConnectionError.PASSWORD_REJECTED
            ACK_PERMISSION -> ConnectionError.MPD_PERMISSION
            else -> ConnectionError.MPD_REJECTED
        }
        is CredentialException -> error.connectionError()
        is SocketTimeoutException -> ConnectionError.TIMEOUT
        is UnknownHostException, is NoRouteToHostException -> ConnectionError.UNREACHABLE
        is ConnectException -> if (error.message.orEmpty().contains("refused", ignoreCase = true)) {
            ConnectionError.REFUSED
        } else ConnectionError.UNREACHABLE
        is EOFException, is SocketException -> ConnectionError.LOST
        else -> ConnectionError.NETWORK
    }

    companion object {
        private const val QUEUE_BATCH_SONGS = 200
        private const val MAX_QUEUE_SONGS = 20_000
        private const val MAX_QUEUE_FIELDS = 300_000
        private const val MAX_QUEUE_CHARACTERS = 8 * 1_024 * 1_024
        private const val QUEUE_LOAD_NANOS = 8_000_000_000L
        private const val QUEUE_RETRY_NANOS = 1_000_000_000L

        /** Independent, bounded one-shot connection. Never toggles playback or touches the queue. */
        suspend fun pauseForTimer(
            server: ServerProfile,
            resolve: suspend (String) -> String = { it },
            factory: () -> MpdConnection = { MpdClient(2_000, 2_000, 2_500) },
            resolvePassword: suspend (ServerProfile) -> String? = NoMpdCredentials::resolve,
        ): TimerError? {
            val client = factory()
            return try {
                kotlinx.coroutines.withTimeout(6_000) {
                    require(server.isValid())
                    client.openMpd(server, resolvePassword) { resolve(server.host) }
                    if (client.execute("pause", "1").isNotEmpty()) throw InvalidMpdResponse()
                }
                null
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                TimerError.TIMEOUT
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (ack: MpdAckException) {
                when (ack.code) {
                    ACK_PASSWORD -> TimerError.PASSWORD_REJECTED
                    ACK_PERMISSION -> TimerError.MPD_PERMISSION
                    else -> TimerError.REJECTED
                }
            } catch (error: CredentialException) {
                if (error.connectionError() == ConnectionError.CREDENTIAL_MISSING) TimerError.CREDENTIAL_MISSING
                else TimerError.CREDENTIAL_UNREADABLE
            } catch (_: SocketTimeoutException) {
                TimerError.TIMEOUT
            } catch (_: InvalidMpdResponse) {
                TimerError.PROTOCOL
            } catch (_: SecurityException) {
                TimerError.PERMISSION
            } catch (_: IOException) {
                TimerError.NETWORK
            } finally { client.close() }
        }
    }

}
