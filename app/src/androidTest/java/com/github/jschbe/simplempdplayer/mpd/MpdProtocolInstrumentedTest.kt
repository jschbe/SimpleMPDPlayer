package com.github.jschbe.simplempdplayer.mpd

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Run on Android: its ICU regex parser differs from the JVM unit-test runtime. */
@RunWith(AndroidJUnit4::class)
class MpdProtocolInstrumentedTest {
    @Test
    fun parsesPlayerAndQueueFieldsOnAndroid() {
        val status = MpdData.status(listOf("state" to "pause", "elapsed" to "10.25", "duration" to "120", "volume" to "-1"))
        assertEquals(PlaybackState.PAUSED, status.playback)
        assertEquals(10.25, status.elapsed!!, 0.001)
        assertEquals(null, status.volume)
        val entries = MpdData.queue(listOf("file" to "音楽.flac", "Id" to "1", "Artist" to "First", "Artist" to "Second", "file" to "音楽.flac", "Id" to "2"))
        assertEquals(listOf(1L, 2L), entries.map { it.id })
        assertEquals(listOf("First", "Second"), entries[0].song.artists)
        assertEquals("音楽.flac", entries[0].song.title)
    }

    @Test
    fun initializesProtocolAndParsesGreetingAndAckOnAndroid() {
        assertEquals("0.24.0", MpdProtocol.parseGreeting("OK MPD 0.24.0"))
        val error = MpdProtocol.parseAck("ACK [4@0] {ping} permission denied")
        assertEquals(4, error.code)
        assertEquals(0, error.commandIndex)
        assertEquals("ping", error.command)
    }

    @Test
    fun connectsAndPingsOnAndroid(): Unit = runBlocking {
        for (host in listOf("localhost", "127.0.0.1", "::1")) checkLoopback(host)
        assertEquals(1, (platformMpdHostLookup.resolve("fe80::1%1") as Inet6Address).scopeId)
    }

    private suspend fun checkLoopback(host: String) {
        ServerSocket(0, 1, InetAddress.getByName(host)).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val response = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val output = socket.getOutputStream()
                    output.write("OK MPD 0.24.0\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                    assertEquals("ping", socket.getInputStream().bufferedReader().readLine())
                    output.write("OK\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }
            try {
                MpdClient().use { client ->
                    client.connect(host, server.localPort)
                    client.ping()
                }
                response.get(5, TimeUnit.SECONDS)
                Unit
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }
}
