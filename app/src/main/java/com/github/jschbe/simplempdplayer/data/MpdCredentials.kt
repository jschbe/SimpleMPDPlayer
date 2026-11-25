package com.github.jschbe.simplempdplayer.data

import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Passed to an explicit Save or Test only. A draft never belongs to observable editor state. */
sealed interface PasswordEdit {
    data object Keep : PasswordEdit
    data object Remove : PasswordEdit
    class Replace(val password: String) : PasswordEdit
}

internal enum class CredentialFailure { MISSING, UNREADABLE, REENTRY, INVALID, SAVE }
internal class CredentialException(val failure: CredentialFailure) : IOException("MPD credential unavailable")

internal const val MAX_MPD_PASSWORD_BYTES = 16_384

internal fun validMpdPassword(value: CharSequence): Boolean =
    value.isNotEmpty() && value.length <= MAX_MPD_PASSWORD_BYTES &&
        value.none { it == '\r' || it == '\n' || it == '\u0000' } &&
        Charsets.UTF_8.newEncoder().canEncode(value) &&
        value.toString().toByteArray(Charsets.UTF_8).let { bytes ->
            try { bytes.size <= MAX_MPD_PASSWORD_BYTES } finally { bytes.fill(0) }
        }

internal fun keepCredential(candidate: ServerProfile, current: ServerProfile?): ServerProfile {
    if (current?.credentialRevision != null && !candidate.sameControlEndpoint(current)) {
        throw CredentialException(CredentialFailure.REENTRY)
    }
    return candidate.copy(credentialRevision = current?.credentialRevision)
}

internal interface MpdCredentialStore {
    suspend fun resolve(profile: ServerProfile): String?
    suspend fun put(profile: ServerProfile, password: String)
    suspend fun remove(revision: String)
    suspend fun prune(referenced: Set<String>)
}

/** A blocked provider can occupy a worker, but cannot hold a cancelled caller open. */
internal class CredentialReadWorker {
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(), { work ->
        Thread(work, "MPD credential read").apply { isDaemon = true }
    })

    suspend fun read(block: () -> String): String = suspendCancellableCoroutine { continuation ->
        val work = FutureTask {
            continuation.resumeWith(runCatching(block))
        }
        continuation.invokeOnCancellation { work.cancel(true) }
        try {
            workers.execute(work)
        } catch (_: RejectedExecutionException) {
            continuation.resumeWithException(CredentialException(CredentialFailure.UNREADABLE))
        }
    }
}

internal object NoMpdCredentials : MpdCredentialStore {
    override suspend fun resolve(profile: ServerProfile): String? {
        if (profile.credentialRevision != null) throw CredentialException(CredentialFailure.MISSING)
        return null
    }

    override suspend fun put(profile: ServerProfile, password: String) {
        throw CredentialException(CredentialFailure.SAVE)
    }

    override suspend fun remove(revision: String) = Unit
    override suspend fun prune(referenced: Set<String>) = Unit
}
