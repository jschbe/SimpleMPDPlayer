package com.github.jschbe.simplempdplayer.timer

import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.PasswordEdit
import com.github.jschbe.simplempdplayer.data.ServerProfile
import com.github.jschbe.simplempdplayer.data.profileMutationLock
import com.github.jschbe.simplempdplayer.data.serverDataStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The host terminates the isolated process without Force Stop after arming the OS alarm. */
@RunWith(AndroidJUnit4::class)
class CredentialCleanupAlarmProbe {
    @Test fun alarmOnlyStartupLeavesOrphansForInteractiveCleanup() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("cleanupAlarmProbe")
        assumeTrue("Host-driven cold alarm/credential cleanup probe", phase == "arm" || phase == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PlayerApplication
        check(app.packageName.endsWith(".devicetest"))
        val port = requireNotNull(arguments.getString("fixturePort")).toInt()
        val target = ServerProfile("00000000-0000-0000-0000-000000000910", "Cold alarm fixture", "10.0.2.2", port)
        val orphan = target.copy(
            id = "00000000-0000-0000-0000-000000000911",
            credentialRevision = "00000000-0000-0000-0000-000000000912",
        )
        val revision = requireNotNull(orphan.credentialRevision)
        val orphanFile = File(app.noBackupFilesDir, "mpd_credentials/$revision")
        val synthetic = "synthetic cold alarm fixture"
        val protected = arguments.getString("fixtureProtected") == "true"
        val storage = SleepTimerStore(app.sleepTimerDataStore, app.serverDataStore)
        if (phase == "arm") {
            app.sleepTimer.setEnabled(false)
            app.serverSettings.saveAndSelect(target, if (protected) PasswordEdit.Replace(synthetic) else PasswordEdit.Keep)
            val clock = AndroidTimerClock(app)
            val now = clock.elapsed()
            val record = TimerRecord(UUID.randomUUID().toString(), target, requireNotNull(clock.boot()), now, now + 15_000)
            profileMutationLock.withLock {
                app.credentials.put(orphan, synthetic)
                storage.write(TimerSettings(record = record.copy(status = TimerStatus.ARMING)))
                AndroidTimerScheduler(app).schedule(record)
                storage.write(TimerSettings(record = record))
            }
            assertTrue(orphanFile.isFile)
            instrumentation.sendStatus(2, Bundle().apply { putInt("cleanupArmPid", Process.myPid()) })
        } else {
            try {
                assertEquals(TimerStatus.COMPLETED, storage.read().record?.status)
                assertTrue("Alarm-only process pruned the orphan", orphanFile.isFile)
                app.sleepTimer.setEnabled(true)
                assertEquals(TimerStatus.WAITING, storage.read().record?.status)
                ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    instrumentation.waitForIdleSync()
                    assertEquals(TimerStatus.WAITING, storage.read().record?.status)
                    assertTrue("Interactive cleanup ran with a waiting timer", orphanFile.isFile)
                    activity.moveToState(Lifecycle.State.CREATED)
                    app.sleepTimer.setEnabled(false)
                    app.sleepTimer.refresh()
                    activity.moveToState(Lifecycle.State.RESUMED)
                    withTimeout(10_000) { while (orphanFile.exists()) delay(50) }
                    val saved = app.serverSettings.settings.first().servers.single { it.id == target.id }
                    assertTrue(app.credentials.resolve(saved) == synthetic.takeIf { protected })
                }
            } finally {
                app.sleepTimer.setEnabled(false)
                app.serverSettings.delete(target.id)
                if (orphanFile.exists()) app.credentials.remove(revision)
            }
        }
    }
}
