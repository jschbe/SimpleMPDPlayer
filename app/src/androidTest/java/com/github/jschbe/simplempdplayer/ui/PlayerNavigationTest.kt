package com.github.jschbe.simplempdplayer.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerSettings
import com.github.jschbe.simplempdplayer.mpd.*
import com.github.jschbe.simplempdplayer.timer.*
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure UI fixtures: no real server, ViewModel or stored profiles are accessed. */
@RunWith(AndroidJUnit4::class)
class PlayerNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = ServerInput("Test server", "localhost").toProfile()!!
    private val actions = mutableListOf<PlayerAction>()
    private val queueContexts = mutableListOf<QueueActionContext>()
    private val queueRetries = mutableListOf<Long>()
    private val timerEvents = mutableListOf<String>()
    private val snapshot = MpdSnapshot(
        MpdStatus(playback = PlaybackState.PAUSED, songId = 1, elapsed = 10.0, duration = 100.0, volume = 50,
            queueVersion = 1, queueLength = 20),
        MpdSong(1, "same.mp3", "Song 1"),
        (1L..20L).map { QueueEntry(it, MpdSong(it, "same.mp3", "Song $it", listOf("Artist"), duration = 100.0)) },
        0,
    )
    private val ui = mutableStateOf(ServersUiState())

    @Before
    fun keepTestWindowVisible() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun show(data: MpdSnapshot = snapshot, selected: Boolean = true, restore: StateRestorationTester? = null) {
        ui.value = ServersUiState(
            settings = ServerSettings(listOf(server), if (selected) server.id else null),
            loading = false,
            connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = data, sessionId = 1, idleConnected = true, playerConfirmedAtMillis = 0),
            positionSeconds = data.status.elapsed,
        )
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            SimpleMPDPlayerTheme {
                ServersApp(
                    state = ui.value, onAdd = {}, onEdit = {}, onDismissEditor = {}, onSave = { _, _ -> }, onDelete = {},
                    onSelect = {}, onReconnect = {}, onReload = {}, onGrantNetworkAccess = {}, onAction = { actions.add(it) },
                    onQueueAction = { action, context -> actions.add(action); queueContexts.add(context) },
                    onRetryQueue = { queueRetries.add(it) },
                    onSleepEnabled = { timerEvents.add("enabled=$it") }, onSleepMinutes = { timerEvents.add("minutes=$it") },
                )
            }
        }
        if (restore == null) compose.setContent(content) else restore.setContent(content)
    }

    @Test
    fun startsOnPlayerAndTabsNavigateInOrder() {
        show()
        compose.onNodeWithTag("tab_1").assertIsSelected()
        compose.onNodeWithTag("player_page").assertIsDisplayed()
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.onNodeWithText("Music library").assertIsDisplayed()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("tab_2").assertIsSelected()
        compose.onNodeWithTag("queue_page").assertIsDisplayed()
    }

    @Test
    fun horizontalSwipesAndTabsShareState() {
        show()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab_2").assertIsSelected()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("tab_1").assertIsSelected()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab_1").assertIsSelected()
    }

    @Test
    fun emptyQueueAndUnknownDurationDoNotShowMisleadingProgress() {
        show(snapshot.copy(status = MpdStatus(playback = PlaybackState.STOPPED), currentSong = null, queue = emptyList()))
        compose.onNodeWithText("The queue is empty").assertIsDisplayed()
        compose.onNodeWithTag("seek_slider").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
        compose.onNodeWithTag("volume_slider").assertDoesNotExist()
    }

    @Test
    fun queueSelectsByIdAndScrollsVertically() {
        show()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("queue_entry_1").assertIsSelected()
        compose.onNodeWithTag("queue_play_2").performClick()
        compose.runOnIdle { assertEquals(PlayerAction.PlayId(2), actions.single()) }
        compose.onNodeWithTag("queue_list").performScrollToIndex(12)
        compose.onNodeWithTag("queue_play_13").assertIsDisplayed()
        compose.onNodeWithTag("queue_play_13").performClick()
        compose.runOnIdle { assertEquals(PlayerAction.PlayId(13), actions.last()) }
    }

    @Test
    fun queueConfirmationAndMenusDisappearAcrossRecoveryEndpointEditAndSwitch() {
        show()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithText("Clear queue").performClick()
        compose.onNodeWithText("Clear the queue?").assertIsDisplayed()

        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ui.value.connection.copy(status = ConnectionStatus.FAILED, sessionId = null))
        }
        compose.onNodeWithText("Clear the queue?").assertDoesNotExist()
        val newQueue = snapshot.copy(queue = snapshot.queue.reversed())
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = newQueue, sessionId = 2, idleConnected = true, playerConfirmedAtMillis = 0))
        }
        compose.onNodeWithText("Clear the queue?").assertDoesNotExist()
        compose.onNodeWithTag("queue_list").performScrollToIndex(0)
        compose.onNodeWithTag("queue_actions_20").performClick()
        compose.onNodeWithText("Remove").assertIsDisplayed()

        val edited = server.copy(mpdPort = server.mpdPort + 1)
        compose.runOnIdle {
            ui.value = ui.value.copy(settings = ServerSettings(listOf(edited), edited.id),
                connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = snapshot, sessionId = 3, idleConnected = true, playerConfirmedAtMillis = 0))
        }
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithText("Clear queue").performClick()

        val another = ServerInput("Another fixture", "localhost").toProfile()!!
        compose.runOnIdle {
            ui.value = ui.value.copy(settings = ServerSettings(listOf(edited, another), another.id),
                connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = snapshot, sessionId = 4, idleConnected = true, playerConfirmedAtMillis = 0))
        }
        compose.onNodeWithText("Clear the queue?").assertDoesNotExist()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("Clear queue").performClick()
        compose.onAllNodesWithText("Clear queue").filterToOne(hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            assertEquals(listOf(PlayerAction.Clear), actions)
            assertEquals(listOf(QueueActionContext(4, 1)), queueContexts)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun commandPendingKeepsCurrentDialogAndStateRestorationKeepsScrollOnly() {
        val restoration = StateRestorationTester(compose)
        show(restore = restoration)
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("queue_list").performScrollToIndex(12)
        compose.onNodeWithText("Clear queue").performClick()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ui.value.connection.copy(commandPending = true,
                snapshot = snapshot.copy(status = snapshot.status.copy(elapsed = 20.0))))
        }
        compose.onNodeWithText("Clear the queue?").assertIsDisplayed()
        compose.onAllNodesWithText("Clear queue").filterToOne(hasAnyAncestor(isDialog())).assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(connection = ui.value.connection.copy(commandPending = false)) }
        compose.onNodeWithText("Clear the queue?").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Clear the queue?").assertDoesNotExist()
        compose.onNodeWithTag("queue_play_13").assertIsDisplayed()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    fun playerShowsStaleStatusUntilOngoingSynchronizationReturns() {
        show()
        val stale = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "Showing the last received server state"))
        stale.assertDoesNotExist()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ui.value.connection.copy(waitingForNetwork = true,
                playerConfirmedAtMillis = null))
        }
        stale.assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ui.value.connection.copy(waitingForNetwork = false,
                idleConnected = false, playerConfirmedAtMillis = 1))
        }
        stale.assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(connection = ui.value.connection.copy(idleConnected = true)) }
        stale.assertDoesNotExist()
    }

    @Test
    fun rejectedQueueActionShowsItsErrorOnlyInTheQueue() {
        show()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ui.value.connection.copy(
                commandError = ConnectionError.QUEUE_CHANGED, commandErrorFromQueue = true))
        }
        val error = compose.activity.getString(com.github.jschbe.simplempdplayer.R.string.queue_changed_action)
        compose.onNodeWithText(error).assertDoesNotExist()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNode(hasText(error) and hasAnyAncestor(hasTestTag("queue_page"))).assertIsDisplayed()
    }

    @Test
    fun unavailableQueueKeepsPlayerControlsAndOffersRetryAndExplicitClear() {
        show(snapshot.copy(queue = emptyList(), queueAvailability = QueueAvailability.LIMIT))
        compose.onNodeWithContentDescription("Play").assertIsEnabled()
        compose.onNodeWithText("Showing the last received server state").assertDoesNotExist()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithText("Queue unavailable. It exceeds the loading limit.").assertIsDisplayed()
        compose.onNodeWithText("The queue is empty").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Clear queue").performClick()
        compose.onNodeWithText("The queue could not be displayed. Remove all tracks from the active MPD queue?").assertIsDisplayed()
        compose.onAllNodesWithText("Clear queue").filterToOne(hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            assertEquals(listOf(1L), queueRetries)
            assertEquals(listOf(PlayerAction.Clear), actions)
            assertEquals(listOf(QueueActionContext(1)), queueContexts)
        }
    }

    @Test
    fun queueActionsAndClearConfirmation() {
        show()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("queue_actions_2").performClick()
        compose.onNodeWithText("Move up").performClick()
        compose.runOnIdle { assertEquals(PlayerAction.MoveId(2, -1), actions.last()) }
        compose.onNodeWithTag("queue_actions_2").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals(PlayerAction.DeleteId(2), actions.last()) }
        compose.onNodeWithText("Clear queue").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertFalse(actions.contains(PlayerAction.Clear)) }
        compose.onNodeWithText("Clear queue").performClick()
        compose.onAllNodesWithText("Clear queue").onLast().performClick()
        compose.runOnIdle { assertEquals(PlayerAction.Clear, actions.last()) }
    }

    @Test
    fun settingsOpensServersAndSleepTimerIsAvailable() {
        show()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Sleep timer").assertIsEnabled().performClick()
        compose.onNodeWithTag("sleep_timer_switch").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Servers").performClick()
        compose.onNodeWithContentDescription("Add server").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_1").assertIsSelected()
    }

    @Test
    fun playerTimerStatusAndSettingsOpenTheSameDialogForOffWaitingRunningAndErrorStates() {
        show()
        val record = TimerRecord("00000000-0000-0000-0000-000000000001", server, 1, 1, null, TimerStatus.WAITING)
        val timers = listOf(
            SleepTimerState(loading = false),
            SleepTimerState(settings = TimerSettings(record = record), loading = false),
            SleepTimerState(settings = TimerSettings(record = record.copy(status = TimerStatus.ACTIVE, deadline = 120_001)),
                remainingMillis = 120_000, loading = false),
            SleepTimerState(settings = TimerSettings(record = record.copy(status = TimerStatus.FAILED, error = TimerError.NETWORK)), loading = false),
        )
        for (timer in timers) {
            compose.runOnIdle { ui.value = ui.value.copy(sleepTimer = timer) }
            for (fromPlayer in listOf(true, false)) {
                if (fromPlayer) compose.onNodeWithTag("sleep_compact_status").performScrollTo().performClick()
                else {
                    compose.onNodeWithContentDescription("Settings").performClick()
                    compose.onNodeWithText("Sleep timer").performClick()
                }
                compose.onNodeWithTag("sleep_timer_switch").assertExists()
                if (timer.enabled) compose.onNodeWithTag("sleep_timer_switch").assertIsOn()
                else compose.onNodeWithTag("sleep_timer_switch").assertIsOff()
                compose.onNodeWithTag("timer_duration_dropdown").performScrollTo().assertTextContains("30 minutes")
                if (timer.settings.record?.status == TimerStatus.FAILED) {
                    compose.onNodeWithText("Pause could not be confirmed. Check the server and network. No automatic retry.")
                        .performScrollTo().assertIsDisplayed()
                }
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithTag("sleep_timer_switch").assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(timer, ui.value.sleepTimer)
                    assertTrue(timerEvents.isEmpty())
                    assertTrue(actions.isEmpty())
                }
            }
        }
    }

    @Test
    fun firstStartWithoutSelectionOpensServers() {
        show(selected = false)
        compose.onNodeWithContentDescription("Add server").assertIsDisplayed()
    }

    @Test
    fun playAndOptionsSendActionsWithoutOptimisticState() {
        show()
        compose.onNodeWithContentDescription("Play").performClick()
        compose.runOnIdle { assertEquals(PlayerAction.PlayPause, actions.last()) }
        compose.onNodeWithTag("random_switch").performClick()
        compose.onNodeWithTag("random_switch").assertIsOff()
        compose.runOnIdle { assertEquals(PlayerAction.Random(true), actions.last()) }
    }

    @Test
    fun seekWaitsForReleaseAndKeepsDraggedPositionDuringUpdates() {
        show()
        compose.onNodeWithTag("seek_slider").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, centerY))
        }
        compose.runOnIdle {
            assertTrue(actions.isEmpty())
            ui.value = ui.value.copy(positionSeconds = 20.0)
        }
        val slider = compose.onNodeWithTag("seek_slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertTrue(slider.current > 60f)
        compose.onNodeWithTag("seek_slider").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, actions.size)
            assertTrue(actions.single() is PlayerAction.Seek)
        }
    }
}
