package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerInput
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MpdRepositoryTest {
    private val first = ServerInput("First", "localhost").toProfile()!!
    private val second = ServerInput("Second", "localhost").toProfile()!!

    @Test
    fun missingPermissionOpensNoSockets() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first, networkAllowed = false)
        assertTrue(server.connections.isEmpty())
        assertEquals(ConnectionError.PERMISSION_REQUIRED, repository.state.value.error)
        repository.close()
    }

    @Test
    fun loadsInitialStateAndDoesNotPollProgressOverNetwork() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(11L, repository.state.value.snapshot!!.currentSong!!.id)
        assertEquals(2, repository.state.value.snapshot!!.queue.size)
        val calls = server.calls.size
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(calls, server.calls.size)
        assertEquals(1, server.connections[1].idleCalls)
        repository.close()
        assertTrue(server.connections.all { it.closed })
    }

    @Test
    fun idleRefreshesExternalQueueAndOptions() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        server.ids.add(13)
        server.version++
        server.random = true
        server.events.send(setOf("playlist", "options"))
        runCurrent()
        assertEquals(listOf(11L, 12L, 13L), repository.state.value.snapshot!!.queue.map { it.id })
        assertTrue(repository.state.value.snapshot!!.status.random)
        assertEquals(2, server.connections[1].idleCalls)
        repository.close()
    }

    @Test
    fun playbackControlsUseCorrectCommandsAndReadBackState() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        val transitions = listOf("play" to ("pause" to listOf("1")), "pause" to ("pause" to listOf("0")), "stop" to ("play" to emptyList()))
        for ((state, command) in transitions) {
            server.playback = state
            server.calls.clear()
            repository.perform(PlayerAction.PlayPause)
            runCurrent()
            assertTrue(server.calls.contains(command))
            assertFalse(repository.state.value.commandPending)
        }
        for ((action, command) in listOf(PlayerAction.Stop to "stop", PlayerAction.Next to "next", PlayerAction.Previous to "previous")) {
            server.calls.clear()
            repository.perform(action)
            runCurrent()
            assertTrue(server.calls.contains(command to emptyList()))
        }
        repository.close()
    }

    @Test
    fun optionsVolumeSeekAndQueueActionsUseIds() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        val actions = listOf(
            PlayerAction.Random(true) to ("random" to listOf("1")),
            PlayerAction.Repeat(false) to ("repeat" to listOf("0")),
            PlayerAction.Volume(73) to ("setvol" to listOf("73")),
            PlayerAction.Seek(25.5, 11) to ("seekcur" to listOf("25.5")),
            PlayerAction.PlayId(12) to ("playid" to listOf("12")),
            PlayerAction.DeleteId(12) to ("deleteid" to listOf("12")),
            PlayerAction.MoveId(11, 1) to ("moveid" to listOf("11", "1")),
            PlayerAction.MoveId(12, -1) to ("moveid" to listOf("12", "0")),
            PlayerAction.Clear to ("clear" to emptyList()),
        )
        for ((action, expected) in actions) {
            server.calls.clear()
            repository.perform(action)
            runCurrent()
            assertTrue("Missing $expected", server.calls.contains(expected))
        }
        repository.close()
    }

    @Test
    fun rejectsInvalidOrStaleSeekAndOutOfRangeMoves() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        for (action in listOf(PlayerAction.Seek(30.0, 99), PlayerAction.Seek(Double.NaN, 11), PlayerAction.Volume(101), PlayerAction.MoveId(11, -1), PlayerAction.MoveId(99, 1))) {
            server.calls.clear()
            repository.perform(action)
            runCurrent()
            assertFalse(server.calls.any { it.first in listOf("seekcur", "setvol", "moveid") })
        }
        repository.close()
    }

    @Test
    fun ackDoesNotPretendThatPlaybackChangedAndAllowsRetry() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        server.reject = "pause"
        repository.perform(PlayerAction.PlayPause)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(PlaybackState.PLAYING, repository.state.value.snapshot!!.status.playback)
        assertEquals(ConnectionError.MPD_REJECTED, repository.state.value.commandError)
        assertFalse(repository.state.value.commandPending)
        server.reject = null
        repository.perform(PlayerAction.Stop)
        runCurrent()
        assertNull(repository.state.value.commandError)
        repository.close()
    }

    @Test
    fun heartbeatFailureClosesBothWithoutReconnect() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection, pingIntervalMillis = 100)
        repository.connect(first)
        runCurrent()
        server.pingError = EOFException()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ConnectionError.LOST, repository.state.value.error)
        assertTrue(server.connections.all { it.closed })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, server.connections.size)
        repository.close()
    }

    @Test
    fun idleFailureIsControlledAndClosesCommandSocket() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        server.events.close(EOFException())
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
        assertEquals(ConnectionError.LOST, repository.state.value.error)
        assertTrue(server.connections.all { it.closed })
        repository.close()
    }

    @Test
    fun serverSwitchClosesIdleAndRemovesOldState() = runTest {
        val old = FakeServer()
        val next = FakeServer()
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first)
        runCurrent()
        repository.connect(second)
        assertNull(repository.state.value.snapshot)
        assertTrue(old.connections.all { it.closed })
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertTrue(old.connections[1].idleCancelled)
        old.events.send(setOf("playlist"))
        runCurrent()
        assertEquals(1, next.connections[1].idleCalls)
        repository.connect(null)
        assertEquals(ConnectionState(), repository.state.value)
        assertTrue(next.connections.all { it.closed })
    }

    @Test
    fun switchingCancelsPendingConnectAndRejectsLateFailure() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = FakeServer()
        old.connectBlock = { withContext(NonCancellable) { release.await(); throw SocketTimeoutException() } }
        val next = FakeServer()
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first)
        runCurrent()
        repository.connect(second)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertTrue(old.connections.all { it.closed })
        repository.close()
    }

    @Test
    fun oldSnapshotCannotOverwriteNewServer() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = FakeServer()
        old.statusBlock = { withContext(NonCancellable) { release.await() } }
        val next = FakeServer().apply { ids.clear(); selectedId = null }
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first)
        runCurrent()
        repository.connect(second)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(repository.state.value.snapshot!!.queue.isEmpty())
        assertNull(repository.state.value.snapshot!!.currentSong)
        repository.close()
    }

    @Test
    fun lateCommandAckCannotSetAnErrorOnTheNewServer() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = FakeServer().apply {
            commandBlock = { name ->
                if (name == "pause") withContext(NonCancellable) {
                    release.await()
                    throw MpdAckException(4, 0, "pause")
                }
            }
        }
        val next = FakeServer()
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first)
        runCurrent()
        repository.perform(PlayerAction.PlayPause)
        runCurrent()
        assertTrue(repository.state.value.commandPending)
        repository.connect(second)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertNull(repository.state.value.commandError)
        assertFalse(repository.state.value.commandPending)
        repository.close()
    }

    @Test
    fun continuouslyChangingSnapshotFailsWithinBoundedRetries() = runTest {
        val server = FakeServer()
        server.statusBlock = { server.version++ }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
        assertEquals(ConnectionError.INVALID_RESPONSE, repository.state.value.error)
        assertEquals(3, server.calls.count { it.first == "playlistinfo" })
        assertTrue(server.connections.all { it.closed })
        repository.close()
    }

    @Test
    fun transportAndProtocolFailuresStayDistinct() = runTest {
        for ((failure, expected) in listOf(ConnectException("Connection refused") to ConnectionError.REFUSED, InvalidMpdResponse() to ConnectionError.INVALID_RESPONSE, SocketTimeoutException() to ConnectionError.TIMEOUT)) {
            val server = FakeServer().apply { connectBlock = { throw failure } }
            val repository = MpdRepository(backgroundScope, server::connection)
            repository.connect(first)
            runCurrent()
            assertEquals(expected, repository.state.value.error)
            repository.close()
        }
    }

    private class FakeServer {
        val connections = mutableListOf<FakeConnection>()
        val calls = mutableListOf<Pair<String, List<String>>>()
        val events = Channel<Set<String>>(Channel.UNLIMITED)
        val ids = mutableListOf(11L, 12L)
        var selectedId: Long? = 11
        var version = 1
        var random = false
        var playback = "play"
        var reject: String? = null
        var pingError: IOException? = null
        var connectBlock: suspend () -> Unit = {}
        var statusBlock: suspend () -> Unit = {}
        var commandBlock: suspend (String) -> Unit = {}
        fun connection(): MpdConnection = FakeConnection(this).also { connections.add(it) }
        fun song(id: Long) = listOf("file" to "same.mp3", "Id" to id.toString(), "Title" to "Song $id")
        suspend fun execute(name: String, arguments: List<String>): List<Pair<String, String>> {
            calls.add(name to arguments)
            commandBlock(name)
            if (name == reject) throw MpdAckException(4, 0, name)
            return when (name) {
                "status" -> {
                    statusBlock()
                    listOf("state" to playback, "playlist" to version.toString(), "playlistlength" to ids.size.toString(), "volume" to "50", "elapsed" to "10", "duration" to "180", "random" to if (random) "1" else "0") + listOfNotNull(selectedId?.let { "songid" to it.toString() })
                }
                "currentsong" -> selectedId?.let(::song).orEmpty()
                "playlistinfo" -> ids.flatMap(::song)
                else -> emptyList()
            }
        }
    }

    private class FakeConnection(private val server: FakeServer) : MpdConnection {
        var closed = false
        var idleCancelled = false
        var idleCalls = 0
        override suspend fun connect(host: String, port: Int) = server.connectBlock()
        override suspend fun ping() { server.pingError?.let { throw it } }
        override suspend fun execute(name: String, vararg arguments: String) = server.execute(name, arguments.toList())
        override suspend fun idle(): Set<String> {
            idleCalls++
            try { return server.events.receive() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                idleCancelled = true
                throw cancelled
            }
        }
        override fun close() { closed = true }
    }
}
