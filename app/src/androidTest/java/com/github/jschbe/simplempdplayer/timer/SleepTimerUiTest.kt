package com.github.jschbe.simplempdplayer.timer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepTimerUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PlayerApplication
    private val profiles = ServerSettingsStore(app.serverDataStore)
    private lateinit var fixture: TimerMpdFixture
    private lateinit var profile: ServerProfile
    private var activity: ActivityScenario<MainActivity>? = null

    @Before fun setup() {
        fixture = TimerMpdFixture()
        profile = ServerInput("Sleep fixture", fixture.host, fixture.port.toString()).toProfile()!!
        runBlocking {
            app.sleepTimer.setEnabled(false)
            profiles.saveAndSelect(profile)
            app.sleepTimer.setMinutes(30)
            app.sleepTimer.refresh()
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity!!.onActivity {
            it.setShowWhenLocked(true); it.setTurnScreenOn(true)
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Sleep timer").performClick()
    }
    @After fun cleanup() {
        runBlocking { app.sleepTimer.setEnabled(false) }
        activity?.close()
        runBlocking { profiles.delete(profile.id) }
        fixture.close()
    }

    @Test fun presetsCustomValidationRecreationAndCancellationKeepStateAndTheme() {
        compose.onNodeWithTag("sleep_timer_switch").assertIsOff()
        compose.onNodeWithTag("timer_preset_15").performClick()
        compose.waitUntil { app.sleepTimer.state.value.settings.minutes == 15 }
        compose.onNodeWithTag("sleep_timer_switch").performClick()
        compose.waitUntil { app.sleepTimer.state.value.active }
        val first = app.sleepTimer.state.value.settings.record!!
        compose.onAllNodesWithTag("timer_remaining").onLast().assertIsDisplayed()
        activity!!.recreate()
        compose.onNodeWithTag("sleep_timer_switch").assertIsOn()
        assertEquals(first.token, app.sleepTimer.state.value.settings.record!!.token)
        assertEquals(first.deadline, app.sleepTimer.state.value.settings.record!!.deadline)
        compose.onNodeWithTag("timer_custom_minutes").performTextReplacement("0")
        compose.onNodeWithTag("timer_apply_duration").assertIsNotEnabled()
        compose.onNodeWithTag("timer_custom_minutes").performTextReplacement("1441")
        compose.onNodeWithTag("timer_apply_duration").assertIsNotEnabled()
        compose.onNodeWithTag("timer_custom_minutes").performTextReplacement("1")
        compose.onNodeWithTag("timer_apply_duration").performScrollTo().performClick()
        compose.waitUntil { app.sleepTimer.state.value.settings.minutes == 1 }
        assertNotEquals(first.token, app.sleepTimer.state.value.settings.record!!.token)
        for (mode in ThemeMode.entries) {
            runBlocking { profiles.setTheme(mode) }
            compose.onNodeWithTag("sleep_timer_switch").performScrollTo().assertIsOn()
            assertEquals(1, runBlocking { SleepTimerStore(app.sleepTimerDataStore).read().minutes })
        }
        compose.onNodeWithTag("sleep_timer_switch").performClick()
        compose.waitUntil { !app.sleepTimer.state.value.active }
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
        assertTrue(fixture.mutations.isEmpty())
    }
}
