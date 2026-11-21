package com.github.jschbe.simplempdplayer.data

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ServerSettings(
    val servers: List<ServerProfile> = emptyList(),
    val selectedId: String? = null,
    val recovered: Boolean = false,
    val readFailed: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/** Version 1: one tab-separated profile per line; control characters are invalid input. */
internal object ServerSettingsCodec {
    fun encode(servers: List<ServerProfile>): String = servers.joinToString("\n") {
        require(it.isValid())
        listOf(it.id, it.name, it.host, it.mpdPort, it.streamPort).joinToString("\t")
    }

    fun decode(text: String?, selectedId: String?): ServerSettings {
        var recovered = false
        val ids = mutableSetOf<String>()
        val profiles = text.orEmpty().lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
            val fields = line.split('\t')
            val profile = if (fields.size == 5) {
                val port = fields[3].toIntOrNull()
                val streamPort = fields[4].toIntOrNull()
                if (port != null && streamPort != null) {
                    ServerProfile(fields[0], fields[1], fields[2], port, streamPort)
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
