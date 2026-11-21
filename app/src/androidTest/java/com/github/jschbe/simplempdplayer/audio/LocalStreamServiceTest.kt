package com.github.jschbe.simplempdplayer.audio

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerSettingsStore
import com.github.jschbe.simplempdplayer.data.serverDataStore
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All endpoints are loopback fixtures in the isolated .devicetest app. No real MPD mutations. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalStreamServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as PlayerApplication
    private lateinit var fixture: AudioFixture
    private var activity: ActivityScenario<MainActivity>? = null
    private var mediaController: MediaController? = null
    private val store get() = ServerSettingsStore(app.serverDataStore)

    @Before fun setup() {
        fixture = AudioFixture(instrumentation.context.assets.open("stream-tone.mp3").use { it.readBytes() })
        val profile = ServerInput("Audio fixture", "localhost", fixture.mpdPort.toString(), fixture.httpPort.toString()).toProfile()!!
        // Only the suffixed test app's DataStore is used; the normal app is never installed/cleared.
        runBlocking { store.saveAndSelect(profile) }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity!!.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        waitFor { app.repository.state.value.status == ConnectionStatus.CONNECTED }
    }

    @After fun cleanup() {
        main { mediaController?.release(); app.localStream.disable(); app.repository.close() }
        mediaController = null
        activity?.close()
        fixture.close()
        runBlocking {
            val settings = store.settings.first()
            settings.servers.forEach { store.delete(it.id) }
        }
    }

    @Test fun manualStreamSurvivesRecreationAndBackgroundAndStopsCompletely() {
        assertEquals(StreamPhase.OFF, app.localStream.state.value.phase)
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        waitFor { fixture.httpRequests.get() == 1 }
        assertTrue(serviceRunning())
        assertTrue(serviceForeground())
        val generation = app.localStream.request.value!!.generation
        activity!!.recreate()
        assertEquals(generation, app.localStream.request.value!!.generation)
        assertEquals(1, fixture.httpRequests.get())
        // Hide Activity behind Home, then turn screen off through UiAutomation shell.
        shell("input keyevent KEYCODE_HOME")
        val before = fixture.bytesSent.get()
        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(1_000)
        assertTrue(fixture.bytesSent.get() > before)
        assertEquals(StreamPhase.ACTIVE, app.localStream.state.value.phase)
        shell("input keyevent KEYCODE_WAKEUP")
        main { app.localStream.disable() }
        waitFor { !serviceRunning() && fixture.openHttpConnections.get() == 0 }
        assertEquals(ConnectionStatus.CONNECTED, app.repository.state.value.status)
        fixture.assertReadOnly()
    }

    @Test fun sessionHasMpdMetadataNoTimelineProgressAndNoTransportCommands() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        main {
            assertEquals("Fixture title", controller.mediaMetadata.title.toString())
            assertEquals(C.TIME_UNSET, controller.duration)
            assertNull(controller.mediaMetadata.durationMs)
            listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM).forEach {
                assertFalse(controller.isCommandAvailable(it))
            }
            controller.pause()
            controller.play()
            controller.stop()
        }
        shell("input keyevent KEYCODE_MEDIA_PAUSE")
        shell("input keyevent KEYCODE_MEDIA_PLAY")
        SystemClock.sleep(250)
        assertEquals(StreamPhase.ACTIVE, app.localStream.state.value.phase)
        fixture.playback("pause")
        waitFor { app.localStream.state.value.phase == StreamPhase.WAITING_FOR_MPD }
        waitFor { !controller.playWhenReady }
        main { assertFalse(controller.isPlaying) }
        fixture.playback("play")
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        fixture.assertReadOnly()
    }

    @Test fun unavailableHttpIsTerminalManualRetryKeepsMpdConnected() {
        fixture.responseCode = 503
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ERROR }
        waitFor { !serviceRunning() }
        val requests = fixture.httpRequests.get()
        SystemClock.sleep(600)
        assertEquals(requests, fixture.httpRequests.get())
        assertEquals(ConnectionStatus.CONNECTED, app.repository.state.value.status)
        fixture.responseCode = 200
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        assertEquals(requests + 1, fixture.httpRequests.get())
        fixture.assertReadOnly()
    }

    @Test fun initiallyPausedMpdPreparesHttpWithoutClaimingMusicIsPlaying() {
        fixture.playback("pause")
        waitFor { app.repository.state.value.snapshot?.status?.playback == com.github.jschbe.simplempdplayer.mpd.PlaybackState.PAUSED }
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.WAITING_FOR_MPD }
        val controller = controller()
        main { assertFalse(controller.isPlaying); assertFalse(controller.playWhenReady) }
        assertTrue(fixture.bytesSent.get() > 0)
        fixture.assertReadOnly()
    }

    @Test fun stalledHttpFailsWithinTheReadDeadlineWithoutRetry() {
        fixture.stall = true
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ERROR }
        assertEquals(StreamError.TIMEOUT, app.localStream.state.value.error)
        assertEquals(1, fixture.httpRequests.get())
        assertEquals(ConnectionStatus.CONNECTED, app.repository.state.value.status)
        fixture.assertReadOnly()
    }

    @Test fun reconnectAndServerDeletionStopReceptionAndDoNotRestartIt() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        activity!!.onActivity { activity ->
            val provider = androidx.lifecycle.ViewModelProvider(activity)
            provider[com.github.jschbe.simplempdplayer.ServersViewModel::class.java].reconnect()
        }
        waitFor { !serviceRunning() }
        waitFor { app.repository.state.value.status == ConnectionStatus.CONNECTED }
        assertEquals(StreamPhase.OFF, app.localStream.state.value.phase)
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val server = app.repository.activeServer!!
        runBlocking { store.delete(server.id) }
        waitFor { app.repository.activeServer == null && !serviceRunning() }
        assertEquals(StreamPhase.OFF, app.localStream.state.value.phase)
        fixture.assertReadOnly()
    }

    @Test fun transientAndPermanentAudioFocusLossNeverControlMpd() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val audio = app.getSystemService(AudioManager::class.java)
        val transient = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener {}.build()
        main { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(transient)) }
        waitFor { app.localStream.state.value.phase == StreamPhase.INTERRUPTED }
        main { audio.abandonAudioFocusRequest(transient) }
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener {}.build()
        main { audio.requestAudioFocus(focus) }
        waitFor { !app.localStream.state.value.enabled }
        main { audio.abandonAudioFocusRequest(focus) }
        fixture.assertReadOnly()
    }

    @Test fun externalStartWithoutManualAuthorizationCannotOpenHttp() {
        main { app.startService(Intent(app, LocalStreamService::class.java)) }
        waitFor { !serviceRunning() }
        assertEquals(0, fixture.httpRequests.get())
        val future = mainResult {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, LocalStreamService::class.java))).buildAsync()
        }
        try {
            val unexpected = future.get(3, TimeUnit.SECONDS)
            main { unexpected.play(); unexpected.release() }
        } catch (_: Exception) { /* Rejected: there is no authorized session. */ }
        waitFor { !serviceRunning() }
        assertEquals(0, fixture.httpRequests.get())
        assertEquals(StreamPhase.OFF, app.localStream.state.value.phase)
        fixture.assertReadOnly()
    }

    private fun enable() = main { app.localStream.enable(app.repository.activeServer, app.repository.state.value) }
    private fun controller(): MediaController {
        val future = mainResult {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, LocalStreamService::class.java))).buildAsync()
        }
        return future.get(5, TimeUnit.SECONDS).also { mediaController = it }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> mainResult(block: () -> T): T {
        var result: T? = null
        main { result = block() }
        return requireNotNull(result)
    }
    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (mainResult(condition)) return
            SystemClock.sleep(50)
        }
        fail("Condition timed out; stream=${app.localStream.state.value}, connection=${app.repository.state.value.status}")
    }
    @Suppress("DEPRECATION")
    private fun services() = app.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .filter { it.service.className == LocalStreamService::class.java.name }
    private fun serviceRunning() = services().isNotEmpty()
    private fun serviceForeground() = services().any { it.foreground }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
    }
}

