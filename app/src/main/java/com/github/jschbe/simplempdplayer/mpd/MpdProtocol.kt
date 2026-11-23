package com.github.jschbe.simplempdplayer.mpd

import java.io.IOException

class InvalidMpdResponse : IOException("Invalid MPD response")

/** Reading stopped before the response terminator. The client closes the socket. */
internal class MpdResponseLimit : IOException("MPD response exceeds the read budget")

class MpdAckException(
    val code: Int,
    val commandIndex: Int,
    val command: String,
) : IOException("MPD rejected a command")

internal object MpdProtocol {
    private val greeting = Regex("OK MPD ([0-9]+\\.[0-9]+\\.[0-9]+)")
    // Android's ICU regex parser requires escaping the literal closing brace.
    private val ack = Regex("ACK \\[([0-9]+)@([0-9]+)\\] \\{([^}]*)\\} (.*)")
    private val key = Regex("[A-Za-z][A-Za-z0-9_-]*")

    fun parseGreeting(line: String): String =
        greeting.matchEntire(line)?.groupValues?.get(1) ?: throw InvalidMpdResponse()

    fun quoteArgument(argument: String): String {
        require(argument.none { it == '\n' || it == '\r' || it == '\u0000' })
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun command(name: String, arguments: List<String>): String {
        require(name.matches(Regex("[a-z][a-z0-9_]*")))
        return (listOf(name) + arguments.map(::quoteArgument)).joinToString(" ") + "\n"
    }

    fun parseAck(line: String): MpdAckException {
        val match = ack.matchEntire(line) ?: throw InvalidMpdResponse()
        val code = match.groupValues[1].toIntOrNull() ?: throw InvalidMpdResponse()
        val index = match.groupValues[2].toIntOrNull() ?: throw InvalidMpdResponse()
        return MpdAckException(code, index, match.groupValues[3])
    }

    fun parseField(line: String): Pair<String, String> {
        val separator = line.indexOf(": ")
        if (separator <= 0) throw InvalidMpdResponse()
        val name = line.substring(0, separator)
        if (!key.matches(name) || name == "binary") throw InvalidMpdResponse()
        return name to line.substring(separator + 2)
    }
}
