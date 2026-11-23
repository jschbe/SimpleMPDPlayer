package com.github.jschbe.simplempdplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
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
    private var timerOpens = 0
    private val state = mutableStateOf(ServersUiState(loading = false,
        settings = ServerSettings(listOf(server), server.id),
        connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = MpdSnapshot(
            MpdStatus(playback = PlaybackState.PAUSED, repeat = false, single = SingleMode.OFF), song, listOf(QueueEntry(1, song)), 0)),
        sleepTimer = SleepTimerState(settings = TimerSettings(record = TimerRecord(
            "00000000-0000-0000-0000-000000000001", server, 1, 1, null, TimerStatus.WAITING)), loading = false)))

    private fun show(fontScale: Float = 1f, width: Int = 360, controlsEnabled: Boolean = true) {
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SimpleMPDPlayerTheme {
                    Box(Modifier.width(width.dp)) {
                        PlayerScreen(state.value, controlsEnabled, actions::add, listening::add, onTrackInfoExpanded = {
                            state.value = state.value.copy(settings = state.value.settings.copy(trackInfoExpanded = it))
                        }, onOpenSleepTimer = { timerOpens++ })
                    }
                }
            }
        }
    }

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertMatchingStatusStyles(timerTag: String = "timer_status") {
        val mpd = layout("mpd_playback_status")
        val timer = layout(timerTag)
        val mpdStyle = mpd.layoutInput.style
        val timerStyle = timer.layoutInput.style
        assertEquals(mpdStyle.fontSize, timerStyle.fontSize)
        assertEquals(mpdStyle.fontWeight, timerStyle.fontWeight)
        assertEquals(mpdStyle.fontFamily, timerStyle.fontFamily)
        assertEquals(mpdStyle.fontStyle, timerStyle.fontStyle)
        assertEquals(mpdStyle.lineHeight, timerStyle.lineHeight)
        assertEquals(mpdStyle.letterSpacing, timerStyle.letterSpacing)
        assertEquals(mpdStyle.color, timerStyle.color)
        assertEquals(TextAlign.Start, mpdStyle.textAlign)
        assertEquals(TextAlign.End, timerStyle.textAlign)
        assertFalse(mpd.hasVisualOverflow)
        assertFalse(timer.hasVisualOverflow)
    }

    @Test fun fullTimerStatusAreaIsAnAccessibleButtonEvenWhenPlaybackControlsAreDisabled() {
        show(controlsEnabled = false)
        compose.onNodeWithTag("sleep_compact_status").performScrollTo().assertHasClickAction()
            .assertContentDescriptionEquals("Sleep timer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Waiting for playback"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performTouchInput { click(Offset(width - 1f, 1f)) }
        assertMatchingStatusStyles()
        compose.runOnIdle {
            assertEquals(1, timerOpens)
            assertTrue(actions.isEmpty())
            assertTrue(listening.isEmpty())
        }
    }

    @Test fun timerStatusPressHasNoVisualFeedbackAndStillClicks() {
        show()
        val timer = compose.onNodeWithTag("sleep_compact_status").performScrollTo()
        val before = timer.captureToImage()
        val expected = IntArray(before.width * before.height).also { before.readPixels(it) }
        timer.performTouchInput { down(center) }
        try {
            android.os.SystemClock.sleep(200) // Include the scroll container's delayed press interaction.
            compose.mainClock.advanceTimeBy(200)
            val pressed = timer.captureToImage()
            assertEquals(before.width, pressed.width)
            assertEquals(before.height, pressed.height)
            assertArrayEquals(expected, IntArray(expected.size).also { pressed.readPixels(it) })
        } finally { timer.performTouchInput { up() } }
        compose.mainClock.advanceTimeBy(200)
        val released = timer.captureToImage()
        assertArrayEquals(expected, IntArray(expected.size).also { released.readPixels(it) })
        compose.runOnIdle { assertEquals(1, timerOpens); assertTrue(actions.isEmpty()) }
    }

    @Test fun timerStatusUpdatesKeepMatchingStylesAndRemainClickableAtLargeFontSize() {
        show(fontScale = 2f, width = 300)
        val waiting = state.value.sleepTimer.settings.record!!
        val active = waiting.copy(status = TimerStatus.ACTIVE, deadline = 120_001)
        val cases = listOf(
            SleepTimerState(loading = false) to "Sleep off",
            SleepTimerState(settings = TimerSettings(record = waiting), loading = false) to "Waiting for playback",
            SleepTimerState(settings = TimerSettings(record = active), remainingMillis = 120_000, loading = false) to "Sleep in 2 min",
            SleepTimerState(settings = TimerSettings(record = active), remainingMillis = 60_000, loading = false) to "Sleep in 1 min",
            SleepTimerState(settings = TimerSettings(record = active), remainingMillis = 0, loading = false) to "Due · Waiting for Android",
            SleepTimerState(settings = TimerSettings(record = active.copy(status = TimerStatus.FAILED, error = TimerError.NETWORK)), loading = false) to "Sleep off",
        )
        for ((index, case) in cases.withIndex()) {
            val (timer, text) = case
            compose.runOnIdle { state.value = state.value.copy(sleepTimer = timer) }
            compose.onNodeWithTag("sleep_compact_status").performScrollTo().assertTextContains(text)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)).performClick()
            assertMatchingStatusStyles(if (timer.active) "timer_remaining" else "timer_status")
            val mpd = compose.onNodeWithTag("mpd_playback_status").fetchSemanticsNode().boundsInRoot
            val status = compose.onNodeWithTag("sleep_compact_status").fetchSemanticsNode().boundsInRoot
            assertTrue(mpd.right <= status.left)
            compose.runOnIdle {
                assertEquals(index + 1, timerOpens)
                assertEquals(timer, state.value.sleepTimer)
                assertTrue(actions.isEmpty())
            }
        }
        compose.onNodeWithText("Sleep timer needs attention. Open Settings → Sleep timer.").performScrollTo().assertIsDisplayed()
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
        assertMatchingStatusStyles()
        compose.onNodeWithTag("playback_options").performScrollTo()
        val random = compose.onNodeWithTag("random_switch").fetchSemanticsNode().boundsInRoot
        val repeat = compose.onNodeWithTag("repeat_dropdown").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("random_label").fetchSemanticsNode().boundsInRoot
        val options = compose.onNodeWithTag("playback_options").fetchSemanticsNode().boundsInRoot
        assertTrue(label.right <= random.left)
        assertTrue(kotlin.math.abs(random.center.y - repeat.center.y) < 2f)
        assertEquals(options.center.x, (label.left + repeat.right) / 2f, 1f)
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
        assertMatchingStatusStyles()
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
