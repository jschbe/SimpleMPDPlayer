package com.github.jschbe.simplempdplayer.timer

import com.github.jschbe.simplempdplayer.data.ServerSettings
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.mpd.PlaybackState
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One global timer. The same mutation lock protects profile edits and bounded delivery. */
class SleepTimerController(
    private val store: TimerStorage,
    private val profiles: Flow<ServerSettings>,
    private val clock: TimerClock,
    private val scheduler: TimerScheduler,
    private val mutationLock: Mutex,
    private val pause: suspend (ServerProfile) -> TimerError?,
    private val playback: () -> TimerPlayback? = { null },
) {
    private val mutableState = MutableStateFlow(SleepTimerState())
    val state = mutableState.asStateFlow()
    private var delivering: String? = null
    private var observation: kotlinx.coroutines.Job? = null
    private var ownerScope: CoroutineScope? = null
    // The receiver may run out of time before it can claim or write. Keep that evidence
    // until the next ordinary reconciliation, without extending the receiver's lifetime.
    private val failedDeliveries = ConcurrentHashMap<String, Long>()

    fun retry() {
        ownerScope?.let { scope ->
            // Foreground/retry must not cancel an in-flight WAITING -> ARMING commit.
            if (observation?.isActive == true) scope.launch { refresh() } else observe(scope)
        }
    }

    fun observe(scope: CoroutineScope) {
        ownerScope = scope
        observation?.cancel()
        observation = scope.launch {
            combine(store.settings, profiles) { _, _ -> Unit }.catch { error ->
                if (error is IOException) mutableState.update { it.copy(loading = false, operationError = TimerError.STORAGE) }
                else throw error
            }.collect { refresh() }
        }
    }

    suspend fun refresh() = reconcile()

    private suspend fun reconcile(failedToken: String? = null) = operation {
        var saved = store.read()
        val record = saved.record
        if (failedToken != null && (record?.token != failedToken ||
                !(knownDeliveryFailure(record) || record.status == TimerStatus.DELIVERING))) return@operation
        val servers = profiles.first().also { if (it.readFailed) throw IOException() }
        if (record != null && record.pending) {
            val invalid = when {
                clock.boot() == null -> record.copy(status = TimerStatus.FAILED, error = TimerError.CLOCK)
                record.boot != clock.boot() || clock.elapsed() < record.started -> record.copy(status = TimerStatus.REBOOTED)
                !record.matches(servers.servers.find { it.id == record.target.id }) -> record.copy(status = TimerStatus.PROFILE_CHANGED)
                record.status == TimerStatus.ARMING -> record.copy(status = TimerStatus.FAILED, error = TimerError.SCHEDULING)
                record.status == TimerStatus.DELIVERING && delivering != record.token -> record.copy(status = TimerStatus.FAILED, error = TimerError.UNCERTAIN)
                record.status == TimerStatus.ACTIVE && (knownDeliveryFailure(record) || !scheduler.hasPendingIntent()) ->
                    record.copy(status = TimerStatus.FAILED, error = TimerError.MISSING_ALARM)
                else -> null
            }
            if (invalid != null) {
                saved = saved.copy(record = invalid)
                store.write(saved)
                scheduler.cancel()
            }
        } else scheduler.cancel()
        failedDeliveries.keys.removeAll { saved.record?.pending != true || it != saved.record?.token }
        val waiting = saved.record?.takeIf { it.status == TimerStatus.WAITING }
        if (waiting != null) {
            scheduler.cancel()
            val current = servers.servers.first { it.id == waiting.target.id }
            if (isPlaying(current)) {
                startCountdown(saved, waiting.target.copy(credentialRevision = current.credentialRevision), servers)
                return@operation
            }
        }
        publish(saved, servers)
    }

    suspend fun setEnabled(enabled: Boolean) = operation {
        val saved = showFailedDelivery(store.read())
        val servers = profiles.first().also { if (it.readFailed) throw IOException() }
        if (enabled) {
            if (saved.record?.pending == true) { publish(saved, servers); return@operation }
            val target = servers.servers.find { it.id == servers.selectedId } ?: return@operation
            arm(saved, target, servers)
        } else {
            val stopped = saved.copy(record = saved.record?.copy(status = TimerStatus.CANCELLED, error = null))
            store.write(stopped)
            scheduler.cancel()
            publish(stopped, servers)
        }
    }

    suspend fun setMinutes(minutes: Int) {
        if (minutes !in 1..MAX_SLEEP_MINUTES) return
        operation {
            val saved = showFailedDelivery(store.read())
            val servers = profiles.first().also { if (it.readFailed) throw IOException() }
            if (saved.minutes == minutes) {
                store.confirmMinutes(minutes)
                publish(saved, servers)
                return@operation
            }
            val changed = saved.copy(minutes = minutes)
            val old = saved.record
            val current = servers.servers.find { it.id == old?.target?.id }
            if (old != null && old.status in setOf(TimerStatus.WAITING, TimerStatus.ACTIVE) &&
                current != null && old.matches(current)) {
                // Changing duration keeps the originally bound server, even after selecting another.
                arm(changed, old.target.copy(credentialRevision = current.credentialRevision), servers)
            } else {
                store.write(changed)
                publish(changed, servers)
            }
        }
    }

    /** Status notifications carry no snapshot: read current authority only after acquiring the lock. */
    suspend fun playbackChanged() {
        if (state.value.waiting) refresh()
    }

    private fun isPlaying(target: ServerProfile): Boolean {
        val current = playback() ?: return false
        return current.server.id == target.id && current.server.host == target.host &&
            current.server.mpdPort == target.mpdPort && current.server.streamPort == target.streamPort &&
            current.server.credentialRevision == target.credentialRevision &&
            current.connection.hasConfirmedPlayerStatus(clock.elapsed()) &&
            current.connection.snapshot?.status?.playback == PlaybackState.PLAYING
    }

    private suspend fun arm(saved: TimerSettings, target: ServerProfile, servers: ServerSettings) {
        if (isPlaying(target)) {
            startCountdown(saved, target, servers)
        } else {
            val boot = clock.boot()
            if (boot == null) { mutableState.update { it.copy(operationError = TimerError.CLOCK) }; return }
            val record = TimerRecord(UUID.randomUUID().toString(), target.copy(credentialRevision = null),
                boot, clock.elapsed(), null, TimerStatus.WAITING)
            val waiting = saved.copy(record = record, recovered = false)
            store.write(waiting)
            scheduler.cancel()
            publish(waiting, servers)
        }
    }

    private suspend fun startCountdown(saved: TimerSettings, target: ServerProfile, servers: ServerSettings) {
        val boot = clock.boot()
        if (boot == null) { mutableState.update { it.copy(operationError = TimerError.CLOCK) }; return }
        val now = clock.elapsed()
        val record = TimerRecord(UUID.randomUUID().toString(), target.copy(credentialRevision = null), boot, now,
            now + saved.minutes * 60_000L, TimerStatus.ARMING)
        val arming = saved.copy(record = record, recovered = false)
        // A crash between persistence and OS registration is detectable, never silently re-armed.
        store.write(arming)
        scheduler.cancel()
        try {
            scheduler.schedule(record)
            val active = arming.copy(record = record.copy(status = TimerStatus.ACTIVE))
            store.write(active)
            publish(active, servers)
        } catch (_: SecurityException) {
            schedulingFailed(arming, servers)
        } catch (_: IllegalStateException) {
            schedulingFailed(arming, servers)
        }
    }

    private suspend fun schedulingFailed(saved: TimerSettings, servers: ServerSettings) {
        scheduler.cancel()
        val failed = saved.copy(record = saved.record?.copy(status = TimerStatus.FAILED, error = TimerError.SCHEDULING))
        store.write(failed)
        publish(failed, servers)
    }

    /** Receiver entry point. Account for storage failures and timeout within the original budget. */
    internal suspend fun deliverAlarm(token: String, budgetMillis: Long): Boolean {
        val received = clock.elapsed()
        val completed = withTimeoutOrNull(budgetMillis) {
            if (!fire(token)) {
                recordFailedDelivery(token, received)
                reconcile(failedToken = token)
            }
            true
        } ?: false
        if (!completed) recordFailedDelivery(token, received)
        return completed
    }

    private fun recordFailedDelivery(token: String, received: Long) {
        failedDeliveries.merge(token, received, ::maxOf)
        mutableState.update { current ->
            val display = showFailedDelivery(current.settings)
            if (display === current.settings) current
            else current.copy(settings = display, remainingMillis = null, estimatedEndMillis = null)
        }
    }

    private fun knownDeliveryFailure(record: TimerRecord): Boolean =
        record.status == TimerStatus.ACTIVE &&
            (failedDeliveries[record.token] ?: -1) >= requireNotNull(record.deadline)

    private fun showFailedDelivery(saved: TimerSettings): TimerSettings {
        val record = saved.record ?: return saved
        return if (knownDeliveryFailure(record)) {
            saved.copy(record = record.copy(status = TimerStatus.FAILED, error = TimerError.MISSING_ALARM))
        } else saved
    }

    /** Durable claim before IO; duplicated/stale/early events cannot send a command. */
    suspend fun fire(token: String): Boolean = tryOperation {
        var saved = store.read()
        val record = saved.record ?: return@tryOperation
        if (record.token != token || record.status != TimerStatus.ACTIVE || knownDeliveryFailure(record)) return@tryOperation
        val servers = profiles.first().also { if (it.readFailed) throw IOException() }
        val target = servers.servers.find { it.id == record.target.id }
        val invalid = when {
            clock.boot() == null -> record.copy(status = TimerStatus.FAILED, error = TimerError.CLOCK)
            clock.boot() != record.boot || clock.elapsed() < record.started -> record.copy(status = TimerStatus.REBOOTED)
            !record.matches(target) -> record.copy(status = TimerStatus.PROFILE_CHANGED)
            else -> null
        }
        if (invalid != null) {
            saved = saved.copy(record = invalid)
            store.write(saved); scheduler.cancel(); publish(saved, servers)
            return@tryOperation
        }
        if (clock.elapsed() < requireNotNull(record.deadline)) return@tryOperation
        delivering = record.token
        try {
            saved = saved.copy(record = record.copy(status = TimerStatus.DELIVERING))
            store.write(saved)
            publish(saved, servers)
            scheduler.cancel()
            val error = pause(record.target.copy(credentialRevision = requireNotNull(target).credentialRevision))
            saved = saved.copy(record = record.copy(
                status = if (error == null) TimerStatus.COMPLETED else TimerStatus.FAILED, error = error))
            store.write(saved)
            publish(saved, servers)
        } finally { delivering = null }
    }

    /** UI-only calculation; never moves the elapsed deadline or schedules another alarm. */
    fun tick() {
        val record = mutableState.value.settings.record
        val untilDeadline = record?.takeIf { it.status == TimerStatus.ACTIVE }?.let { it.deadline?.minus(clock.elapsed()) }
        mutableState.update { it.copy(remainingMillis = untilDeadline?.coerceAtLeast(0),
            estimatedEndMillis = untilDeadline?.let { time -> clock.wallTime() + time }) }
    }

    private fun publish(saved: TimerSettings, servers: ServerSettings) {
        mutableState.update { it.copy(settings = showFailedDelivery(saved), loading = false,
            targetName = saved.record?.let { record -> servers.servers.find { it.id == record.target.id }?.name ?: record.target.name },
            operationError = null) }
        tick()
    }

    private suspend fun operation(block: suspend () -> Unit) {
        tryOperation(block)
    }

    private suspend fun tryOperation(block: suspend () -> Unit): Boolean {
        try {
            mutationLock.withLock {
                mutableState.update { it.copy(busy = true) }
                try { block() } finally { mutableState.update { it.copy(busy = false) } }
            }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) {
            mutableState.update { it.copy(loading = false, operationError = TimerError.STORAGE) }
            return false
        }
    }
}
