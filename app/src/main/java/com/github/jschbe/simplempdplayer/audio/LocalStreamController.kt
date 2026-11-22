package com.github.jschbe.simplempdplayer.audio

import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.mpd.ConnectionState
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import com.github.jschbe.simplempdplayer.mpd.MpdSnapshot
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Builds only the configured origin and root path; never accepts a URL from a controller. */
fun ServerProfile.streamUri(): URI {
    require(isValid())
    val normalized = requireNotNull(ServerInput(name, host, mpdPort.toString(), streamPort.toString()).toProfile(id))
    return URI("http", null, normalized.host, normalized.streamPort, "/", null, null)
}

enum class StreamPhase { OFF, CONNECTING, BUFFERING, ACTIVE, WAITING_FOR_MPD, INTERRUPTED, ERROR }
enum class StreamError { UNAVAILABLE, TIMEOUT, FORMAT, PERMISSION, CONNECTION_LOST, START_FAILED }
data class StreamState(val phase: StreamPhase = StreamPhase.OFF, val error: StreamError? = null) {
    val enabled: Boolean get() = phase != StreamPhase.OFF && phase != StreamPhase.ERROR
}
data class StreamRequest(val generation: Long, val server: ServerProfile, val uri: URI, val snapshot: MpdSnapshot)

/** Only local operations exist here. This interface deliberately has no MPD command capability. */
interface StreamBackend {
    fun start(generation: Long)
    /** Must release an attached player synchronously, before a server switch can proceed. */
    fun stop()
}

/** Main-thread, process-local intent. No persistence or automatic retry/resumption. */
class LocalStreamController(private val backend: StreamBackend) {
    private val mutableState = MutableStateFlow(StreamState())
    val state = mutableState.asStateFlow()
    private val mutableRequest = MutableStateFlow<StreamRequest?>(null)
    val request = mutableRequest.asStateFlow()
    private var generation = 0L

    fun enable(server: ServerProfile?, connection: ConnectionState) {
        if (state.value.enabled || server == null || connection.status != ConnectionStatus.CONNECTED) return
        val snapshot = connection.snapshot ?: return
        val uri = runCatching { server.streamUri() }.getOrElse { return }
        val token = ++generation
        mutableRequest.value = StreamRequest(token, server, uri, snapshot)
        mutableState.value = StreamState(StreamPhase.CONNECTING)
        try {
            backend.start(token)
        } catch (_: SecurityException) {
            fail(token, StreamError.PERMISSION)
        } catch (_: IllegalStateException) {
            fail(token, StreamError.START_FAILED)
        }
    }

    fun disable() {
        ++generation
        mutableRequest.value = null
        mutableState.value = StreamState()
        backend.stop()
    }

    fun connectionChanged(server: ServerProfile?, connection: ConnectionState) {
        val current = request.value ?: return
        if (server != current.server || connection.snapshot == null ||
            (connection.status != ConnectionStatus.CONNECTED && !connection.recovering)) {
            fail(current.generation, StreamError.CONNECTION_LOST)
        } else {
            mutableRequest.value = current.copy(snapshot = connection.snapshot)
        }
    }

    fun report(token: Long, phase: StreamPhase) {
        if (request.value?.generation == token) mutableState.value = StreamState(phase)
    }

    fun fail(token: Long, error: StreamError) {
        if (request.value?.generation != token) return
        disable()
        mutableState.value = StreamState(StreamPhase.ERROR, error)
    }

    fun serviceDestroyed(token: Long) {
        if (request.value?.generation == token) disable()
    }
}
