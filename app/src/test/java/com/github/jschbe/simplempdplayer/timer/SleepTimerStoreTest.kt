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
}
