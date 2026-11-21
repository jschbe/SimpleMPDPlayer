package com.github.jschbe.simplempdplayer.mpd

sealed interface LibraryEntry {
    val uri: String
    val title: String

    data class Directory(override val uri: String) : LibraryEntry {
        override val title get() = uri.substringAfterLast('/').ifEmpty { uri }
    }

    data class Track(val song: MpdSong) : LibraryEntry {
        override val uri get() = song.file
        override val title get() = song.title
    }
}

data class DirectoryListing(val entries: List<LibraryEntry>, val hasPlaylists: Boolean = false)

enum class BrowserQueueMessage {
    APPENDED, REPLACED, ADD_FAILED, CLEAR_FAILED, REPLACE_UNCONFIRMED, REPLACE_PARTIAL, REFRESH_FAILED,
}

data class BrowserState(
    val generation: Long = 0,
    val path: String = "",
    val entries: List<LibraryEntry> = emptyList(),
    val hasPlaylists: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: ConnectionError? = null,
    val queueMessage: BrowserQueueMessage? = null,
) {
    val parent: String get() = path.substringBeforeLast('/', "")
}

/** lsinfo records have their own boundaries; playlist metadata must never become song metadata. */
internal object MpdBrowserData {
    private val recordTypes = setOf("directory", "file", "playlist")
    fun directory(fields: List<Pair<String, String>>): DirectoryListing {
        val entries = mutableListOf<LibraryEntry>()
        val identities = mutableSetOf<Pair<String, String>>()
        var hasPlaylists = false
        var kind: String? = null
        var record = mutableListOf<Pair<String, String>>()
        fun finish() {
            val type = kind ?: return
            if (type == "playlist") {
                hasPlaylists = true
                return
            }
            val uri = record.first().second
            if (uri.isEmpty() || uri.any { it == '\n' || it == '\r' || it == '\u0000' } || !identities.add(type to uri)) {
                throw InvalidMpdResponse()
            }
            entries += if (type == "directory") LibraryEntry.Directory(uri)
            else LibraryEntry.Track(MpdData.currentSong(record) ?: throw InvalidMpdResponse())
        }
        for (field in fields) {
            if (field.first in recordTypes) {
                finish()
                kind = field.first
                record = mutableListOf()
            }
            if (kind != null) record.add(field)
        }
        finish()
        return DirectoryListing(entries.sortedWith(
            compareBy<LibraryEntry> { if (it is LibraryEntry.Directory) 0 else 1 }
                .thenBy { it.title.lowercase() }.thenBy { it.uri },
        ), hasPlaylists)
    }
}
