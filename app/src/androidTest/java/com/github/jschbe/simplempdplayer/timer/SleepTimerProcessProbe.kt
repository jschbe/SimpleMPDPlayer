package com.github.jschbe.simplempdplayer.timer

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in host-driven process-kill check. Only the emulator's host-loopback bridge is allowed.
 * The host driver must close the fixture and call cleanup after inspecting a normal Activity restart.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerProcessProbe {
    @Test fun prepare() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("timerProcessProbe") == "prepare")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PlayerApplication
        val port = args.getString("fixturePort")!!.toInt()
        val store = ServerSettingsStore(app.serverDataStore)
        val profile = ServerInput("Process fixture", "10.0.2.2", port.toString()).toProfile()!!
        app.sleepTimer.setEnabled(false)
        store.saveAndSelect(profile)
        app.sleepTimer.setMinutes(15)
        app.sleepTimer.setEnabled(true)
        val record = SleepTimerStore(app.sleepTimerDataStore).read().record!!
        assertEquals(TimerStatus.WAITING, record.status)
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("timerToken", record.token)
            putString("timerStatus", record.status.name)
        })
    }
    @Test fun cleanup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("timerProcessProbe") == "cleanup")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PlayerApplication
        app.sleepTimer.setEnabled(false)
        val store = ServerSettingsStore(app.serverDataStore)
        store.settings.first().servers.filter { it.name == "Process fixture" }.forEach { store.delete(it.id) }
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
    }
}
