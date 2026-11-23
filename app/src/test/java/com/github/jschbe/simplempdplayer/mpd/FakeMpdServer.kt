package com.github.jschbe.simplempdplayer.mpd

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class FakeMpdServer(scripts: List<(Socket) -> Unit>) : Closeable {
    constructor(script: (Socket) -> Unit) : this(listOf(script))

    private val server = ServerSocket(0, scripts.size, InetAddress.getLoopbackAddress())
    private val executor = Executors.newFixedThreadPool(scripts.size + 1)
    private val accepted = CopyOnWriteArrayList<Socket>()
    val host: String = requireNotNull(server.inetAddress.hostAddress)
    val port: Int = server.localPort
    private val result = executor.submit {
        val clients = scripts.map { script ->
            val socket = server.accept()
            accepted += socket
            executor.submit {
                socket.use {
                    it.soTimeout = 4_000
                    script(it)
                }
            }
        }
        clients.forEach { it.get(5, TimeUnit.SECONDS) }
    }

    fun await() { result.get(6, TimeUnit.SECONDS) }

    override fun close() {
        server.close()
        accepted.forEach(Socket::close)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}

internal fun Socket.send(text: String) {
    getOutputStream().write(text.toByteArray(Charsets.UTF_8))
    getOutputStream().flush()
}

internal suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
    val error = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }
    if (error !is T) throw AssertionError("Expected ${T::class.java.simpleName}, got $error", error)
    return error
}
