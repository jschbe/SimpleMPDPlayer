package com.github.jschbe.simplempdplayer.mpd

import com.github.jschbe.simplempdplayer.data.ServerInput
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MpdBrowserRepositoryTest {
    private val first = ServerInput("First", "localhost").toProfile()!!
    private val second = ServerInput("Second", "localhost").toProfile()!!

    @Test fun listingsAreLazyOneLevelOnlyAndNotReloadedByQueueUpdatesOrTabReentry() = runTest {
        val server = LibraryServer()
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent()
        assertFalse(server.calls.any { it.first == "lsinfo" })
        repository.loadBrowser(); repository.loadBrowser(); runCurrent()
        assertEquals(listOf(emptyList<String>()), server.calls.filter { it.first == "lsinfo" }.map { it.second })
        assertTrue(repository.browser.value.loaded)
        repository.loadBrowser(); server.events.send(setOf("playlist")); runCurrent()
        assertEquals(1, server.calls.count { it.first == "lsinfo" })
        repository.browse("Albums", repository.browser.value.generation); runCurrent()
        assertEquals("Albums", repository.browser.value.path)
        assertTrue(repository.browser.value.entries.isEmpty())
        assertTrue(repository.browser.value.loaded)
        repository.browse("", repository.browser.value.generation); runCurrent()
        assertEquals(3, server.calls.count { it.first == "lsinfo" })
        repository.close()
    }

    @Test fun directoryAckIsRecoverableAndRetryOrUpDoesNotDisconnectPlayback() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        server.reject = "lsinfo"
        repository.browse("Albums", repository.browser.value.generation); runCurrent()
        assertEquals("Albums", repository.browser.value.path)
        assertEquals(ConnectionError.MPD_REJECTED, repository.browser.value.error)
        assertTrue(repository.browser.value.entries.isEmpty())
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertNull(repository.state.value.commandError)
        server.reject = null
        repository.browse("Albums", repository.browser.value.generation); runCurrent()
        assertTrue(repository.browser.value.loaded)
        assertNull(repository.browser.value.error)
        repository.browse("", repository.browser.value.generation); runCurrent()
        assertEquals(2, repository.browser.value.entries.size)
        repository.close()
    }

    @Test fun goingUpDuringAnInflightReadRejectsTheSupersededListingWithoutClosingTheSocket() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        val release = CompletableDeferred<Unit>()
        server.listings["Albums"] = listOf("file" to "Albums/late.mp3")
        server.before = { name, args -> if (name == "lsinfo" && args == listOf("Albums")) release.await() }
        val generation = repository.browser.value.generation
        repository.browse("Albums", generation); runCurrent()
        repository.browse("", generation); runCurrent()
        assertEquals("", repository.browser.value.path)
        assertTrue(repository.browser.value.loading)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("Albums", "track \"音楽\"\\live.flac"), repository.browser.value.entries.map { it.uri })
        assertFalse(server.connections.any { it.closed })
        repository.close()
    }

    @Test fun appendUsesOneAddForEitherFileOrRecursiveFolderAndReadsBackActualQueue() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        val generation = repository.browser.value.generation
        for (uri in listOf("track \"音楽\"\\live.flac", "Albums")) {
            server.calls.clear()
            repository.enqueue(uri, false, generation); runCurrent()
            assertEquals("add" to listOf(uri), server.calls.first())
            assertFalse(server.calls.any { it.first in listOf("clear", "lsinfo", "play", "pause") })
            assertEquals(BrowserQueueMessage.APPENDED, repository.browser.value.queueMessage)
        }
        assertEquals(4, repository.state.value.snapshot!!.queue.size)
        assertEquals(PlaybackState.PLAYING, repository.state.value.snapshot!!.status.playback)
        assertTrue(server.connections[1].calls.isEmpty())
        repository.close()
    }

    @Test fun replacementSerializesClearAndAddAgainstBrowseIdleAndHeartbeatAndNeverPlays() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        val release = CompletableDeferred<Unit>()
        server.before = { name, _ -> if (name == "clear") release.await() }
        server.calls.clear()
        repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
        assertTrue(repository.state.value.commandPending)
        assertNull(repository.browser.value.queueMessage)
        repository.browse("Albums", repository.browser.value.generation)
        server.events.send(setOf("playlist"))
        advanceTimeBy(15_000); runCurrent()
        assertEquals(listOf("clear" to emptyList<String>()), server.calls)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("clear", "add"), server.calls.take(2).map { it.first })
        assertFalse(server.calls.any { it.first in listOf("play", "pause", "playid") })
        assertEquals(2, repository.state.value.snapshot!!.queue.size)
        assertEquals(PlaybackState.STOPPED, repository.state.value.snapshot!!.status.playback)
        assertEquals(BrowserQueueMessage.REPLACED, repository.browser.value.queueMessage)
        assertFalse(repository.state.value.commandPending)
        assertTrue(server.connections[1].calls.isEmpty())
        repository.close()
    }

    @Test fun acknowledgedAppendAndReplaceRemainSuccessfulWithBoundedReadback() = runTest {
        for (replace in listOf(false, true)) {
            val server = LibraryServer()
            val repository = ready(server)
            server.calls.clear()
            server.before = { name, _ ->
                if (name == "add") repeat(20_001) { server.queue += (100L + it) to "large.flac" }
            }
            repository.enqueue("Albums", replace, repository.browser.value.generation); runCurrent()
            assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
            assertEquals(QueueAvailability.LIMIT, repository.state.value.snapshot!!.queueAvailability)
            assertEquals(if (replace) BrowserQueueMessage.REPLACED else BrowserQueueMessage.APPENDED,
                repository.browser.value.queueMessage)
            assertEquals(1, server.calls.count { it.first == "add" })
            assertEquals(if (replace) 1 else 0, server.calls.count { it.first == "clear" })
            assertFalse(server.calls.any { it.first in listOf("play", "playid", "playlistinfo") })
            repository.close()
        }
    }

    @Test fun rejectedAddAfterClearReportsPartialFailureRefreshesEmptyQueueAndDoesNotReplay() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        server.calls.clear(); server.reject = "add"
        repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
        assertEquals(BrowserQueueMessage.REPLACE_PARTIAL, repository.browser.value.queueMessage)
        assertTrue(repository.state.value.snapshot!!.queue.isEmpty())
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertEquals(1, server.calls.count { it.first == "clear" })
        assertEquals(1, server.calls.count { it.first == "add" })
        server.reject = null
        repository.enqueue("Albums", false, repository.browser.value.generation); runCurrent()
        assertEquals(BrowserQueueMessage.APPENDED, repository.browser.value.queueMessage)
        repository.close()
    }

    @Test fun rejectedClearDoesNotAddAnythingAndRejectedAppendCanExposePartialFolderChanges() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        server.calls.clear(); server.reject = "clear"
        repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
        assertEquals(BrowserQueueMessage.CLEAR_FAILED, repository.browser.value.queueMessage)
        assertFalse(server.calls.any { it.first == "add" })
        assertEquals(1, repository.state.value.snapshot!!.queue.size)
        server.reject = "add"
        server.before = { name, _ -> if (name == "add") { server.queue.add(99L to "partial.flac"); server.version++ } }
        repository.enqueue("Albums", false, repository.browser.value.generation); runCurrent()
        assertEquals(BrowserQueueMessage.ADD_FAILED, repository.browser.value.queueMessage)
        assertEquals(2, repository.state.value.snapshot!!.queue.size)
        repository.close()
    }

    @Test fun transportFailureAfterClearClosesBothSocketsAndReportsPartialReplacement() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        server.before = { name, _ -> if (name == "add") throw EOFException() }
        repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
        assertEquals(BrowserQueueMessage.REPLACE_PARTIAL, repository.browser.value.queueMessage)
        assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
        assertFalse(repository.browser.value.loading)
        assertTrue(server.connections.all { it.closed })
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, server.connections.size)
        assertEquals(1, server.calls.count { it.first == "clear" })
        assertEquals(1, server.calls.count { it.first == "add" })
        repository.close()
    }

    @Test fun lostClearAcknowledgementAndFailedReadbackDoNotProduceFalseSuccess() = runTest {
        for (failAt in listOf("clear", "status")) {
            val server = LibraryServer()
            val repository = ready(server)
            server.before = { name, _ -> if (name == failAt) throw EOFException() }
            repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
            assertEquals(if (failAt == "clear") BrowserQueueMessage.REPLACE_UNCONFIRMED else BrowserQueueMessage.REFRESH_FAILED, repository.browser.value.queueMessage)
            assertEquals(ConnectionStatus.FAILED, repository.state.value.status)
            repository.close()
        }
    }

    @Test fun switchingServerCancelsDirectoryWorkRejectsLateResponsesAndStartsAtNewRoot() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = LibraryServer()
        val next = LibraryServer().apply { listings[""] = listOf("directory" to "Other") }
        var created = 0
        val repository = MpdRepository(backgroundScope, { if (created++ < 2) old.connection() else next.connection() })
        repository.connect(first); runCurrent(); repository.loadBrowser(); runCurrent()
        val generation = repository.browser.value.generation
        old.before = { name, _ -> if (name == "lsinfo") withContext(NonCancellable) { release.await() } }
        repository.browse("Albums", generation); runCurrent()
        repository.connect(second)
        assertTrue(repository.browser.value.entries.isEmpty())
        assertEquals("", repository.browser.value.path)
        assertTrue(old.connections.all { it.closed })
        runCurrent(); repository.loadBrowser(); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(listOf("Other"), repository.browser.value.entries.map { it.uri })
        assertNull(repository.browser.value.error)
        next.calls.clear()
        repository.enqueue("Other", true, generation)
        repository.browse("Albums", generation); runCurrent()
        assertTrue(next.calls.isEmpty())
        repository.close()
    }

    @Test fun switchingDuringClearNeverSendsAddAndCannotPublishOldOperationResults() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = LibraryServer()
        val next = LibraryServer()
        var created = 0
        val repository = MpdRepository(backgroundScope, { if (created++ < 2) old.connection() else next.connection() })
        repository.connect(first); runCurrent(); repository.loadBrowser(); runCurrent()
        old.before = { name, _ -> if (name == "clear") withContext(NonCancellable) { release.await() } }
        old.calls.clear()
        repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
        repository.connect(second); runCurrent()
        release.complete(Unit); runCurrent()
        assertFalse(old.calls.any { it.first == "add" })
        assertTrue(old.connections.all { it.closed })
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertFalse(repository.state.value.commandPending)
        assertNull(repository.browser.value.queueMessage)
        repository.close()
    }

    @Test fun switchingDuringAddRejectsLateAckAndIdleFailureDoesNotInterruptHealthyAdd() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = LibraryServer()
        val next = LibraryServer()
        var created = 0
        val repository = MpdRepository(backgroundScope, { if (created++ < 2) old.connection() else next.connection() })
        repository.connect(first); runCurrent(); repository.loadBrowser(); runCurrent()
        old.before = { name, _ -> if (name == "add") withContext(NonCancellable) { release.await(); throw MpdAckException(50, 0, "add") } }
        repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
        repository.connect(second); runCurrent()
        release.complete(Unit); runCurrent()
        assertNull(repository.browser.value.queueMessage)
        assertNull(repository.state.value.error)
        repository.loadBrowser(); runCurrent()
        val addGate = CompletableDeferred<Unit>()
        next.before = { name, _ -> if (name == "add") addGate.await() }
        repository.enqueue("Albums", true, repository.browser.value.generation); runCurrent()
        next.events.close(EOFException()); runCurrent()
        assertTrue(repository.state.value.commandPending)
        assertEquals(ConnectionStatus.CONNECTED, repository.state.value.status)
        assertFalse(next.connections.first().closed)
        addGate.complete(Unit); runCurrent()
        assertEquals(BrowserQueueMessage.REPLACED, repository.browser.value.queueMessage)
        assertFalse(repository.state.value.commandPending)
        repository.close()
    }

    @Test fun malformedListingFailsSafelyAndUnsupportedOrStaleUrisCannotBeEnqueued() = runTest {
        val server = LibraryServer()
        val repository = ready(server)
        server.calls.clear()
        repository.enqueue("mix.m3u", true, repository.browser.value.generation)
        repository.enqueue("Albums", true, repository.browser.value.generation - 1); runCurrent()
        assertTrue(server.calls.isEmpty())
        server.listings["Albums"] = listOf("file" to "")
        repository.browse("Albums", repository.browser.value.generation); runCurrent()
        assertEquals(ConnectionError.INVALID_RESPONSE, repository.state.value.error)
        assertTrue(server.connections.all { it.closed })
        repository.close()
    }

    private fun TestScope.ready(server: LibraryServer): MpdRepository {
        val repository = MpdRepository(backgroundScope, server::connection)
        repository.connect(first); runCurrent(); repository.loadBrowser(); runCurrent()
        return repository
    }

    private class LibraryServer {
        val connections = mutableListOf<LibraryConnection>()
        val calls = mutableListOf<Pair<String, List<String>>>()
        val events = Channel<Set<String>>(Channel.UNLIMITED)
        val listings = mutableMapOf("" to listOf("directory" to "Albums", "file" to "track \"音楽\"\\live.flac", "playlist" to "mix.m3u"), "Albums" to emptyList())
        val queue = mutableListOf(1L to "old.mp3")
        var selected: Long? = 1
        var playback = "play"
        var version = 1
        var nextId = 2L
        var reject: String? = null
        var before: suspend (String, List<String>) -> Unit = { _, _ -> }
        fun connection() = LibraryConnection(this).also(connections::add)
        suspend fun execute(name: String, args: List<String>): List<Pair<String, String>> {
            calls += name to args
            before(name, args)
            if (name == reject) throw MpdAckException(50, 0, name)
            fun song(entry: Pair<Long, String>) = listOf("file" to entry.second, "Id" to entry.first.toString())
            return when (name) {
                "lsinfo" -> listings.getValue(args.firstOrNull().orEmpty())
                "status" -> listOf("state" to playback, "playlistlength" to queue.size.toString(), "playlist" to version.toString()) + listOfNotNull(selected?.let { "songid" to it.toString() })
                "currentsong" -> queue.find { it.first == selected }?.let(::song).orEmpty()
                "playlistinfo" -> {
                    val (start, end) = args.single().split(':').map(String::toInt)
                    queue.subList(start, minOf(end, queue.size)).flatMapIndexed { index, entry ->
                        song(entry) + ("Pos" to (start + index).toString())
                    }
                }
                "clear" -> { queue.clear(); selected = null; playback = "stop"; version++; emptyList() }
                "add" -> { repeat(if (args.single() == "Albums") 2 else 1) { queue += nextId++ to args.single() }; version++; emptyList() }
                else -> emptyList()
            }
        }
    }

    private class LibraryConnection(private val server: LibraryServer) : MpdConnection {
        var closed = false
        val calls = mutableListOf<Pair<String, List<String>>>()
        override suspend fun connect(host: String, port: Int) {}
        override suspend fun ping() { calls += "ping" to emptyList<String>(); server.calls += "ping" to emptyList<String>() }
        override suspend fun execute(name: String, vararg arguments: String): List<Pair<String, String>> {
            calls += name to arguments.toList()
            return server.execute(name, arguments.toList())
        }
        override suspend fun idle(): Set<String> = server.events.receive()
        override fun close() { closed = true }
    }
}
