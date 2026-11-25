package com.github.jschbe.simplempdplayer.data

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ServerSettings(
    val servers: List<ServerProfile> = emptyList(),
    val selectedId: String? = null,
    val recovered: Boolean = false,
    val readFailed: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val trackInfoExpanded: Boolean = true,
)

/** Legacy five-field rows remain valid. A sixth field holds a versioned, non-secret reference. */
internal object ServerSettingsCodec {
    private const val PASSWORD_REFERENCE = "password-v1:"

    fun encode(servers: List<ServerProfile>): String = servers.joinToString("\n") {
        require(it.isValid())
        val fields = listOf(it.id, it.name, it.host, it.mpdPort, it.streamPort)
        (fields + listOfNotNull(it.credentialRevision?.let { revision -> PASSWORD_REFERENCE + revision })).joinToString("\t")
    }

    fun decode(text: String?, selectedId: String?): ServerSettings {
        var recovered = false
        val ids = mutableSetOf<String>()
        val profiles = text.orEmpty().lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
            val fields = line.split('\t')
            val reference = fields.getOrNull(5)?.takeIf { it.startsWith(PASSWORD_REFERENCE) }
                ?.removePrefix(PASSWORD_REFERENCE)
            val profile = if (fields.size == 5 || (fields.size == 6 && reference != null)) {
                val port = fields[3].toIntOrNull()
                val streamPort = fields[4].toIntOrNull()
                if (port != null && streamPort != null) {
                    ServerProfile(fields[0], fields[1], fields[2], port, streamPort, reference)
                } else null
            } else null
            if (profile != null && profile.isValid() && ids.add(profile.id)) profile else {
                recovered = true
                null
            }
        }.toList()
        val validSelection = selectedId?.takeIf { id -> profiles.any { it.id == id } }
        return ServerSettings(profiles, validSelection, recovered || validSelection != selectedId)
    }
}
