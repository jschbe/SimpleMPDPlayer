package com.github.jschbe.simplempdplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.*
import com.github.jschbe.simplempdplayer.mpd.*
import com.github.jschbe.simplempdplayer.timer.*
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompactPlayerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = ServerInput("Fixture", "localhost").toProfile()!!
    private val song = MpdSong(1, "fallback.mp3", "Fixture title", listOf("Fixture artist"), listOf("Fixture album"))
    private val actions = mutableListOf<PlayerAction>()
    private val listening = mutableListOf<Boolean>()
    private val state = mutableStateOf(ServersUiState(loading = false,
        settings = ServerSettings(listOf(server), server.id),
        connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = MpdSnapshot(
            MpdStatus(playback = PlaybackState.PAUSED, repeat = false, single = SingleMode.OFF), song, listOf(QueueEntry(1, song)), 0)),
        sleepTimer = SleepTimerState(settings = TimerSettings(record = TimerRecord(
            "00000000-0000-0000-0000-000000000001", server, 1, 1, null, TimerStatus.WAITING)), loading = false)))

    private fun show(fontScale: Float = 1f, width: Int = 360) {
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SimpleMPDPlayerTheme {
                    Box(Modifier.width(width.dp)) {
                        PlayerScreen(state.value, true, actions::add, listening::add, onTrackInfoExpanded = {
                            state.value = state.value.copy(settings = state.value.settings.copy(trackInfoExpanded = it))
                        })
                    }
                }
            }
        }
    }

    @Test fun infoPopupDoesNotToggleListeningOrSendTransportActions() {
        show()
        compose.onNodeWithContentDescription("About Listen on phone").performClick()
        compose.onNodeWithText("Plays the active MPD HTTP stream on this phone.", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(listening.isEmpty()); assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("local_listening_switch").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(listOf(true), listening); assertTrue(actions.isEmpty()) }
    }

    @Test fun statusAndWaitingShareARowAndOptionsStayCompact() {
        show()
        compose.onNodeWithTag("player_status_row").performScrollTo()
        val mpd = compose.onNodeWithTag("mpd_playback_status").fetchSemanticsNode().boundsInRoot
        val timer = compose.onNodeWithTag("sleep_compact_status").fetchSemanticsNode().boundsInRoot
        assertTrue(mpd.right <= timer.left)
        assertTrue(kotlin.math.abs(mpd.center.y - timer.center.y) < 2f)
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithText("Waiting for playback").assertIsDisplayed()
        compose.onNodeWithTag("playback_options").performScrollTo()
        val random = compose.onNodeWithTag("random_switch").fetchSemanticsNode().boundsInRoot
        val repeat = compose.onNodeWithTag("repeat_dropdown").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("random_label").fetchSemanticsNode().boundsInRoot
        assertTrue(label.right <= random.left)
        assertTrue(kotlin.math.abs(random.center.y - repeat.center.y) < 2f)
        compose.onNodeWithTag("random_switch").performClick()
        compose.onNodeWithTag("repeat_dropdown").performClick()
        compose.onNodeWithTag("repeat_option_QUEUE").performClick()
        compose.runOnIdle { assertEquals(listOf(PlayerAction.Random(true), PlayerAction.Repeat(RepeatMode.QUEUE)), actions) }
    }

    @Test fun collapsedMetadataStaysCollapsedAcrossTitleChangesAndUsesSmallerType() {
        show()
        compose.onNodeWithText("Fixture artist", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Fixture album", useUnmergedTree = true).assertIsDisplayed()
        val expandedHeight = compose.onNodeWithTag("track_title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("track_info_toggle").performClick()
        compose.onNodeWithText("Fixture artist", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Fixture album", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("track_title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height < expandedHeight)
        compose.runOnIdle {
            val snapshot = state.value.connection.snapshot!!
            state.value = state.value.copy(connection = state.value.connection.copy(snapshot = snapshot.copy(
                currentSong = song.copy(title = "Another title", artists = emptyList(), albums = emptyList()))))
        }
        compose.onNodeWithText("Another title").assertIsDisplayed()
        compose.onNodeWithTag("track_info_toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithTag("track_info_toggle").performClick()
        compose.onNodeWithTag("track_info_toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test fun narrowLargeTextKeepsStatusAndBothOptionsInsideThePage() {
        state.value = state.value.copy(connection = state.value.connection.copy(snapshot = state.value.connection.snapshot!!.copy(
            currentSong = song.copy(title = "Very long track title ".repeat(20)))))
        show(fontScale = 2f, width = 300)
        compose.onNodeWithTag("player_status_row").performScrollTo()
        val status = compose.onNodeWithTag("mpd_playback_status").fetchSemanticsNode().boundsInRoot
        val timer = compose.onNodeWithTag("sleep_compact_status").fetchSemanticsNode().boundsInRoot
        assertTrue(status.right <= timer.left)
        compose.onNodeWithTag("repeat_dropdown").performScrollTo().assertIsDisplayed()
        val page = compose.onNodeWithTag("player_page").fetchSemanticsNode().boundsInRoot
        val repeat = compose.onNodeWithTag("repeat_dropdown").fetchSemanticsNode().boundsInRoot
        assertTrue(repeat.left >= page.left && repeat.right <= page.right)
        compose.onNodeWithTag("random_switch").performScrollTo().assertIsDisplayed()
        val random = compose.onNodeWithTag("random_switch").fetchSemanticsNode().boundsInRoot
        assertTrue(random.left >= page.left && random.right <= page.right)
        assertTrue(repeat.top > random.top)
    }
}
