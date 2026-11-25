package com.github.jschbe.simplempdplayer.timer

import androidx.datastore.preferences.core.*
import com.github.jschbe.simplempdplayer.data.ServerInput
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SleepTimerStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val target = ServerInput("Test", "localhost").toProfile()!!
    private val record = TimerRecord(UUID.randomUUID().toString(), target, 5, 10, 60_010)

    @Test fun codecRejectsMalformedInputAndRoundTripsEveryTerminalStatus() {
        val longName = record.copy(target = target.copy(name = "A".repeat(5000)))
        assertEquals(longName, TimerCodec.decode(TimerCodec.encode(longName)))
        TimerStatus.entries.forEach { status ->
            val original = record.copy(status = status, deadline = if (status == TimerStatus.WAITING) null else record.deadline, error = if (status == TimerStatus.FAILED) TimerError.UNCERTAIN else null)
            assertEquals(original, TimerCodec.decode(TimerCodec.encode(original)))
        }
        TimerError.entries.forEach { error ->
            val original = record.copy(status = TimerStatus.FAILED, error = error)
            assertEquals(original, TimerCodec.decode(TimerCodec.encode(original)))
        }
        listOf("", "garbage", TimerCodec.encode(record).replace(record.token, "not-a-token"),
            TimerCodec.encode(record).replace("\t60010\t", "\t9\t"),
            TimerCodec.encode(record).replace("\tlocalhost\t", "\thttp://bad.invalid/\t"))
            .forEach { assertNull(TimerCodec.decode(it)) }
    }

    @Test fun durationAndActiveRecordSurviveActualDiskStoreRecreation() = runBlocking {
        val file = folder.root.resolve("timer.preferences_pb")
        suspend fun withStore(block: suspend (SleepTimerStore) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val data = PreferenceDataStoreFactory.create(scope = scope) { file }
                block(SleepTimerStore(data))
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        withStore { it.write(TimerSettings(45, record)) }
        withStore { assertEquals(TimerSettings(45, record), it.read()) }
        val waiting = record.copy(status = TimerStatus.WAITING, deadline = null)
        withStore { it.write(TimerSettings(60, waiting)) }
        withStore { assertEquals(TimerSettings(60, waiting), it.read()) }
    }

    @Test fun corruptTypesAreRecoverableButOrdinaryReadFailureDoesNotWriteDefaults() = runTest {
        var writes = 0
        val data = object : androidx.datastore.core.DataStore<Preferences> {
            override val data = kotlinx.coroutines.flow.flow<Preferences> { throw IOException() }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences { writes++; return emptyPreferences() }
        }
        val store = SleepTimerStore(data)
        assertTrue(runCatching { store.read() }.exceptionOrNull() is IOException)
        assertEquals(0, writes)
        val bad = object : androidx.datastore.core.DataStore<Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(mutablePreferencesOf(stringPreferencesKey("minutes") to "bad", intPreferencesKey("timer_v1") to 5))
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
        }
        val recovered = SleepTimerStore(bad).read()
        assertTrue(recovered.recovered)
        assertEquals(30, recovered.minutes)
        assertNull(recovered.record)
    }
    @Test fun migratesLegacyDurationIncludingExplicitDefaultWithoutChangingExistingTimers() = runTest {
        for (minutes in listOf(30, 45)) {
            for (saved in listOf(record, record.copy(status = TimerStatus.WAITING, deadline = null))) {
                val runtime = MemoryPreferences()
                val backup = MemoryPreferences()
                SleepTimerStore(runtime).write(TimerSettings(minutes, saved))
                val before = runtime.data.value
                val migrated = SleepTimerStore(runtime, backup)
                assertEquals(TimerSettings(minutes, saved), migrated.read())
                assertEquals(minutes, backup.data.value[preferredMinutesKey])
                assertEquals(before, runtime.data.value)
                assertEquals(TimerSettings(minutes, saved), migrated.read())
                assertEquals(1, backup.writes)
            }
        }
    }

    @Test fun restoredDurationWinsOverFreshOrLegacyValuesAndDoesNotRestoreTimer() = runTest {
        val runtime = MemoryPreferences()
        val backup = MemoryPreferences()
        val fresh = SleepTimerStore(runtime, backup)
        assertEquals(TimerSettings(), fresh.read())
        assertFalse(backup.data.value.contains(preferredMinutesKey))
        fresh.confirmMinutes(30)
        assertEquals(30, backup.data.value[preferredMinutesKey])
        SleepTimerStore(runtime).write(TimerSettings(45, record))
        assertEquals(TimerSettings(30, record), fresh.read())
        fresh.write(TimerSettings(60, record))
        val restored = SleepTimerStore(MemoryPreferences(), backup)
        assertEquals(TimerSettings(60), restored.read())
        assertEquals(60, backup.data.value[preferredMinutesKey])
    }

    @Test fun failedMigrationRemainsRetryableAndFailedDurationChangePreservesTimer() = runTest {
        val runtime = MemoryPreferences()
        val backup = MemoryPreferences()
        SleepTimerStore(runtime).write(TimerSettings(45, record))
        backup.failWrite = true
        val store = SleepTimerStore(runtime, backup)
        assertEquals(TimerSettings(45, record), store.read())
        assertFalse(backup.data.value.contains(preferredMinutesKey))
        backup.failWrite = false
        assertEquals(TimerSettings(45, record), store.read())
        assertEquals(45, backup.data.value[preferredMinutesKey])
        runtime.failWrite = true
        assertTrue(runCatching { store.write(TimerSettings(60)) }.exceptionOrNull() is IOException)
        assertEquals(TimerSettings(45, record), store.read())
        assertEquals(45, backup.data.value[preferredMinutesKey])
    }

    private class MemoryPreferences : androidx.datastore.core.DataStore<Preferences> {
        override val data = kotlinx.coroutines.flow.MutableStateFlow(emptyPreferences())
        var writes = 0
        var failWrite = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrite) throw IOException()
            return transform(data.value).also {
                writes++
                data.value = it
            }
        }
    }

}
