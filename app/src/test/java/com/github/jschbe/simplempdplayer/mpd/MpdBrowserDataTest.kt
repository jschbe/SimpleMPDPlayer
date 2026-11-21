package com.github.jschbe.simplempdplayer.mpd

import org.junit.Assert.*
import org.junit.Test

class MpdBrowserDataTest {
    @Test fun mixedRecordsSortDirectoriesFirstAndKeepMetadataOnItsOwnTrack() {
        val listing = MpdBrowserData.directory(listOf(
            "file" to "root/z.flac", "Title" to "Zulu", "Artist" to "Björk", "Artist" to "Second", "duration" to "120.25",
            "directory" to "root/音楽 \"Live\"\\set", "Last-Modified" to "2026-01-01", "Title" to "Not a song",
            "playlist" to "favorites.m3u", "Title" to "Never a track", "Artist" to "Ignored",
            "file" to "root/a b.ogg", "Time" to "30",
            "directory" to "root/Albums",
        ))
        assertTrue(listing.hasPlaylists)
        assertEquals(listOf("root/Albums", "root/音楽 \"Live\"\\set", "root/a b.ogg", "root/z.flac"), listing.entries.map { it.uri })
        assertEquals("音楽 \"Live\"\\set", listing.entries[1].title)
        val missing = (listing.entries[2] as LibraryEntry.Track).song
        assertEquals("a b.ogg", missing.title)
        assertTrue(missing.artists.isEmpty())
        assertEquals(30.0, missing.duration!!, 0.001)
        val tagged = (listing.entries[3] as LibraryEntry.Track).song
        assertNull(tagged.id)
        assertEquals(listOf("Björk", "Second"), tagged.artists)
        assertEquals(120.25, tagged.duration!!, 0.001)
    }

    @Test fun absentMetadataIsNotInventedAndUnknownAttributesAreIgnored() {
        val entry = MpdBrowserData.directory(listOf("file" to "音楽/track.mp3", "future-tag" to "value")).entries.single() as LibraryEntry.Track
        assertEquals("track.mp3", entry.title)
        assertNull(entry.song.duration)
        assertTrue(entry.song.artists.isEmpty())
    }

    @Test fun emptyDirectoryAndUnsupportedOnlyListingsAreDistinct() {
        assertEquals(DirectoryListing(emptyList()), MpdBrowserData.directory(emptyList()))
        assertEquals(DirectoryListing(emptyList(), true), MpdBrowserData.directory(listOf("playlist" to "mix", "Last-Modified" to "date")))
        assertTrue(MpdBrowserData.directory(listOf("future-type" to "something")).entries.isEmpty())
    }

    @Test fun invalidAndDuplicateRecordsFailWithoutProducingDuplicateUiKeys() {
        for (fields in listOf(listOf("directory" to ""), listOf("file" to ""), listOf("directory" to "x", "directory" to "x"))) {
            assertThrows(InvalidMpdResponse::class.java) { MpdBrowserData.directory(fields) }
        }
    }
}
