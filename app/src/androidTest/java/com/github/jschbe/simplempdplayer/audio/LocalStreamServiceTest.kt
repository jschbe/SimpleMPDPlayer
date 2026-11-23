package com.github.jschbe.simplempdplayer.audio

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.github.jschbe.simplempdplayer.data.ThemeMode
import com.github.jschbe.simplempdplayer.R
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
        runBlocking { app.sleepTimer.setEnabled(false); store.setTheme(ThemeMode.SYSTEM) }
        main { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
        main { mediaController?.release(); app.localStream.disable(); app.repository.close(); app.repository.networkChanged(true) }
        mediaController = null
        activity?.close()
        fixture.close()
        runBlocking {
            val settings = store.settings.first()
            settings.servers.forEach { store.delete(it.id) }
        }
    }

    @Test fun languageChangesRetainAudioSessionSocketsProfilesThemeAndTimer() {
        runBlocking { store.setTheme(ThemeMode.DARK); app.sleepTimer.setMinutes(60); app.sleepTimer.setEnabled(true) }
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE && app.sleepTimer.state.value.active }
        val controller = controller()
        val generation = app.localStream.request.value!!.generation
        val record = app.sleepTimer.state.value.settings.record!!
        val profiles = runBlocking { store.settings.first() }
        val connections = fixture.mpdConnections.get()
        val http = fixture.httpRequests.get()
        for (tag in listOf("en", "de", "fr", "it", "es", "uk", "ru")) {
            main { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
            waitFor {
                var ready = false
                activity!!.onActivity { ready = it.resources.configuration.locales[0].language == tag }
                ready
            }
            var subtitle = ""
            var channelName = ""
            activity!!.onActivity {
                subtitle = it.getString(R.string.stream_notification_status, it.getString(R.string.playing))
                channelName = it.getString(androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID)
            }
            waitFor { controller.mediaMetadata.subtitle?.toString() == subtitle }
            waitFor { app.getSystemService(android.app.NotificationManager::class.java)
                .getNotificationChannel(androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)?.name?.toString() == channelName }
            assertEquals(generation, app.localStream.request.value!!.generation)
            assertEquals(connections, fixture.mpdConnections.get())
            assertEquals(http, fixture.httpRequests.get())
            assertEquals(record, app.sleepTimer.state.value.settings.record)
            assertEquals(profiles, runBlocking { store.settings.first() })
            assertTrue(fixture.mutations().isEmpty())
        }
        main { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
        activity!!.recreate()
        assertEquals(record, app.sleepTimer.state.value.settings.record)
        assertEquals(http, fixture.httpRequests.get())
        assertTrue(fixture.mutations().isEmpty())
        runBlocking { app.sleepTimer.setEnabled(false); store.setTheme(ThemeMode.SYSTEM) }
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

    @Test fun sessionRoutesTransportToMpdAndAdvertisesOnlySupportedCommands() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        main {
            assertEquals("Fixture title", controller.mediaMetadata.title.toString())
            assertEquals("Fixture artist", controller.mediaMetadata.artist.toString())
            assertEquals(C.TIME_UNSET, controller.duration)
            assertNull(controller.mediaMetadata.durationMs)
            assertNull(controller.mediaMetadata.artworkData)
            assertNull(controller.mediaMetadata.artworkUri)
            listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).forEach { assertTrue(controller.isCommandAvailable(it)) }
            listOf(Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_SET_VOLUME).forEach { assertFalse(controller.isCommandAvailable(it)) }
            controller.pause()
        }
        waitFor { !controller.playWhenReady && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        assertEquals(listOf("pause \"1\""), fixture.mutations())
        main { controller.play() }
        waitFor { controller.isPlaying && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        main { controller.seekToNext() }
        waitFor { fixture.mutations().size == 3 && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        main { controller.seekToPrevious() }
        waitFor { fixture.mutations().size == 4 && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        main { controller.seekToNextMediaItem() }
        waitFor { fixture.mutations().size == 5 && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        main { controller.seekToPreviousMediaItem() }
        waitFor { fixture.mutations().size == 6 && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        assertEquals(listOf("pause \"1\"", "pause \"0\"", "next", "previous", "next", "previous"), fixture.mutations())
        main { controller.seekTo(30_000); controller.stop() }
        SystemClock.sleep(250)
        assertEquals(6, fixture.mutations().size)
    }

    @Test fun platformNotificationAndHeadsetKeysRouteToMpdAndOffRevokesOldControllers() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        waitFor { notifications.activeNotifications.any { it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) } }
        val notification = notifications.activeNotifications.first { it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) }.notification
        @Suppress("DEPRECATION")
        val token = notification.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)!!
        val platform = android.media.session.MediaController(app, token)
        assertNotNull(notification.contentIntent)
        assertEquals("Fixture title", requireNotNull(platform.metadata).description.title.toString())
        assertEquals(android.media.session.PlaybackState.STATE_PLAYING, requireNotNull(platform.playbackState).state)
        val actions = requireNotNull(platform.playbackState).actions
        assertTrue(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertEquals(0L, actions and android.media.session.PlaybackState.ACTION_SEEK_TO)
        // Exercise the actual notification PendingIntent, then Android's headset-key dispatch path.
        val pause = notification.actions.first { it.title.toString().contains("pause", ignoreCase = true) }
        pause.actionIntent.send()
        waitFor { !controller.playWhenReady && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        waitFor { controller.isPlaying && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        shell("input keyevent KEYCODE_MEDIA_NEXT")
        waitFor { fixture.mutations().size == 3 && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        shell("input keyevent KEYCODE_MEDIA_PREVIOUS")
        waitFor { fixture.mutations().size == 4 && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        shell("input keyevent KEYCODE_MEDIA_PAUSE")
        waitFor { !controller.playWhenReady && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        shell("input keyevent KEYCODE_MEDIA_PLAY")
        waitFor { controller.isPlaying && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        assertEquals(listOf("pause \"1\"", "pause \"0\"", "next", "previous", "pause \"1\"", "pause \"0\""), fixture.mutations())
        main { app.localStream.disable() }
        waitFor { !serviceRunning() && !controller.isConnected }
        platform.transportControls.pause()
        platform.transportControls.skipToNext()
        SystemClock.sleep(250)
        assertEquals(6, fixture.mutations().size)
        assertEquals(com.github.jschbe.simplempdplayer.mpd.PlaybackState.PLAYING, app.repository.state.value.snapshot!!.status.playback)
    }

    @Test fun externalStateAndMetadataAndTimerPauseReachSystemEvenWithContinuousHttpBytes() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        fixture.title = "External title"
        fixture.artist = "External artist"
        fixture.playback("pause")
        waitFor { !controller.playWhenReady && controller.mediaMetadata.title.toString() == "External title" }
        main {
            assertEquals("External artist", controller.mediaMetadata.artist.toString())
            assertEquals(Player.STATE_READY, controller.playbackState)
            assertFalse(controller.isPlaying)
        }
        assertTrue(fixture.bytesSent.get() > 0)
        fixture.playback("play")
        waitFor { controller.isPlaying && app.localStream.state.value.phase == StreamPhase.ACTIVE }
        // The unchanged M5 delivery helper uses its own one-shot connection and explicit pause 1.
        runBlocking { assertNull(com.github.jschbe.simplempdplayer.mpd.MpdRepository.pauseForTimer(app.repository.activeServer!!)) }
        waitFor { !controller.playWhenReady && app.localStream.state.value.phase == StreamPhase.WAITING_FOR_MPD }
        assertEquals(listOf("pause \"1\""), fixture.mutations())
        fixture.title = ""
        fixture.artist = ""
        fixture.playback("pause")
        waitFor { controller.mediaMetadata.title.toString() == "fixture.mp3" }
        main { assertTrue(controller.mediaMetadata.artist.isNullOrEmpty()) }
    }

    @Test fun stoppedPlayUsesMpdPlayAndRejectedPauseDoesNotClaimSuccess() {
        fixture.playback("stop")
        waitFor { app.repository.state.value.snapshot?.status?.playback == com.github.jschbe.simplempdplayer.mpd.PlaybackState.STOPPED }
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.WAITING_FOR_MPD }
        val controller = controller()
        main { assertEquals(Player.STATE_IDLE, controller.playbackState); controller.play() }
        waitFor { controller.isPlaying && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        assertEquals(listOf("play"), fixture.mutations())
        fixture.reject = "pause"
        main { controller.pause() }
        waitFor { app.repository.state.value.commandError == com.github.jschbe.simplempdplayer.mpd.ConnectionError.MPD_REJECTED && controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        main { assertTrue(controller.isPlaying) }
        assertEquals(ConnectionStatus.CONNECTED, app.repository.state.value.status)
        assertEquals(listOf("play", "pause \"1\""), fixture.mutations())
    }

    @Test fun disconnectAndServerSwitchRevokeSessionWithoutControllingEitherServer() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        main { app.repository.close() }
        waitFor { !controller.isConnected && !serviceRunning() }
        main { controller.play(); controller.seekToNext() }
        fixture.assertReadOnly()
        val other = AudioFixture(instrumentation.context.assets.open("stream-tone.mp3").use { it.readBytes() })
        try {
            val profile = ServerInput("Other fixture", "localhost", other.mpdPort.toString(), other.httpPort.toString()).toProfile()!!
            runBlocking { store.saveAndSelect(profile) }
            waitFor { app.repository.activeServer?.id == profile.id && app.repository.state.value.status == ConnectionStatus.CONNECTED }
            assertFalse(app.localStream.state.value.enabled)
            main { controller.pause(); controller.seekToPrevious() }
            SystemClock.sleep(250)
            fixture.assertReadOnly()
            other.assertReadOnly()
        } finally { other.close() }
    }

    @Test fun disablingReceptionDuringSystemStatusReadPreventsTheMpdMutation() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        val gate = java.util.concurrent.CountDownLatch(1)
        val before = fixture.statusReads.get()
        fixture.statusGate = gate
        try {
            main { controller.pause() }
            waitFor { fixture.statusReads.get() > before }
            main { app.localStream.disable() }
        } finally { gate.countDown() }
        waitFor { !serviceRunning() && !app.repository.state.value.commandPending }
        fixture.assertReadOnly()
        assertEquals(com.github.jschbe.simplempdplayer.mpd.PlaybackState.PLAYING, app.repository.state.value.snapshot!!.status.playback)
    }

    @Test fun headsetToggleUsesMpdPlayingEvenWhenAndroidSuppressesTheDecoder() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        val audio = app.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener {}.build()
        try {
            main { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(focus)) }
            waitFor { app.localStream.state.value.phase == StreamPhase.INTERRUPTED }
            fixture.assertReadOnly()
            shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
            waitFor { app.repository.state.value.snapshot?.status?.playback == com.github.jschbe.simplempdplayer.mpd.PlaybackState.PAUSED && !controller.playWhenReady }
            assertEquals(listOf("pause \"1\""), fixture.mutations())
        } finally { main { audio.abandonAudioFocusRequest(focus) } }
    }

    @Test fun mediaKeyToggleReadsFreshMpdStatusInsteadOfAnOutdatedSessionSnapshot() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        // Simulate the window before an external pause's idle notification has arrived.
        fixture.playback("pause", notify = false)
        main { assertTrue(controller.playWhenReady) }
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        waitFor { fixture.mutations().isNotEmpty() && !app.repository.state.value.commandPending }
        assertEquals(listOf("pause \"0\""), fixture.mutations())
        waitFor { controller.isPlaying }
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

    @Test fun sameServerReconnectPreservesReceiverButServerDeletionStopsIt() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val generation = app.localStream.request.value!!.generation
        val reads = fixture.statusReads.get()
        activity!!.onActivity { activity ->
            val provider = androidx.lifecycle.ViewModelProvider(activity)
            provider[com.github.jschbe.simplempdplayer.ServersViewModel::class.java].reconnect()
        }
        waitFor { fixture.statusReads.get() > reads && app.repository.state.value.idleConnected && !app.repository.state.value.recovering }
        assertEquals(generation, app.localStream.request.value!!.generation)
        assertEquals(1, fixture.httpRequests.get())
        assertTrue(serviceRunning())
        val server = app.repository.activeServer!!
        runBlocking { store.delete(server.id) }
        waitFor { app.repository.activeServer == null && !serviceRunning() }
        assertEquals(StreamPhase.OFF, app.localStream.state.value.phase)
        fixture.assertReadOnly()
    }

    @Test fun idleSocketLossWhileScreenOffRepairsOnlyIdleAndPreservesHttp() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE && fixture.idleSubscriptions.get() > 0 }
        val generation = app.localStream.request.value!!.generation
        shell("input keyevent KEYCODE_HOME")
        shell("input keyevent KEYCODE_SLEEP")
        fixture.dropIdle()
        waitFor { fixture.mpdConnections.get() == 3 && app.repository.state.value.idleConnected && !app.repository.state.value.recovering }
        assertEquals(1, fixture.controlSockets.size)
        assertFalse(fixture.controlSockets.first().isClosed)
        assertEquals(generation, app.localStream.request.value!!.generation)
        assertEquals(1, fixture.httpRequests.get())
        fixture.title = "Recovered idle title"
        fixture.playback("play")
        waitFor { app.repository.state.value.snapshot?.currentSong?.title == "Recovered idle title" }
        shell("input keyevent KEYCODE_WAKEUP")
        fixture.assertReadOnly()
    }

    @Test fun foregroundReturnDetectsDeadControlAndRestoresMetadataWithoutRestartingExoPlayer() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        val generation = app.localStream.request.value!!.generation
        activity!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        shell("input keyevent KEYCODE_SLEEP")
        fixture.dropControl()
        fixture.title = "Recovered control title"
        shell("input keyevent KEYCODE_WAKEUP")
        activity!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitFor { fixture.mpdConnections.get() == 4 && app.repository.state.value.idleConnected && !app.repository.state.value.recovering }
        waitFor { controller.mediaMetadata.title.toString() == "Recovered control title" }
        assertEquals(generation, app.localStream.request.value!!.generation)
        assertEquals(1, fixture.httpRequests.get())
        assertTrue(serviceForeground())
        assertEquals("Recovered control title", app.repository.state.value.snapshot!!.queue.single().song.title)
        activity!!.recreate()
        assertEquals(4, fixture.mpdConnections.get())
        assertEquals(1, fixture.httpRequests.get())
        fixture.assertReadOnly()
    }

    @Test fun windowFocusWithoutResumeChecksHealthySocketsAndMergesBothCallbackOrders() {
        waitFor { app.repository.state.value.idleConnected && !app.repository.state.value.recovering }
        SystemClock.sleep(500) // Finish the startup resume/focus burst before measuring.
        lateinit var original: MainActivity
        lateinit var dialog: android.app.Dialog
        val resumes = AtomicInteger()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes.incrementAndGet()
        }
        activity!!.onActivity {
            original = it
            it.lifecycle.addObserver(observer)
            dialog = android.app.Dialog(it).apply {
                setContentView(android.widget.TextView(it).apply { text = "Focus fixture" })
                show()
            }
        }
        waitFor { !original.hasWindowFocus() }
        val resumeCount = resumes.get()
        val reads = fixture.statusReads.get()
        fixture.title = "Focus readback title"
        fixture.playback("pause", notify = false)
        main { dialog.dismiss() }
        waitFor { original.hasWindowFocus() && app.repository.state.value.snapshot?.currentSong?.title == fixture.title }
        assertEquals(resumeCount, resumes.get()) // Actual window focus changed without a resume callback.
        assertEquals(reads + 2, fixture.statusReads.get())
        assertEquals(com.github.jschbe.simplempdplayer.mpd.PlaybackState.PAUSED, app.repository.state.value.snapshot!!.status.playback)

        // Focus before resume is ignored while STARTED; resume must still request the check.
        activity!!.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        val before = fixture.statusReads.get()
        activity!!.onActivity { it.onWindowFocusChanged(true) }
        SystemClock.sleep(400)
        assertEquals(before, fixture.statusReads.get())
        activity!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        activity!!.onActivity { repeat(4) { _ -> it.onWindowFocusChanged(true) } }
        waitFor { fixture.statusReads.get() == before + 2 }
        SystemClock.sleep(400)
        assertEquals(before + 2, fixture.statusReads.get())
        assertEquals(2, fixture.mpdConnections.get())
        activity!!.onActivity { assertSame(original, it); it.lifecycle.removeObserver(observer) }
        fixture.assertReadOnly()
    }

    @Test fun unlockWithoutActivityRestartRecoversControlAndKeepsTimerAndInactiveServerUntouched() {
        val other = AudioFixture(instrumentation.context.assets.open("stream-tone.mp3").use { it.readBytes() })
        try {
            val inactive = ServerInput("Inactive unlock fixture", "localhost", other.mpdPort.toString(), other.httpPort.toString()).toProfile()!!
            runBlocking { store.save(inactive); app.sleepTimer.setMinutes(60); app.sleepTimer.setEnabled(true) }
            waitFor { app.sleepTimer.state.value.settings.record?.status == com.github.jschbe.simplempdplayer.timer.TimerStatus.ACTIVE }
            val record = app.sleepTimer.state.value.settings.record!!
            lateinit var original: MainActivity
            activity!!.onActivity { original = it }
            shell("input keyevent KEYCODE_SLEEP")
            // Wait for the actual lock transition before waking; shell input returns earlier.
            waitFor { !original.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                !original.hasWindowFocus() }
            fixture.dropControl()
            fixture.title = "Unlock readback title"
            fixture.playback("pause", notify = false)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            waitFor { app.repository.state.value.idleConnected && !app.repository.state.value.recovering &&
                app.repository.state.value.snapshot?.currentSong?.title == fixture.title }
            activity!!.onActivity { assertSame(original, it) }
            assertEquals(4, fixture.mpdConnections.get())
            assertEquals(0, other.mpdConnections.get())
            assertEquals(record, app.sleepTimer.state.value.settings.record)
            assertEquals(fixture.title, app.repository.state.value.snapshot!!.queue.single().song.title)
            assertEquals(com.github.jschbe.simplempdplayer.mpd.PlaybackState.PAUSED, app.repository.state.value.snapshot!!.status.playback)
            assertTrue(other.mutations().isEmpty())
            fixture.assertReadOnly()
        } finally { other.close() }
    }

    @Test fun switchDuringFocusRecoveryStopsOldChecksAndPreservesOriginalTimerTarget() {
        runBlocking { app.sleepTimer.setMinutes(60); app.sleepTimer.setEnabled(true) }
        waitFor { app.sleepTimer.state.value.settings.record?.status == com.github.jschbe.simplempdplayer.timer.TimerStatus.ACTIVE }
        val record = app.sleepTimer.state.value.settings.record!!
        val other = AudioFixture(instrumentation.context.assets.open("stream-tone.mp3").use { it.readBytes() })
        try {
            fixture.acceptMpd = false
            fixture.dropControl()
            activity!!.onActivity { it.onWindowFocusChanged(true) }
            waitFor { app.repository.state.value.recovering }
            main { app.repository.networkChanged(false) }
            val oldConnections = fixture.mpdConnections.get()
            val profile = ServerInput("Focus recovery target", "localhost", other.mpdPort.toString(), other.httpPort.toString()).toProfile()!!
            runBlocking { store.saveAndSelect(profile) }
            waitFor { app.repository.activeServer?.id == profile.id }
            activity!!.onActivity { repeat(4) { _ -> it.onWindowFocusChanged(true) } }
            main { app.repository.networkChanged(true) }
            waitFor { app.repository.state.value.idleConnected && !app.repository.state.value.recovering }
            SystemClock.sleep(1_200)
            assertEquals(oldConnections, fixture.mpdConnections.get())
            assertEquals(2, other.mpdConnections.get())
            assertNull(app.repository.state.value.error)
            assertFalse(app.repository.state.value.retriesExhausted)
            assertEquals(record, app.sleepTimer.state.value.settings.record)
            assertNotEquals(profile.id, record.target.id)
            fixture.assertReadOnly(); other.assertReadOnly()
        } finally { other.close() }
    }

    @Test fun offlineRecoveryWaitsAndSwitchCancelsOldServerRetries() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        fixture.acceptMpd = false
        fixture.dropControl()
        main { app.repository.networkChanged(false) }
        // Mark the dead command socket through its ordinary bounded foreground health check.
        main { app.repository.networkChanged(true); app.repository.checkConnection() }
        waitFor { app.repository.state.value.recovering }
        main { app.repository.networkChanged(false) }
        val connections = fixture.mpdConnections.get()
        SystemClock.sleep(1_200)
        assertEquals(connections, fixture.mpdConnections.get())
        assertEquals(0, app.repository.state.value.retryAttempt)
        val other = AudioFixture(instrumentation.context.assets.open("stream-tone.mp3").use { it.readBytes() })
        try {
            val profile = ServerInput("Recovery target", "localhost", other.mpdPort.toString(), other.httpPort.toString()).toProfile()!!
            runBlocking { store.saveAndSelect(profile) }
            waitFor { app.repository.activeServer?.id == profile.id && !serviceRunning() }
            main { app.repository.networkChanged(true) }
            waitFor { app.repository.state.value.idleConnected && !app.repository.state.value.recovering }
            assertEquals(connections, fixture.mpdConnections.get())
            assertEquals(2, other.mpdConnections.get())
            assertEquals(StreamPhase.OFF, app.localStream.state.value.phase)
            main { app.repository.close() }
            SystemClock.sleep(1_200)
            assertEquals(2, other.mpdConnections.get())
            fixture.assertReadOnly(); other.assertReadOnly()
        } finally { other.close() }
    }

    @Test fun transientAndPermanentAudioFocusLossNeverControlMpd() {
        enable()
        waitFor { app.localStream.state.value.phase == StreamPhase.ACTIVE }
        val controller = controller()
        val audio = app.getSystemService(AudioManager::class.java)
        val transient = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener {}.build()
        main { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(transient)) }
        waitFor { app.localStream.state.value.phase == StreamPhase.INTERRUPTED }
        main { assertTrue(controller.isPlaying) }
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
    val controlSockets = CopyOnWriteArrayList<Socket>()
    private val idleSockets = CopyOnWriteArrayList<Socket>()
    val mpdConnections = AtomicInteger()
    val idleSubscriptions = AtomicInteger()
    @Volatile var acceptMpd = true
    val mpdPort get() = mpd.localPort
    val httpPort get() = http.localPort
    val httpRequests = AtomicInteger()
    val bytesSent = AtomicInteger()
    val statusReads = AtomicInteger()
    @Volatile var statusGate: java.util.concurrent.CountDownLatch? = null
    val openHttpConnections = AtomicInteger()
    @Volatile var responseCode = 200
    @Volatile var stall = false
    @Volatile var title = "Fixture title"
    @Volatile var artist = "Fixture artist"
    @Volatile var reject: String? = null
    @Volatile private var state = "play"
    @Volatile private var closed = false

    init {
        accept(mpd) { socket ->
            mpdConnections.incrementAndGet()
            if (!acceptMpd) return@accept
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            fun send(text: String) { writer.write(text); writer.flush() }
            send("OK MPD 0.23.0\n")
            while (!closed) {
                val command = reader.readLine() ?: break
                commands.add(command)
                if (command.substringBefore(' ') != "idle") controlSockets.addIfAbsent(socket)
                if (command.substringBefore(' ') == reject) {
                    send("ACK [4@0] {} Rejected by fixture\n")
                    continue
                }
                when (command.substringBefore(' ')) {
                    "status" -> {
                        statusReads.incrementAndGet()
                        check(statusGate?.await(5, TimeUnit.SECONDS) != false)
                        send("state: $state\nplaylist: 1\nplaylistlength: 1\nsong: 0\nsongid: 1\nelapsed: 0\nduration: 120\nOK\n")
                    }
                    "currentsong", "playlistinfo" -> send("file: fixture.mp3\nTitle: $title\nArtist: $artist\nId: 1\nPos: 0\nduration: 120\nOK\n")
                    "idle" -> {
                        idleSockets.addIfAbsent(socket)
                        idleSubscriptions.incrementAndGet()
                        while (!closed && !socket.isClosed) {
                            if (changes.poll(100, TimeUnit.MILLISECONDS) != null) {
                                send("changed: player\nOK\n")
                                break
                            }
                        }
                    }
                    "ping" -> send("OK\n")
                    "pause" -> { playback(if (command.substringAfter(' ').trim('"') == "1") "pause" else "play"); send("OK\n") }
                    "play" -> { playback("play"); send("OK\n") }
                    "next", "previous" -> { changes.offer(Unit); send("OK\n") }
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
    fun dropIdle() { idleSockets.forEach { it.close() } }
    fun dropControl() { controlSockets.forEach { it.close() } }
    fun playback(value: String, notify: Boolean = true) { state = value; if (notify) changes.offer(Unit) }
    fun mutations() = commands.filter { it.substringBefore(' ') !in setOf("ping", "status", "currentsong", "playlistinfo", "idle") }
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
