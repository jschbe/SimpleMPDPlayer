package com.github.jschbe.simplempdplayer.mpd

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MpdIdleTest {
    @Test
    fun idleHasNoOrdinaryReadDeadlineAndDoesNotBlockCommandSocket(): Unit = runBlocking {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newFixedThreadPool(2)
            val idleEntered = CountDownLatch(1)
            val releaseEvent = CountDownLatch(1)
            val serverTask = executor.submit {
                server.accept().use { commandSocket ->
                    val commandTask = executor.submit {
                        commandSocket.soTimeout = 3_000
                        commandSocket.send("OK MPD 0.24.0\n")
                        val input = commandSocket.getInputStream().bufferedReader()
                        assertEquals("ping", input.readLine())
                        commandSocket.send("OK\n")
                    }
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        socket.send("OK MPD 0.24.0\n")
                        assertEquals("idle \"player\" \"playlist\" \"mixer\" \"options\"", socket.getInputStream().bufferedReader().readLine())
                        idleEntered.countDown()
                        assertTrue(releaseEvent.await(3, TimeUnit.SECONDS))
                        socket.send("changed: player\nchanged: playlist\nOK\n")
                    }
                    commandTask.get(3, TimeUnit.SECONDS)
                }
            }
            try {
                MpdClient(readTimeoutMillis = 100, operationTimeoutMillis = 200).use { command ->
                    command.connect(requireNotNull(server.inetAddress.hostAddress), server.localPort)
                    MpdClient(readTimeoutMillis = 100, operationTimeoutMillis = 200).use { events ->
                        events.connect(requireNotNull(server.inetAddress.hostAddress), server.localPort)
                        val waiting = async { events.idle() }
                        withContext(Dispatchers.IO) { assertTrue(idleEntered.await(2, TimeUnit.SECONDS)) }
                        delay(350)
                        assertFalse(waiting.isCompleted)
                        command.ping()
                        releaseEvent.countDown()
                        assertEquals(setOf("player", "playlist"), waiting.await())
                    }
                }
                serverTask.get(3, TimeUnit.SECONDS)
            } finally {
                releaseEvent.countDown()
                executor.shutdownNow()
                executor.awaitTermination(4, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun cancellingIdleClosesItsSocket(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            entered.countDown()
            assertEquals(-1, input.read())
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                val waiting = async { client.idle() }
                withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
                withTimeout(1_000) { waiting.cancelAndJoin() }
            }
            server.await()
        }
    }
}
