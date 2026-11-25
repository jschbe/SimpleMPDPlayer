package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.CredentialException
import com.github.jschbe.simplempdplayer.data.CredentialFailure
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.validMpdPassword
import com.github.jschbe.simplempdplayer.timer.TimerError
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpdAuthenticationTest {
    private val secret = "  träck \"♬\" \\ end  "
    private val wire = "password \"  träck \\\"♬\\\" \\\\ end  \""
    private val status = "state: stop\nplaylist: 1\nplaylistlength: 0\nOK\n"

    private fun FakeMpdServer.profile(protected: Boolean = true): ServerProfile =
        ServerInput("Synthetic", host, port.toString()).toProfile()!!.copy(
            credentialRevision = if (protected) UUID.randomUUID().toString() else null,
        )

    @Test fun temporaryProbeAuthenticatesAfterGreetingAndUsesReadOnlyStatus() = runBlocking {
        for (expected in listOf(null, ConnectionError.MPD_PERMISSION, ConnectionError.PASSWORD_REJECTED)) {
            FakeMpdServer { socket ->
                val reader = socket.getInputStream().bufferedReader()
                socket.send("OK MPD 0.23.12\n")
                assertTrue(reader.readLine() == wire)
                if (expected == ConnectionError.PASSWORD_REJECTED) {
                    socket.send("ACK [3@0] {password} private detail\n")
                } else {
                    socket.send("OK\n")
                    assertEquals("status", reader.readLine())
                    socket.send(if (expected == null) status else "ACK [4@0] {status} private detail\n")
                }
                assertNull(reader.readLine())
            }.use { server ->
                val repository = MpdRepository(this)
                assertEquals(expected, repository.testConnection(server.profile()) { secret })
                assertNull(repository.activeServer)
                server.await()
            }
        }
    }

    @Test fun guestStatusCanBeDeniedEvenWherePingWouldWork() = runBlocking {
        FakeMpdServer { socket ->
            val reader = socket.getInputStream().bufferedReader()
            socket.send("OK MPD 0.23.12\n")
            val command = reader.readLine()
            if (command == "ping") socket.send("OK\n")
            assertEquals("status", command)
            socket.send("ACK [4@0] {status} private detail\n")
            assertNull(reader.readLine())
        }.use { server ->
            assertEquals(ConnectionError.MPD_PERMISSION, MpdRepository(this).testConnection(server.profile(false)))
            server.await()
        }
    }

    @Test fun malformedAuthSuccessAndUnsafeDraftsNeverReachStatus() = runBlocking {
        val invalid = listOf("", "line\nstatus", "line\rstatus", "line\u0000status", "\uD800")
        assertTrue(invalid.none(::validMpdPassword))
        assertTrue(validMpdPassword(secret))
        assertTrue(validMpdPassword(" "))
        val noSocket = object : MpdConnection {
            override suspend fun connect(host: String, port: Int) = error("Invalid draft opened a socket")
            override suspend fun ping() = error("Unexpected ping")
            override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> =
                error("Unexpected command")
            override suspend fun idle(): Set<String> = error("Unexpected idle")
            override fun close() = Unit
        }
        val profile = ServerInput("Protected", "localhost").toProfile()!!
        for (draft in invalid) {
            val repository = MpdRepository(this, connectionFactory = { noSocket })
            assertEquals(ConnectionError.INVALID_PASSWORD, repository.testConnection(profile) { draft })
        }
        FakeMpdServer { socket ->
            val reader = socket.getInputStream().bufferedReader()
            socket.send("OK MPD 0.23.12\n")
            assertTrue(reader.readLine() == wire)
            socket.send("unexpected: private detail\nOK\n")
            assertNull(reader.readLine())
        }.use { server ->
            assertEquals(ConnectionError.INVALID_RESPONSE, MpdRepository(this).testConnection(server.profile()) { secret })
            server.await()
        }
    }

    @Test fun controlRecoveryAndIsolatedIdleRepairEachAuthenticateOnTheNewSocket() = runBlocking {
        val breakControl = AtomicBoolean(false)
        val initialIdle = AtomicBoolean(false)
        val repairedIdle = AtomicBoolean(false)
        val finalIdle = AtomicBoolean(false)
        fun control(drop: Boolean): (Socket) -> Unit = { socket ->
            val reader = socket.getInputStream().bufferedReader()
            socket.send("OK MPD 0.23.12\n")
            assertTrue(reader.readLine() == wire)
            socket.send("OK\n")
            while (true) {
                when (val command = reader.readLine()) {
                    null -> break
                    "ping" -> if (drop && breakControl.get()) break else socket.send("OK\n")
                    "status" -> socket.send(status)
                    "currentsong" -> socket.send("OK\n")
                    else -> error("Unexpected fixture command type ${command.substringBefore(' ')}")
                }
            }
        }
        fun idle(seen: AtomicBoolean, drop: Boolean): (Socket) -> Unit = { socket ->
            val reader = socket.getInputStream().bufferedReader()
            socket.send("OK MPD 0.23.12\n")
            assertTrue(reader.readLine() == wire)
            socket.send("OK\n")
            assertTrue(reader.readLine().startsWith("idle "))
            seen.set(true)
            if (!drop) assertNull(reader.readLine())
        }
        FakeMpdServer(listOf(control(true), idle(initialIdle, true), idle(repairedIdle, false),
            control(false), idle(finalIdle, false))).use { server ->
            val profile = server.profile()
            val repository = MpdRepository(this, resolvePassword = { target ->
                assertEquals(profile.id, target.id)
                secret
            })
            try {
                repository.connect(profile)
                withTimeout(5_000) {
                    while (!initialIdle.get() || !repairedIdle.get() || !repository.state.value.idleConnected) delay(20)
                }
                assertEquals(1L, repository.state.value.sessionId)
                breakControl.set(true)
                repository.checkConnection()
                withTimeout(5_000) {
                    while (!finalIdle.get() || !repository.state.value.idleConnected) delay(20)
                }
                assertEquals(2L, repository.state.value.sessionId)
            } finally {
                repository.close()
            }
            server.await()
        }
    }

    @Test fun missingUnreadableRejectedAndDeniedControlDoNotUseAutomaticRetries() = runTest {
        val profile = ServerInput("Protected", "localhost").toProfile()!!.copy(credentialRevision = UUID.randomUUID().toString())
        for (kind in listOf(ConnectionError.CREDENTIAL_MISSING, ConnectionError.CREDENTIAL_UNREADABLE,
            ConnectionError.PASSWORD_REJECTED, ConnectionError.MPD_PERMISSION)) {
            var attempts = 0
            val repository = MpdRepository(backgroundScope, connectionFactory = {
                object : MpdConnection {
                    override suspend fun connect(host: String, port: Int) = Unit
                    override suspend fun ping() = Unit
                    override suspend fun idle(): Set<String> = error("No idle after failed control")
                    override fun close() = Unit
                    override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> =
                        when (name) {
                            "password" -> if (kind == ConnectionError.PASSWORD_REJECTED) throw MpdAckException(3, 0, name)
                                else emptyList()
                            "status" -> throw MpdAckException(4, 0, name)
                            else -> error("Unexpected command")
                        }
                }
            }, resolvePassword = {
                attempts++
                when (kind) {
                    ConnectionError.CREDENTIAL_MISSING -> throw CredentialException(CredentialFailure.MISSING)
                    ConnectionError.CREDENTIAL_UNREADABLE -> throw CredentialException(CredentialFailure.UNREADABLE)
                    else -> secret
                }
            })
            try {
                repository.connect(profile)
                runCurrent()
                assertEquals(kind, repository.state.value.error)
                assertFalse(repository.state.value.recovering)
                repository.noteDeparture()
                assertTrue(repository.consumeReturn())
                repository.resumeExhaustedRecovery()
                repository.checkConnection(ConnectionCheckTrigger.WINDOW_FOCUS)
                advanceTimeBy(40_000)
                assertEquals(1, attempts)
                assertEquals(0, repository.state.value.retryAttempt)
                repository.reconnect()
                runCurrent()
                assertEquals(2, attempts)
            } finally { repository.close() }
        }
    }

    @Test fun idlePermissionDenialPreservesControlAndDoesNotReconnect() = runTest {
        var openings = 0
        val profile = ServerInput("Protected", "localhost").toProfile()!!.copy(credentialRevision = UUID.randomUUID().toString())
        val repository = MpdRepository(backgroundScope, connectionFactory = {
            object : MpdConnection {
                private var authenticated = false
                override suspend fun connect(host: String, port: Int) { openings++ }
                override suspend fun ping() { check(authenticated) }
                override suspend fun idle(): Set<String> {
                    check(authenticated)
                    throw MpdAckException(4, 0, "idle")
                }
                override fun close() = Unit
                override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> = when (name) {
                    "password" -> emptyList<Pair<String, String>>().also { authenticated = true }
                    "status" -> listOf("state" to "stop", "playlist" to "1", "playlistlength" to "0").also { check(authenticated) }
                    else -> emptyList<Pair<String, String>>().also { check(authenticated) }
                }
            }
        }, resolvePassword = { secret })
        try {
            repository.connect(profile)
            runCurrent()
            assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
            assertEquals(ConnectionError.MPD_PERMISSION, repository.state.value.error)
            assertEquals(ConnectionFailureSource.IDLE, repository.state.value.failureSource)
            repository.checkConnection(ConnectionCheckTrigger.WINDOW_FOCUS)
            advanceTimeBy(40_000)
            assertEquals(2, openings)
            assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
            assertFalse(repository.state.value.idleConnected)
        } finally { repository.close() }
    }

    @Test fun timerUsesOnlyPasswordAndPauseAndNeverFallsBackToGuest() = runBlocking {
        val missing = ServerInput("Protected", "localhost").toProfile()!!.copy(credentialRevision = UUID.randomUUID().toString())
        assertEquals(TimerError.CREDENTIAL_MISSING, MpdRepository.pauseForTimer(missing,
            resolve = { error("Missing credential reached DNS") }))
        for (expected in listOf(null, TimerError.PASSWORD_REJECTED)) {
            FakeMpdServer { socket ->
                val reader = socket.getInputStream().bufferedReader()
                socket.send("OK MPD 0.23.12\n")
                assertTrue(reader.readLine() == wire)
                if (expected == TimerError.PASSWORD_REJECTED) socket.send("ACK [3@0] {password} private detail\n")
                else {
                    socket.send("OK\n")
                    assertEquals("pause \"1\"", reader.readLine())
                    socket.send("OK\n")
                }
                assertNull(reader.readLine())
            }.use { server ->
                assertEquals(expected, MpdRepository.pauseForTimer(server.profile(), resolvePassword = { secret }))
                server.await()
            }
        }
    }
}
