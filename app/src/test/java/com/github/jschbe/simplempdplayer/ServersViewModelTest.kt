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
import java.io.IOException
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
        val commands = mutableListOf<String>()
        override suspend fun connect(host: String, port: Int) { gate(); failure?.let { throw it } }
        override suspend fun ping() { pings++ }
        override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> {
            commands.add(name)
            return if (name == "status") listOf("state" to "stop", "playlist" to "1", "playlistlength" to "0") else emptyList()
        }
        override suspend fun idle(): Set<String> = awaitCancellation()
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
