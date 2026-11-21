package com.github.jschbe.simplempdplayer.mpd

import java.io.EOFException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MpdClientTest {
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
