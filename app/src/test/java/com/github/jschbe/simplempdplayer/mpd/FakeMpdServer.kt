package com.github.jschbe.simplempdplayer.mpd

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class FakeMpdServer(script: (Socket) -> Unit) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var accepted: Socket? = null
    val host: String = requireNotNull(server.inetAddress.hostAddress)
    val port: Int = server.localPort
    private val result = executor.submit {
        server.accept().use { socket ->
            accepted = socket
            socket.soTimeout = 3_000
            script(socket)
        }
    }

    fun await() { result.get(4, TimeUnit.SECONDS) }

    override fun close() {
        server.close()
        accepted?.close()
        executor.shutdownNow()
        check(executor.awaitTermination(4, TimeUnit.SECONDS))
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
