package com.github.jschbe.simplempdplayer.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerSettings
import com.github.jschbe.simplempdplayer.mpd.*
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
    private val snapshot = MpdSnapshot(
        MpdStatus(playback = PlaybackState.PAUSED, songId = 1, elapsed = 10.0, duration = 100.0, volume = 50),
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

    private fun show(data: MpdSnapshot = snapshot, selected: Boolean = true) {
        ui.value = ServersUiState(
            settings = ServerSettings(listOf(server), if (selected) server.id else null),
            loading = false,
            connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = data),
            positionSeconds = data.status.elapsed,
        )
        compose.setContent {
            SimpleMPDPlayerTheme {
                ServersApp(
                    state = ui.value, onAdd = {}, onEdit = {}, onDismissEditor = {}, onSave = {}, onDelete = {},
                    onSelect = {}, onReconnect = {}, onReload = {}, onGrantNetworkAccess = {}, onAction = { actions.add(it) },
                )
            }
        }
    }

    @Test
    fun startsOnPlayerAndTabsNavigateInOrder() {
        show()
        compose.onNodeWithTag("tab_1").assertIsSelected()
        compose.onNodeWithTag("player_page").assertIsDisplayed()
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.onNodeWithText("Music browser coming soon").assertIsDisplayed()
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
    fun settingsOpensServersAndSleepTimerIsUnavailable() {
        show()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Sleep timer — coming soon").assertIsNotEnabled()
        compose.onNodeWithText("Servers").performClick()
        compose.onNodeWithContentDescription("Add server").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_1").assertIsSelected()
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
