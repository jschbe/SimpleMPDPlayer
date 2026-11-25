package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.CredentialReadWorker
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.timer.TimerError
import java.io.EOFException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpdCredentialTimeoutTest {
    private val guest = ServerInput("Guest fixture", "localhost").toProfile()!!
    private val protected = ServerInput("Protected fixture", "localhost").toProfile()!!
        .copy(credentialRevision = UUID.randomUUID().toString())
    private val synthetic = "synthetic fixture"

    @Test fun initialConnectionTimesOutBeforeDialAndUsesExistingRetryCycle() = runTest {
        val clients = mutableListOf<Connection>()
        var reads = 0
        val repository = MpdRepository(backgroundScope,
            connectionFactory = { Connection().also(clients::add) },
            resolvePassword = { if (++reads == 1) awaitCancellation() else synthetic })
        try {
            repository.connect(protected)
            runCurrent()
            advanceTimeBy(CREDENTIAL_READ_TIMEOUT_MILLIS - 1)
            assertEquals(ConnectionStatus.CONNECTING, repository.state.value.status)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(ConnectionError.TIMEOUT, repository.state.value.error)
            assertTrue(repository.state.value.recovering)
            assertTrue(clients.all { it.closed && it.connects == 0 })
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(repository.state.value.idleConnected)
            assertEquals(3, reads)
        } finally { repository.close() }
    }

    @Test fun probeTimeoutAndCancellationReturnWhileBothProviderWorkersRemainBlocked() = runTest {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val pool = CredentialReadWorker()
        val clients = mutableListOf<Connection>()
        val repository = MpdRepository(backgroundScope, connectionFactory = { Connection().also(clients::add) })
        fun blockedRead(): String {
            entered.countDown()
            try {
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                return synthetic
            } finally { done.countDown() }
        }
        try {
            val timed = async { repository.testConnection(protected) { pool.read(::blockedRead) } }
            val cancelled = async { repository.testConnection(protected) { pool.read(::blockedRead) } }
            runCurrent()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            cancelled.cancelAndJoin()
            advanceTimeBy(CREDENTIAL_READ_TIMEOUT_MILLIS)
            assertEquals(ConnectionError.TIMEOUT, timed.await())
            assertEquals(2L, done.count)
            assertTrue(clients.all { it.closed && it.connects == 0 })
            assertEquals(ConnectionError.CREDENTIAL_UNREADABLE,
                repository.testConnection(protected) { pool.read { error("Both workers are still occupied") } })
        } finally {
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            runCurrent()
            repository.close()
        }
        assertTrue(clients.all { it.closed && it.connects == 0 && it.commands.isEmpty() })
    }

    @Test fun serverSwitchRejectsAResultEvenIfResolverIgnoresCancellation() = runTest {
        val clients = mutableListOf<Connection>()
        val release = CompletableDeferred<String>()
        val repository = MpdRepository(backgroundScope,
            connectionFactory = { Connection().also(clients::add) },
            resolvePassword = { target ->
                if (target.id == protected.id) withContext(NonCancellable) { release.await() } else null
            })
        try {
            repository.connect(protected)
            runCurrent()
            repository.connect(guest)
            runCurrent()
            release.complete(synthetic)
            runCurrent()
            assertEquals(guest, repository.activeServer)
            assertTrue(repository.state.value.idleConnected)
            assertTrue(clients.take(2).all { it.closed && it.connects == 0 && it.commands.isEmpty() })
        } finally {
            release.complete(synthetic)
            repository.close()
        }
    }

    @Test fun stalledIdleRepairSpendsOneAttemptAndKeepsControlUsable() = runTest {
        val clients = mutableListOf<Connection>()
        val dropIdle = CompletableDeferred<Unit>()
        var reads = 0
        val repository = MpdRepository(backgroundScope,
            connectionFactory = { Connection(dropIdle.takeIf { clients.size == 1 }).also(clients::add) },
            resolvePassword = { if (++reads == 3) awaitCancellation() else synthetic })
        try {
            repository.connect(protected)
            runCurrent()
            assertTrue(repository.state.value.idleConnected)
            dropIdle.complete(Unit)
            runCurrent()
            advanceTimeBy(1_000 + CREDENTIAL_READ_TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(ConnectionFailureSource.IDLE, repository.state.value.failureSource)
            assertEquals(ConnectionError.TIMEOUT, repository.state.value.error)
            assertEquals(1, repository.state.value.retryAttempt)
            assertTrue(clients[2].closed && clients[2].connects == 0)
            assertFalse(clients[0].closed)
            requireNotNull(repository.perform(PlayerAction.Next)).join()
            assertTrue("next" in clients[0].commands)
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(repository.state.value.idleConnected)
            assertEquals(1, clients[0].connects)
            assertEquals(4, clients.size)
        } finally { repository.close() }
    }

    @Test fun credentialReadAndConnectionShareTheExistingTimerBudget() = runTest {
        val client = object : Connection() {
            override suspend fun connect(host: String, port: Int) {
                super.connect(host, port)
                awaitCancellation()
            }
        }
        val started = currentTime
        val error = MpdRepository.pauseForTimer(protected, factory = { client },
            resolvePassword = { delay(4_500); synthetic })
        assertEquals(TimerError.TIMEOUT, error)
        assertEquals(6_000, currentTime - started)
        assertEquals(1, client.connects)
        assertTrue(client.closed && client.commands.isEmpty())
    }

    private open class Connection(private val idleDrop: CompletableDeferred<Unit>? = null) : MpdConnection {
        var connects = 0
        var closed = false
        val commands = mutableListOf<String>()
        override suspend fun connect(host: String, port: Int) { connects++ }
        override suspend fun ping() = Unit
        override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> {
            commands.add(name)
            return if (name == "status") listOf("state" to "stop", "playlist" to "1", "playlistlength" to "0")
            else emptyList()
        }
        override suspend fun idle(): Set<String> {
            idleDrop?.await() ?: awaitCancellation()
            throw EOFException()
        }
        override fun close() { closed = true }
    }
}
