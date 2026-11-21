package com.github.jschbe.simplempdplayer.timer

import com.github.jschbe.simplempdplayer.data.ServerSettings
import com.github.jschbe.simplempdplayer.data.ServerProfile
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One global timer. The same mutation lock protects profile edits and bounded delivery. */
class SleepTimerController(
    private val store: TimerStorage,
    private val profiles: Flow<ServerSettings>,
    private val clock: TimerClock,
    private val scheduler: TimerScheduler,
    private val mutationLock: Mutex,
    private val pause: suspend (ServerProfile) -> TimerError?,
) {
    private val mutableState = MutableStateFlow(SleepTimerState())
    val state = mutableState.asStateFlow()
    private var delivering: String? = null
    private var observation: kotlinx.coroutines.Job? = null
    private var ownerScope: CoroutineScope? = null

    fun retry() { ownerScope?.let { observe(it) } }

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

    suspend fun refresh() = operation {
        var saved = store.read()
        val servers = profiles.first().also { if (it.readFailed) throw IOException() }
        val record = saved.record
        if (record != null && record.pending) {
            val invalid = when {
                clock.boot() == null -> record.copy(status = TimerStatus.FAILED, error = TimerError.CLOCK)
                record.boot != clock.boot() || clock.elapsed() < record.started -> record.copy(status = TimerStatus.REBOOTED)
                !record.matches(servers.servers.find { it.id == record.target.id }) -> record.copy(status = TimerStatus.PROFILE_CHANGED)
                record.status == TimerStatus.ARMING -> record.copy(status = TimerStatus.FAILED, error = TimerError.SCHEDULING)
                record.status == TimerStatus.DELIVERING && delivering != record.token -> record.copy(status = TimerStatus.FAILED, error = TimerError.UNCERTAIN)
                record.status == TimerStatus.ACTIVE && !scheduler.hasPendingIntent() -> record.copy(status = TimerStatus.FAILED, error = TimerError.MISSING_ALARM)
                else -> null
            }
            if (invalid != null) {
                saved = saved.copy(record = invalid)
                store.write(saved)
                scheduler.cancel()
            }
        } else scheduler.cancel()
        publish(saved, servers)
    }

    suspend fun setEnabled(enabled: Boolean) = operation {
        val saved = store.read()
        val servers = profiles.first().also { if (it.readFailed) throw IOException() }
        if (enabled) {
            if (saved.record?.status == TimerStatus.ACTIVE) { publish(saved, servers); return@operation }
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
            val saved = store.read()
            val servers = profiles.first().also { if (it.readFailed) throw IOException() }
            if (saved.minutes == minutes) { publish(saved, servers); return@operation }
            val changed = saved.copy(minutes = minutes)
            val old = saved.record
            if (old?.status == TimerStatus.ACTIVE && old.matches(servers.servers.find { it.id == old.target.id })) {
                // Changing duration keeps the originally bound server, even after selecting another.
                arm(changed, old.target, servers)
            } else {
                store.write(changed)
                publish(changed, servers)
            }
        }
    }

    private suspend fun arm(saved: TimerSettings, target: ServerProfile, servers: ServerSettings) {
        val boot = clock.boot()
        if (boot == null) { mutableState.update { it.copy(operationError = TimerError.CLOCK) }; return }
        val now = clock.elapsed()
        val record = TimerRecord(UUID.randomUUID().toString(), target, boot, now,
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

    /** Receiver only: durable claim before IO; duplicated/stale/early events cannot send a command. */
    suspend fun fire(token: String) = operation {
        var saved = store.read()
        val record = saved.record ?: return@operation
        if (record.token != token || record.status != TimerStatus.ACTIVE) return@operation
        val servers = profiles.first().also { if (it.readFailed) throw IOException() }
        val invalid = when {
            clock.boot() == null -> record.copy(status = TimerStatus.FAILED, error = TimerError.CLOCK)
            clock.boot() != record.boot || clock.elapsed() < record.started -> record.copy(status = TimerStatus.REBOOTED)
            !record.matches(servers.servers.find { it.id == record.target.id }) -> record.copy(status = TimerStatus.PROFILE_CHANGED)
            else -> null
        }
        if (invalid != null) {
            saved = saved.copy(record = invalid)
            store.write(saved); scheduler.cancel(); publish(saved, servers)
            return@operation
        }
        if (clock.elapsed() < record.deadline) return@operation
        delivering = record.token
        try {
            saved = saved.copy(record = record.copy(status = TimerStatus.DELIVERING))
            store.write(saved)
            publish(saved, servers)
            scheduler.cancel()
            val error = pause(record.target)
            saved = saved.copy(record = record.copy(
                status = if (error == null) TimerStatus.COMPLETED else TimerStatus.FAILED, error = error))
            store.write(saved)
            publish(saved, servers)
        } finally { delivering = null }
    }

    /** UI-only calculation; never moves the elapsed deadline or schedules another alarm. */
    fun tick() {
        val record = mutableState.value.settings.record
        val untilDeadline = record?.takeIf { it.status == TimerStatus.ACTIVE }?.let { it.deadline - clock.elapsed() }
        mutableState.update { it.copy(remainingMillis = untilDeadline?.coerceAtLeast(0),
            estimatedEndMillis = untilDeadline?.let { time -> clock.wallTime() + time }) }
    }

    private fun publish(saved: TimerSettings, servers: ServerSettings) {
        mutableState.update { it.copy(settings = saved, loading = false,
            targetName = saved.record?.let { record -> servers.servers.find { it.id == record.target.id }?.name ?: record.target.name },
            operationError = null) }
        tick()
    }

    private suspend fun operation(block: suspend () -> Unit) {
        try {
            mutationLock.withLock {
                mutableState.update { it.copy(busy = true) }
                try { block() } finally { mutableState.update { it.copy(busy = false) } }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { mutableState.update { it.copy(loading = false, operationError = TimerError.STORAGE) } }
    }
}
