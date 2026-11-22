package com.github.jschbe.simplempdplayer.timer

import com.github.jschbe.simplempdplayer.data.*
import java.io.IOException
import com.github.jschbe.simplempdplayer.mpd.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {
    private val first = ServerInput("First", "localhost").toProfile()!!
    private val second = ServerInput("Second", "example.invalid").toProfile()!!
    private class Clock : TimerClock {
        var now = 100_000L
        var wall = 1_800_000_000_000L
        var bootId: Int? = 4
        override fun elapsed() = now
        override fun wallTime() = wall
        override fun boot() = bootId
    }
    private class Store : TimerStorage {
        val value = MutableStateFlow(TimerSettings())
        var fail = false
        var writes = 0
        override val settings = value.asStateFlow()
        override suspend fun read(): TimerSettings { if (fail) throw IOException(); return value.value }
        override suspend fun write(settings: TimerSettings) { if (fail) throw IOException(); writes++; value.value = settings }
    }
    private class Scheduler : TimerScheduler {
        var current: TimerRecord? = null
        val registrations = mutableListOf<TimerRecord>()
        var fail = false
        override fun schedule(record: TimerRecord) {
            if (fail) throw SecurityException()
            current = record; registrations.add(record)
        }
        override fun cancel() { current = null }
        override fun hasPendingIntent() = current != null
    }
    private inner class Fixture {
        val store = Store()
        val profiles = MutableStateFlow(ServerSettings(listOf(first, second), first.id))
        val clock = Clock()
        val scheduler = Scheduler()
        val lock = Mutex()
        val targets = mutableListOf<ServerProfile>()
        var deliver: suspend () -> TimerError? = { null }
        var playback: TimerPlayback? = TimerPlayback(first, ConnectionState(ConnectionStatus.CONNECTED,
            snapshot = MpdSnapshot(MpdStatus(playback = PlaybackState.PLAYING), null, emptyList(), 0)))
        fun controller() = SleepTimerController(store, profiles, clock, scheduler, lock, pause = { targets.add(it); deliver() }, playback = { playback })
        val timer = controller()
        suspend fun start(minutes: Int = 30): TimerRecord {
            timer.setMinutes(minutes); timer.setEnabled(true)
            return store.read().record!!
        }
        suspend fun expire() { clock.now = requireNotNull(store.read().record!!.deadline); timer.fire(store.read().record!!.token) }
    }

    private fun confirmed(playback: PlaybackState, server: ServerProfile = first,
        connection: ConnectionStatus = ConnectionStatus.CONNECTED) = TimerPlayback(server,
        ConnectionState(connection, snapshot = MpdSnapshot(MpdStatus(playback = playback), null, emptyList(), 0)))

    @Test fun enablingWithoutConfirmedPlayWaitsWithoutDeadlineOrAlarm() = runTest {
        for (status in listOf(PlaybackState.PAUSED, PlaybackState.STOPPED, PlaybackState.UNKNOWN)) {
            val f = Fixture(); f.playback = confirmed(status)
            val record = f.start()
            assertEquals(TimerStatus.WAITING, record.status)
            assertNull(record.deadline)
            assertTrue(record.valid())
            assertTrue(f.timer.state.value.enabled)
            assertTrue(f.timer.state.value.waiting)
            assertFalse(f.timer.state.value.active)
            assertNull(f.timer.state.value.remainingMillis)
            assertNull(f.scheduler.current)
            f.timer.setEnabled(true)
            assertEquals(record.token, f.store.read().record!!.token)
            f.timer.fire(record.token)
            assertTrue(f.targets.isEmpty())
        }
        val f = Fixture(); f.playback = null
        assertEquals(TimerStatus.WAITING, f.start().status)
    }

    @Test fun externalPlayStartsFullDurationOnceAndSubsequentStatesDoNotMoveDeadline() = runTest {
        val f = Fixture(); f.playback = confirmed(PlaybackState.PAUSED)
        val waiting = f.start(15)
        f.clock.now += 600_000
        f.playback = confirmed(PlaybackState.PLAYING)
        f.timer.playbackChanged()
        val started = f.store.read().record!!
        assertNotEquals(waiting.token, started.token)
        assertEquals(f.clock.now + 15 * 60_000L, started.deadline)
        for (status in listOf(PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.STOPPED, PlaybackState.PLAYING)) {
            f.clock.now += 1_000
            f.playback = confirmed(status)
            f.timer.playbackChanged(); f.timer.refresh()
            assertEquals(started, f.store.read().record)
        }
        assertEquals(1, f.scheduler.registrations.size)
        f.playback = confirmed(PlaybackState.STOPPED)
        f.expire()
        assertEquals(listOf(first), f.targets)
    }

    @Test fun durationChangesReevaluatePlaybackAndInvalidateThePreviousAlarm() = runTest {
        val f = Fixture(); val running = f.start(30)
        f.playback = confirmed(PlaybackState.PAUSED)
        f.timer.setMinutes(45)
        val waiting = f.store.read().record!!
        assertEquals(TimerStatus.WAITING, waiting.status)
        assertNull(waiting.deadline)
        assertNull(f.scheduler.current)
        f.clock.now = requireNotNull(running.deadline)
        f.timer.fire(running.token)
        assertTrue(f.targets.isEmpty())
        f.timer.setMinutes(60)
        assertNotEquals(waiting.token, f.store.read().record!!.token)
        assertTrue(f.timer.state.value.waiting)
        f.playback = confirmed(PlaybackState.PLAYING)
        f.timer.playbackChanged()
        assertEquals(f.clock.now + 60 * 60_000L, f.store.read().record!!.deadline)
        f.clock.now += 1_000
        f.timer.setMinutes(15)
        assertEquals(f.clock.now + 15 * 60_000L, f.store.read().record!!.deadline)
        val record = f.store.read().record
        f.timer.setMinutes(15)
        assertEquals(record, f.store.read().record)
    }

    @Test fun offWhileWaitingAndQueuedObsoleteStatusCannotStartCountdown() = runTest {
        val f = Fixture(); f.playback = confirmed(PlaybackState.PAUSED)
        val waiting = f.start()
        f.lock.lock()
        f.playback = confirmed(PlaybackState.PLAYING)
        val event = launch { f.timer.playbackChanged() }
        runCurrent()
        f.playback = confirmed(PlaybackState.PAUSED)
        f.lock.unlock(); event.join()
        assertTrue(f.timer.state.value.waiting)
        f.timer.setEnabled(false)
        f.playback = confirmed(PlaybackState.PLAYING)
        f.timer.playbackChanged(); f.timer.fire(waiting.token)
        assertFalse(f.timer.state.value.enabled)
        assertTrue(f.scheduler.registrations.isEmpty())
        assertTrue(f.targets.isEmpty())
    }

    @Test fun reconnectUsesConfirmedStatusAndNeverResetsAnAlreadyStartedTimer() = runTest {
        val f = Fixture(); f.playback = confirmed(PlaybackState.PAUSED)
        f.start()
        f.playback = confirmed(PlaybackState.PLAYING, connection = ConnectionStatus.FAILED)
        f.timer.playbackChanged()
        assertTrue(f.timer.state.value.waiting)
        f.playback = confirmed(PlaybackState.PAUSED)
        f.timer.playbackChanged()
        assertTrue(f.timer.state.value.waiting)
        f.playback = confirmed(PlaybackState.PLAYING)
        List(3) { launch { f.timer.playbackChanged() } }.joinAll()
        val record = f.store.read().record
        f.playback = null
        f.timer.refresh()
        f.playback = confirmed(PlaybackState.PLAYING)
        f.timer.playbackChanged()
        assertEquals(record, f.store.read().record)
        assertEquals(1, f.scheduler.registrations.size)
    }

    @Test fun waitingSurvivesReloadAndStartsWhenPlayIsNextObservedNotRetroactively() = runTest {
        val f = Fixture(); f.playback = confirmed(PlaybackState.PAUSED)
        val waiting = f.start(45)
        f.clock.now += 600_000
        val restored = f.controller()
        restored.refresh()
        assertEquals(waiting, restored.state.value.settings.record)
        assertTrue(restored.state.value.waiting)
        assertNull(f.scheduler.current)
        f.playback = confirmed(PlaybackState.PLAYING)
        f.clock.now += 600_000
        restored.refresh()
        assertEquals(f.clock.now + 45 * 60_000L, f.store.read().record!!.deadline)
        assertEquals(1, f.scheduler.registrations.size)
    }

    @Test fun waitingRetainsTargetAndBootProfileSafeguards() = runTest {
        val f = Fixture(); f.playback = confirmed(PlaybackState.PAUSED)
        f.start()
        f.profiles.value = f.profiles.value.copy(selectedId = second.id)
        f.playback = confirmed(PlaybackState.PLAYING, second)
        f.timer.setMinutes(15); f.timer.playbackChanged()
        assertEquals(first, f.store.read().record!!.target)
        assertTrue(f.timer.state.value.waiting)
        f.clock.bootId = 5
        f.timer.refresh()
        assertEquals(TimerStatus.REBOOTED, f.store.read().record!!.status)
        assertNull(f.scheduler.current)
        assertTrue(f.scheduler.registrations.isEmpty())
        val other = Fixture(); other.playback = confirmed(PlaybackState.STOPPED)
        other.start()
        other.profiles.value = other.profiles.value.copy(servers = listOf(second))
        other.timer.refresh()
        assertEquals(TimerStatus.PROFILE_CHANGED, other.store.read().record!!.status)
    }

    @Test fun repeatedForegroundRefreshAndDuplicateObserverEventsRegisterOnlyOneCountdown() = runTest {
        val f = Fixture(); f.playback = confirmed(PlaybackState.PAUSED)
        f.timer.observe(backgroundScope); runCurrent()
        f.start(); runCurrent()
        f.playback = confirmed(PlaybackState.PLAYING)
        repeat(3) { f.timer.retry() }
        List(3) { launch { f.timer.playbackChanged() } }.joinAll()
        runCurrent()
        assertTrue(f.timer.state.value.active)
        assertEquals(1, f.scheduler.registrations.size)
    }

    @Test fun defaultsPresetsAndCustomValidation() = runTest {
        assertEquals(30, TimerSettings().minutes)
        assertEquals(listOf(15, 30, 45, 60), sleepTimerPresets)
        listOf("", "0", "-1", "1.5", "1e2", " 30", "30 ", "1441", "999999999999", "+1", "٣٠").forEach { assertNull(it, sleepMinutes(it)) }
        listOf("1" to 1, "30" to 30, "1440" to 1440).forEach { (s, n) -> assertEquals(n, sleepMinutes(s)) }
        val f = Fixture()
        f.timer.refresh()
        assertFalse(f.timer.state.value.active)
        f.timer.setMinutes(0); f.timer.setMinutes(1441)
        assertEquals(30, f.store.read().minutes)
        assertTrue(f.scheduler.registrations.isEmpty())
    }

    @Test fun startCancelAndDurationChangeKeepOnlyOneAlarm() = runTest {
        val f = Fixture()
        val old = f.start(15)
        assertEquals(f.clock.now + 15 * 60_000, old.deadline)
        f.timer.setEnabled(true)
        assertEquals(1, f.scheduler.registrations.size)
        f.clock.now += 7_000
        f.timer.setMinutes(45)
        val current = f.store.read().record!!
        assertNotEquals(old.token, current.token)
        assertEquals(f.clock.now + 45 * 60_000, current.deadline)
        assertEquals(current.token, f.scheduler.current!!.token)
        f.timer.setEnabled(false)
        assertEquals(TimerStatus.CANCELLED, f.store.read().record!!.status)
        assertNull(f.scheduler.current)
        assertNull(f.timer.state.value.remainingMillis)
        f.clock.now = requireNotNull(current.deadline)
        f.timer.fire(current.token); f.timer.fire(old.token)
        assertTrue(f.targets.isEmpty())
    }

    @Test fun wallClockChangesDoNotChangeDeadlineAndProcessRestartDoesNotReschedule() = runTest {
        val f = Fixture()
        val old = f.start()
        f.clock.now += 60_000
        f.clock.wall -= 86_400_000
        f.timer.tick()
        assertEquals(29 * 60_000L, f.timer.state.value.remainingMillis)
        assertEquals(f.clock.wall + 29 * 60_000L, f.timer.state.value.estimatedEndMillis)
        val restored = f.controller()
        restored.refresh()
        assertEquals(old, restored.state.value.settings.record)
        assertEquals(29 * 60_000L, restored.state.value.remainingMillis)
        assertEquals(1, f.scheduler.registrations.size)
        f.clock.now = requireNotNull(old.deadline) + 3_600_000
        restored.tick()
        assertEquals(0L, restored.state.value.remainingMillis)
        assertEquals(f.clock.wall - 3_600_000L, restored.state.value.estimatedEndMillis)
        assertTrue(restored.state.value.active)
        assertTrue(f.targets.isEmpty())
    }

    @Test fun rebootOrBackwardsMonotonicTimeCancelsWithoutRearming() = runTest {
        for (changeBoot in listOf(true, false)) {
            val f = Fixture(); val record = f.start()
            if (changeBoot) f.clock.bootId = 5 else f.clock.now = 0
            f.controller().refresh()
            f.timer.fire(record.token)
            assertEquals(TimerStatus.REBOOTED, f.store.read().record!!.status)
            assertNull(f.scheduler.current)
            assertEquals(1, f.scheduler.registrations.size)
            assertTrue(f.targets.isEmpty())
        }
    }

    @Test fun missingBootReferenceCannotStartAndMissingAlarmIsVisible() = runTest {
        val f = Fixture(); f.clock.bootId = null
        f.timer.setEnabled(true)
        assertNull(f.store.read().record)
        assertEquals(TimerError.CLOCK, f.timer.state.value.operationError)
        f.clock.bootId = 4; f.start()
        f.scheduler.cancel()
        f.controller().refresh()
        assertEquals(TimerError.MISSING_ALARM, f.store.read().record!!.error)
    }

    @Test fun serverSwitchAndDurationChangeKeepOriginalTargetButOffOnBindsNewSelection() = runTest {
        val f = Fixture(); f.start()
        f.profiles.value = f.profiles.value.copy(selectedId = second.id)
        f.timer.refresh()
        f.timer.setMinutes(60)
        assertEquals(first, f.store.read().record!!.target)
        f.expire()
        assertEquals(listOf(first), f.targets)
        assertEquals(TimerStatus.COMPLETED, f.store.read().record!!.status)
        f.timer.setEnabled(true)
        assertEquals(second, f.store.read().record!!.target)
    }

    @Test fun deletingOrChangingAnyEndpointCancelsButRenameAndSortDoNot() = runTest {
        for (replacement in listOf(null, first.copy(host = "example.invalid"), first.copy(mpdPort = 6601), first.copy(streamPort = 8001))) {
            val f = Fixture(); val record = f.start()
            f.profiles.value = f.profiles.value.copy(servers = listOfNotNull(second, replacement))
            // Even before the profile observer runs, dispatch revalidates the endpoint.
            f.clock.now = requireNotNull(record.deadline)
            f.timer.fire(record.token)
            assertEquals(TimerStatus.PROFILE_CHANGED, f.store.read().record!!.status)
            assertNull(f.scheduler.current)
            assertTrue(f.targets.isEmpty())
        }
        val f = Fixture(); val record = f.start()
        f.profiles.value = f.profiles.value.copy(servers = listOf(second, first.copy(name = "Renamed")))
        f.timer.refresh()
        assertEquals("Renamed", f.timer.state.value.targetName)
        assertEquals(record.token, f.scheduler.current!!.token)
        f.expire()
        assertEquals(listOf(first), f.targets)
    }

    @Test fun noSelectionNoTimerAndStorageReadFailureDoesNotOverwrite() = runTest {
        val f = Fixture()
        f.profiles.value = f.profiles.value.copy(selectedId = null)
        f.timer.setEnabled(true)
        assertNull(f.store.read().record)
        f.profiles.value = f.profiles.value.copy(selectedId = first.id)
        f.start()
        val saved = f.store.value.value
        val writes = f.store.writes
        f.store.fail = true
        f.timer.setEnabled(false); f.timer.setMinutes(45); f.timer.refresh()
        assertEquals(saved, f.store.value.value)
        assertEquals(writes, f.store.writes)
        assertEquals(TimerError.STORAGE, f.timer.state.value.operationError)
    }

    @Test fun duplicateOldAndEarlyEventsNeverCauseExtraDelivery() = runTest {
        val f = Fixture(); val old = f.start()
        f.timer.fire(old.token)
        assertTrue(f.targets.isEmpty())
        f.timer.setMinutes(15)
        val current = f.store.read().record!!
        f.clock.now = requireNotNull(current.deadline)
        f.timer.fire(old.token)
        val jobs = List(3) { launch { f.timer.fire(current.token) } }
        jobs.joinAll()
        assertEquals(listOf(first), f.targets)
    }

    @Test fun cancellationAndRestartAtDeadlineInvalidateQueuedOldEvent() = runTest {
        val f = Fixture(); val old = f.start()
        f.clock.now = requireNotNull(old.deadline)
        f.lock.lock()
        val cancel = launch { f.timer.setEnabled(false) }
        val restart = launch { f.timer.setEnabled(true) }
        val delivery = launch { f.timer.fire(old.token) }
        runCurrent(); f.lock.unlock()
        joinAll(cancel, restart, delivery)
        assertTrue(f.targets.isEmpty())
        assertNotEquals(old.token, f.store.read().record!!.token)
    }

    @Test fun alreadyClaimedDeliverySerializesCancelAndProfileChangesUntilCompletion() = runTest {
        val f = Fixture(); val record = f.start()
        f.clock.now = requireNotNull(record.deadline)
        val gate = CompletableDeferred<Unit>()
        f.deliver = { gate.await(); null }
        val delivery = launch { f.timer.fire(record.token) }
        runCurrent()
        assertEquals(TimerStatus.DELIVERING, f.store.read().record!!.status)
        val cancel = launch { f.timer.setEnabled(false) }
        val edit = launch { f.lock.withLock { f.profiles.value = f.profiles.value.copy(servers = listOf(second)) } }
        runCurrent()
        assertFalse(cancel.isCompleted); assertFalse(edit.isCompleted)
        gate.complete(Unit); joinAll(delivery, cancel, edit)
        assertEquals(listOf(first), f.targets)
        assertEquals(TimerStatus.CANCELLED, f.store.read().record!!.status)
    }

    @Test fun interruptedClaimNeverReplaysAfterProcessRestart() = runTest {
        val f = Fixture(); val record = f.start()
        f.clock.now = requireNotNull(record.deadline)
        f.deliver = { awaitCancellation() }
        val delivery = launch { f.timer.fire(record.token) }
        runCurrent(); delivery.cancelAndJoin()
        assertEquals(TimerStatus.DELIVERING, f.store.read().record!!.status)
        val count = f.targets.size
        val restarted = f.controller()
        restarted.refresh(); restarted.fire(record.token)
        assertEquals(TimerError.UNCERTAIN, f.store.read().record!!.error)
        assertEquals(count, f.targets.size)
        assertNull(f.scheduler.current)
    }

    @Test fun failedSchedulingAndInterruptedRegistrationAreNotShownAsActive() = runTest {
        val f = Fixture(); f.scheduler.fail = true
        f.timer.setEnabled(true)
        assertEquals(TimerError.SCHEDULING, f.store.read().record!!.error)
        assertFalse(f.timer.state.value.active)
        assertNull(f.scheduler.current)
        f.scheduler.fail = false
        f.start()
        f.store.write(f.store.read().copy(record = f.store.read().record!!.copy(status = TimerStatus.ARMING)))
        f.controller().refresh()
        assertEquals(TimerError.SCHEDULING, f.store.read().record!!.error)
        assertNull(f.scheduler.current)
    }

    @Test fun errorsArePersistedWithoutRetryAndProfileObserverCancelsWhileUiIsGone() = runTest {
        for (error in listOf(TimerError.NETWORK, TimerError.TIMEOUT, TimerError.AUTHENTICATION, TimerError.REJECTED)) {
            val f = Fixture(); f.start(); f.deliver = { error }; f.expire()
            f.controller().refresh()
            assertEquals(error, f.store.read().record!!.error)
            assertEquals(1, f.targets.size)
            assertNull(f.scheduler.current)
        }
        val f = Fixture(); f.timer.observe(backgroundScope); runCurrent(); f.start(); runCurrent()
        f.profiles.value = f.profiles.value.copy(servers = listOf(second))
        runCurrent()
        assertEquals(TimerStatus.PROFILE_CHANGED, f.store.read().record!!.status)
        assertNull(f.scheduler.current)
    }
}
