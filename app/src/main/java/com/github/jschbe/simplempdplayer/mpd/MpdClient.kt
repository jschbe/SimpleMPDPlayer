package com.github.jschbe.simplempdplayer.mpd

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface MpdConnection : Closeable {
    suspend fun connect(host: String, port: Int)
    suspend fun ping()
    suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>>
    suspend fun idle(): Set<String>
}

/** One socket for one connection attempt/session. A closed client is never reused. */
class MpdClient(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 5_000,
    private val operationTimeoutMillis: Long = 10_000,
    private val resolveHost: suspend (String) -> InetAddress = platformMpdHostLookup::resolve,
) : MpdConnection {
    private val socket = Socket()
    private val mutex = Mutex()
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override suspend fun connect(host: String, port: Int) = exchange {
        val address = InetSocketAddress(resolveHost(host), port)
        socketIo { connectSocket(address) }
    }

    private fun connectSocket(address: InetSocketAddress) {
        check(reader == null) { "Client already connected" }
        socket.connect(address, connectTimeoutMillis)
        socket.soTimeout = readTimeoutMillis
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), decoder))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        MpdProtocol.parseGreeting(readLine())
    }

    override suspend fun ping() {
        val response = execute("ping")
        if (response.isNotEmpty()) {
            close()
            throw InvalidMpdResponse()
        }
    }

    override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> {
        val command = MpdProtocol.command(name, arguments.toList())
        return exchange {
            socketIo {
                write(command)
                readResponse()
            }
        }
    }

    /** Only the dedicated event socket calls this; cancellation closes it to unblock the read. */
    override suspend fun idle(): Set<String> = exchange(waitForIdle = true) {
        socketIo {
            socket.soTimeout = 0
            write(MpdProtocol.command("idle", listOf("player", "playlist", "mixer", "options")))
            readResponse().map { (key, value) ->
                if (key != "changed") throw InvalidMpdResponse()
                value
            }.toSet()
        }
    }

    private fun write(command: String) {
        val output = writer ?: throw IOException("Not connected")
        output.write(command)
        output.flush()
    }

    private fun readResponse(): List<Pair<String, String>> {
        val fields = mutableListOf<Pair<String, String>>()
        var characters = 0
        while (true) {
            val line = readLine()
            characters += line.length
            when {
                line == "OK" -> return fields
                line.startsWith("ACK") -> throw MpdProtocol.parseAck(line)
                else -> {
                    val field = MpdProtocol.parseField(line)
                    if (characters > 1_048_576 || fields.size == 10_000) throw MpdResponseLimit()
                    fields.add(field)
                }
            }
        }
    }

    private fun readLine(): String {
        val input = reader ?: throw IOException("Not connected")
        val line = StringBuilder()
        try {
            while (true) {
                val character = input.read()
                if (character < 0) throw EOFException("Connection lost")
                if (character == '\n'.code) return line.toString()
                if (character == '\r'.code || character == 0) throw InvalidMpdResponse()
                if (line.length == 65_536) throw MpdResponseLimit()
                line.append(character.toChar())
            }
        } catch (_: CharacterCodingException) {
            throw InvalidMpdResponse()
        }
    }

    private suspend fun <T> socketIo(block: () -> T): T = coroutineScope {
        val operation = async(Dispatchers.IO) {
            try {
                block()
            } catch (error: IOException) {
                // Socket.close during cancellation must not replace cancellation with IO failure.
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
        try {
            operation.await()
        } catch (cancelled: CancellationException) {
            // Closing unblocks socket reads/connects before waiting for the IO child.
            close()
            throw cancelled
        }
    }

    private suspend fun <T> exchange(waitForIdle: Boolean = false, block: suspend () -> T): T = mutex.withLock {
        try {
            if (waitForIdle) block() else withTimeout(operationTimeoutMillis) { block() }
        } catch (_: TimeoutCancellationException) {
            close()
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("MPD operation timed out")
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (error: IOException) {
            if (error !is MpdAckException) close()
            throw error
        }
    }

    override fun close() {
        // Socket.close is idempotent and also closes both stream wrappers.
        runCatching { socket.close() }
    }
}
