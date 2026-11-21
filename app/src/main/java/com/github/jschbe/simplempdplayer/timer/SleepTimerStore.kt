package com.github.jschbe.simplempdplayer.timer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.github.jschbe.simplempdplayer.data.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val minutesKey = intPreferencesKey("minutes")
private val timerKey = stringPreferencesKey("timer_v1")
private val recoveredKey = booleanPreferencesKey("timer_recovered")

val Context.sleepTimerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sleep_timer",
    corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(recoveredKey to true) },
)

interface TimerStorage {
    val settings: Flow<TimerSettings>
    suspend fun read(): TimerSettings
    suspend fun write(settings: TimerSettings)
}

class SleepTimerStore(private val dataStore: DataStore<Preferences>) : TimerStorage {
    override val settings = dataStore.data.map(::decode)
    override suspend fun read() = settings.first()
    override suspend fun write(settings: TimerSettings) {
        require(settings.minutes in 1..MAX_SLEEP_MINUTES)
        dataStore.edit { preferences ->
            preferences[minutesKey] = settings.minutes
            settings.record?.let { preferences[timerKey] = TimerCodec.encode(it) } ?: preferences.remove(timerKey)
            if (settings.recovered) preferences[recoveredKey] = true else preferences.remove(recoveredKey)
        }
    }
    private fun decode(preferences: Preferences): TimerSettings {
        val values = preferences.asMap().entries.associate { it.key.name to it.value }
        val minutes = (values[minutesKey.name] as? Int)?.takeIf { it in 1..MAX_SLEEP_MINUTES }
        val raw = values[timerKey.name]
        val record = (raw as? String)?.let(TimerCodec::decode)
        return TimerSettings(minutes ?: DEFAULT_SLEEP_MINUTES, record,
            values[recoveredKey.name] == true || (raw != null && record == null) ||
                (values[minutesKey.name] != null && minutes == null))
    }
}

/** Versioned preference key and fixed fields; profile validation excludes the delimiters. */
internal object TimerCodec {
    fun encode(record: TimerRecord): String {
        require(record.valid())
        return with(record) {
            listOf(token, target.id, target.name, target.host, target.mpdPort, target.streamPort,
                boot, started, deadline, status.name, error?.name.orEmpty()).joinToString("\t")
        }
    }
    fun decode(text: String): TimerRecord? = runCatching {
        val f = text.split('\t')
        require(f.size == 11)
        TimerRecord(f[0], ServerProfile(f[1], f[2], f[3], f[4].toInt(), f[5].toInt()),
            f[6].toInt(), f[7].toLong(), f[8].toLong(), TimerStatus.valueOf(f[9]),
            f[10].takeIf { it.isNotEmpty() }?.let(TimerError::valueOf)).also { require(it.valid()) }
    }.getOrNull()
}
