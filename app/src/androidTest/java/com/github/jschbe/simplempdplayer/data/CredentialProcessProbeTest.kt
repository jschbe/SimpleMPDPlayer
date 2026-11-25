package com.github.jschbe.simplempdplayer.data

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.PlayerApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host stops the isolated app between two explicit phases. Uses only synthetic loopback settings. */
@RunWith(AndroidJUnit4::class)
class CredentialProcessProbeTest {
    @Test fun encryptedCredentialSurvivesASeparateProcess() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("credentialProcessProbe")
        assumeTrue("Host-driven credential process probe", phase in listOf("seed", "verify"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PlayerApplication
        val fixture = ServerProfile("00000000-0000-0000-0000-000000000909", "Credential process fixture", "127.0.0.1", 1)
        val synthetic = " synthetic ♫ \\ password "
        if (phase == "seed") {
            app.serverSettings.saveAndSelect(fixture, PasswordEdit.Replace(synthetic))
        } else {
            try {
                val profile = app.serverSettings.settings.first().servers.first { it.id == fixture.id }
                assertTrue(app.credentials.resolve(profile) == synthetic)
            } finally {
                app.serverSettings.delete(fixture.id)
            }
        }
        instrumentation.sendStatus(2, Bundle().apply { putInt("credentialProbePid", Process.myPid()) })
    }
}
