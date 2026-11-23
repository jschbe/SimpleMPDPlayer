package com.github.jschbe.simplempdplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.ThemeMode
import com.github.jschbe.simplempdplayer.mpd.*
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled server snapshots and action capture only; no network or stored profiles. */
class RepeatOptionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val status = mutableStateOf(MpdStatus(repeat = false, single = SingleMode.OFF))
    private val enabled = mutableStateOf(true)
    private val theme = mutableStateOf(ThemeMode.SYSTEM)
    private val actions = mutableListOf<PlayerAction>()

    private fun show(width: Int = 360, fontScale: Float = 1f) {
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SimpleMPDPlayerTheme(mode = theme.value) {
                    Box(Modifier.width(width.dp)) {
                        PlayerScreen(ServersUiState(loading = false, connection = ConnectionState(
                            status = ConnectionStatus.CONNECTED,
                            snapshot = MpdSnapshot(status.value, null, emptyList(), 0))), enabled.value, actions::add)
                    }
                }
            }
        }
    }

    private fun assertMode(label: String) {
        compose.onNodeWithTag("repeat_dropdown").performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
    }

    private fun assertClosedStyleMatchesRandom() {
        compose.onNodeWithTag("playback_options").performScrollTo()
        val random = compose.onNodeWithTag("random_label", useUnmergedTree = true)
        val repeat = compose.onNodeWithText("Repeat: None", useUnmergedTree = true)
        fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val randomLayout = layout(random)
        val repeatLayout = layout(repeat)
        val randomStyle = randomLayout.layoutInput.style
        val repeatStyle = repeatLayout.layoutInput.style
        assertEquals(randomStyle.fontSize, repeatStyle.fontSize)
        assertEquals(randomStyle.fontWeight, repeatStyle.fontWeight)
        assertEquals(randomStyle.color, repeatStyle.color)
        val randomBaseline = random.fetchSemanticsNode().boundsInRoot.top + randomLayout.firstBaseline
        val repeatBaseline = repeat.fetchSemanticsNode().boundsInRoot.top + repeatLayout.firstBaseline
        assertEquals(randomBaseline, repeatBaseline, 1f)
        assertFalse(repeatLayout.hasVisualOverflow)
    }

    @Test fun allSelectionsKeepConfirmedDisplayUntilReadbackAndRandomIndependent() {
        show()
        for (random in listOf(false, true)) for (mode in RepeatMode.entries) {
            compose.runOnIdle { status.value = MpdStatus(random = random, repeat = false, single = SingleMode.OFF) }
            assertMode("None")
            compose.onNodeWithTag("repeat_dropdown").performClick()
            for (option in RepeatMode.entries) compose.onNodeWithTag("repeat_option_${option.name}").assertIsDisplayed()
            compose.onNodeWithTag("repeat_option_${mode.name}").performClick()
            compose.runOnIdle { assertEquals(PlayerAction.Repeat(mode), actions.last()) }
            assertMode("None") // A rejected action must not leave an optimistic selection.
            compose.runOnIdle { status.value = status.value.copy(repeat = mode != RepeatMode.NONE,
                single = if (mode == RepeatMode.SINGLE) SingleMode.ON else SingleMode.OFF) }
            assertMode(mode.name.lowercase().replaceFirstChar { it.uppercase() })
            compose.onNodeWithTag("repeat_dropdown").performClick()
            compose.onNodeWithTag("repeat_option_${mode.name}").assertIsSelected().performClick()
            compose.onNodeWithTag("random_switch").performScrollTo()
            if (random) compose.onNodeWithTag("random_switch").assertIsOn() else compose.onNodeWithTag("random_switch").assertIsOff()
            compose.onNodeWithTag("random_switch").performClick()
            compose.runOnIdle { assertEquals(PlayerAction.Random(!random), actions.last()) }
            assertMode(mode.name.lowercase().replaceFirstChar { it.uppercase() })
        }
    }

    @Test fun externalOneshotUnknownAndStopAfterTrackRemainVisibleWithoutActions() {
        show()
        for ((value, label) in listOf(
            MpdStatus(repeat = true, single = SingleMode.ON) to "Single",
            MpdStatus(repeat = true, single = SingleMode.ONESHOT) to "One-shot (MPD)",
            MpdStatus(repeat = false, single = SingleMode.ON) to "Stop after track (MPD)",
            MpdStatus() to "Unknown",
        )) {
            compose.runOnIdle { status.value = value }
            assertMode(label)
        }
        compose.runOnIdle { assertTrue(actions.isEmpty()); status.value = MpdStatus(repeat = true, single = SingleMode.ONESHOT) }
        assertMode("One-shot (MPD)")
        compose.onNodeWithTag("repeat_dropdown").performClick()
        compose.onNodeWithTag("repeat_option_SINGLE").performClick()
        compose.runOnIdle { assertEquals(listOf(PlayerAction.Repeat(RepeatMode.SINGLE)), actions) }
        assertMode("One-shot (MPD)")
    }

    @Test fun unavailableControlsDismissMenuAndKeepConfirmedState() {
        show()
        assertMode("None")
        compose.onNodeWithTag("repeat_dropdown").performClick()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("repeat_option_QUEUE").assertDoesNotExist()
        compose.onNodeWithTag("repeat_dropdown").assertIsNotEnabled()
        compose.onNodeWithTag("random_switch").assertIsNotEnabled()
        assertMode("None")
        assertClosedStyleMatchesRandom()
        compose.runOnIdle { assertTrue(actions.isEmpty()); enabled.value = true }
        compose.onNodeWithTag("repeat_option_QUEUE").assertDoesNotExist()
    }

    @Test fun dropdownWorksInSystemLightAndDarkThemes() {
        show()
        for (mode in ThemeMode.entries) {
            compose.runOnIdle { theme.value = mode }
            assertMode("None")
            assertClosedStyleMatchesRandom()
            compose.onNodeWithTag("repeat_dropdown").performClick()
            compose.onNodeWithTag("repeat_option_QUEUE").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(PlayerAction.Repeat(RepeatMode.QUEUE), actions.last()) }
        }
    }

    @Test fun narrowLargeFontWrapsControlsAndEveryLabelWithoutClipping() {
        show(width = 300, fontScale = 2f)
        for ((value, label) in listOf(
            MpdStatus(repeat = false, single = SingleMode.OFF) to "None",
            MpdStatus(repeat = true, single = SingleMode.OFF) to "Queue",
            MpdStatus(repeat = true, single = SingleMode.ON) to "Single",
            MpdStatus(repeat = true, single = SingleMode.ONESHOT) to "One-shot (MPD)",
            MpdStatus(repeat = false, single = SingleMode.ON) to "Stop after track (MPD)",
            MpdStatus() to "Unknown",
        )) {
            compose.runOnIdle { status.value = value }
            assertMode(label)
            val dropdown = compose.onNodeWithTag("repeat_dropdown").fetchSemanticsNode().boundsInRoot
            val options = compose.onNodeWithTag("playback_options").fetchSemanticsNode().boundsInRoot
            assertEquals(options.center.x, dropdown.center.x, 1f)
            val page = compose.onNodeWithTag("player_page").fetchSemanticsNode().boundsInRoot
            assertTrue(dropdown.left >= page.left && dropdown.right <= page.right)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Repeat: $label", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertFalse("Clipped repeat label: $label (${layouts.single().size})", layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("repeat_dropdown").performClick()
            for (mode in RepeatMode.entries) compose.onNodeWithTag("repeat_option_${mode.name}").assertIsDisplayed()
            compose.onNodeWithTag("repeat_option_NONE").performClick()
            compose.onNodeWithTag("random_switch").performScrollTo().assertIsDisplayed()
            val random = compose.onNodeWithTag("random_switch").fetchSemanticsNode().boundsInRoot
            val randomLabel = compose.onNodeWithTag("random_label").fetchSemanticsNode().boundsInRoot
            assertEquals(options.center.x, (randomLabel.left + random.right) / 2f, 1f)
            assertTrue(dropdown.top > random.top)
        }
    }
}
