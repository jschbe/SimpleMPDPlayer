package com.github.jschbe.simplempdplayer.audio

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only. Endpoint comes from instrumentation arguments, never saved profiles.
 * Repository startup/idle reads only; this class never calls perform or any MPD mutation API.
 */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class LiveStreamReadOnlyTest {
    @Test fun manuallyAuthorizedReceiverPreparesLiveHttpWithoutMpdMutation() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("readOnlyStreamHost")
        assumeTrue("Optional read-only LAN integration", !host.isNullOrBlank())
        val profile = ServerInput("Temporary read-only check", host!!,
            args.getString("readOnlyMpdPort", "6600"), args.getString("readOnlyStreamPort", "8000")).toProfile()!!
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PlayerApplication
        val activity = ActivityScenario.launch(MainActivity::class.java)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var done = false
                main { done = condition() }
                if (done) return
                SystemClock.sleep(50)
            }
            fail("Read-only live check timed out")
        }
        try {
            instrumentation.waitForIdleSync()
            lateinit var model: com.github.jschbe.simplempdplayer.ServersViewModel
            activity.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[com.github.jschbe.simplempdplayer.ServersViewModel::class.java] }
            await { !model.state.value.loading }
            val http = profile.streamUri().toURL().openConnection() as java.net.HttpURLConnection
            val received = try {
                http.connectTimeout = 8_000
                http.readTimeout = 8_000
                assertEquals(200, http.responseCode)
                assertTrue(http.contentType.startsWith("audio/mpeg"))
                http.inputStream.use { input ->
                    val buffer = ByteArray(16_384)
                    var total = 0
                    while (total < buffer.size) {
                        val count = input.read(buffer, total, buffer.size - total)
                        if (count < 0) break
                        total += count
                    }
                    assertEquals(buffer.size, total)
                    total
                }
            } finally { http.disconnect() }
            main { app.localStream.disable(); app.repository.connect(profile) }
            await { app.repository.state.value.status == ConnectionStatus.CONNECTED }
            val playback = app.repository.state.value.snapshot!!.status.playback
            main { app.localStream.enable(profile, app.repository.state.value) }
            await { app.localStream.state.value.phase in setOf(StreamPhase.ACTIVE, StreamPhase.WAITING_FOR_MPD) }
            assertEquals(ConnectionStatus.CONNECTED, app.repository.state.value.status)
            instrumentation.sendStatus(0, Bundle().apply {
                putInt("readOnlyHttpBytes", received)
                putString("readOnlyMpdPlayback", playback.name)
                putString("readOnlyStreamPhase", app.localStream.state.value.phase.name)
            })
        } finally {
            main { app.localStream.disable(); app.repository.close() }
            activity.close()
        }
    }
}
