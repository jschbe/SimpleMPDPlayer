package com.github.jschbe.simplempdplayer.data

import org.junit.Assert.*
import org.junit.Test

class ServerProfileTest {
    @Test fun duplicateNamesIgnoreCaseAndSurroundingWhitespace() {
        val servers = listOf(ServerInput("Living room", "localhost").toProfile()!!)
        for (name in listOf("Living room", "living ROOM", "  Living room  ", "\u2003LIVING ROOM\u2003")) {
            assertTrue(name, servers.hasDuplicateName(name))
            assertTrue(name, servers.map { it.copy(name = "  Living room  ") }.hasDuplicateName(name))
        }
        assertFalse(servers.hasDuplicateName("Livingroom"))
        assertFalse(emptyList<ServerProfile>().hasDuplicateName("Living room"))
        assertTrue(listOf(servers.single().copy(name = "Кімната")).hasDuplicateName("КІМНАТА"))
    }

    @Test fun editingExcludesOnlyTheStableProfileId() {
        val first = ServerInput("First", "localhost").toProfile()!!
        val second = ServerInput("Second", "localhost").toProfile()!!
        val servers = listOf(first, second)
        assertFalse(servers.hasDuplicateName(" FIRST ", first.id))
        assertTrue(servers.hasDuplicateName(" second ", first.id))
        assertTrue(servers.hasDuplicateName("first", second.id))
        assertFalse(servers.hasDuplicateName("New name", first.id))
    }

    @Test fun existingDuplicatesStillConflictAfterExcludingEditedProfile() {
        val first = ServerInput("First", "localhost").toProfile()!!
        val duplicate = ServerInput("FIRST", "localhost").toProfile()!!
        val servers = listOf(first, duplicate)
        assertTrue(servers.hasDuplicateName(first.name, first.id))
        assertTrue(servers.hasDuplicateName(duplicate.name, duplicate.id))
        assertFalse(servers.hasDuplicateName("Unique", duplicate.id))
    }

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
