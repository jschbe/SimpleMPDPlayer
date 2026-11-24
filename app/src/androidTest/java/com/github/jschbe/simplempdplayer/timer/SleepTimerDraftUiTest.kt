package com.github.jschbe.simplempdplayer.timer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerSettings
import com.github.jschbe.simplempdplayer.ui.SleepTimerDialog
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepTimerDraftUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = ServerInput("Timer fixture", "localhost").toProfile()!!
    private val saved = MutableStateFlow(TimerSettings())
    private var failWrites = false
    private var writes = 0
    private val timer = SleepTimerController(
        store = object : TimerStorage {
            override val settings = saved
            override suspend fun read() = saved.value
            override suspend fun write(settings: TimerSettings) {
                writes++
                if (failWrites) throw IOException("Test write failure")
                saved.value = settings
            }
        },
        profiles = flowOf(ServerSettings(listOf(server), server.id)),
        clock = object : TimerClock {
            override fun elapsed() = 1L
            override fun wallTime() = 1L
            override fun boot() = 1
        },
        scheduler = object : TimerScheduler {
            override fun schedule(record: TimerRecord) = error("Off timer scheduled an alarm")
            override fun cancel() = Unit
            override fun hasPendingIntent() = false
        },
        mutationLock = Mutex(),
        pause = { error("The fixture must never send Pause") },
    )
    private val visible = mutableStateOf(true)
    private val settingsBusy = mutableStateOf(false)
    private val restrict = mutableStateOf<(SleepTimerState) -> SleepTimerState>({ it })

    private fun show() {
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        compose.setContent {
            val state by timer.state.collectAsState()
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { timer.refresh() }
            SimpleMPDPlayerTheme {
                if (visible.value) SleepTimerDialog(restrict.value(state), server.name, settingsBusy.value, {},
                    { minutes -> scope.launch { timer.setMinutes(minutes) }; true },
                    { scope.launch { timer.refresh() } }, { visible.value = false })
            }
        }
        compose.onNodeWithTag("timer_duration_dropdown").performScrollTo().performClick()
        compose.onNodeWithTag("timer_custom_option").performClick()
    }

    @Test fun changedDraftWaitsForAvailabilityAndAppliesAfterAnActualWriteFailure() {
        show()
        val custom = compose.onNodeWithTag("timer_custom_minutes")
        val done = compose.onNodeWithText("Done")
        custom.performScrollTo().performTextReplacement("42")
        val delivering = TimerRecord("delivery", server, 1, 1L, 10L, TimerStatus.DELIVERING)
        val restrictions: List<(SleepTimerState) -> SleepTimerState> = listOf(
            { it.copy(loading = true) },
            { it.copy(busy = true) },
            { it.copy(settings = it.settings.copy(record = delivering)) },
            { it.copy(operationError = TimerError.STORAGE) },
        )
        for (restriction in restrictions) {
            compose.runOnIdle { restrict.value = restriction }
            done.assertIsNotEnabled()
            compose.onNodeWithText("Cancel").assertIsEnabled()
            custom.performScrollTo().assertTextContains("42")
        }
        compose.runOnIdle {
            restrict.value = { it }
            settingsBusy.value = true
        }
        done.assertIsNotEnabled()
        custom.performScrollTo().assertTextContains("42")
        compose.runOnIdle {
            settingsBusy.value = false
            failWrites = true
            assertEquals(0, writes)
        }
        done.assertIsEnabled().performClick()
        compose.waitUntil { timer.state.value.operationError == TimerError.STORAGE }
        done.assertIsNotEnabled()
        custom.performScrollTo().assertTextContains("42")
        compose.runOnIdle {
            assertEquals(30, saved.value.minutes)
            assertEquals(1, writes)
            failWrites = false
        }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        done.assertIsEnabled().performClick()
        compose.waitUntil { saved.value.minutes == 42 }
        done.assertDoesNotExist()
        compose.runOnIdle {
            assertNull(saved.value.record)
            assertEquals(2, writes)
        }
    }

    @Test fun unchangedDurationClosesWithoutWritingAndInvalidDraftCanBeCancelled() {
        show()
        val custom = compose.onNodeWithTag("timer_custom_minutes")
        val done = compose.onNodeWithText("Done")
        custom.performScrollTo().performTextReplacement("1441")
        done.assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        custom.performTextReplacement("30")
        compose.runOnIdle { settingsBusy.value = true }
        done.assertIsEnabled().performClick()
        done.assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes) }
    }
}
