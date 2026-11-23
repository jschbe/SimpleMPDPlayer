package com.github.jschbe.simplempdplayer.mpd

enum class PlaybackState { PLAYING, PAUSED, STOPPED, UNKNOWN }
enum class RepeatMode { NONE, QUEUE, SINGLE }
enum class SingleMode { OFF, ON, ONESHOT, UNKNOWN }

data class MpdStatus(
    val playback: PlaybackState = PlaybackState.UNKNOWN,
    val songId: Long? = null,
    val elapsed: Double? = null,
    val duration: Double? = null,
    val volume: Int? = null,
    val random: Boolean = false,
    val repeat: Boolean? = null,
    val single: SingleMode = SingleMode.UNKNOWN,
    val queueVersion: Long? = null,
    val queueLength: Int? = null,
) {
    /** Unsupported combinations stay visible without changing the server on read. */
    val repeatMode: RepeatMode?
        get() = when {
            repeat == false && single == SingleMode.OFF -> RepeatMode.NONE
            repeat == true && single == SingleMode.OFF -> RepeatMode.QUEUE
            repeat == true && single == SingleMode.ON -> RepeatMode.SINGLE
            else -> null
        }
}

data class MpdSong(
    val id: Long?,
    val file: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val duration: Double? = null,
)

data class QueueEntry(val id: Long, val song: MpdSong)

enum class QueueAvailability { COMPLETE, LIMIT, CHANGING }

data class QueueActionContext(val sessionId: Long, val clearVersion: Long? = null)

data class MpdSnapshot(
    val status: MpdStatus,
    val currentSong: MpdSong?,
    val queue: List<QueueEntry>,
    val capturedAtNanos: Long,
    val queueAvailability: QueueAvailability = QueueAvailability.COMPLETE,
) {
    val hasQueuedSongs: Boolean get() = status.queueLength?.let { it > 0 } ?: queue.isNotEmpty()
    fun positionAt(nowNanos: Long): Double? {
        val elapsed = status.elapsed ?: return null
        val delta = if (status.playback == PlaybackState.PLAYING) {
            ((nowNanos - capturedAtNanos).coerceAtLeast(0) / 1_000_000_000.0)
        } else 0.0
        val position = elapsed + delta
        return status.duration?.let { position.coerceAtMost(it) } ?: position
    }
}

sealed interface PlayerAction {
    data object PlayPause : PlayerAction
    data object Play : PlayerAction
    data object Pause : PlayerAction
    data object Stop : PlayerAction
    data object Previous : PlayerAction
    data object Next : PlayerAction
    sealed interface QueueAction : PlayerAction
    data object Clear : QueueAction
    data class Seek(val seconds: Double, val songId: Long?) : PlayerAction
    data class Volume(val value: Int) : PlayerAction
    data class Random(val enabled: Boolean) : PlayerAction
    data class Repeat(val mode: RepeatMode) : PlayerAction
    data class PlayId(val id: Long) : QueueAction
    data class DeleteId(val id: Long) : QueueAction
    data class MoveId(val id: Long, val offset: Int) : QueueAction
}

/** Missing metadata/options stay unknown; malformed identities and queue numbers are protocol errors. */
internal object MpdData {
    fun status(fields: List<Pair<String, String>>): MpdStatus {
        val values = fields.toMap()
        return MpdStatus(
            playback = when (values["state"]) {
                "play" -> PlaybackState.PLAYING
                "pause" -> PlaybackState.PAUSED
                "stop" -> PlaybackState.STOPPED
                else -> PlaybackState.UNKNOWN
            },
            songId = values.nonNegativeLong("songid"),
            elapsed = values["elapsed"].seconds(allowZero = true),
            duration = values["duration"].seconds(),
            volume = values["volume"]?.toIntOrNull()?.takeIf { it in 0..100 },
            random = values.flag("random"),
            repeat = when (values["repeat"]) { "0" -> false; "1" -> true; else -> null },
            single = when (values["single"]) {
                "0" -> SingleMode.OFF
                "1" -> SingleMode.ON
                "oneshot" -> SingleMode.ONESHOT
                else -> SingleMode.UNKNOWN
            },
            queueVersion = values.nonNegativeLong("playlist"),
            queueLength = values.nonNegativeLong("playlistlength")?.let {
                if (it > Int.MAX_VALUE) throw InvalidMpdResponse()
                it.toInt()
            },
        )
    }

    fun currentSong(fields: List<Pair<String, String>>): MpdSong? =
        if (fields.isEmpty()) null else song(fields)

    fun queue(fields: List<Pair<String, String>>, start: Int? = null): List<QueueEntry> {
        if (fields.isEmpty()) return emptyList()
        val records = mutableListOf<MutableList<Pair<String, String>>>()
        for (field in fields) {
            if (field.first == "file") records.add(mutableListOf())
            if (records.isEmpty()) throw InvalidMpdResponse()
            records.last().add(field)
        }
        val ids = mutableSetOf<Long>()
        return records.mapIndexed { index, record ->
            if (start != null && record.filter { it.first == "Pos" }.singleOrNull()?.second?.toIntOrNull() != start + index) {
                throw InvalidMpdResponse()
            }
            val song = song(record)
            val id = song.id ?: throw InvalidMpdResponse()
            if (!ids.add(id)) throw InvalidMpdResponse()
            QueueEntry(id, song)
        }
    }

    private fun song(fields: List<Pair<String, String>>): MpdSong {
        val values = fields.groupBy({ it.first.lowercase() }, { it.second })
        val file = values["file"]?.singleOrNull()?.takeIf { it.isNotBlank() } ?: throw InvalidMpdResponse()
        val ids = values["id"].orEmpty()
        if (ids.size > 1) throw InvalidMpdResponse()
        val idText = ids.singleOrNull()
        val id = idText?.toLongOrNull()?.takeIf { it >= 0 }
        if (idText != null && id == null) throw InvalidMpdResponse()
        return MpdSong(
            id = id,
            file = file,
            title = values["title"]?.firstOrNull { it.isNotBlank() } ?: file.substringAfterLast('/').ifBlank { file },
            artists = values["artist"].orEmpty().filter { it.isNotBlank() },
            albums = values["album"].orEmpty().filter { it.isNotBlank() },
            duration = values["duration"]?.firstOrNull().seconds() ?: values["time"]?.firstOrNull().seconds(),
        )
    }

    private fun Map<String, String>.nonNegativeLong(key: String): Long? {
        val text = this[key] ?: return null
        return text.toLongOrNull()?.takeIf { it >= 0 } ?: throw InvalidMpdResponse()
    }

    private fun Map<String, String>.flag(key: String): Boolean = when (this[key]) {
        null, "0" -> false
        "1" -> true
        else -> throw InvalidMpdResponse()
    }

    private fun String?.seconds(allowZero: Boolean = false): Double? =
        this?.toDoubleOrNull()?.takeIf { it.isFinite() && it <= Int.MAX_VALUE && if (allowZero) it >= 0 else it > 0 }
}
