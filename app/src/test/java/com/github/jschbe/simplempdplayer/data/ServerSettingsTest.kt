package com.github.jschbe.simplempdplayer.data

import org.junit.Assert.*
import org.junit.Test

class ServerSettingsTest {
    private val first = ServerInput("First 🎵", "localhost").toProfile()!!
    private val second = ServerInput("Second", "::1").toProfile()!!

    @Test
    fun profilesAndSelectionRoundTrip() {
        val profiles = listOf(first, second)
        val decoded = ServerSettingsCodec.decode(ServerSettingsCodec.encode(profiles), second.id)
        assertEquals(profiles, decoded.servers)
        assertEquals(second.id, decoded.selectedId)
        assertFalse(decoded.recovered)
    }

    @Test
    fun corruptRowsAreIgnoredWithoutLosingValidProfiles() {
        val encoded = ServerSettingsCodec.encode(listOf(first))
        val decoded = ServerSettingsCodec.decode("broken\n$encoded\n$encoded\ninvalid\tdata", first.id)
        assertEquals(listOf(first), decoded.servers)
        assertEquals(first.id, decoded.selectedId)
        assertTrue(decoded.recovered)
    }

    @Test
    fun invalidProfileAndStaleSelectionAreDiscarded() {
        val line = listOf(first.id, "Test", "localhost", "0", "8000").joinToString("\t")
        val decoded = ServerSettingsCodec.decode(line, first.id)
        assertTrue(decoded.servers.isEmpty())
        assertNull(decoded.selectedId)
        assertTrue(decoded.recovered)
        assertEquals(ServerSettings(), ServerSettingsCodec.decode(null, null))
    }
}
