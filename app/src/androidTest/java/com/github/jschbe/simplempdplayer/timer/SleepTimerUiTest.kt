package com.github.jschbe.simplempdplayer.timer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
import com.github.jschbe.simplempdplayer.mpd.*
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
            profiles.setTrackInfoExpanded(true)
            app.sleepTimer.setMinutes(30)
            app.sleepTimer.refresh()
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity!!.onActivity {
            it.setShowWhenLocked(true); it.setTurnScreenOn(true)
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { app.repository.activeServer?.id == profile.id && app.repository.state.value.status == ConnectionStatus.CONNECTED }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Sleep timer").performClick()
    }
    @After fun cleanup() {
        runBlocking { app.sleepTimer.setEnabled(false) }
        activity?.close()
        runBlocking { profiles.setTrackInfoExpanded(true); profiles.delete(profile.id) }
        fixture.close()
    }

    private fun preset(minutes: Int) {
        compose.onNodeWithTag("timer_duration_dropdown").performScrollTo().performClick()
        compose.onNodeWithTag("timer_preset_$minutes").performClick()
        compose.waitUntil { app.sleepTimer.state.value.settings.minutes == minutes }
    }
    private fun custom() {
        compose.onNodeWithTag("timer_duration_dropdown").performScrollTo().performClick()
        compose.onNodeWithTag("timer_custom_option").performClick()
    }
    private fun externalPlayback(state: String, expected: PlaybackState) {
        fixture.playback(state)
        compose.waitUntil(10_000) { app.repository.state.value.snapshot?.status?.playback == expected }
    }

    @Test fun selectingTheInitialPresetPersistsAnExplicitDefaultWithoutArming() {
        val previous = runBlocking {
            profileMutationLock.withLock {
                app.sleepTimerDataStore.edit { it.remove(intPreferencesKey("minutes")) }
                app.serverDataStore.edit { it.remove(preferredMinutesKey) }
            }
            app.sleepTimer.refresh()
            assertNull(app.serverDataStore.data.first()[preferredMinutesKey])
            app.sleepTimer.state.value.settings.record
        }
        preset(30)
        compose.waitUntil(5_000) { runBlocking { app.serverDataStore.data.first()[preferredMinutesKey] } == 30 }
        assertEquals(previous, app.sleepTimer.state.value.settings.record)
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test fun dropdownAndImeDoneApplyValidCustomDurationWithoutEnablingTimer() {
        compose.onNodeWithTag("sleep_timer_switch").assertIsOff()
        preset(15)
        custom()
        compose.onNodeWithTag("timer_apply_duration").assertDoesNotExist()
        for (invalid in listOf("0", "1441")) {
            compose.onNodeWithTag("timer_custom_minutes").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithTag("timer_custom_minutes").performImeAction()
            compose.onNodeWithText("Enter a whole number from 1 to 1440.").assertIsDisplayed()
            assertEquals(15, app.sleepTimer.state.value.settings.minutes)
            assertFalse(app.sleepTimer.state.value.enabled)
        }
        compose.onNodeWithTag("timer_custom_minutes").performTextReplacement("1")
        compose.onNodeWithTag("timer_custom_minutes").performImeAction()
        compose.waitUntil { app.sleepTimer.state.value.settings.minutes == 1 }
        compose.onNodeWithTag("sleep_timer_switch").performScrollTo().assertIsOff()
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
        compose.onNodeWithTag("timer_custom_minutes").performScrollTo().performTextReplacement("2")
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil { app.sleepTimer.state.value.settings.minutes == 2 }
        assertFalse(app.sleepTimer.state.value.enabled)
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test fun waitingExternalPlayAndDurationResetSurviveRecreationWithoutDuplicateAlarms() {
        preset(15)
        compose.onNodeWithTag("sleep_timer_switch").performScrollTo().performClick()
        compose.waitUntil { app.sleepTimer.state.value.waiting }
        val waiting = app.sleepTimer.state.value.settings.record!!
        assertNull(waiting.deadline)
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
        compose.onAllNodesWithText("Waiting for playback").onLast().assertIsDisplayed()
        activity!!.recreate()
        compose.onNodeWithTag("sleep_timer_switch").assertIsOn()
        assertEquals(waiting.token, app.sleepTimer.state.value.settings.record!!.token)
        val before = SystemClock.elapsedRealtime()
        externalPlayback("play", PlaybackState.PLAYING)
        compose.waitUntil { app.sleepTimer.state.value.active }
        val active = app.sleepTimer.state.value.settings.record!!
        assertTrue(active.started >= before)
        assertEquals(active.started + 15 * 60_000L, active.deadline)
        assertTrue(AndroidTimerScheduler(app).hasPendingIntent())
        for ((wire, status) in listOf("pause" to PlaybackState.PAUSED, "stop" to PlaybackState.STOPPED, "play" to PlaybackState.PLAYING)) {
            externalPlayback(wire, status)
            assertEquals(active.token, app.sleepTimer.state.value.settings.record!!.token)
            assertEquals(active.deadline, app.sleepTimer.state.value.settings.record!!.deadline)
        }
        externalPlayback("pause", PlaybackState.PAUSED)
        custom()
        compose.onNodeWithTag("timer_custom_minutes").performScrollTo().performTextReplacement("1")
            compose.onNodeWithTag("timer_custom_minutes").performImeAction()
        compose.waitUntil { app.sleepTimer.state.value.settings.minutes == 1 && app.sleepTimer.state.value.waiting }
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
        externalPlayback("play", PlaybackState.PLAYING)
        compose.waitUntil { app.sleepTimer.state.value.active }
        val countdown = app.sleepTimer.state.value.settings.record!!
        activity!!.recreate()
        compose.onNodeWithTag("sleep_timer_switch").assertIsOn()
        assertEquals(countdown.token, app.sleepTimer.state.value.settings.record!!.token)
        assertEquals(countdown.deadline, app.sleepTimer.state.value.settings.record!!.deadline)
        for (mode in ThemeMode.entries) {
            runBlocking { profiles.setTheme(mode) }
            compose.onNodeWithTag("sleep_timer_switch").performScrollTo().assertIsOn()
            assertEquals(1, runBlocking { SleepTimerStore(app.sleepTimerDataStore, app.serverDataStore).read().minutes })
        }
        compose.onNodeWithTag("sleep_timer_switch").performClick()
        compose.waitUntil { !app.sleepTimer.state.value.enabled }
        assertFalse(AndroidTimerScheduler(app).hasPendingIntent())
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test fun trackInfoExpansionPersistsAcrossActivityRecreationAndStatusChanges() {
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("track_info_toggle").assertIsDisplayed().performClick()
        compose.waitUntil { !runBlocking { profiles.settings.first().trackInfoExpanded } }
        compose.onNodeWithTag("track_info_toggle").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
        activity!!.recreate()
        compose.onNodeWithTag("track_info_toggle").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
        externalPlayback("play", PlaybackState.PLAYING)
        compose.onNodeWithTag("track_info_toggle").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithTag("track_info_toggle").performClick()
        compose.waitUntil { runBlocking { profiles.settings.first().trackInfoExpanded } }
        assertTrue(fixture.mutations.isEmpty())
    }
}
