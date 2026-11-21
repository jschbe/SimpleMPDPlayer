package com.github.jschbe.simplempdplayer.mpd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MpdBrowserClientTest {
    @Test fun rootAndSpecialCharacterUrisRoundTripWithoutChangingQuoting() = runBlocking {
        val uri = "音楽/a b\"c\\d"
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            assertEquals("lsinfo", input.readLine())
            socket.send("directory: $uri\nplaylist: mix.m3u\nOK\n")
            assertEquals("lsinfo \"音楽/a b\\\"c\\\\d\"", input.readLine())
            socket.send("file: $uri/song.flac\nTitle: Unicode 🎵\nOK\n")
            assertEquals("add \"音楽/a b\\\"c\\\\d/song.flac\"", input.readLine())
            socket.send("OK\n")
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                val root = MpdBrowserData.directory(client.execute("lsinfo"))
                assertEquals(uri, root.entries.single().uri)
                assertTrue(root.hasPlaylists)
                val track = MpdBrowserData.directory(client.execute("lsinfo", uri)).entries.single()
                assertEquals("Unicode 🎵", track.title)
                client.execute("add", track.uri)
            }
            server.await()
        }
    }

    @Test fun directoryAckDiscardsPartialFieldsAndKeepsTheConnectionUsable() = runBlocking {
        FakeMpdServer { socket ->
            socket.send("OK MPD 0.24.0\n")
            val input = socket.getInputStream().bufferedReader()
            assertEquals("lsinfo \"missing\"", input.readLine())
            socket.send("file: partial.mp3\nACK [50@0] {lsinfo} private server detail\n")
            assertEquals("lsinfo", input.readLine())
            socket.send("OK\n")
        }.use { server ->
            MpdClient().use { client ->
                client.connect(server.host, server.port)
                val error = expectFailure<MpdAckException> { client.execute("lsinfo", "missing") }
                assertEquals("lsinfo", error.command)
                assertFalse(error.message!!.contains("private server detail"))
                assertEquals(DirectoryListing(emptyList()), MpdBrowserData.directory(client.execute("lsinfo")))
            }
            server.await()
        }
    }
}
