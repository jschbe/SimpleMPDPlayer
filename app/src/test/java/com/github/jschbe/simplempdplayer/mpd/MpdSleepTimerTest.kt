package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.timer.TimerError
import java.net.ConnectException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpdSleepTimerTest {
    @Test fun shortConnectionValidatesGreetingSendsOnlyExplicitPauseAndCloses() = runBlocking {
        FakeMpdServer { socket ->
            val reader = socket.getInputStream().bufferedReader()
            socket.send("OK MPD 0.23.0\n")
            assertEquals("pause \"1\"", reader.readLine())
            socket.send("OK\n")
            assertNull(reader.readLine())
        }.use { server ->
            val profile = ServerInput("Fake", server.host, server.port.toString()).toProfile()!!
            assertNull(MpdRepository.pauseForTimer(profile))
            server.await()
        }
    }

    @Test fun ackAndAuthenticationFailuresRemainDistinct() = runBlocking {
        for ((code, expected) in listOf(3 to TimerError.PASSWORD_REJECTED, 4 to TimerError.MPD_PERMISSION, 5 to TimerError.REJECTED)) {
            FakeMpdServer { socket ->
                socket.send("OK MPD 0.23.0\n")
                assertEquals("pause \"1\"", socket.getInputStream().bufferedReader().readLine())
                socket.send("ACK [$code@0] {pause} private server detail\n")
            }.use { server ->
                assertEquals(expected, MpdRepository.pauseForTimer(ServerInput("Fake", server.host, server.port.toString()).toProfile()!!))
                server.await()
            }
        }
    }

    @Test fun badGreetingNeverSendsAnyCommand() = runBlocking {
        FakeMpdServer { socket ->
            val reader = socket.getInputStream().bufferedReader()
            socket.send("not MPD\n")
            assertNull(reader.readLine())
        }.use { server ->
            assertEquals(TimerError.PROTOCOL, MpdRepository.pauseForTimer(ServerInput("Fake", server.host, server.port.toString()).toProfile()!!))
            server.await()
        }
    }

    @Test fun timeoutCancellationAndConnectionFailureCloseTheClientWithoutRetry() = runTest {
        var closes = 0
        var attempts = 0
        val profile = ServerInput("Fake", "localhost").toProfile()!!
        val client = object : MpdConnection {
            override suspend fun connect(host: String, port: Int) { attempts++; awaitCancellation() }
            override suspend fun ping() { error("No ping") }
            override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> = error("No command before greeting")
            override suspend fun idle(): Set<String> = error("No idle")
            override fun close() { closes++ }
        }
        assertEquals(TimerError.TIMEOUT, MpdRepository.pauseForTimer(profile, factory = { client }))
        assertEquals(1, attempts); assertEquals(1, closes)
        val job = launch { MpdRepository.pauseForTimer(profile, factory = { client }) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(2, closes)
        assertEquals(TimerError.NETWORK, MpdRepository.pauseForTimer(profile, resolve = { throw ConnectException() }, factory = { client }))
        assertEquals(3, closes)
    }
}
