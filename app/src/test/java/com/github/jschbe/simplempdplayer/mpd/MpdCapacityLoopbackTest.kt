package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerInput
import java.io.BufferedReader
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MpdCapacityLoopbackTest {
    @Test fun oversizedFolderClosesTheUnreadSocketAndRetryAndUpUseFreshFraming() = runBlocking {
        val tooLarge = AtomicBoolean(true)
        val firstControl: (Socket) -> Unit = { socket ->
            serveControl(socket) { command ->
                when (command) {
                    "lsinfo" -> "directory: Albums\nOK\n"
                    "lsinfo \"Albums\"" -> {
                        assertTrue(tooLarge.get())
                        // A syntactically valid listing, with bytes and an OK beyond the field budget.
                        val listing = buildString {
                            repeat(10_002) { appendLine("file: Albums/track-$it.flac") }
                            appendLine("OK")
                        }
                        listing
                    }
                    else -> null
                }
            }
        }
        val restoredControl: (Socket) -> Unit = { socket ->
            serveControl(socket) { command ->
                when (command) {
                    "lsinfo \"Albums\"" -> {
                        assertFalse(tooLarge.get())
                        "file: Albums/small.flac\nOK\n"
                    }
                    "lsinfo" -> "directory: Albums\nOK\n"
                    "next" -> "OK\n"
                    else -> null
                }
            }
        }
        FakeMpdServer(listOf(firstControl, ::serveIdle, restoredControl, ::serveIdle)).use { server ->
            val repository = MpdRepository(this)
            try {
                repository.connect(ServerInput("Fixture", server.host, server.port.toString()).toProfile()!!)
                withTimeout(8_000) {
                    val old = repository.state.first { it.idleConnected }.sessionId
                    repository.loadBrowser()
                    val root = repository.browser.first { it.loaded }
                    repository.browse("Albums", root.generation)
                    val limited = repository.browser.first { it.listingLimited && !it.loading }
                    assertEquals("Albums", limited.path)
                    assertTrue(limited.entries.isEmpty())
                    val restored = repository.state.first { it.idleConnected && it.sessionId != old }
                    assertEquals(ConnectionStatus.CONNECTED, restored.status)
                    assertEquals(listOf(4L), restored.snapshot!!.queue.map { it.id })
                    repository.loadBrowser()
                    assertEquals(limited, repository.browser.value)
                    repository.perform(PlayerAction.Next)!!.join()
                    tooLarge.set(false)
                    repository.browse(limited.path, limited.generation)
                    assertEquals(listOf("Albums/small.flac"), repository.browser.first { it.loaded }.entries.map { it.uri })
                    repository.browse(limited.parent, limited.generation)
                    assertEquals(listOf("Albums"), repository.browser.first { it.loaded && it.path.isEmpty() }.entries.map { it.uri })
                }
            } finally {
                repository.close()
            }
            server.await()
        }
    }

    @Test fun oversizedRangeRecoversWithConfirmedPlaybackAndClearThenReloads() = runBlocking {
        val cleared = AtomicBoolean(false)
        val firstControl: (Socket) -> Unit = { socket ->
            serveControl(socket) { command ->
                if (command == "playlistinfo \"0:1\"") "file: track.flac\nTitle: ${"x".repeat(65_536)}\nId: 4\nPos: 0\nOK\n"
                else null
            }
        }
        val restoredControl: (Socket) -> Unit = { socket ->
            serveControl(socket) { command ->
                when {
                    command == "clear" -> { assertTrue(cleared.compareAndSet(false, true)); "OK\n" }
                    command == "status" && cleared.get() -> "state: stop\nplaylist: 2\nplaylistlength: 0\nOK\n"
                    command == "currentsong" && cleared.get() -> "OK\n"
                    command == "playlistinfo \"0:1\"" -> error("Oversized range was read again during recovery")
                    else -> null
                }
            }
        }
        FakeMpdServer(listOf(firstControl, restoredControl, ::serveIdle)).use { server ->
            val repository = MpdRepository(this)
            try {
                repository.connect(ServerInput("Fixture", server.host, server.port.toString()).toProfile()!!)
                withTimeout(8_000) {
                    val ready = repository.state.first { it.idleConnected }
                    val snapshot = ready.snapshot!!
                    assertEquals(PlaybackState.PLAYING, snapshot.status.playback)
                    assertEquals(4L, snapshot.currentSong!!.id)
                    assertEquals(QueueAvailability.LIMIT, snapshot.queueAvailability)
                    assertTrue(snapshot.hasQueuedSongs)
                    repository.performQueue(PlayerAction.Clear, QueueActionContext(ready.sessionId!!))!!.join()
                    assertTrue(cleared.get())
                    assertEquals(QueueAvailability.COMPLETE, repository.state.value.snapshot!!.queueAvailability)
                    assertFalse(repository.state.value.snapshot!!.hasQueuedSongs)
                }
            } finally {
                repository.close()
            }
            server.await()
        }
    }

    private fun serveControl(socket: Socket, response: (String) -> String?) {
        val input = greet(socket)
        try {
            while (true) {
                val command = input.readLine() ?: return
                socket.send(response(command) ?: when (command) {
                    "ping" -> "OK\n"
                    "status" -> "state: play\nplaylist: 1\nplaylistlength: 1\nsongid: 4\nOK\n"
                    "currentsong", "playlistinfo \"0:1\"" -> "file: track.flac\nId: 4\nPos: 0\nOK\n"
                    else -> error("Unexpected command: $command")
                })
            }
        } catch (_: SocketException) {
            // Closing a socket with unread response bytes can reset it instead of sending FIN.
        }
    }

    private fun serveIdle(socket: Socket) {
        val input = greet(socket)
        assertTrue(input.readLine().startsWith("idle "))
        assertNull(input.readLine())
    }

    private fun greet(socket: Socket): BufferedReader {
        socket.send("OK MPD 0.23.5\n")
        return socket.getInputStream().bufferedReader()
    }
}
