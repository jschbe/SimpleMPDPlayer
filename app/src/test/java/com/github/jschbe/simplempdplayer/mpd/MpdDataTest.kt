package com.github.jschbe.simplempdplayer.mpd

import org.junit.Assert.*
import org.junit.Test

class MpdDataTest {
    @Test
    fun parsesStatusAndUnavailableMixer() {
        val status = MpdData.status(fields("state" to "play", "songid" to "42", "elapsed" to "3.25", "duration" to "180.5", "volume" to "65", "random" to "1", "repeat" to "0", "playlist" to "9", "playlistlength" to "2"))
        assertEquals(PlaybackState.PLAYING, status.playback)
        assertEquals(42L, status.songId)
        assertEquals(3.25, status.elapsed!!, 0.001)
        assertEquals(180.5, status.duration!!, 0.001)
        assertEquals(65, status.volume)
        assertTrue(status.random)
        assertEquals(false, status.repeat)
        assertEquals(9L, status.queueVersion)
        assertNull(MpdData.status(fields("volume" to "-1")).volume)
        assertNull(MpdData.status(emptyList()).volume)
    }

    @Test
    fun parsesCurrentSongRepeatedTagsUnicodeAndMissingMetadata() {
        val song = MpdData.currentSong(fields("file" to "folder/音楽.flac", "Id" to "12", "Artist" to "Björk", "Artist" to "Second", "Album" to "Album", "duration" to "123.5"))!!
        assertEquals("音楽.flac", song.title)
        assertEquals(listOf("Björk", "Second"), song.artists)
        assertEquals(listOf("Album"), song.albums)
        assertEquals(123.5, song.duration!!, 0.001)
        val bare = MpdData.currentSong(fields("file" to "song.mp3", "Title" to "", "Time" to "90"))!!
        assertEquals("song.mp3", bare.title)
        assertTrue(bare.artists.isEmpty())
        assertEquals(90.0, bare.duration!!, 0.001)
        assertNull(MpdData.currentSong(emptyList()))
    }

    @Test
    fun queueUsesIdsAndPreservesDuplicateFiles() {
        val queue = MpdData.queue(fields("file" to "same.mp3", "Id" to "1", "Title" to "One", "file" to "same.mp3", "Id" to "2", "Title" to "Two"))
        assertEquals(listOf(1L, 2L), queue.map { it.id })
        assertEquals(listOf("One", "Two"), queue.map { it.song.title })
        assertEquals(queue[0].song.file, queue[1].song.file)
        assertTrue(MpdData.queue(emptyList()).isEmpty())
    }

    @Test
    fun invalidNumbersAndIdentitiesAreControlled() {
        for (value in listOf("NaN", "Infinity", "-1", "0", "nonsense", "1e100")) {
            assertNull(MpdData.status(fields("duration" to value)).duration)
        }
        assertNull(MpdData.status(fields("elapsed" to "-1")).elapsed)
        assertThrows(InvalidMpdResponse::class.java) { MpdData.status(fields("songid" to "bad")) }
        assertThrows(InvalidMpdResponse::class.java) { MpdData.queue(fields("file" to "a", "Id" to "1", "file" to "b", "Id" to "1")) }
        assertThrows(InvalidMpdResponse::class.java) { MpdData.queue(fields("file" to "a")) }
        assertThrows(InvalidMpdResponse::class.java) { MpdData.currentSong(fields("Title" to "no file")) }
    }

    @Test
    fun interpolatesOnlyPlayingAndClampsDuration() {
        val snapshot = MpdSnapshot(MpdStatus(playback = PlaybackState.PLAYING, elapsed = 10.0, duration = 12.0), null, emptyList(), 1_000_000_000)
        assertEquals(11.0, snapshot.positionAt(2_000_000_000)!!, 0.001)
        assertEquals(12.0, snapshot.positionAt(5_000_000_000)!!, 0.001)
        assertEquals(10.0, snapshot.copy(status = snapshot.status.copy(playback = PlaybackState.PAUSED)).positionAt(5_000_000_000)!!, 0.001)
        assertNull(snapshot.copy(status = snapshot.status.copy(elapsed = null)).positionAt(5_000_000_000))
    }

    private fun fields(vararg values: Pair<String, String>) = values.toList()
}
