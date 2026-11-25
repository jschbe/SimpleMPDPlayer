package com.github.jschbe.simplempdplayer.timer

import com.github.jschbe.simplempdplayer.data.ServerProfile
import java.util.UUID
import com.github.jschbe.simplempdplayer.mpd.ConnectionState

val sleepTimerPresets = listOf(15, 30, 45, 60)
const val DEFAULT_SLEEP_MINUTES = 30
const val MAX_SLEEP_MINUTES = 1_440
fun sleepMinutes(text: String): Int? = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it in 1..MAX_SLEEP_MINUTES }

enum class TimerStatus { WAITING, ARMING, ACTIVE, DELIVERING, COMPLETED, CANCELLED, PROFILE_CHANGED, REBOOTED, FAILED }
enum class TimerError {
    NETWORK, TIMEOUT, REJECTED, AUTHENTICATION, PERMISSION, PROTOCOL, SCHEDULING, UNCERTAIN, MISSING_ALARM, CLOCK, STORAGE,
    PASSWORD_REJECTED, MPD_PERMISSION, CREDENTIAL_MISSING, CREDENTIAL_UNREADABLE,
}

data class TimerRecord(
    val token: String,
    val target: ServerProfile,
    val boot: Int,
    val started: Long,
    val deadline: Long?,
    val status: TimerStatus = TimerStatus.ACTIVE,
    val error: TimerError? = null,
) {
    val pending get() = status in setOf(TimerStatus.WAITING, TimerStatus.ARMING, TimerStatus.ACTIVE, TimerStatus.DELIVERING)
    fun matches(profile: ServerProfile?) = profile != null && target.id == profile.id &&
        target.host == profile.host && target.mpdPort == profile.mpdPort && target.streamPort == profile.streamPort
    fun valid(): Boolean = runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false) &&
        target.isValid() && boot >= 0 && started >= 0 &&
        (if (deadline == null) status !in setOf(TimerStatus.ARMING, TimerStatus.ACTIVE, TimerStatus.DELIVERING)
         else status != TimerStatus.WAITING && deadline > started && deadline - started <= MAX_SLEEP_MINUTES * 60_000L) &&
        (status == TimerStatus.FAILED || error == null)
}

data class TimerSettings(val minutes: Int = DEFAULT_SLEEP_MINUTES, val record: TimerRecord? = null, val recovered: Boolean = false)
data class SleepTimerState(
    val settings: TimerSettings = TimerSettings(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val remainingMillis: Long? = null,
    val estimatedEndMillis: Long? = null,
    val targetName: String? = null,
    val operationError: TimerError? = null,
) {
    val active get() = settings.record?.status == TimerStatus.ACTIVE
    val waiting get() = settings.record?.status == TimerStatus.WAITING
    val enabled get() = settings.record?.pending == true
    val delivering get() = settings.record?.status == TimerStatus.DELIVERING
    val editable get() = !loading && !busy && !delivering && operationError != TimerError.STORAGE
}

/** Pulled under the timer mutation lock, never trusted from a queued status-event payload. */
data class TimerPlayback(val server: ServerProfile, val connection: ConnectionState)

interface TimerClock {
    fun elapsed(): Long
    fun wallTime(): Long
    fun boot(): Int?
}
interface TimerScheduler {
    fun schedule(record: TimerRecord)
    fun cancel()
    /** A PendingIntent is evidence of registration, not an Android delivery guarantee. */
    fun hasPendingIntent(): Boolean
}
