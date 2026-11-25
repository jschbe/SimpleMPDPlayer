package com.github.jschbe.simplempdplayer.timer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.github.jschbe.simplempdplayer.data.ServerProfile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val minutesKey = intPreferencesKey("minutes")
private val timerKey = stringPreferencesKey("timer_v1")
private val recoveredKey = booleanPreferencesKey("timer_recovered")
internal val preferredMinutesKey = intPreferencesKey("preferred_sleep_minutes_v1")

val Context.sleepTimerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sleep_timer",
    corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(recoveredKey to true) },
)

interface TimerStorage {
    val settings: Flow<TimerSettings>
    suspend fun read(): TimerSettings
    suspend fun write(settings: TimerSettings)
    suspend fun confirmMinutes(minutes: Int) = Unit
}

class SleepTimerStore(
    private val dataStore: DataStore<Preferences>,
    private val backupSettings: DataStore<Preferences>? = null,
) : TimerStorage {
    override val settings = if (backupSettings == null) dataStore.data.map { decode(it) }
        else combine(dataStore.data, backupSettings.data, ::decode)

    // Called under the profile/timer lock. Optional cleanup needs only the live record.
    internal suspend fun hasPendingTimer(): Boolean = decode(dataStore.data.first()).record?.pending == true

    // Production calls these methods under the profile/timer lock. An existing backed-up
    // value wins, including an explicit 30. Keep legacy minutes for recovery until copied.
    override suspend fun read(): TimerSettings {
        val runtime = dataStore.data.first()
        val backup = backupSettings ?: return decode(runtime)
        val preferences = backup.data.first()
        val legacy = runtime.raw(minutesKey).validMinutes()
        if (preferences.raw(preferredMinutesKey) != null || legacy == null) return decode(runtime, preferences)
        return try {
            val migrated = backup.edit {
                if (it.raw(preferredMinutesKey) == null) it[preferredMinutesKey] = legacy
            }
            decode(runtime, migrated)
        } catch (_: IOException) {
            // A migration write failure leaves the legacy value and live timer usable.
            decode(runtime, preferences)
        }
    }

    override suspend fun confirmMinutes(minutes: Int) {
        val backup = backupSettings ?: return
        if (backup.data.first().raw(preferredMinutesKey).validMinutes() == null) {
            backup.edit { it[preferredMinutesKey] = minutes }
        }
    }

    override suspend fun write(settings: TimerSettings) {
        require(settings.minutes in 1..MAX_SLEEP_MINUTES)
        val backup = backupSettings
        if (backup == null || read().minutes == settings.minutes) {
            writeRuntime(settings)
            return
        }
        val previous = backup.data.first().raw(preferredMinutesKey).validMinutes()
        backup.edit { it[preferredMinutesKey] = settings.minutes }
        try {
            writeRuntime(settings)
        } catch (error: IOException) {
            try {
                backup.edit {
                    if (previous == null) it.remove(preferredMinutesKey) else it[preferredMinutesKey] = previous
                }
            } catch (_: IOException) {
                // Report the operation failure; a committed preference remains authoritative.
            }
            throw error
        }
    }

    private suspend fun writeRuntime(settings: TimerSettings) {
        dataStore.edit { preferences ->
            if (backupSettings == null) preferences[minutesKey] = settings.minutes
            settings.record?.let { preferences[timerKey] = TimerCodec.encode(it) } ?: preferences.remove(timerKey)
            if (settings.recovered) preferences[recoveredKey] = true else preferences.remove(recoveredKey)
        }
    }
    private fun decode(preferences: Preferences, backup: Preferences? = null): TimerSettings {
        val rawMinutes = backup?.raw(preferredMinutesKey) ?: preferences.raw(minutesKey)
        val minutes = rawMinutes.validMinutes()
        val raw = preferences.raw(timerKey)
        val record = (raw as? String)?.let(TimerCodec::decode)
        return TimerSettings(minutes ?: DEFAULT_SLEEP_MINUTES, record,
            preferences.raw(recoveredKey) == true || (raw != null && record == null) ||
                (rawMinutes != null && minutes == null))
    }

    private fun Preferences.raw(key: Preferences.Key<*>) = asMap()[key]
    private fun Any?.validMinutes() = (this as? Int)?.takeIf { it in 1..MAX_SLEEP_MINUTES }
}

/** Versioned preference key and fixed fields; profile validation excludes the delimiters. */
internal object TimerCodec {
    fun encode(record: TimerRecord): String {
        require(record.valid())
        return with(record) {
            listOf(token, target.id, target.name, target.host, target.mpdPort, target.streamPort,
                boot, started, deadline?.toString().orEmpty(), status.name, error?.name.orEmpty()).joinToString("\t")
        }
    }
    fun decode(text: String): TimerRecord? = runCatching {
        val f = text.split('\t')
        require(f.size == 11)
        TimerRecord(f[0], ServerProfile(f[1], f[2], f[3], f[4].toInt(), f[5].toInt()),
            f[6].toInt(), f[7].toLong(), f[8].takeIf { it.isNotEmpty() }?.toLong(), TimerStatus.valueOf(f[9]),
            f[10].takeIf { it.isNotEmpty() }?.let(TimerError::valueOf)).also { require(it.valid()) }
    }.getOrNull()
}
