package com.github.jschbe.simplempdplayer.timer

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import com.github.jschbe.simplempdplayer.mpd.*
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** No Activity or playback service. The only active network endpoint is an in-process MPD fake. */
@RunWith(AndroidJUnit4::class)
class SleepTimerAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as PlayerApplication
    private val profiles = ServerSettingsStore(app.serverDataStore)
    private val storage = SleepTimerStore(app.sleepTimerDataStore)
    private val scheduler = AndroidTimerScheduler(app)
    private val clock = AndroidTimerClock(app)
    private lateinit var mpd: TimerMpdFixture
    private lateinit var target: ServerProfile

    @Before fun setup() = runBlocking {
        app.sleepTimer.setEnabled(false)
        mpd = TimerMpdFixture()
        target = ServerInput("Timer fixture", mpd.host, mpd.port.toString()).toProfile()!!
        profiles.saveAndSelect(target)
        profileMutationLock.withLock { storage.write(TimerSettings()) }
        app.sleepTimer.refresh()
    }
    @After fun cleanup() = runBlocking {
        app.sleepTimer.setEnabled(false)
        profiles.delete(target.id)
        mpd.close()
    }

    @Test fun scheduleCancelRestoreAndPermissionsUseOnlyOneInexactAlarm() = runBlocking {
        val timer = SleepTimerController(storage, profiles.settings, clock, scheduler, profileMutationLock,
            pause = { error("No delivery") }, playback = { TimerPlayback(target, ConnectionState(ConnectionStatus.CONNECTED,
                snapshot = MpdSnapshot(MpdStatus(playback = PlaybackState.PLAYING), null, emptyList(), 0))) })
        timer.setEnabled(true)
        val record = storage.read().record!!
        assertTrue(scheduler.hasPendingIntent())
        assertEquals(TimerStatus.ACTIVE, record.status)
        assertEquals(30, storage.read().minutes)
        val restored = SleepTimerController(storage, profiles.settings, clock, scheduler, profileMutationLock, pause = { error("No delivery during restore") })
        restored.refresh()
        assertEquals(record.token, restored.state.value.settings.record!!.token)
        assertEquals(record.deadline, restored.state.value.settings.record!!.deadline)
        timer.setMinutes(15)
        assertNotEquals(record.token, storage.read().record!!.token)
        assertEquals(15, storage.read().minutes)
        app.sleepTimer.setEnabled(false)
        assertFalse(scheduler.hasPendingIntent())
        assertTrue(mpd.mutations.isEmpty())
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.USE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        val receiver = app.packageManager.getReceiverInfo(android.content.ComponentName(app, SleepTimerReceiver::class.java), 0)
        assertFalse(receiver.exported)
    }

    @Test fun waitingStateRestoresWithoutAlarmOrUnsolicitedDelivery() = runBlocking {
        app.sleepTimer.setEnabled(true)
        val waiting = storage.read().record!!
        assertEquals(TimerStatus.WAITING, waiting.status)
        assertNull(waiting.deadline)
        assertFalse(scheduler.hasPendingIntent())
        val restored = SleepTimerController(storage, profiles.settings, clock, scheduler, profileMutationLock,
            pause = { error("Waiting must never deliver") })
        restored.refresh()
        restored.fire(waiting.token)
        assertEquals(waiting, restored.state.value.settings.record)
        assertFalse(scheduler.hasPendingIntent())
        assertTrue(mpd.mutations.isEmpty())
        app.sleepTimer.setEnabled(false)
        assertFalse(scheduler.hasPendingIntent())
    }

    @Test fun pendingIntentExecutesPauseOnceWithoutActivityOrLocalAudio() = runBlocking {
        val record = due()
        val pending = pendingIntent()!!
        pending.send()
        // A separately queued duplicate carries the same old generation.
        app.sendBroadcast(Intent(app, SleepTimerReceiver::class.java).setAction(AndroidTimerScheduler.ACTION)
            .putExtra(AndroidTimerScheduler.TOKEN, record.token))
        await { app.sleepTimer.state.value.settings.record?.status == TimerStatus.COMPLETED }
        assertEquals(listOf("pause \"1\""), mpd.mutations)
        assertFalse(scheduler.hasPendingIntent())
        assertEquals(com.github.jschbe.simplempdplayer.audio.StreamPhase.OFF, app.localStream.state.value.phase)
        assertTrue(mpd.allCommands.all { it == "pause \"1\"" })
        val reloaded = SleepTimerStore(app.sleepTimerDataStore).read()
        assertEquals(TimerStatus.COMPLETED, reloaded.record!!.status)
    }

    @Test fun oldPendingIntentAndDeletedOrEditedProfileCannotPause() = runBlocking {
        val old = due(future = true)
        val stale = pendingIntent()!!
        app.sleepTimer.setMinutes(45)
        assertNotEquals(old.token, storage.read().record!!.token)
        assertTrue(runCatching { stale.send() }.exceptionOrNull() is PendingIntent.CanceledException)
        val current = storage.read().record!!
        profiles.save(target.copy(mpdPort = if (target.mpdPort == 65535) 65534 else target.mpdPort + 1))
        app.sleepTimer.fire(current.token)
        app.sleepTimer.refresh()
        assertEquals(TimerStatus.PROFILE_CHANGED, storage.read().record!!.status)
        assertTrue(mpd.mutations.isEmpty())
        assertFalse(scheduler.hasPendingIntent())
    }

    @Test fun receiverFailurePersistsAndIsNotRetried() = runBlocking {
        mpd.ackCode = 4
        due()
        pendingIntent()!!.send()
        await { app.sleepTimer.state.value.settings.record?.status == TimerStatus.FAILED }
        assertEquals(TimerError.AUTHENTICATION, storage.read().record!!.error)
        assertEquals(listOf("pause \"1\""), mpd.mutations)
        app.sleepTimer.refresh()
        assertEquals(1, mpd.mutations.size)
        assertFalse(scheduler.hasPendingIntent())
    }

    @Test fun receiverReadTimeoutIsBoundedAndDoesNotRetry() = runBlocking {
        mpd.holdAcknowledgement = true
        due()
        val start = SystemClock.elapsedRealtime()
        pendingIntent()!!.send()
        await { app.sleepTimer.state.value.settings.record?.status == TimerStatus.FAILED }
        assertEquals(TimerError.TIMEOUT, storage.read().record!!.error)
        assertTrue(SystemClock.elapsedRealtime() - start < 8_000)
        assertEquals(1, mpd.mutations.size)
    }

    private suspend fun due(future: Boolean = false): TimerRecord = profileMutationLock.withLock {
        val now = clock.elapsed()
        val deadline = if (future) now + 60_000 else now - 1
        val record = TimerRecord(UUID.randomUUID().toString(), target, clock.boot()!!, now - 2_000, deadline)
        // Test-only shortened deadline. The production UI still accepts whole minutes only.
        scheduler.cancel()
        // Keep Android's actual alarm in the future; explicitly send its PendingIntent below.
        scheduler.schedule(record.copy(deadline = now + 60_000))
        storage.write(TimerSettings(record = record))
        record
    }
    private fun pendingIntent() = PendingIntent.getBroadcast(app, 5,
        Intent(app, SleepTimerReceiver::class.java).setAction(AndroidTimerScheduler.ACTION),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(25) }
        fail("Timer receiver did not finish")
    }
}

