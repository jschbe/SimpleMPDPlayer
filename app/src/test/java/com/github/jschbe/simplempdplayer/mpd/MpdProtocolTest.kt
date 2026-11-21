package com.github.jschbe.simplempdplayer.mpd

import org.junit.Assert.*
import org.junit.Test

class MpdProtocolTest {
    @Test
    fun greetingIsStrict() {
        assertEquals("0.24.0", MpdProtocol.parseGreeting("OK MPD 0.24.0"))
        for (line in listOf("OK", "HTTP/1.1 200 OK", "OK MPD", "OK MPD x.y.z", "OK MPD 0.24.0 extra")) {
            assertThrows(InvalidMpdResponse::class.java) { MpdProtocol.parseGreeting(line) }
        }
    }

    @Test
    fun argumentsAreQuotedAndEscaped() {
        assertEquals("\"\"", MpdProtocol.quoteArgument(""))
        assertEquals("\"a b\\\"c\\\\d 🎵\"", MpdProtocol.quoteArgument("a b\"c\\d 🎵"))
        assertEquals("add \"folder/a b.mp3\"\n", MpdProtocol.command("add", listOf("folder/a b.mp3")))
        assertEquals("ping\n", MpdProtocol.command("ping", emptyList()))
        for (argument in listOf("x\ny", "x\ry", "x\u0000y")) {
            assertThrows(IllegalArgumentException::class.java) { MpdProtocol.quoteArgument(argument) }
        }
        assertThrows(IllegalArgumentException::class.java) { MpdProtocol.command("ping\nclear", emptyList()) }
    }

    @Test
    fun fieldsPreserveRepeatedTagsAndColons() {
        assertEquals("Artist" to "A: B", MpdProtocol.parseField("Artist: A: B"))
        assertEquals("Title" to "", MpdProtocol.parseField("Title: "))
        for (line in listOf("invalid", "OK extra", ": value", "binary: 42")) {
            assertThrows(InvalidMpdResponse::class.java) { MpdProtocol.parseField(line) }
        }
    }

    @Test
    fun ackHasStructuredErrorWithoutExposingServerText() {
        val error = MpdProtocol.parseAck("ACK [4@0] {ping} permission denied")
        assertEquals(4, error.code)
        assertEquals(0, error.commandIndex)
        assertEquals("ping", error.command)
        assertFalse(error.message!!.contains("permission denied"))
        assertThrows(InvalidMpdResponse::class.java) { MpdProtocol.parseAck("ACK broken") }
    }
}