private class AudioFixture(private val mp3: ByteArray) : AutoCloseable {
    private val mpd = ServerSocket(0)
    private val http = ServerSocket(0)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val commands = CopyOnWriteArrayList<String>()
    private val changes = LinkedBlockingQueue<Unit>()
    val mpdPort get() = mpd.localPort
    val httpPort get() = http.localPort
    val httpRequests = AtomicInteger()
    val bytesSent = AtomicInteger()
    val openHttpConnections = AtomicInteger()
    @Volatile var responseCode = 200
    @Volatile var stall = false
    @Volatile private var state = "play"
    @Volatile private var closed = false

    init {
        accept(mpd) { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            fun send(text: String) { writer.write(text); writer.flush() }
            send("OK MPD 0.23.0\n")
            while (!closed) {
                val command = reader.readLine() ?: break
                commands.add(command)
                when (command.substringBefore(' ')) {
                    "status" -> send("state: $state\nplaylist: 1\nplaylistlength: 1\nsong: 0\nsongid: 1\nelapsed: 0\nduration: 120\nOK\n")
                    "currentsong", "playlistinfo" -> send("file: fixture.mp3\nTitle: Fixture title\nArtist: Fixture artist\nId: 1\nPos: 0\nduration: 120\nOK\n")
                    "idle" -> { changes.take(); if (!closed) send("changed: player\nOK\n") }
                    "ping" -> send("OK\n")
                    else -> send("ACK [5@0] {} Read-only fixture\n")
                }
            }
        }
        accept(http) { socket ->
            openHttpConnections.incrementAndGet()
            try {
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { /* Headers only. */ }
                httpRequests.incrementAndGet()
                val output = socket.getOutputStream()
                if (responseCode != 200) {
                    output.write("HTTP/1.1 $responseCode Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    while (stall && !closed && !socket.isClosed) Thread.sleep(20)
                    while (!closed && !socket.isClosed) {
                        // 2 s at 128 kbit/s, paced sufficiently to exercise real buffering/decoding.
                        mp3.asList().chunked(2048).forEach { chunk ->
                            output.write(chunk.toByteArray()); output.flush()
                            bytesSent.addAndGet(chunk.size)
                            Thread.sleep(64)
                        }
                    }
                }
            } finally { openHttpConnections.decrementAndGet() }
        }
    }
    private fun accept(server: ServerSocket, handler: (Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                thread(isDaemon = true) { try { socket.use(handler) } catch (_: Exception) { /* Fixture shutdown/client disconnect. */ } }
            }
        }
    }
    fun playback(value: String) { state = value; changes.offer(Unit) }
    fun assertReadOnly() {
        assertTrue(commands.isNotEmpty())
        assertTrue("Local media actions sent an MPD mutation", commands.all { it.substringBefore(' ') in setOf("ping", "status", "currentsong", "playlistinfo", "idle") })
    }
    override fun close() {
        closed = true
        changes.offer(Unit)
        mpd.close(); http.close()
        sockets.forEach { it.close() }
    }
}
