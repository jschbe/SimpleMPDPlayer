package com.github.jschbe.simplempdplayer.timer

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
    private val profiles = app.serverSettings
    private val storage = SleepTimerStore(app.sleepTimerDataStore, app.serverDataStore)
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
                snapshot = MpdSnapshot(MpdStatus(playback = PlaybackState.PLAYING), null, emptyList(), 0),
                idleConnected = true, playerConfirmedAtMillis = clock.elapsed())) })
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
        val reloaded = SleepTimerStore(app.sleepTimerDataStore, app.serverDataStore).read()
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
        assertEquals(TimerError.MPD_PERMISSION, storage.read().record!!.error)
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

    @Test fun actualOneShotClaimsThenSendsExactlyOnePause() = runBlocking {
        mpd.holdAcknowledgement = true
        val record = profileMutationLock.withLock { scheduleActualAlarm() }
        assertTrue(isAlarmScheduled())
        try {
            awaitActual("Pause from the OS alarm") { mpd.mutations.isNotEmpty() }
            assertEquals(TimerStatus.DELIVERING, storage.read().record?.status)
            assertFalse(isAlarmScheduled())
            println("Actual alarm: Pause at deadline+${clock.elapsed() - record.deadline!!} ms, durable DELIVERING")
        } finally {
            mpd.holdAcknowledgement = false
        }
        awaitActual("durable completion") { storage.read().record?.status == TimerStatus.COMPLETED }
        assertEquals(record.copy(status = TimerStatus.COMPLETED), storage.read().record)
        assertEquals(listOf("pause \"1\""), mpd.allCommands)
        assertFalse(scheduler.hasPendingIntent())
    }

    @Test fun actualAlarmWhileLockedUsesLatestPasswordOfNonSelectedTarget() = runBlocking {
        val other = ServerInput("Other timer profile", "127.0.0.1", "1").toProfile()!!
        val initialPassword = " synthetic old ♫ "
        val currentPassword = " synthetic \"new\" \\ "
        val lockDisabled = shell("locksettings get-disabled").trim() == "true"
        try {
            if (lockDisabled) shell("locksettings set-disabled false")
            profiles.saveAndSelect(target, PasswordEdit.Replace(initialPassword))
            val record = profileMutationLock.withLock { scheduleActualAlarm() }
            profiles.saveAndSelect(target, PasswordEdit.Replace(currentPassword))
            profiles.saveAndSelect(other)
            mpd.requiredPassword = currentPassword
            assertEquals(record, storage.read().record)
            assertEquals(other.id, profiles.settings.first().selectedId)
            val keyguard = app.getSystemService(KeyguardManager::class.java)
            val power = app.getSystemService(PowerManager::class.java)
            shell("input keyevent KEYCODE_SLEEP")
            awaitActual("sleeping, locked display", timeoutMillis = 4_000) {
                !power.isInteractive && keyguard.isKeyguardLocked &&
                    (!keyguard.isDeviceSecure || keyguard.isDeviceLocked)
            }
            awaitActual("authenticated alarm completion") { storage.read().record?.status == TimerStatus.COMPLETED }
            assertTrue(keyguard.isKeyguardLocked)
            assertEquals(record.copy(status = TimerStatus.COMPLETED), storage.read().record)
            assertEquals(listOf("password", "pause \"1\""), mpd.allCommands)
            assertEquals(listOf("pause \"1\""), mpd.mutations)
            assertFalse(scheduler.hasPendingIntent())
            assertTrue(app.sleepTimer.fire(record.token))
            assertEquals(1, mpd.mutations.size)
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            if (lockDisabled) shell("locksettings set-disabled true")
            profiles.delete(other.id)
        }
    }

    @Test fun missingAndRejectedPasswordPersistDistinctErrorsWithoutGuestPause() = runBlocking {
        profiles.saveAndSelect(target, PasswordEdit.Replace(" synthetic fixture "))
        val saved = profiles.settings.first().servers.first { it.id == target.id }
        mpd.requiredPassword = "different synthetic fixture"
        val rejected = due()
        pendingIntent().send()
        awaitActual("persisted password rejection") { storage.read().record?.error == TimerError.PASSWORD_REJECTED }
        assertEquals(rejected.copy(status = TimerStatus.FAILED, error = TimerError.PASSWORD_REJECTED), storage.read().record)
        assertEquals(listOf("password"), mpd.allCommands)
        assertTrue(mpd.mutations.isEmpty())
        app.credentials.remove(requireNotNull(saved.credentialRevision))
        val missing = due()
        pendingIntent().send()
        awaitActual("persisted missing credential") { storage.read().record?.error == TimerError.CREDENTIAL_MISSING }
        assertEquals(missing.copy(status = TimerStatus.FAILED, error = TimerError.CREDENTIAL_MISSING), storage.read().record)
        assertEquals(listOf("password"), mpd.allCommands)
        assertTrue(mpd.mutations.isEmpty())
        assertFalse(scheduler.hasPendingIntent())
    }

    @Test fun actualOneShotConsumedDuringLockTimeoutIsRecognizedOnRefresh() = runBlocking {
        val record = profileMutationLock.withLock { scheduleActualAlarm() }
        app.sleepTimer.refresh()
        assertEquals(TimerStatus.ACTIVE, app.sleepTimer.state.value.settings.record?.status)
        profileMutationLock.withLock {
            assertTrue(isAlarmScheduled())
            awaitActual("consumption of the registered alarm") { !isAlarmScheduled() }
            println("Actual alarm: consumed at deadline+${clock.elapsed() - record.deadline!!} ms, token exists=${scheduler.hasPendingIntent()}")
            assertTrue(scheduler.hasPendingIntent())
            awaitActual("receiver timeout before claim", timeoutMillis = 12_000) {
                app.sleepTimer.state.value.settings.record?.error == TimerError.MISSING_ALARM
            }
            assertEquals(record, storage.read().record)
            assertTrue(mpd.allCommands.isEmpty())
        }
        app.sleepTimer.refresh()
        println("Actual alarm: after refresh ${storage.read().record?.status}, token exists=${scheduler.hasPendingIntent()}")
        assertEquals(record.copy(status = TimerStatus.FAILED, error = TimerError.MISSING_ALARM), storage.read().record)
        assertFalse(scheduler.hasPendingIntent())
        assertTrue(mpd.allCommands.isEmpty())
    }

    private suspend fun scheduleActualAlarm(): TimerRecord {
        val now = clock.elapsed()
        val record = TimerRecord(UUID.randomUUID().toString(), target, clock.boot()!!, now, now + 6_000)
        storage.write(TimerSettings(record = record))
        scheduler.schedule(record)
        return record
    }

    private fun isAlarmScheduled(): Boolean {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys alarm"))
            .bufferedReader().use { it.readText() }
        return dump.substringAfter(" pending alarms:", "").substringBefore("LazyAlarmStore stats:")
            .lineSequence().any { it.trim() == "tag=*walarm*:${AndroidTimerScheduler.ACTION}" }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private suspend fun awaitActual(event: String, timeoutMillis: Long = 75_000, condition: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMillis) { while (!condition()) delay(150) }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Did not observe $event", failure)
        }
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
    @Volatile var requiredPassword: String? = null
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
                            var authenticated = false
                            while (!closed) {
                                val command = reader.readLine() ?: break
                                if (command.startsWith("password ")) {
                                    allCommands.add("password")
                                    val expected = requiredPassword
                                    authenticated = expected != null && command == MpdProtocol.command("password", listOf(expected)).trimEnd('\n')
                                    if (authenticated) send("OK\n") else send("ACK [3@0] {password} Private detail\n")
                                    continue
                                }
                                allCommands.add(command)
                                if (requiredPassword != null && !authenticated) {
                                    send("ACK [4@0] {${command.substringBefore(' ')}} Private detail\n")
                                    continue
                                }
                                when (command.substringBefore(' ')) {
                                    "pause" -> {
                                        mutations.add(command)
                                        while (holdAcknowledgement && !closed && !socket.isClosed) Thread.sleep(10)
                                        val code = ackCode
                                        if (code == null) send("OK\n") else send("ACK [$code@0] {pause} Private detail\n")
                                    }
                                    "ping" -> send("OK\n")
                                    "status" -> send("state: $playbackState\nplaylist: 1\nplaylistlength: 0\nOK\n")
                                    "currentsong" -> send("OK\n")
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
