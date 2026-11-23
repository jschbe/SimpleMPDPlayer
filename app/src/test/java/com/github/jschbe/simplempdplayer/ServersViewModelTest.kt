package com.github.jschbe.simplempdplayer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import com.github.jschbe.simplempdplayer.data.*
import com.github.jschbe.simplempdplayer.audio.*
import com.github.jschbe.simplempdplayer.mpd.*
import com.github.jschbe.simplempdplayer.timer.*
import java.io.IOException
import java.io.EOFException
import java.net.ConnectException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    private val models = ViewModelStore()
    private val first = ServerInput("First", "localhost").toProfile()!!
    private val second = ServerInput("Second", "localhost").toProfile()!!

    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun clear() { models.clear(); Dispatchers.resetMain() }

    @Test fun emptySetupOpensOnceAndAgainAfterLastDeletion() = vmTest {
        val fixture = fixture()
        runCurrent()
        assertNotNull(fixture.vm.state.value.editor)
        fixture.vm.dismissEditor()
        fixture.vm.setTheme(ThemeMode.DARK)
        runCurrent()
        assertNull(fixture.vm.state.value.editor)
        fixture.vm.save(first)
        runCurrent()
        assertNull(fixture.vm.state.value.editor)
        fixture.vm.delete(first)
        runCurrent()
        assertNotNull(fixture.vm.state.value.editor)
        assertTrue(fixture.vm.state.value.settings.servers.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, fixture.vm.state.value.connection.status)
        fixture.vm.dismissEditor()
        fixture.vm.setTheme(ThemeMode.LIGHT)
        runCurrent()
        assertNull(fixture.vm.state.value.editor)
    }

    @Test fun existingUnselectedProfileDoesNotOpenSetup() = vmTest {
        val f = fixture(listOf(first))
        runCurrent()
        assertNull(f.vm.state.value.editor)
        assertTrue(f.connections.isEmpty())
    }

    @Test fun saveActivatesConnectsExactlyOnceAndNotifiesOnlyAfterResult() = vmTest {
        val f = fixture()
        val notices = mutableListOf<UiNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.notices.collect(notices::add) }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.connectGate = { gate.await() }
        f.vm.save(first)
        runCurrent()
        assertEquals(first.id, f.vm.state.value.settings.selectedId)
        assertNull(f.vm.state.value.editor)
        assertEquals(ConnectionStatus.CONNECTING, f.vm.state.value.connection.status)
        assertTrue(notices.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, f.connections.size)
        assertEquals(NoticeMessage.CONNECTED, notices.single().message)
        assertEquals(ConnectionStatus.CONNECTED, f.vm.state.value.connection.status)
    }

    @Test fun editingInactiveOrSameEndpointProfileAlwaysActivatesAndReconnectsOnce() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        runCurrent()
        f.vm.openEditor(second)
        f.vm.save(second.copy(name = "Edited"))
        runCurrent()
        assertEquals(second.id, f.vm.state.value.settings.selectedId)
        assertEquals(4, f.connections.size)
        assertTrue(f.connections.take(2).all { it.closed })
        f.vm.openEditor(second)
        f.vm.save(second.copy(name = "Edited again"))
        runCurrent()
        assertEquals(6, f.connections.size)
    }

    @Test fun failedSaveConnectionKeepsProfileAndShowsDetailedState() = vmTest {
        val f = fixture()
        val notices = mutableListOf<UiNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.notices.collect(notices::add) }
        runCurrent()
        f.failure = ConnectException("Connection refused")
        f.vm.save(first)
        runCurrent()
        assertEquals(listOf(first), f.vm.state.value.settings.servers)
        assertEquals(first.id, f.vm.state.value.settings.selectedId)
        assertEquals(ConnectionError.REFUSED, f.vm.state.value.connection.error)
        assertEquals(NoticeMessage.CONNECTION_FAILED, notices.single().message)
        assertNull(f.vm.state.value.editor)
    }

    @Test fun diskFailureDoesNotCloseDialogOrOpenConnection() = vmTest {
        val f = fixture()
        runCurrent()
        val editor = f.vm.state.value.editor
        f.data.failWrites = true
        f.vm.save(first)
        runCurrent()
        assertEquals(editor, f.vm.state.value.editor)
        assertTrue(f.vm.state.value.saveFailed)
        assertTrue(f.connections.isEmpty())
        assertTrue(f.vm.state.value.settings.servers.isEmpty())
    }

    @Test fun conflictingSaveRefreshesValidationWithoutClosingEditorOrConnecting() = vmTest {
        val f = fixture(listOf(first), first.id)
        runCurrent()
        f.vm.openEditor()
        val editor = f.vm.state.value.editor
        val newProfile = second.copy(name = "Concurrent")
        f.vm.save(newProfile)
        // Another writer saves the same name after the editor's snapshot, before its atomic write.
        val concurrent = ServerInput(" CONCURRENT ", "::1").toProfile()!!
        f.data.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[stringPreferencesKey("server_profiles_v1")] = ServerSettingsCodec.encode(listOf(first, concurrent))
            }
        }
        runCurrent()
        assertEquals(editor, f.vm.state.value.editor)
        assertEquals(listOf(first, concurrent), f.vm.state.value.settings.servers)
        assertEquals(first.id, f.vm.state.value.settings.selectedId)
        assertFalse(f.vm.state.value.saving)
        assertFalse(f.vm.state.value.saveFailed)
        assertTrue(f.vm.state.value.settings.servers.hasDuplicateName(newProfile.name))
        assertEquals(2, f.connections.size)
        assertTrue(f.connections.none { it.closed })
        f.vm.save(newProfile.copy(name = "Unique"))
        runCurrent()
        assertNull(f.vm.state.value.editor)
        assertEquals(newProfile.id, f.vm.state.value.settings.selectedId)
        assertEquals(4, f.connections.size)
    }

    @Test fun probeClosesTemporaryConnectionWithoutSavingOrInterruptingActiveSession() = vmTest {
        val f = fixture(listOf(first), first.id)
        runCurrent()
        val active = f.connections.toList()
        f.vm.openEditor()
        f.vm.test(second)
        runCurrent()
        assertEquals(3, f.connections.size)
        assertTrue(f.connections.last().closed)
        assertEquals(1, f.connections.last().pings)
        assertTrue(active.none { it.closed })
        assertEquals(listOf(first), f.vm.state.value.settings.servers)
        assertEquals(first.id, f.vm.state.value.settings.selectedId)
        assertEquals(ConnectionStatus.CONNECTED, f.vm.state.value.connection.status)
    }

    @Test fun probeErrorsStayInDialogAndParallelTestsArePrevented() = vmTest {
        val f = fixture()
        val notices = mutableListOf<UiNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.notices.collect(notices::add) }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.connectGate = { gate.await() }
        f.failure = ConnectException("Connection refused")
        f.vm.test(first)
        f.vm.test(first)
        runCurrent()
        assertTrue(f.vm.state.value.editor!!.testing)
        assertEquals(1, f.connections.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(ConnectionError.REFUSED, f.vm.state.value.editor!!.testError)
        assertEquals(NoticeMessage.MPD_UNAVAILABLE, notices.single().message)
        assertTrue(f.vm.state.value.settings.servers.isEmpty())
    }

    @Test fun staleSaveAndProbeResultsDoNotProduceToasts() = vmTest {
        val f = fixture(listOf(first, second))
        val notices = mutableListOf<UiNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.notices.collect(notices::add) }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.connectGate = { withContext(NonCancellable) { gate.await() } }
        f.vm.openEditor(first)
        f.vm.save(first)
        runCurrent()
        f.connectGate = {}
        f.vm.select(second)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(notices.isEmpty())
        assertEquals(second.id, f.vm.state.value.settings.selectedId)
        f.vm.openEditor()
        val probeGate = CompletableDeferred<Unit>()
        f.connectGate = { withContext(NonCancellable) { probeGate.await() } }
        f.vm.test(first)
        runCurrent()
        f.vm.dismissEditor()
        probeGate.complete(Unit)
        runCurrent()
        assertTrue(notices.isEmpty())
        assertNull(f.vm.state.value.editor)
        assertTrue(f.connections.last().closed)
    }

    @Test fun reorderAndThemeDoNotChangeSelectionOrConnection() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        runCurrent()
        f.vm.reorder(listOf(second.id, first.id))
        runCurrent()
        f.vm.setTheme(ThemeMode.DARK)
        runCurrent()
        assertEquals(listOf(second, first), f.vm.state.value.settings.servers)
        assertEquals(first.id, f.vm.state.value.settings.selectedId)
        assertEquals(ThemeMode.DARK, f.vm.state.value.settings.theme)
        assertEquals(2, f.connections.size)
        assertTrue(f.connections.none { it.closed })
    }

    @Test fun deletingActiveServerDisconnectsWithoutSelectingAnother() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        runCurrent()
        f.vm.delete(first)
        runCurrent()
        assertNull(f.vm.state.value.settings.selectedId)
        assertEquals(listOf(second), f.vm.state.value.settings.servers)
        assertTrue(f.connections.all { it.closed })
        assertEquals(ConnectionStatus.DISCONNECTED, f.vm.state.value.connection.status)
        assertNull(f.vm.state.value.editor)
    }

    @Test fun resumeAndFocusBurstsCheckOnlySelectedServerOnceWithoutReplacingHealthySockets() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        runCurrent()
        val control = f.connections.first()
        val pings = control.pings
        val statusReads = control.commands.count { it == "status" }
        repeat(4) { f.vm.onForeground(); f.vm.onWindowFocusGained() }
        runCurrent()
        advanceTimeBy(299); runCurrent()
        assertEquals(pings, control.pings)
        advanceTimeBy(1); runCurrent()
        assertEquals(pings + 1, control.pings)
        assertEquals(statusReads + 2, control.commands.count { it == "status" })
        assertFalse(control.commands.contains("playlistinfo"))
        assertEquals(2, f.connections.size)
        assertTrue(f.connections.none { it.closed })
        assertEquals(first.id, f.vm.state.value.settings.selectedId)
        assertTrue(f.connections.all { it.commands.all { command -> command in setOf("status", "currentsong", "playlistinfo") } })
    }

    @Test fun foregroundBeforeSettingsLoadRevalidatesTheAdoptedSharedSession() = vmTest {
        val connections = mutableListOf<FakeConnection>()
        val repository = MpdRepository(backgroundScope, { FakeConnection({}, null).also(connections::add) })
        repository.connect(first)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("server_profiles_v1") to ServerSettingsCodec.encode(listOf(first, second)),
            stringPreferencesKey("selected_server_id") to first.id,
        )
        val data = object : DataStore<Preferences> {
            override val data = flow<Preferences> { gate.await(); emit(preferences) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("No writes expected")
        }
        val vm = ServersViewModel(ServerSettingsStore(data), sharedRepository = repository)
        models.put("adopted", vm)
        val pings = connections.first().pings
        vm.onForeground(); vm.onWindowFocusGained()
        advanceTimeBy(300); runCurrent()
        assertEquals(pings, connections.first().pings)
        gate.complete(Unit)
        runCurrent()
        assertEquals(pings + 1, connections.first().pings)
        assertEquals(2, connections.size)
        assertEquals(first.id, vm.state.value.activeServer!!.id)
        repository.close()
    }

    @Test fun focusFailureUsesExistingBackoffWithoutRestartingAnOngoingRecovery() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        runCurrent()
        f.connections.first().pingFailure = EOFException()
        f.vm.onWindowFocusGained()
        advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionFailureSource.CONTROL, f.vm.state.value.connection.failureSource)
        assertTrue(f.vm.state.value.connection.recovering)
        repeat(3) { f.vm.onForeground(); f.vm.onWindowFocusGained() }
        advanceTimeBy(999); runCurrent()
        assertEquals(2, f.connections.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(4, f.connections.size)
        assertEquals(ConnectionStatus.CONNECTED, f.vm.state.value.connection.status)
        assertTrue(f.vm.state.value.connection.idleConnected)
        assertFalse(f.vm.state.value.connection.recovering)
        assertNull(f.vm.state.value.connection.error)
    }

    @Test fun oneDepartureAuthorizesOneRecoveryInEitherCallbackOrder() = vmTest {
        for (focusFirst in listOf(false, true)) {
            val f = fixture(listOf(first, second), first.id)
            f.failure = EOFException()
            runCurrent()
            advanceTimeBy(31_000); runCurrent()
            assertTrue(f.vm.state.value.connection.retriesExhausted)
            repeat(3) {
                f.vm.onWindowFocusGained(canReturn = true)
                f.vm.onForeground(canReturn = true)
                advanceTimeBy(1_000); runCurrent()
            }
            assertEquals(12, f.connections.size)
            f.vm.noteDeparture()
            f.vm.noteDeparture() // Screen-off and stop can describe the same departure.
            if (focusFirst) {
                f.vm.onWindowFocusGained(canReturn = true)
                f.vm.onForeground(canReturn = true)
            } else {
                f.vm.onForeground(canReturn = false)
                f.vm.onWindowFocusGained(canReturn = true)
            }
            assertFalse(f.vm.onVisibleReturn())
            advanceTimeBy(31_000); runCurrent()
            assertTrue(f.vm.state.value.connection.retriesExhausted)
            assertEquals(22, f.connections.size)
            f.failure = null
            f.vm.noteDeparture()
            f.vm.onForeground(canReturn = true)
            advanceTimeBy(1_000); runCurrent()
            assertTrue(f.vm.state.value.connection.livePlayerStatus)
            assertEquals(24, f.connections.size)
        }
    }

    @Test fun selectionChangeInvalidatesAnOutstandingDeparture() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        runCurrent()
        f.vm.noteDeparture()
        f.failure = EOFException()
        f.vm.select(second); runCurrent()
        advanceTimeBy(31_000); runCurrent()
        assertTrue(f.vm.state.value.connection.retriesExhausted)
        f.vm.onForeground(canReturn = true)
        f.vm.onWindowFocusGained(canReturn = true)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(14, f.connections.size)
        assertTrue(f.vm.state.value.connection.retriesExhausted)
    }

    @Test fun foregroundReconciliationCannotStartAWaitingTimerFromPreReturnPlaying() = vmTest {
        var playback = "pause"
        val data = MemoryDataStore(mutablePreferencesOf(
            stringPreferencesKey("server_profiles_v1") to ServerSettingsCodec.encode(listOf(first)),
            stringPreferencesKey("selected_server_id") to first.id,
        ))
        val profiles = ServerSettingsStore(data)
        val repository = MpdRepository(backgroundScope, {
            FakeConnection({}, null).apply {
                statusFields = { listOf("state" to playback, "playlist" to "1", "playlistlength" to "0") }
            }
        }, elapsedMillis = { testScheduler.currentTime })
        val store = object : TimerStorage {
            val saved = MutableStateFlow(TimerSettings())
            override val settings = saved.asStateFlow()
            override suspend fun read() = saved.value
            override suspend fun write(settings: TimerSettings) { saved.value = settings }
        }
        val clock = object : TimerClock {
            override fun elapsed() = testScheduler.currentTime
            override fun wallTime() = elapsed()
            override fun boot() = 1
        }
        val alarms = mutableListOf<TimerRecord>()
        val timer = SleepTimerController(store, profiles.settings, clock, object : TimerScheduler {
            override fun schedule(record: TimerRecord) { alarms.add(record) }
            override fun cancel() = Unit
            override fun hasPendingIntent() = alarms.isNotEmpty()
        }, Mutex(), pause = { error("Unexpected delivery") },
            playback = { repository.activeServer?.let { TimerPlayback(it, repository.state.value) } })
        timer.observe(backgroundScope)
        val vm = ServersViewModel(profiles, sharedRepository = repository, sleepTimer = timer)
        models.put("foreground-timer", vm)
        runCurrent()
        timer.setEnabled(true); runCurrent()
        assertTrue(timer.state.value.waiting)

        // The process observer has not delivered this earlier Playing snapshot to the waiting timer.
        playback = "play"
        repository.checkConnection(); runCurrent()
        assertTrue(repository.state.value.livePlayerStatus)
        assertEquals(PlaybackState.PLAYING, repository.state.value.snapshot!!.status.playback)
        playback = "pause"
        vm.onForeground()
        backgroundScope.launch { repository.state.collect { timer.playbackChanged() } }
        advanceTimeBy(299); runCurrent()
        assertNull(repository.state.value.playerConfirmedAtMillis)
        assertTrue(timer.state.value.waiting)
        assertTrue(alarms.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(PlaybackState.PAUSED, repository.state.value.snapshot!!.status.playback)
        assertTrue(timer.state.value.waiting)

        playback = "play"
        repository.checkConnection(); runCurrent()
        assertEquals(clock.elapsed() + 30 * 60_000L, alarms.single().deadline)
        assertTrue(timer.state.value.active)
        repository.close()
    }

    @Test fun progressFreezesForOfflineAndIdleLossThenUsesRecoveredPlaybackAndPosition() = vmTest {
        val connections = mutableListOf<FakeConnection>()
        val now = { testScheduler.currentTime * 1_000_000 }
        var playback = "play"
        var elapsed = "10"
        var songId = "1"
        val eventLoss = CompletableDeferred<Unit>()
        val repository = MpdRepository(backgroundScope, {
            FakeConnection({}, null).apply {
                statusFields = { listOf("state" to playback, "playlist" to songId, "playlistlength" to "1",
                    "elapsed" to elapsed, "songid" to songId) }
                songFields = { listOf("file" to "track-$songId.flac", "Id" to songId, "Pos" to "0") }
                if (connections.size == 1) idleBlock = { eventLoss.await(); throw EOFException() }
            }.also(connections::add)
        }, nanoTime = now)
        val vm = ServersViewModel(ServerSettingsStore(MemoryDataStore(mutablePreferencesOf(
            stringPreferencesKey("server_profiles_v1") to ServerSettingsCodec.encode(listOf(first)),
            stringPreferencesKey("selected_server_id") to first.id,
        ))),
            sharedRepository = repository, nanoTime = now)
        models.put("progress", vm)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(11.0, vm.state.value.positionSeconds!!, 0.001)
        repository.networkChanged(false); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertEquals(11.0, vm.state.value.positionSeconds!!, 0.001)

        elapsed = "20"
        repository.networkChanged(true); runCurrent()
        assertEquals(20.0, vm.state.value.positionSeconds!!, 0.001)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(21.0, vm.state.value.positionSeconds!!, 0.001)
        eventLoss.complete(Unit); runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(21.0, vm.state.value.positionSeconds!!, 0.001)
        elapsed = "30"
        repository.confirmTimerPlayback(); runCurrent()
        assertEquals(30.0, vm.state.value.positionSeconds!!, 0.001)
        assertFalse(vm.state.value.connection.livePlayerStatus)
        advanceTimeBy(500); runCurrent()
        assertTrue(vm.state.value.connection.livePlayerStatus)
        advanceTimeBy(500); runCurrent()
        assertEquals(30.5, vm.state.value.positionSeconds!!, 0.001)

        for ((state, position) in listOf("pause" to "31", "stop" to "0", "play" to "4")) {
            playback = state
            elapsed = position
            if (state == "play") songId = "2"
            repository.checkConnection(); runCurrent()
            assertEquals(songId.toLong(), vm.state.value.connection.snapshot!!.currentSong!!.id)
            assertEquals(position.toDouble(), vm.state.value.positionSeconds!!, 0.001)
            advanceTimeBy(500); runCurrent()
            assertEquals(position.toDouble() + if (state == "play") 0.5 else 0.0, vm.state.value.positionSeconds!!, 0.001)
        }
        repository.close()
    }

    @Test fun switchingDiscardsQueuedFocusChecksAndOldRecoveryErrors() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        runCurrent()
        val old = f.connections.first()
        f.vm.onWindowFocusGained()
        f.vm.select(second)
        runCurrent()
        val pings = old.pings
        advanceTimeBy(300); runCurrent()
        assertEquals(pings, old.pings)
        f.connections[2].pingFailure = EOFException()
        f.vm.onForeground()
        advanceTimeBy(300); runCurrent()
        assertTrue(f.vm.state.value.connection.recovering)
        f.vm.select(first)
        runCurrent()
        f.vm.onWindowFocusGained()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(6, f.connections.size)
        assertEquals(first.id, f.vm.state.value.settings.selectedId)
        assertEquals(ConnectionStatus.CONNECTED, f.vm.state.value.connection.status)
        assertNull(f.vm.state.value.connection.error)
        assertFalse(f.vm.state.value.connection.recovering)
    }

    @Test fun lateInactiveHealthFailureCannotReappearInUiOrNotices() = vmTest {
        val f = fixture(listOf(first, second), first.id)
        val notices = mutableListOf<UiNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.notices.collect(notices::add) }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.connections.first().pingGate = { withContext(NonCancellable) { gate.await(); throw EOFException() } }
        f.vm.onWindowFocusGained()
        advanceTimeBy(300); runCurrent()
        f.vm.select(second)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        f.vm.onForeground(); f.vm.onWindowFocusGained()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(second.id, f.vm.state.value.settings.selectedId)
        assertEquals(ConnectionStatus.CONNECTED, f.vm.state.value.connection.status)
        assertNull(f.vm.state.value.connection.error)
        assertFalse(f.vm.state.value.connection.recovering)
        assertEquals(4, f.connections.size)
        assertTrue(notices.isEmpty())
    }

    @Test fun localActionsSendNoMpdCommandsAndSwitchReleasesBeforeConnecting() = vmTest {
        var starts = 0
        var stops = 0
        val stream = LocalStreamController(object : StreamBackend {
            override fun start(generation: Long) { starts++ }
            override fun stop() { stops++ }
        })
        val f = fixture(listOf(first, second), first.id, stream)
        runCurrent()
        val commands = f.connections.flatMap { it.commands }.toList()
        f.vm.setLocalListening(true)
        runCurrent()
        assertEquals(1, starts)
        assertTrue(f.vm.state.value.localStream.enabled)
        f.vm.setLocalListening(false)
        runCurrent()
        assertFalse(f.vm.state.value.localStream.enabled)
        assertEquals(commands, f.connections.flatMap { it.commands })
        f.vm.setLocalListening(true)
        runCurrent()
        val oldStops = stops
        f.connectGate = {
            assertFalse(stream.state.value.enabled)
            assertTrue(stops > oldStops)
        }
        f.vm.select(second)
        runCurrent()
        assertEquals(second.id, f.vm.state.value.settings.selectedId)
        assertFalse(f.vm.state.value.localStream.enabled)
        assertEquals(2, starts)
        f.vm.setLocalListening(true)
        f.connectGate = { assertTrue(stream.state.value.enabled) }
        val generation = stream.request.value!!.generation
        f.vm.reconnect()
        runCurrent()
        assertTrue(stream.state.value.enabled)
        assertEquals(generation, stream.request.value!!.generation)
        assertEquals(3, starts)
    }

    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        try { block() } finally { models.clear(); runCurrent() }
    }

    private fun fixture(profiles: List<ServerProfile> = emptyList(), selected: String? = null, stream: LocalStreamController? = null): Fixture {
        val f = Fixture(profiles, selected, stream)
        models.put("model${System.nanoTime()}", f.vm)
        return f
    }

    private class Fixture(profiles: List<ServerProfile>, selected: String?, stream: LocalStreamController?) {
        val data = MemoryDataStore(mutablePreferencesOf(stringPreferencesKey("server_profiles_v1") to ServerSettingsCodec.encode(profiles)).apply {
            selected?.let { this[stringPreferencesKey("selected_server_id")] = it }
        })
        var connectGate: suspend () -> Unit = {}
        var failure: IOException? = null
        val connections = mutableListOf<FakeConnection>()
        val vm = ServersViewModel(ServerSettingsStore(data), localStream = stream, connectionFactory = {
            FakeConnection(connectGate, failure).also(connections::add)
        })
    }

    private class FakeConnection(private val gate: suspend () -> Unit, private val failure: IOException?) : MpdConnection {
        var closed = false
        var pings = 0
        var pingFailure: IOException? = null
        var pingGate: suspend () -> Unit = {}
        var idleBlock: suspend () -> Set<String> = { awaitCancellation() }
        var songFields: () -> List<Pair<String, String>> = { emptyList() }
        var statusFields: () -> List<Pair<String, String>> = {
            listOf("state" to "stop", "playlist" to "1", "playlistlength" to "0")
        }
        val commands = mutableListOf<String>()
        override suspend fun connect(host: String, port: Int) { gate(); failure?.let { throw it } }
        override suspend fun ping() { pings++; pingGate(); pingFailure?.let { throw it } }
        override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> {
            commands.add(name)
            return when (name) {
                "status" -> statusFields()
                "playlistinfo" -> { check(arguments.single() == "0:1"); songFields() }
                "currentsong" -> songFields()
                else -> emptyList()
            }
        }
        override suspend fun idle(): Set<String> = idleBlock()
        override fun close() { closed = true }
    }

    private class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        var failWrites = false
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            if (failWrites) throw IOException("Write failed")
            transform(data.value).also { data.value = it }
        }
    }
}
