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
            PlayerAction.Repeat(RepeatMode.NONE) to ("repeat" to listOf("0")),
            PlayerAction.Volume(73) to ("setvol" to listOf("73")),
            PlayerAction.Seek(25.5, 11) to ("seekcur" to listOf("25.5")),
            PlayerAction.PlayId(12) to ("playid" to listOf("12")),
            PlayerAction.MoveId(11, 1) to ("moveid" to listOf("11", "1")),
            PlayerAction.MoveId(11, -1) to ("moveid" to listOf("11", "0")),
            PlayerAction.DeleteId(12) to ("deleteid" to listOf("12")),
            PlayerAction.Clear to ("clear" to emptyList()),
        )
        for ((action, expected) in actions) {
            server.calls.clear()
            repository.submit(action)
            runCurrent()
            if (action is PlayerAction.MoveId) {
                assertEquals("status", server.calls.first().first)
                assertEquals("moveid", server.calls[1].first)
            }
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
            repository.submit(action)
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
    fun heartbeatFailureClosesBothAndSchedulesBoundedRecovery() = runTest {
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
        assertEquals(4, server.connections.size)
        assertEquals(1, repository.state.value.retryAttempt)
        assertEquals(ConnectionFailureSource.CONNECT, repository.state.value.failureSource)
        repository.close()
    }

    @Test
    fun idleFailureRetainsHealthyCommandSocketAndRepairsOnlyIdle() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        val oldEvents = server.events
        server.events = Channel(Channel.UNLIMITED)
        oldEvents.close(EOFException())
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(ConnectionFailureSource.IDLE, repository.state.value.failureSource)
        assertTrue(repository.state.value.recovering)
        assertFalse(server.connections[0].closed)
        assertTrue(server.connections[1].closed)
        repository.perform(PlayerAction.Next)
        runCurrent()
        assertTrue(server.calls.any { it.first == "next" })
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, server.connections.size)
        assertTrue(repository.state.value.idleConnected)
        assertFalse(repository.state.value.recovering)
        assertFalse(server.connections[0].closed)
        server.playback = "pause"
        server.events.send(setOf("player")); runCurrent()
        assertEquals(PlaybackState.PAUSED, repository.state.value.snapshot!!.status.playback)
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
    fun largeQueueUsesExactRangesAndRetainsSongIdentityAndMetadata() = runTest {
        val server = FakeServer().apply { ids += (13L..1_513L); selectedId = 311 }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        val snapshot = repository.state.value.snapshot!!
        assertEquals(QueueAvailability.COMPLETE, snapshot.queueAvailability)
        assertEquals(server.ids, snapshot.queue.map { it.id })
        assertEquals(311L, snapshot.currentSong!!.id)
        assertEquals(listOf("same.mp3"), snapshot.queue.map { it.song.file }.distinct())
        assertEquals("Song 211", snapshot.queue[200].song.title)
        assertEquals(listOf("Artist", "Guest"), snapshot.queue.last().song.artists)
        assertTrue(snapshot.queue.size * server.song(11).size > 10_000)
        assertEquals((0..1_400 step 200).map { "$it:${minOf(it + 200, 1_503)}" }, server.ranges())
        repository.close()
    }

    @Test
    fun queueOverEntryLimitKeepsPlaybackAndClearAcrossReconnect() = runTest {
        val server = FakeServer().apply { ids += (13L..20_011L) }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertTrue(repository.state.value.livePlayerStatus)
        assertEquals(QueueAvailability.LIMIT, repository.state.value.snapshot!!.queueAvailability)
        assertTrue(repository.state.value.snapshot!!.hasQueuedSongs)
        assertTrue(server.ranges().isEmpty())
        repository.reconnect(); runCurrent()
        assertEquals(QueueAvailability.LIMIT, repository.state.value.snapshot!!.queueAvailability)
        repository.perform(PlayerAction.PlayPause); runCurrent()
        assertTrue(server.calls.contains("pause" to listOf("1")))
        repository.submit(PlayerAction.MoveId(11, 1)); runCurrent()
        assertFalse(server.calls.any { it.first == "moveid" })
        repository.submit(PlayerAction.Clear); runCurrent()
        assertEquals(1, server.calls.count { it.first == "clear" })
        assertEquals(QueueAvailability.COMPLETE, repository.state.value.snapshot!!.queueAvailability)
        assertFalse(repository.state.value.snapshot!!.hasQueuedSongs)
        assertTrue(server.ranges().isEmpty())
        repository.close()
    }

    @Test
    fun metadataBudgetAcrossValidRangesKeepsTheConnectionUsable() = runTest {
        val server = FakeServer().apply {
            ids += (13L..2_413L)
            extraTag = listOf("Comment" to "x".repeat(4_500))
        }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        val snapshot = repository.state.value.snapshot!!
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(QueueAvailability.LIMIT, snapshot.queueAvailability)
        assertTrue(snapshot.hasQueuedSongs)
        assertTrue(snapshot.queue.isEmpty())
        assertEquals((0..1_800 step 200).map { "$it:${it + 200}" }, server.ranges())
        assertTrue(server.connections.none { it.closed })
        repository.submit(PlayerAction.Clear); runCurrent()
        assertEquals(QueueAvailability.COMPLETE, repository.state.value.snapshot!!.queueAvailability)
        repository.close()
    }

    @Test
    fun repeatedEditsBetweenBatchesNeverPublishAMixedQueueAndRetryCanRecover() = runTest {
        val server = FakeServer().apply { ids += (13L..460L) }
        server.rangeBlock = { start ->
            if (start == 200) {
                server.ids[0] = 5_000L + server.version
                server.selectedId = server.ids[0]
                server.version++
            }
        }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertNull(repository.state.value.error)
        assertEquals(QueueAvailability.CHANGING, repository.state.value.snapshot!!.queueAvailability)
        assertTrue(repository.state.value.snapshot!!.queue.isEmpty())
        assertEquals(server.selectedId, repository.state.value.snapshot!!.currentSong!!.id)
        assertEquals(List(3) { listOf("0:200", "200:400") }.flatten(), server.ranges())
        assertTrue(server.connections.none { it.closed })
        server.rangeBlock = {}
        repository.retryQueue(repository.state.value.sessionId!!); runCurrent()
        assertEquals(QueueAvailability.COMPLETE, repository.state.value.snapshot!!.queueAvailability)
        assertEquals(server.ids, repository.state.value.snapshot!!.queue.map { it.id })
        repository.close()
    }

    @Test
    fun shrinkingQueueCanRejectAnOldRangeAndStillSynchronize() = runTest {
        val server = FakeServer().apply { ids += (13L..460L) }
        server.rangeBlock = { start ->
            if (start == 200) {
                server.ids.subList(2, server.ids.size).clear()
                server.version++
            }
        }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        assertEquals(listOf("0:200", "200:400", "0:2"), server.ranges())
        assertEquals(QueueAvailability.COMPLETE, repository.state.value.snapshot!!.queueAvailability)
        assertEquals(server.ids, repository.state.value.snapshot!!.queue.map { it.id })
        repository.close()
    }

    @Test
    fun malformedPositionsOrDuplicateIdsAcrossRangesStillFailTheProtocol() = runTest {
        val duplicate = FakeServer().apply { ids += (13L..212L); ids[200] = 11 }
        val badPosition = FakeServer().apply { positionOffset = 1 }
        for (server in listOf(duplicate, badPosition)) {
            val repository = MpdRepository(backgroundScope, server::connection)
            repository.connect(first); runCurrent()
            assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
            assertEquals(ConnectionError.INVALID_RESPONSE, repository.state.value.error)
            assertFalse(repository.state.value.recovering)
            repository.close()
        }
    }

    @Test
    fun switchingDuringARangeReadDiscardsTheOldQueue() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = FakeServer().apply {
            ids += (13L..460L)
            rangeBlock = { if (it == 200) withContext(NonCancellable) { release.await() } }
        }
        val next = FakeServer().apply { ids.clear(); ids.add(11); selectedId = 11 }
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first); runCurrent()
        assertEquals(listOf("0:200", "200:400"), old.ranges())
        repository.connect(second); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(listOf(11L), repository.state.value.snapshot!!.queue.map { it.id })
        assertEquals(listOf("0:1"), next.ranges())
        assertTrue(old.connections.all { it.closed })
        repository.close()
    }

    @Test
    fun delayedQueueActionsAreRejectedAfterSameProfileRecoveryEditAndSwitch() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        val reconnects: List<() -> Unit> = listOf(
            {
                server.pingError = EOFException()
                repository.checkConnection(); runCurrent()
                server.pingError = null
                advanceTimeBy(1_000)
            },
            { repository.reconnect() },
            { repository.connect(first.copy(mpdPort = first.mpdPort + 1)) },
            { repository.connect(second) },
        )
        for (reconnect in reconnects) {
            val obsolete = repository.context()
            server.version++
            reconnect(); runCurrent()
            assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
            assertNotEquals(obsolete.sessionId, repository.context().sessionId)
            assertNull(repository.performQueue(PlayerAction.Clear, obsolete))
            assertNull(repository.performQueue(PlayerAction.DeleteId(11), obsolete))
            assertNull(repository.performQueue(PlayerAction.PlayId(11), obsolete))
            assertNull(repository.performQueue(PlayerAction.MoveId(11, 1), obsolete))
            assertFalse(server.calls.any { it.first in listOf("clear", "deleteid", "playid", "moveid") })
        }
        assertNull(repository.perform(PlayerAction.Clear))
        repository.submit(PlayerAction.Clear); runCurrent()
        assertEquals(1, server.calls.count { it.first == "clear" })
        repository.close()
    }

    @Test
    fun clearAndMoveCheckTheQueueVersionBeforeSending() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        for (action in listOf(PlayerAction.Clear, PlayerAction.MoveId(12, 1))) {
            val context = repository.context()
            server.ids.reverse()
            server.version++
            repository.performQueue(action, context); runCurrent()
            assertEquals(ConnectionError.QUEUE_CHANGED, repository.state.value.commandError)
            assertEquals(server.ids, repository.state.value.snapshot!!.queue.map { it.id })
        }
        assertFalse(server.calls.any { it.first in listOf("clear", "moveid") })
        repository.close()
    }

    private fun MpdRepository.context(): QueueActionContext {
        val state = state.value
        val snapshot = state.snapshot!!
        return QueueActionContext(state.sessionId!!,
            snapshot.status.queueVersion.takeIf { snapshot.queueAvailability == QueueAvailability.COMPLETE })
    }

    private fun MpdRepository.submit(action: PlayerAction) =
        if (action is PlayerAction.QueueAction) performQueue(action, context()) else perform(action)

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

    @Test fun explicitSystemPlayAndPauseUseFreshStateAndNeverToggleUnexpectedly() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first)
        runCurrent()
        for ((state, action, expected) in listOf(
            Triple("pause", PlayerAction.Play, "pause" to listOf("0")),
            Triple("stop", PlayerAction.Play, "play" to emptyList()),
            Triple("play", PlayerAction.Pause, "pause" to listOf("1")),
            Triple("play", PlayerAction.Play, null),
            Triple("pause", PlayerAction.Pause, null),
            Triple("stop", PlayerAction.Pause, null),
        )) {
            server.playback = state
            server.calls.clear()
            repository.perform(action)
            runCurrent()
            assertEquals(listOfNotNull(expected), server.calls.filter { it.first != "status" && it.first != "currentsong" })
        }
        repository.close()
    }

    @Test fun mediaTransportRequiresLiveManualGenerationAndRechecksAfterStatusRead() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        val stream = com.github.jschbe.simplempdplayer.audio.LocalStreamController(object : com.github.jschbe.simplempdplayer.audio.StreamBackend {
            override fun start(generation: Long) {}
            override fun stop() {}
        })
        repository.connect(first)
        runCurrent()
        stream.enable(first, repository.state.value)
        val transport = com.github.jschbe.simplempdplayer.audio.MpdMediaTransport(repository, stream, stream.request.value!!)
        server.calls.clear()
        val release = CompletableDeferred<Unit>()
        server.statusBlock = { release.await() }
        transport.submit(PlayerAction.Pause)
        runCurrent()
        assertFalse(transport.available)
        stream.disable()
        release.complete(Unit)
        runCurrent()
        assertFalse(server.calls.any { it.first == "pause" })
        assertNull(transport.submit(PlayerAction.Next))
        stream.enable(first, repository.state.value)
        assertNull(transport.submit(PlayerAction.Play))
        assertFalse(transport.available)
        assertNull(transport.submit(PlayerAction.Clear))
        repository.close()
    }

    @Test fun queuedMediaActionCannotOutliveAuthorizationOrServerSession() = runTest {
        val old = FakeServer()
        val next = FakeServer()
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first)
        runCurrent()
        old.calls.clear()
        var allowed = true
        repository.perform(PlayerAction.Next) { allowed }
        allowed = false
        runCurrent()
        assertFalse(old.calls.any { it.first == "next" })
        repository.perform(PlayerAction.Previous)
        repository.connect(second)
        runCurrent()
        assertFalse(old.calls.any { it.first == "previous" })
        assertFalse(next.calls.any { it.first == "previous" })
        repository.close()
    }

    @Test fun recoveryUsesExactlyFiveExponentialDelaysAndOrdinaryChecksDoNotRearm() = runTest {
        val server = FakeServer().apply { connectBlock = { throw ConnectException("refused") } }
        val diagnostics = mutableListOf<String>()
        val repository = MpdRepository(backgroundScope, server::connection, diagnostics = diagnostics::add)
        repository.connect(first); runCurrent()
        assertEquals(2, server.connections.size)
        for ((index, delay) in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L).withIndex()) {
            val count = server.connections.size
            advanceTimeBy(delay - 1); runCurrent()
            assertEquals(count, server.connections.size)
            advanceTimeBy(1); runCurrent()
            assertEquals(count + 2, server.connections.size)
            assertEquals(index + 1, repository.state.value.retryAttempt)
        }
        assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
        assertTrue(repository.state.value.retriesExhausted)
        assertFalse(repository.state.value.recovering)
        val count = server.connections.size
        repository.checkConnection()
        repository.checkConnection(ConnectionCheckTrigger.WINDOW_FOCUS)
        repository.networkChanged(false); repository.networkChanged(true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(count, server.connections.size)
        assertTrue(diagnostics.any { "exhausted generation=" in it })
        assertTrue(diagnostics.any { "trigger=WINDOW_FOCUS" in it && "exhausted=true" in it })
        assertTrue(diagnostics.none { first.host in it || first.name in it || first.id in it })
        server.connectBlock = {}
        repository.reconnect(); runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(0, repository.state.value.retryAttempt)
        assertFalse(repository.state.value.retriesExhausted)
        repository.close()
    }

    @Test fun authorizedReturnWaitsOfflineAndDoesNotResetAnActiveCycle() = runTest {
        val server = FakeServer().apply { connectBlock = { throw EOFException() } }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        advanceTimeBy(31_000); runCurrent()
        assertTrue(repository.state.value.retriesExhausted)
        repository.networkChanged(false)
        repository.resumeExhaustedRecovery()
        advanceTimeBy(120_000); runCurrent()
        assertTrue(repository.state.value.recovering)
        assertEquals(12, server.connections.size)
        assertEquals(0, repository.state.value.retryAttempt)

        repository.networkChanged(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, repository.state.value.retryAttempt)
        repository.resumeExhaustedRecovery()
        advanceTimeBy(1_999); runCurrent()
        assertEquals(14, server.connections.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(16, server.connections.size)
        advanceTimeBy(28_000); runCurrent()
        assertEquals(22, server.connections.size)
        assertTrue(repository.state.value.retriesExhausted)
        repeat(5) { repository.checkConnection(ConnectionCheckTrigger.WINDOW_FOCUS) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(22, server.connections.size)

        server.connectBlock = {}
        repository.resumeExhaustedRecovery()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(24, server.connections.size)
        assertTrue(repository.state.value.livePlayerStatus)
        assertFalse(repository.state.value.retriesExhausted)
        repository.close()
    }

    @Test fun exhaustedIdleRestartsOnlyIdleAndSingleCommandReadCanConfirmPlayback() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection, elapsedMillis = { testScheduler.currentTime })
        repository.connect(first); runCurrent()
        val sessionId = repository.state.value.sessionId
        val oldEvents = server.events
        server.events = Channel(Channel.UNLIMITED)
        oldEvents.close(EOFException()); runCurrent()
        server.connectBlock = { throw EOFException() }
        assertFalse(repository.state.value.hasConfirmedPlayerStatus(testScheduler.currentTime))
        assertFalse(repository.state.value.livePlayerStatus)
        advanceTimeBy(31_000); runCurrent()
        assertTrue(repository.state.value.retriesExhausted)
        assertEquals(7, server.connections.size)

        server.playback = "pause"
        repository.confirmTimerPlayback(); runCurrent()
        assertEquals(PlaybackState.PAUSED, repository.state.value.snapshot!!.status.playback)
        assertTrue(repository.state.value.hasConfirmedPlayerStatus(testScheduler.currentTime))
        assertFalse(repository.state.value.livePlayerStatus)
        assertFalse(repository.state.value.hasConfirmedPlayerStatus(testScheduler.currentTime + 5_001))
        assertFalse(server.connections.first().closed)

        server.connectBlock = {}
        repository.resumeExhaustedRecovery()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(8, server.connections.size)
        assertEquals(sessionId, repository.state.value.sessionId)
        assertTrue(repository.state.value.livePlayerStatus)
        assertFalse(server.connections.first().closed)
        repository.close()
    }

    @Test fun capacityExhaustionDoesNotRearmOnReturn() = runTest {
        val server = FakeServer().apply { connectBlock = { throw MpdResponseLimit() } }
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        advanceTimeBy(31_000); runCurrent()
        assertEquals(ConnectionError.RESPONSE_LIMIT, repository.state.value.error)
        assertTrue(repository.state.value.retriesExhausted)
        repository.resumeExhaustedRecovery()
        advanceTimeBy(31_000); runCurrent()
        assertEquals(12, server.connections.size)
        assertFalse(repository.state.value.recovering)
        repository.close()
    }

    @Test fun returnInvalidatesAnInFlightReadAndMergesChecksForTheNewEpoch() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        val oldRead = CompletableDeferred<Unit>()
        val newRead = CompletableDeferred<Unit>()
        var reads = 0
        server.statusBlock = {
            if (++reads == 1) oldRead.await()
            if (reads == 4) newRead.await()
        }
        repository.checkConnection(); runCurrent()
        repository.invalidatePlayerStatus()
        repeat(3) { repository.checkConnection() }
        oldRead.complete(Unit); runCurrent()
        assertEquals(4, reads)
        assertNull(repository.state.value.playerConfirmedAtMillis)
        server.playback = "pause"
        newRead.complete(Unit); runCurrent()
        assertEquals(6, reads)
        assertEquals(PlaybackState.PAUSED, repository.state.value.snapshot!!.status.playback)
        assertTrue(repository.state.value.livePlayerStatus)
        assertEquals(2, server.connections.size)
        repository.close()
    }

    @Test fun successfulRecoveryRestoresAllDataEvenWithSameQueueVersionAndResetsBudget() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        repeat(2) {
            server.pingError = EOFException()
            repository.checkConnection(); runCurrent()
            assertEquals(ConnectionFailureSource.CONTROL, repository.state.value.failureSource)
            server.pingError = null
            server.ids.clear(); server.ids.add(90L + it); server.selectedId = 90L + it
            server.playback = "pause"
            advanceTimeBy(999); runCurrent()
            assertTrue(repository.state.value.recovering)
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf(90L + it), repository.state.value.snapshot!!.queue.map { entry -> entry.id })
            assertEquals(90L + it, repository.state.value.snapshot!!.currentSong!!.id)
            assertEquals(PlaybackState.PAUSED, repository.state.value.snapshot!!.status.playback)
            assertTrue(repository.state.value.idleConnected)
            assertEquals(0, repository.state.value.retryAttempt)
        }
        assertEquals(6, server.connections.size)
        repository.close()
    }

    @Test fun noAttemptsWhileOfflineAndServerSwitchOrCloseCancelsWaitingRecovery() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.networkChanged(false)
        repository.connect(first); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertTrue(server.connections.isEmpty())
        assertTrue(repository.state.value.waitingForNetwork)
        assertEquals(0, repository.state.value.retryAttempt)
        repository.networkChanged(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        server.pingError = EOFException()
        repository.checkConnection(); runCurrent()
        repository.networkChanged(false)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(2, server.connections.size)
        server.pingError = null
        repository.connect(second); runCurrent()
        repository.close()
        repository.networkChanged(true)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(2, server.connections.size)
        assertNull(repository.activeServer)
        assertEquals(ConnectionState(), repository.state.value)
    }

    @Test fun switchingServersDuringBackoffRejectsOldRetriesAndLateReplies() = runTest {
        val old = FakeServer().apply { connectBlock = { throw EOFException() } }
        val next = FakeServer()
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first); runCurrent()
        repository.connect(second); runCurrent()
        advanceTimeBy(32_000); runCurrent()
        assertEquals(second, repository.activeServer)
        assertEquals(2, old.connections.size)
        assertEquals(2, next.connections.size)
        assertFalse(repository.state.value.recovering)
        repository.close()
    }

    @Test fun idleRetryExhaustionKeepsControlAndControlFailureUpgradesOneRepairLoop() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        val oldEvents = server.events
        server.events = Channel(Channel.UNLIMITED)
        oldEvents.close(EOFException()); runCurrent()
        server.connectBlock = { throw EOFException() }
        for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)) { advanceTimeBy(delay); runCurrent() }
        assertEquals(7, server.connections.size)
        assertFalse(server.connections.first().closed)
        assertTrue(repository.state.value.retriesExhausted)
        repository.perform(PlayerAction.Next); runCurrent()
        assertTrue(server.calls.any { it.first == "next" })
        assertTrue(repository.state.value.retriesExhausted)
        repository.close()

        server.connectBlock = {}
        repository.connect(first); runCurrent()
        val events = server.events
        server.events = Channel(Channel.UNLIMITED)
        events.close(EOFException()); runCurrent()
        val count = server.connections.size
        server.pingError = EOFException()
        repository.checkConnection(); runCurrent()
        server.pingError = null
        advanceTimeBy(1_000); runCurrent()
        assertEquals(count + 2, server.connections.size)
        assertFalse(repository.state.value.recovering)
        assertTrue(repository.state.value.idleConnected)
        repository.close()
    }

    @Test fun foregroundChecksAreSingleFlightRefreshSnapshotsAndDoNotReplaceHealthySockets() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        val gate = CompletableDeferred<Unit>()
        server.pingBlock = { gate.await() }
        val count = server.pingCalls
        repeat(4) { repository.checkConnection() }
        runCurrent()
        assertEquals(count + 1, server.pingCalls)
        server.playback = "pause"
        gate.complete(Unit); runCurrent()
        assertEquals(PlaybackState.PAUSED, repository.state.value.snapshot!!.status.playback)
        assertEquals(2, server.connections.size)
        assertFalse(server.connections.any { it.closed })
        repository.close()
    }

    @Test fun invalidProtocolAndPermissionErrorsNeverRetryAutomatically() = runTest {
        for (error in listOf(InvalidMpdResponse(), SecurityException(), MpdAckException(4, 0, "ping"))) {
            val server = FakeServer().apply { connectBlock = { throw error } }
            val repository = MpdRepository(backgroundScope, server::connection)
            repository.connect(first); runCurrent()
            repository.resumeExhaustedRecovery()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(2, server.connections.size)
            assertFalse(repository.state.value.recovering)
            repository.close()
        }
    }

    @Test fun lateForegroundFailureCannotStartRecoveryForASwitchedServer() = runTest {
        val old = FakeServer()
        val next = FakeServer()
        var creations = 0
        val repository = MpdRepository(backgroundScope, { if (creations++ < 2) old.connection() else next.connection() })
        repository.connect(first); runCurrent()
        val gate = CompletableDeferred<Unit>()
        old.pingBlock = { withContext(NonCancellable) { gate.await(); throw EOFException() } }
        repository.checkConnection(); runCurrent()
        repository.connect(second); runCurrent()
        gate.complete(Unit); runCurrent()
        advanceTimeBy(32_000); runCurrent()
        assertEquals(second, repository.activeServer)
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertFalse(repository.state.value.recovering)
        assertNull(repository.state.value.error)
        assertEquals(2, next.connections.size)
        repository.close()
    }

    @Test fun timedOutMutationIsNeverReplayedByAutomaticRecovery() = runTest {
        val server = FakeServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        server.commandBlock = { if (it == "next") throw SocketTimeoutException() }
        repository.perform(PlayerAction.Next); runCurrent()
        assertEquals(ConnectionError.TIMEOUT, repository.state.value.error)
        assertEquals(ConnectionFailureSource.CONTROL, repository.state.value.failureSource)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(1, server.calls.count { it.first == "next" })
        repository.close()
    }

    private class FakeServer {
        val connections = mutableListOf<FakeConnection>()
        val calls = mutableListOf<Pair<String, List<String>>>()
        var events = Channel<Set<String>>(Channel.UNLIMITED)
        val ids = mutableListOf(11L, 12L)
        var selectedId: Long? = 11
        var version = 1
        var random = false
        var playback = "play"
        var reject: String? = null
        var pingError: IOException? = null
        var pingCalls = 0
        var pingBlock: suspend () -> Unit = {}
        var connectBlock: suspend () -> Unit = {}
        var statusBlock: suspend () -> Unit = {}
        var commandBlock: suspend (String) -> Unit = {}
        fun connection(): MpdConnection = FakeConnection(this).also { connections.add(it) }
        var rangeBlock: suspend (Int) -> Unit = {}
        var positionOffset = 0
        var extraTag = emptyList<Pair<String, String>>()
        fun ranges() = calls.filter { it.first == "playlistinfo" }.map { it.second.single() }
        fun song(id: Long) = listOf("file" to "same.mp3", "Id" to id.toString(), "Title" to "Song $id",
            "Artist" to "Artist", "Artist" to "Guest", "Album" to "Album", "duration" to "180")
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
                "playlistinfo" -> {
                    val (start, end) = arguments.single().split(':').map(String::toInt)
                    rangeBlock(start)
                    if (start > ids.size) throw MpdAckException(2, 0, name)
                    ids.subList(start, minOf(end, ids.size)).flatMapIndexed { index, id ->
                        song(id) + ("Pos" to (start + index + positionOffset).toString()) + extraTag
                    }
                }
                "clear" -> { ids.clear(); selectedId = null; playback = "stop"; version++; emptyList() }
                "moveid" -> {
                    ids.remove(arguments[0].toLong())
                    ids.add(arguments[1].toInt(), arguments[0].toLong())
                    version++
                    emptyList()
                }
                "deleteid" -> { ids.remove(arguments.single().toLong()); version++; emptyList() }
                else -> emptyList()
            }
        }
    }

    private class FakeConnection(private val server: FakeServer) : MpdConnection {
        var closed = false
        var idleCancelled = false
        var idleCalls = 0
        override suspend fun connect(host: String, port: Int) = server.connectBlock()
        override suspend fun ping() { server.pingCalls++; server.pingBlock(); server.pingError?.let { throw it } }
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
