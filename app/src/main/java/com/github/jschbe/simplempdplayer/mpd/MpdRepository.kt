package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerProfile
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
enum class ConnectionError { REFUSED, UNREACHABLE, TIMEOUT, INVALID_RESPONSE, LOST, MPD_REJECTED, NETWORK, PERMISSION_REQUIRED }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val error: ConnectionError? = null,
    val snapshot: MpdSnapshot? = null,
    val commandPending: Boolean = false,
    val commandError: ConnectionError? = null,
)

/** Main-scope owner of two technical connections to exactly one active server. */
class MpdRepository(
    private val scope: CoroutineScope,
    private val connectionFactory: () -> MpdConnection = { MpdClient() },
    private val pingIntervalMillis: Long = 15_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Session(val command: MpdConnection, val events: MpdConnection, val work: Job) {
        val operations = Mutex()
        var browserRequest = 0L
        var closed = false
        fun close() {
            closed = true
            command.close()
            events.close()
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
    private var job: Job? = null

    /** Probe only greeting/ping; never replace the active command/event session. */
    suspend fun testConnection(server: ServerProfile): ConnectionError? {
        val probe = connectionFactory()
        return try {
            probe.connect(server.host, server.mpdPort)
            probe.ping()
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
        if (!networkAllowed) {
            mutableState.value = ConnectionState(ConnectionStatus.FAILED, ConnectionError.PERMISSION_REQUIRED)
            return
        }
        val current = Session(connectionFactory(), connectionFactory(), Job(scope.coroutineContext[Job]))
        session = current
        mutableState.value = ConnectionState(ConnectionStatus.CONNECTING)
        job = scope.launch {
            try {
                coroutineScope {
                    current.command.connect(server.host, server.mpdPort)
                    current.command.ping()
                    current.events.connect(server.host, server.mpdPort)
                    current.operations.withLock { refresh(current) }
                    launch {
                        while (isActive) {
                            val changes = current.events.idle()
                            if (changes.isEmpty()) throw InvalidMpdResponse()
                            current.operations.withLock { refresh(current) }
                        }
                    }
                    while (isActive) {
                        delay(pingIntervalMillis)
                        current.operations.withLock { current.command.ping() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                fail(current, classify(error))
            } catch (_: SecurityException) {
                fail(current, ConnectionError.PERMISSION_REQUIRED)
            } finally {
                current.close()
            }
        }
    }

    fun perform(action: PlayerAction) {
        val current = session ?: return
        if (state.value.status != ConnectionStatus.CONNECTED || state.value.commandPending) return
        mutableState.update { it.copy(commandPending = true, commandError = null) }
        scope.launch(current.work) {
            try {
                current.operations.withLock {
                    if (!isCurrent(current)) return@withLock
                    try {
                        executeAction(current.command, action)
                    } catch (_: MpdAckException) {
                        if (session === current) mutableState.update { it.copy(commandError = ConnectionError.MPD_REJECTED) }
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
        if (!value.loaded && !value.loading && value.error == null) browse(value.path, value.generation)
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
            } catch (_: MpdAckException) {
                if (isCurrent(current) && request == current.browserRequest) {
                    mutableBrowser.update { it.copy(loading = false, error = ConnectionError.MPD_REJECTED) }
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
                        }
                        current.command.execute("add", uri)
                        added = true
                        currentCoroutineContext().ensureActive()
                    } catch (_: MpdAckException) {
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

    private suspend fun executeAction(client: MpdConnection, action: PlayerAction) {
        when (action) {
            PlayerAction.PlayPause -> {
                when (MpdData.status(client.execute("status")).playback) {
                    PlaybackState.PLAYING -> client.execute("pause", "1")
                    PlaybackState.PAUSED -> client.execute("pause", "0")
                    PlaybackState.STOPPED -> client.execute("play")
                    PlaybackState.UNKNOWN -> throw InvalidMpdResponse()
                }
            }
            PlayerAction.Stop -> client.execute("stop")
            PlayerAction.Previous -> client.execute("previous")
            PlayerAction.Next -> client.execute("next")
            PlayerAction.Clear -> client.execute("clear")
            is PlayerAction.Random -> client.execute("random", if (action.enabled) "1" else "0")
            is PlayerAction.Repeat -> client.execute("repeat", if (action.enabled) "1" else "0")
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
            is PlayerAction.PlayId -> if (action.id >= 0) client.execute("playid", action.id.toString())
            is PlayerAction.DeleteId -> if (action.id >= 0) client.execute("deleteid", action.id.toString())
            is PlayerAction.MoveId -> {
                if (action.offset != -1 && action.offset != 1) return
                val queue = MpdData.queue(client.execute("playlistinfo"))
                val index = queue.indexOfFirst { it.id == action.id }
                val target = index + action.offset
                if (index >= 0 && target in queue.indices) {
                    client.execute("moveid", action.id.toString(), target.toString())
                }
            }
        }
    }

    /** All callers hold this session's operations lock, so command + refresh cannot interleave. */
    private suspend fun refresh(current: Session) {
        if (!isCurrent(current)) return
        repeat(3) {
            val before = MpdData.status(current.command.execute("status"))
            val song = MpdData.currentSong(current.command.execute("currentsong"))
            val old = state.value.snapshot
            val queue = if (old == null || before.queueVersion == null || before.queueVersion != old.status.queueVersion) {
                MpdData.queue(current.command.execute("playlistinfo"))
            } else old.queue
            val after = MpdData.status(current.command.execute("status"))
            if (!isCurrent(current)) return
            if (before.queueVersion == after.queueVersion && before.songId == after.songId &&
                song?.id == after.songId && (after.queueLength == null || after.queueLength == queue.size)
            ) {
                val snapshot = MpdSnapshot(after, song, queue, nanoTime())
                mutableState.update { it.copy(status = ConnectionStatus.CONNECTED, error = null, snapshot = snapshot) }
                return
            }
        }
        // Bound retries if another client continuously changes the queue during snapshot loading.
        throw InvalidMpdResponse()
    }

    private fun fail(current: Session, error: ConnectionError) {
        if (!isCurrent(current)) return
        mutableState.update { it.copy(status = ConnectionStatus.FAILED, error = error, commandPending = false) }
        mutableBrowser.update { it.copy(entries = emptyList(), loaded = false, loading = false, error = null) }
        job?.cancel()
        current.close()
    }

    fun close() {
        activeServer = null
        val previous = session
        session = null
        previous?.close()
        job?.cancel()
        job = null
        mutableState.value = ConnectionState()
        mutableBrowser.value = BrowserState(generation = browser.value.generation + 1)
    }

    private fun classify(error: IOException): ConnectionError = when (error) {
        is InvalidMpdResponse -> ConnectionError.INVALID_RESPONSE
        is MpdAckException -> ConnectionError.MPD_REJECTED
        is SocketTimeoutException -> ConnectionError.TIMEOUT
        is UnknownHostException, is NoRouteToHostException -> ConnectionError.UNREACHABLE
        is ConnectException -> if (error.message.orEmpty().contains("refused", ignoreCase = true)) {
            ConnectionError.REFUSED
        } else ConnectionError.UNREACHABLE
        is EOFException, is SocketException -> ConnectionError.LOST
        else -> ConnectionError.NETWORK
    }

    companion object {
        /** Independent, bounded one-shot connection. Never toggles playback or touches the queue. */
        suspend fun pauseForTimer(
            server: ServerProfile,
            resolve: suspend (String) -> String = { it },
            factory: () -> MpdConnection = { MpdClient(2_000, 2_000, 2_500) },
        ): com.github.jschbe.simplempdplayer.timer.TimerError? {
            val client = factory()
            return try {
                kotlinx.coroutines.withTimeout(6_000) {
                    require(server.isValid())
                    val host = resolve(server.host)
                    client.connect(host, server.mpdPort)
                    if (client.execute("pause", "1").isNotEmpty()) throw InvalidMpdResponse()
                }
                null
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                com.github.jschbe.simplempdplayer.timer.TimerError.TIMEOUT
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (ack: MpdAckException) {
                if (ack.code == 3 || ack.code == 4) com.github.jschbe.simplempdplayer.timer.TimerError.AUTHENTICATION
                else com.github.jschbe.simplempdplayer.timer.TimerError.REJECTED
            } catch (_: SocketTimeoutException) {
                com.github.jschbe.simplempdplayer.timer.TimerError.TIMEOUT
            } catch (_: InvalidMpdResponse) {
                com.github.jschbe.simplempdplayer.timer.TimerError.PROTOCOL
            } catch (_: SecurityException) {
                com.github.jschbe.simplempdplayer.timer.TimerError.PERMISSION
            } catch (_: IOException) {
                com.github.jschbe.simplempdplayer.timer.TimerError.NETWORK
            } finally { client.close() }
        }
    }

}
