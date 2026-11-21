package com.github.jschbe.simplempdplayer.data

import org.junit.Assert.*
import org.junit.Test

class ServerProfileTest {
    @Test
    fun defaultsAndStableId() {
        val profile = ServerInput(" Test ", " localhost ").toProfile()!!
        assertEquals("Test", profile.name)
        assertEquals("localhost", profile.host)
        assertEquals(6600, profile.mpdPort)
        assertEquals(8000, profile.streamPort)
        assertTrue(profile.isValid())
        assertEquals(profile.id, ServerInput("Renamed", "localhost").toProfile(profile.id)!!.id)
    }

    @Test
    fun requiredFieldsAndControlCharacters() {
        assertTrue(ProfileField.NAME in ServerInput(" ", "localhost").errors())
        assertTrue(ProfileField.HOST in ServerInput("Test", " ").errors())
        for (control in listOf('\t', '\n', '\r', '\u0000')) {
            assertNull(ServerInput("A${control}B", "localhost").toProfile())
            assertNull(ServerInput("Test", "local${control}host").toProfile())
        }
    }

    @Test
    fun bothPortsMustBeInRange() {
        for (port in listOf("0", "65536", "-1", "abc", "", "99999999999999")) {
            assertTrue(ProfileField.MPD_PORT in ServerInput("Test", "localhost", port).errors())
            assertTrue(ProfileField.STREAM_PORT in ServerInput("Test", "localhost", streamPort = port).errors())
        }
        for (port in listOf("1", "65535")) {
            assertNotNull(ServerInput("Test", "localhost", port, port).toProfile())
        }
    }

    @Test
    fun hostSyntaxWithoutDns() {
        for (host in listOf("localhost", "mpd.example", "127.0.0.1", "::1", "[::1]", "2001:db8::1")) {
            assertNotNull(host, ServerInput("Test", host).toProfile())
        }
        assertEquals("::1", ServerInput("Test", "[::1]").toProfile()!!.host)
        for (host in listOf("http://localhost", "localhost:6600", "localhost/music", "foo bar", "256.1.1.1", "foo..bar", "foo?x", "user@localhost", "[invalid]")) {
            assertNull(host, ServerInput("Test", host).toProfile())
        }
    }
}
