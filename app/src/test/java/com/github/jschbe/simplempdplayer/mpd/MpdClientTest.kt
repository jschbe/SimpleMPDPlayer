package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerInput
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MpdClientTest {
    @Test
    fun blockingLookupDoesNotHoldTheCallerPastTimeoutOrCancellation() = runBlocking {
        for (cancelEarly in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val address = InetAddress.getLoopbackAddress()
            val lookup = MpdHostLookup {
                entered.complete(Unit)
                try {
                    awaitLookupRelease(release)
                    address
                } finally { finished.countDown() }
            }
            MpdClient(operationTimeoutMillis = 250, resolveHost = lookup::resolve).use { client ->
                val start = System.nanoTime()
                val call = async { runCatching { client.connect("fixture.invalid", 1) } }
                try {
                    withTimeout(1_000) { entered.await() }
                    if (cancelEarly) call.cancel()
                    withTimeout(1_000) { call.join() }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    assertEquals("Native lookup remains stuck", 1L, finished.count)
                    val error = try { call.await().exceptionOrNull() } catch (failure: CancellationException) { failure }
                    println("Lookup fixture: cancel=$cancelEarly observedMs=$elapsed result=${error?.javaClass?.simpleName}")
                    assertTrue(if (cancelEarly) error is CancellationException else error is SocketTimeoutException)
                } finally {
                    release.countDown()
                    assertTrue(finished.await(3, TimeUnit.SECONDS))
                }
                FakeMpdServer { error("A cancelled client connected after resolution") }.use { server ->
                    expectFailure<java.net.SocketException> { client.connect(server.host, server.port) }
                }
            }
        }
    }

    @Test
    fun twoStuckLookupsLeaveNoUnboundedQueueAndProbeClassifiesTimeout() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val numericHost = "127.0.0.1"
        val loopback = InetAddress.getByName(numericHost)
        val lookup = MpdHostLookup { host ->
            if (host != numericHost) {
                entered.countDown()
                try { awaitLookupRelease(release) } finally { finished.countDown() }
            }
            loopback
        }
        val jobs = List(2) { launch { lookup.resolve("fixture.invalid") } }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
            }
            withTimeout(1_000) { jobs.forEach { it.cancelAndJoin() } }
            val probe = ServerInput("Fixture", "fixture.invalid", "1").toProfile()!!
            val repository = MpdRepository(this, connectionFactory = { MpdClient(resolveHost = lookup::resolve) })
            try {
                assertEquals(ConnectionError.TIMEOUT, repository.testConnection(probe))
            } finally { repository.close() }
            assertEquals(loopback, lookup.resolve(numericHost))
            assertEquals(2L, finished.count)
        } finally {
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun switchingServersDoesNotWaitForOldLookupOrConnectToItsResult() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val lookup = MpdHostLookup { host ->
            if (host == "old.fixture.invalid") {
                entered.complete(Unit)
                try { awaitLookupRelease(release) } finally { finished.countDown() }
            }
            loopback
        }
        ServerSocket(0, 1, loopback).use { oldListener ->
            val control: (java.net.Socket) -> Unit = { socket ->
                socket.send("OK MPD 0.24.0\n")
                val reader = socket.getInputStream().bufferedReader()
                while (true) {
                    when (reader.readLine() ?: break) {
                        "ping", "currentsong" -> socket.send("OK\n")
                        "status" -> socket.send("state: stop\nplaylistlength: 0\nplaylist: 1\nOK\n")
                        else -> error("Unexpected command on replacement control socket")
                    }
                }
            }
            FakeMpdServer(listOf(control, { socket ->
                socket.send("OK MPD 0.24.0\n")
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("idle "))
                assertNull(reader.readLine())
            })).use { server ->
                val repository = MpdRepository(this, connectionFactory = { MpdClient(resolveHost = lookup::resolve) })
                val old = ServerInput("Old fixture", "old.fixture.invalid", oldListener.localPort.toString()).toProfile()!!
                val selected = ServerInput("New fixture", server.host, server.port.toString()).toProfile()!!
                try {
                    repository.connect(old)
                    withTimeout(1_000) { entered.await() }
                    repository.connect(selected)
                    val replacement = withTimeout(2_000) { repository.state.first { it.idleConnected } }
                    assertEquals(1L, finished.count)
                    release.countDown()
                    assertTrue(finished.await(3, TimeUnit.SECONDS))
                    oldListener.soTimeout = 200
                    expectFailure<SocketTimeoutException> { oldListener.accept().close() }
                    assertEquals(selected, repository.activeServer)
                    assertEquals(replacement.sessionId, repository.state.value.sessionId)
                } finally {
                    release.countDown()
                    repository.close()
                }
                server.await()
            }
        }
    }

    private fun awaitLookupRelease(release: CountDownLatch) {
        // Ignore interrupts to model a native resolver, but always release the fixture worker.
        val stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < stop && release.count != 0L) {
            try { release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        }
    }

    @Test
    fun greetingAndSuccessfulPing() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            assertEquals("ping", socket.getInputStream().bufferedReader().readLine())
            socket.send("OK\n")
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                client.ping()
            }
            server.await()
        }
    }

    @Test
    fun completeResponsesAndConcurrentCommandsStaySerialized() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            repeat(12) {
                val command = input.readLine()
                socket.send("echo: $command\nArtist: Björk 🎵\nArtist: Second\nOK\n")
            }
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                (0 until 12).map { number ->
                    async {
                        val response = client.execute("status", number.toString())
                        assertEquals(listOf("echo" to "status \"$number\"", "Artist" to "Björk 🎵", "Artist" to "Second"), response)
                    }
                }.awaitAll()
            }
            server.await()
        }
    }

    @Test
    fun ackIsDistinctAndEndsTheResponse() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            assertEquals("ping", input.readLine())
            socket.send("ACK [4@0] {ping} permission denied\n")
            assertEquals("ping", input.readLine())
            socket.send("OK\n")
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                assertEquals(4, expectFailure<MpdAckException> { client.ping() }.code)
                client.ping()
            }
            server.await()
        }
    }

    @Test
    fun invalidGreetingIsRejected() = runBlocking {
        FakeMpdServer { it.send("HTTP/1.1 200 OK\n") }.use { server ->
            MpdClient().use { client ->
                expectFailure<InvalidMpdResponse> { client.connect(server.host, server.port) }
            }
            server.await()
        }
    }

    @Test
    fun invalidResponseIsRejectedAndConnectionClosed() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            socket.send("nonsense\n")
            assertEquals(-1, input.read())
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                expectFailure<InvalidMpdResponse> { client.ping() }
            }
            server.await()
        }
    }

    @Test
    fun malformedUtf8IsRejected() = runBlocking {
        FakeMpdServer { socket ->
            socket.getOutputStream().write(byteArrayOf(0xC3.toByte(), 0x28, 0x0A))
        }.use { server ->
            MpdClient().use { client ->
                expectFailure<InvalidMpdResponse> { client.connect(server.host, server.port) }
            }
            server.await()
        }
    }

    @Test
    fun abruptEndBeforeGreetingIsConnectionLost() = runBlocking {
        FakeMpdServer { }.use { server ->
            MpdClient().use { client ->
                expectFailure<EOFException> { client.connect(server.host, server.port) }
            }
            server.await()
        }
    }

    @Test
    fun incompleteResponseIsConnectionLost() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            socket.getInputStream().bufferedReader().readLine()
            socket.send("state: play\n")
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                expectFailure<EOFException> { client.execute("status") }
            }
            server.await()
        }
    }

    @Test
    fun readTimeoutClosesSocket() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            assertEquals(-1, input.read())
        }.use { server ->
            MpdClient(readTimeoutMillis = 150).use { client ->
                client.connect(server.host, server.port)
                expectFailure<SocketTimeoutException> { client.ping() }
            }
            server.await()
        }
    }

    @Test
    fun overallDeadlineClosesSocketBeforeLongReadTimeout() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            assertEquals(-1, input.read())
        }.use { server ->
            MpdClient(readTimeoutMillis = 5_000, operationTimeoutMillis = 300).use { client ->
                client.connect(server.host, server.port)
                expectFailure<SocketTimeoutException> { client.ping() }
            }
            server.await()
        }
    }

    @Test
    fun callerTimeoutRemainsCancellationAndClosesTheSocket() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            assertEquals(-1, input.read())
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                expectFailure<kotlinx.coroutines.TimeoutCancellationException> {
                    withTimeout(300) { client.ping() }
                }
            }
            server.await()
        }
    }

    @Test
    fun cancellationInterruptsPendingGreetingRead() = runBlocking {
        val accepted = CountDownLatch(1)
        FakeMpdServer { socket ->
            accepted.countDown()
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            MpdClient(readTimeoutMillis = 5_000).use { client ->
                val attempt = launch { client.connect(server.host, server.port) }
                // Do not block the runBlocking event loop while waiting for accept.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertTrue(accepted.await(2, TimeUnit.SECONDS))
                }
                withTimeout(1_000) { attempt.cancelAndJoin() }
            }
            server.await()
        }
    }
}
