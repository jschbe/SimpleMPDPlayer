package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerInput
import java.io.EOFException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MpdRepeatTest {
    private val profile = ServerInput("Fixture", "localhost").toProfile()!!

    @Test fun allSixCombinationsAndEveryTransitionUseOnlyNecessaryIndependentCommands() = runTest {
        val server = Fixture()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent()
        for (random in listOf(false, true)) for (source in RepeatMode.entries) for (target in RepeatMode.entries) {
            server.random = random
            server.repeat = if (source == RepeatMode.NONE) "0" else "1"
            server.single = if (source == RepeatMode.SINGLE) "1" else "0"
            // Deliberately do not deliver idle: the cached state must not determine writes.
            server.calls.clear()
            repository.perform(PlayerAction.Repeat(target)); runCurrent()
            val expected = buildList {
                if ((source == RepeatMode.NONE) != (target == RepeatMode.NONE))
                    add("repeat" to listOf(if (target == RepeatMode.NONE) "0" else "1"))
                if ((source == RepeatMode.SINGLE) != (target == RepeatMode.SINGLE))
                    add("single" to listOf(if (target == RepeatMode.SINGLE) "1" else "0"))
            }
            assertEquals(expected, server.mutations())
            assertEquals(target, repository.state.value.snapshot!!.status.repeatMode)
            assertEquals(random, repository.state.value.snapshot!!.status.random)
            assertEquals("status", server.calls.first().first)
            assertEquals("status", server.calls.last().first)
            server.calls.clear()
            repository.perform(PlayerAction.Random(!random)); runCurrent()
            assertEquals(listOf("random" to listOf(if (random) "0" else "1")), server.mutations())
            assertEquals(target, repository.state.value.snapshot!!.status.repeatMode)
            assertEquals(!random, repository.state.value.snapshot!!.status.random)
        }
        repository.close()
    }

    @Test fun externalOptionsAndOneshotAreReadOnlyUntilExplicitSelection() = runTest {
        val server = Fixture()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent()
        for (random in listOf(false, true)) for (single in listOf("0", "1", "oneshot", "future", null)) {
            server.repeat = "1"; server.single = single; server.random = random
            server.calls.clear()
            server.events.send(setOf("options")); runCurrent()
            val status = repository.state.value.snapshot!!.status
            assertEquals(random, status.random)
            assertEquals(when (single) { "0" -> RepeatMode.QUEUE; "1" -> RepeatMode.SINGLE; else -> null }, status.repeatMode)
            if (single == "oneshot") assertEquals(SingleMode.ONESHOT, status.single)
            assertTrue(server.mutations().isEmpty())
        }
        for (mode in RepeatMode.entries) {
            server.repeat = "1"; server.single = "oneshot"
            server.calls.clear()
            repository.perform(PlayerAction.Repeat(mode)); runCurrent()
            assertTrue(server.mutations().contains("single" to listOf(if (mode == RepeatMode.SINGLE) "1" else "0")))
            assertEquals(mode, repository.state.value.snapshot!!.status.repeatMode)
        }
        repository.close()
    }

    @Test fun missingUnexpectedAndUnsupportedStatusDoesNotInventAMode() {
        for (repeat in listOf(null, "unexpected", "2", "0", "1")) {
            for (single in listOf(null, "unexpected", "2", "oneshot", "0", "1")) {
                val status = MpdData.status(listOfNotNull(repeat?.let { "repeat" to it }, single?.let { "single" to it }))
                val expected = when (repeat to single) {
                    "0" to "0" -> RepeatMode.NONE
                    "1" to "0" -> RepeatMode.QUEUE
                    "1" to "1" -> RepeatMode.SINGLE
                    else -> null
                }
                assertEquals(expected, status.repeatMode)
                if (repeat !in listOf("0", "1")) assertNull(status.repeat)
            }
        }
    }

    @Test fun unknownStatusIsNormalizedOnlyByExplicitSelection() = runTest {
        val server = Fixture().apply { repeat = null; single = "future" }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent()
        assertNull(repository.state.value.snapshot!!.status.repeatMode)
        assertTrue(server.mutations().isEmpty())
        repository.perform(PlayerAction.Repeat(RepeatMode.NONE)); runCurrent()
        assertEquals(listOf("repeat" to listOf("0"), "single" to listOf("0")), server.mutations())
        assertEquals(RepeatMode.NONE, repository.state.value.snapshot!!.status.repeatMode)
        repository.close()
    }

    @Test fun secondCommandAckReadsActualPartialStateWithoutOptimisticSingle() = runTest {
        val server = Fixture().apply { random = true }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent(); server.calls.clear()
        server.before = { if (it == "single") throw MpdAckException(5, 0, "single") }
        repository.perform(PlayerAction.Repeat(RepeatMode.SINGLE)); runCurrent()
        assertEquals(listOf("status", "repeat", "single", "status", "currentsong", "status"), server.calls.map { it.first })
        assertEquals(ConnectionError.MPD_REJECTED, repository.state.value.commandError)
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(RepeatMode.QUEUE, repository.state.value.snapshot!!.status.repeatMode)
        assertTrue(repository.state.value.snapshot!!.status.random)
        assertFalse(repository.state.value.commandPending)
        repository.close()
    }

    @Test fun firstCommandAckSkipsSecondCommandAndReadsBack() = runTest {
        val server = Fixture()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent(); server.calls.clear()
        server.before = { if (it == "repeat") throw MpdAckException(5, 0, "repeat") }
        repository.perform(PlayerAction.Repeat(RepeatMode.SINGLE)); runCurrent()
        assertFalse(server.calls.any { it.first == "single" })
        assertEquals("status", server.calls.last().first)
        assertEquals(RepeatMode.NONE, repository.state.value.snapshot!!.status.repeatMode)
        assertEquals(ConnectionError.MPD_REJECTED, repository.state.value.commandError)
        repository.close()
    }

    @Test fun partialDisableReadsBackStopAfterTrackInsteadOfClaimingNone() = runTest {
        val server = Fixture().apply { repeat = "1"; single = "1" }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent()
        server.before = { if (it == "single") throw MpdAckException(5, 0, "single") }
        repository.perform(PlayerAction.Repeat(RepeatMode.NONE)); runCurrent()
        val status = repository.state.value.snapshot!!.status
        assertEquals(false, status.repeat)
        assertEquals(SingleMode.ON, status.single)
        assertNull(status.repeatMode)
        assertEquals(ConnectionError.MPD_REJECTED, repository.state.value.commandError)
        repository.close()
    }

    @Test fun lostFirstAcknowledgementSkipsSecondWriteAndRecoversActualPartialStatus() = runTest {
        val server = Fixture()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent(); server.calls.clear()
        server.before = { if (it == "repeat") { server.repeat = "1"; throw EOFException() } }
        repository.perform(PlayerAction.Repeat(RepeatMode.SINGLE)); runCurrent()
        assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
        assertEquals(RepeatMode.NONE, repository.state.value.snapshot!!.status.repeatMode)
        assertFalse(server.calls.any { it.first == "single" })
        server.before = {}
        advanceTimeBy(1_000); runCurrent()
        assertEquals(RepeatMode.QUEUE, repository.state.value.snapshot!!.status.repeatMode)
        assertEquals(listOf("repeat" to listOf("1")), server.mutations())
        repository.close()
    }

    @Test fun droppedConnectionClosesBothSocketsAndRecoveryReadsWithoutReplayingMutation() = runTest {
        val server = Fixture()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent(); server.calls.clear()
        server.before = { if (it == "single") { server.single = "1"; throw EOFException() } }
        repository.perform(PlayerAction.Repeat(RepeatMode.SINGLE)); runCurrent()
        assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
        assertEquals(RepeatMode.NONE, repository.state.value.snapshot!!.status.repeatMode)
        assertTrue(server.connections.take(2).all { it.closed })
        val mutations = server.mutations().toList()
        server.before = {}
        advanceTimeBy(1_000); runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(RepeatMode.SINGLE, repository.state.value.snapshot!!.status.repeatMode)
        assertEquals(mutations, server.mutations())
        repository.close()
    }

    @Test fun sequenceCannotInterleaveWithIdleBrowserOrHealthCheckAndShowsOnlyReadback() = runTest {
        val server = Fixture()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent(); server.calls.clear()
        val release = CompletableDeferred<Unit>()
        server.before = { if (it == "single") release.await() }
        repository.perform(PlayerAction.Repeat(RepeatMode.SINGLE)); runCurrent()
        assertEquals(RepeatMode.NONE, repository.state.value.snapshot!!.status.repeatMode)
        assertTrue(repository.state.value.commandPending)
        repository.loadBrowser()
        repository.checkConnection()
        server.events.send(setOf("options"))
        advanceTimeBy(15_000); runCurrent()
        assertNull(repository.perform(PlayerAction.Random(true)))
        assertEquals(listOf("status", "repeat", "single"), server.calls.map { it.first })
        release.complete(Unit); runCurrent()
        assertEquals(listOf("status", "repeat", "single", "status", "currentsong", "status"), server.calls.take(6).map { it.first })
        assertTrue(server.calls.any { it.first == "lsinfo" })
        assertTrue(server.calls.indexOfFirst { it.first == "ping" } >= 6)
        assertEquals(RepeatMode.SINGLE, repository.state.value.snapshot!!.status.repeatMode)
        repository.close()
    }

    @Test fun switchingDuringChangeCancelsSecondCommandAndRejectsOldState() = runTest {
        val server = Fixture()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(profile); runCurrent(); server.calls.clear()
        val release = CompletableDeferred<Unit>()
        server.before = { if (it == "repeat") release.await() }
        repository.perform(PlayerAction.Repeat(RepeatMode.SINGLE)); runCurrent()
        server.before = {}
        repository.connect(profile.copy(id = "new-session")); runCurrent()
        release.complete(Unit); runCurrent()
        assertTrue(server.connections.take(2).all { it.closed })
        assertFalse(server.calls.any { it.first == "single" })
        assertEquals(RepeatMode.NONE, repository.state.value.snapshot!!.status.repeatMode)
        assertFalse(repository.state.value.commandPending)
        repository.close()
    }

    private class Fixture {
        var repeat: String? = "0"
        var single: String? = "0"
        var random = false
        var before: suspend (String) -> Unit = {}
        val events = Channel<Set<String>>(Channel.UNLIMITED)
        val calls = mutableListOf<Pair<String, List<String>>>()
        val connections = mutableListOf<Connection>()
        fun mutations() = calls.filter { it.first in listOf("repeat", "single", "random") }
        fun connection(): MpdConnection = Connection().also { connections += it }
        inner class Connection : MpdConnection {
            var closed = false
            override suspend fun connect(host: String, port: Int) = Unit
            override suspend fun ping() { calls += "ping" to emptyList() }
            override suspend fun idle() = events.receive()
            override fun close() { closed = true }
            override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> {
                calls += name to arguments.toList()
                before(name)
                return when (name) {
                    "status" -> listOf("state" to "stop", "playlist" to "1", "playlistlength" to "0", "random" to if (random) "1" else "0") +
                        listOfNotNull(repeat?.let { "repeat" to it }, single?.let { "single" to it })
                    "repeat" -> { repeat = arguments.single(); emptyList() }
                    "single" -> { single = arguments.single(); emptyList() }
                    "random" -> { random = arguments.single() == "1"; emptyList() }
                    else -> emptyList()
                }
            }
        }
    }
}
