package com.github.jschbe.simplempdplayer.mpd

import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Bounds outstanding platform lookups, even when the native resolver ignores interruption. */
internal class MpdHostLookup(private val lookup: (String) -> InetAddress = InetAddress::getByName) {
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(), { work ->
        Thread(work, "MPD host lookup").apply { isDaemon = true }
    })

    suspend fun resolve(host: String): InetAddress {
        // Validated numeric profiles, including scoped IPv6, need no DNS worker.
        if (':' in host || host.all { it in '0'..'9' || it == '.' }) {
            return withContext(Dispatchers.IO) { lookup(host) }
        }
        return suspendCancellableCoroutine { continuation ->
            val work = FutureTask { continuation.resumeWith(runCatching { lookup(host) }) }
            continuation.invokeOnCancellation { work.cancel(true) }
            try {
                workers.execute(work)
            } catch (_: RejectedExecutionException) {
                continuation.resumeWithException(SocketTimeoutException("MPD host lookup capacity exhausted"))
            }
        }
    }
}

internal val platformMpdHostLookup = MpdHostLookup()
