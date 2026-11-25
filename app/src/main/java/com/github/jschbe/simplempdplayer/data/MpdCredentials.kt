package com.github.jschbe.simplempdplayer.data

import java.io.IOException

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
