package com.github.jschbe.simplempdplayer.audio

import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.mpd.*
import org.junit.Assert.*
import org.junit.Test

class LocalStreamControllerTest {
    private val first = ServerInput("First", "localhost").toProfile()!!
    private val second = ServerInput("Second", "example.invalid").toProfile()!!
    private val snapshot = MpdSnapshot(MpdStatus(playback = PlaybackState.PAUSED), null, emptyList(), 0)
    private val connected = ConnectionState(ConnectionStatus.CONNECTED, snapshot = snapshot)
    private class Backend : StreamBackend {
        val starts = mutableListOf<Long>()
        var stops = 0
        var failure: RuntimeException? = null
        override fun start(generation: Long) { failure?.let { throw it }; starts.add(generation) }
        override fun stop() { stops++ }
    }

    @Test fun urlUsesOnlyValidatedProfileHostAndStreamPort() {
        listOf(
            "localhost" to "http://localhost:8123/",
            "203.0.113.9" to "http://203.0.113.9:8123/",
            "[2001:db8::9]" to "http://[2001:db8::9]:8123/",
            "müsik.example" to "http://xn--msik-0ra.example:8123/",
        ).forEach { (host, expected) ->
            assertEquals(expected, ServerInput("Test", host, "6601", "8123").toProfile()!!.streamUri().toASCIIString())
        }
        listOf(first.copy(host = "http://example.invalid/path"), first.copy(streamPort = 0), first.copy(host = "localhost:9000"))
            .forEach { assertTrue(runCatching { it.streamUri() }.isFailure) }
    }

    @Test fun startupAndConnectionChangesNeverEnableAudio() {
        val backend = Backend()
        val controller = LocalStreamController(backend)
        controller.connectionChanged(first, connected)
        controller.connectionChanged(second, connected)
        assertEquals(StreamPhase.OFF, controller.state.value.phase)
        assertTrue(backend.starts.isEmpty())
        assertNull(controller.request.value)
    }

    @Test fun enableOnceDisableAndReconnectRequireManualIntent() {
        val backend = Backend()
        val controller = LocalStreamController(backend)
        controller.enable(first, ConnectionState())
        assertTrue(backend.starts.isEmpty())
        controller.enable(first, connected)
        val token = controller.request.value!!.generation
        controller.enable(first, connected)
        assertEquals(listOf(token), backend.starts)
        controller.report(token, StreamPhase.BUFFERING)
        controller.report(token, StreamPhase.WAITING_FOR_MPD)
        assertTrue(controller.state.value.enabled)
        controller.disable()
        assertEquals(1, backend.stops)
        controller.connectionChanged(first, connected)
        controller.report(token, StreamPhase.ACTIVE)
        controller.fail(token, StreamError.UNAVAILABLE)
        assertEquals(StreamPhase.OFF, controller.state.value.phase)
        assertEquals(1, backend.starts.size)
    }

    @Test fun streamFailureIsTerminalAndRetryRejectsOldEvents() {
        val backend = Backend()
        val controller = LocalStreamController(backend)
        controller.enable(first, connected)
        val old = controller.request.value!!.generation
        controller.fail(old, StreamError.TIMEOUT)
        assertFalse(controller.state.value.enabled)
        assertEquals(StreamError.TIMEOUT, controller.state.value.error)
        controller.enable(first, connected)
        val current = controller.request.value!!.generation
        controller.report(old, StreamPhase.ACTIVE)
        controller.serviceDestroyed(old)
        assertEquals(current, controller.request.value!!.generation)
        assertEquals(StreamPhase.CONNECTING, controller.state.value.phase)
        assertEquals(2, backend.starts.size)
    }

    @Test fun switchEditOrConnectionLossReleasesBeforeAnotherStream() {
        listOf(second, first.copy(streamPort = 8123), null).forEach { changed ->
            val backend = Backend()
            val controller = LocalStreamController(backend)
            controller.enable(first, connected)
            controller.connectionChanged(changed, connected)
            assertEquals(1, backend.stops)
            assertNull(controller.request.value)
            assertEquals(StreamError.CONNECTION_LOST, controller.state.value.error)
        }
        val backend = Backend()
        val controller = LocalStreamController(backend)
        controller.enable(first, connected)
        controller.connectionChanged(first, ConnectionState(ConnectionStatus.FAILED))
        assertFalse(controller.state.value.enabled)
        assertEquals(1, backend.stops)
    }

    @Test fun metadataAndPausedStateDoNotRestartTheStream() {
        val backend = Backend()
        val controller = LocalStreamController(backend)
        controller.enable(first, connected)
        val updated = snapshot.copy(status = MpdStatus(playback = PlaybackState.PLAYING), currentSong = MpdSong(1, "song.mp3", "Title"))
        controller.connectionChanged(first, connected.copy(snapshot = updated))
        assertEquals(updated, controller.request.value!!.snapshot)
        assertEquals(1, backend.starts.size)
        assertEquals(0, backend.stops)
    }

    @Test fun temporaryControlRecoveryKeepsManualReceiverAndTerminalFailureReleasesIt() {
        val backend = Backend()
        val controller = LocalStreamController(backend)
        controller.enable(first, connected)
        val generation = controller.request.value!!.generation
        controller.connectionChanged(first, connected.copy(status = ConnectionStatus.FAILED, recovering = true))
        controller.connectionChanged(first, connected.copy(status = ConnectionStatus.CONNECTING, recovering = true))
        assertEquals(generation, controller.request.value!!.generation)
        assertEquals(1, backend.starts.size)
        assertEquals(0, backend.stops)
        controller.connectionChanged(first, connected.copy(error = ConnectionError.LOST,
            failureSource = ConnectionFailureSource.IDLE, retriesExhausted = true))
        assertEquals(generation, controller.request.value!!.generation)
        controller.connectionChanged(first, connected.copy(status = ConnectionStatus.FAILED, retriesExhausted = true))
        assertNull(controller.request.value)
        assertEquals(1, backend.stops)
        controller.connectionChanged(first, connected)
        assertNull(controller.request.value)
    }

    @Test fun androidStartDenialIsRecoverableAndNeverRetriedAutomatically() {
        val backend = Backend().apply { failure = IllegalStateException("Internal detail") }
        val controller = LocalStreamController(backend)
        controller.enable(first, connected)
        assertEquals(StreamError.START_FAILED, controller.state.value.error)
        assertNull(controller.request.value)
        backend.failure = null
        controller.connectionChanged(first, connected)
        assertTrue(backend.starts.isEmpty())
        controller.enable(first, connected)
        assertEquals(1, backend.starts.size)
    }
}
