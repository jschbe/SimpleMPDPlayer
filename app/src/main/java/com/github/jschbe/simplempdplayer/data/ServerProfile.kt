package com.github.jschbe.simplempdplayer.data

import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.UUID

data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val mpdPort: Int = 6600,
    val streamPort: Int = 8000,
    val credentialRevision: String? = null,
) {
    fun isValid(): Boolean = isCanonicalUuid(id) && (credentialRevision == null || isCanonicalUuid(credentialRevision)) &&
        ServerInput(name, host, mpdPort.toString(), streamPort.toString()).errors().isEmpty()

    internal val controlHost: String get() = host.trim().removeSurrounding("[", "]").let {
        it.substringBefore('%').lowercase(Locale.ROOT) + if ('%' in it) "%" + it.substringAfter('%') else ""
    }

    fun sameControlEndpoint(other: ServerProfile): Boolean = controlHost == other.controlHost && mpdPort == other.mpdPort
}

internal fun isCanonicalUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

enum class ProfileField { NAME, HOST, MPD_PORT, STREAM_PORT }

/** Compare display names without changing stored names or treating an edit as a new profile. */
fun List<ServerProfile>.hasDuplicateName(name: String, excludedId: String? = null): Boolean =
    any { it.id != excludedId && it.name.trim().equals(name.trim(), ignoreCase = true) }

internal class DuplicateServerNameException : IllegalArgumentException()

data class ServerInput(
    val name: String = "",
    val host: String = "",
    val mpdPort: String = "6600",
    val streamPort: String = "8000",
) {
    fun errors(): Set<ProfileField> = buildSet {
        if (name.isBlank() || name.any { it.isISOControl() }) add(ProfileField.NAME)
        if (!validHost(host.trim())) add(ProfileField.HOST)
        if (mpdPort.toIntOrNull() !in 1..65535) add(ProfileField.MPD_PORT)
        if (streamPort.toIntOrNull() !in 1..65535) add(ProfileField.STREAM_PORT)
    }

    fun toProfile(id: String = UUID.randomUUID().toString()): ServerProfile? {
        if (errors().isNotEmpty()) return null
        val normalizedHost = host.trim().removeSurrounding("[", "]")
        return ServerProfile(
            id, name.trim(),
            if (':' in normalizedHost) normalizedHost else IDN.toASCII(normalizedHost),
            mpdPort.toInt(), streamPort.toInt(),
        )
    }
}

private fun validHost(host: String): Boolean {
    if (host.isEmpty() || host.any { it.isWhitespace() || it.isISOControl() } ||
        host.any { it in "/\\?#@" }
    ) return false
    if (':' in host) {
        val address = host.removeSurrounding("[", "]")
        return runCatching { URI("mpd://[$address]").host != null }.getOrDefault(false)
    }
    if (host.all { it.isDigit() || it == '.' }) {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }
    return runCatching {
        val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).removeSuffix(".")
        ascii.isNotEmpty() && ascii.length <= 253 && ascii.split('.').all { it.isNotEmpty() }
    }.getOrDefault(false)
}