internal class TimerMpdFixture : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    val host = server.inetAddress.hostAddress!!
    val port = server.localPort
    val allCommands = CopyOnWriteArrayList<String>()
    val mutations = CopyOnWriteArrayList<String>()
    private val events = LinkedBlockingQueue<Unit>()
    @Volatile private var playbackState = "stop"
    fun playback(value: String) { playbackState = value; events.offer(Unit) }
    @Volatile var ackCode: Int? = null
    @Volatile var holdAcknowledgement = false
    @Volatile private var closed = false
    init {
        thread(isDaemon = true) {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                thread(isDaemon = true) {
                    try {
                        socket.use {
                            val reader = it.getInputStream().bufferedReader()
                            val writer = it.getOutputStream().bufferedWriter()
                            fun send(value: String) { writer.write(value); writer.flush() }
                            send("OK MPD 0.23.0\n")
                            while (!closed) {
                                val command = reader.readLine() ?: break
                                allCommands.add(command)
                                when (command.substringBefore(' ')) {
                                    "pause" -> {
                                        mutations.add(command)
                                        while (holdAcknowledgement && !closed && !socket.isClosed) Thread.sleep(10)
                                        val code = ackCode
                                        if (code == null) send("OK\n") else send("ACK [$code@0] {pause} Private detail\n")
                                    }
                                    "ping" -> send("OK\n")
                                    "status" -> send("state: $playbackState\nplaylist: 1\nplaylistlength: 0\nOK\n")
                                    "currentsong", "playlistinfo" -> send("OK\n")
                                    "idle" -> {
                                        while (!closed && !socket.isClosed) {
                                            if (events.poll(100, TimeUnit.MILLISECONDS) != null) {
                                                send("changed: player\nOK\n")
                                                break
                                            }
                                        }
                                    }
                                    else -> { mutations.add(command); send("ACK [5@0] {} Unsupported\n") }
                                }
                            }
                        }
                    } catch (_: Exception) { /* Client timeout / fixture teardown. */ }
                }
            }
        }
    }
    override fun close() { closed = true; server.close(); sockets.forEach { it.close() } }
}
