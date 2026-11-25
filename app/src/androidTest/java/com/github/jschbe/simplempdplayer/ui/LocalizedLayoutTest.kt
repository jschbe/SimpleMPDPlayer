package com.github.jschbe.simplempdplayer.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.*
import com.github.jschbe.simplempdplayer.mpd.*
import com.github.jschbe.simplempdplayer.timer.*
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled UI only: no settings writes, repository, sockets or live MPD. */
class LocalizedLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aboutActionsAndLicenseOverviewFitDoubleFontSizeInEveryLanguageAndTheme() {
        val tag = mutableStateOf("en")
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val context = compose.activity
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        fun localized(language: String) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
        ).resources
        compose.setContent {
            val density = LocalDensity.current
            val resources = remember(tag.value) { localized(tag.value) }
            CompositionLocalProvider(LocalResources provides resources, LocalDensity provides Density(density.density, 2f)) {
                SimpleMPDPlayerTheme(mode = theme.value) {
                    key(tag.value, theme.value) { AboutDialog {} }
                }
            }
        }

        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (language in listOf("en", "de", "fr", "it", "es", "uk", "ru")) {
                compose.runOnIdle { tag.value = language; theme.value = mode }
                val resources = localized(language)
                fun label(id: Int) = resources.getString(id)
                fun visibleText(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
                    .performScrollTo().assertIsDisplayed()
                val left = visibleText(label(R.string.app_license)).getUnclippedBoundsInRoot().left.value
                for (id in listOf(R.string.gpl_license_link, R.string.third_party_licenses, R.string.open_source)) {
                    val button = compose.onNodeWithText(label(id)).performScrollTo().assertIsDisplayed().assertHasClickAction()
                    val bounds = button.getUnclippedBoundsInRoot()
                    assertTrue("$language/$mode/$id touch height", bounds.bottom - bounds.top >= 48.dp)
                    val node = visibleText(label(id))
                    assertEquals("$language/$mode/$id left alignment", left, node.getUnclippedBoundsInRoot().left.value, 0.5f)
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertFalse("$language/$mode/$id text overflow", layouts.single().hasVisualOverflow)
                    assertEquals(0f, layouts.single().getLineLeft(0), 0.5f)
                }
                compose.onNodeWithText(label(R.string.third_party_licenses)).performScrollTo().performClick()
                for (id in listOf(R.string.third_party_intro, R.string.license_component_androidx,
                    R.string.license_component_media3, R.string.license_component_kotlin, R.string.license_kotlin,
                    R.string.license_component_guava, R.string.license_component_okio, R.string.license_component_protobuf,
                    R.string.license_bsd)) {
                    val layout = mutableListOf<TextLayoutResult>()
                    visibleText(label(id)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                    val measured = layout.single()
                    val description = "$language/$mode ${label(id)}"
                    assertFalse("$description overview overflow", measured.hasVisualOverflow)
                    for (line in 0 until measured.lineCount) {
                        assertTrue("$description line $line exceeds the text bounds",
                            measured.getLineRight(line) <= measured.size.width + 0.5f)
                    }
                }
                compose.onAllNodesWithText(label(R.string.license_apache)).assertCountEquals(4)
                compose.onNodeWithText(label(R.string.full_license_texts)).performScrollTo().assertIsDisplayed()
                    .assertHasClickAction()
                compose.onNodeWithText(label(R.string.done)).assertIsDisplayed().assertHasClickAction()
            }
        }
    }

    @Test fun connectionActionsWrapAtDoubleFontSizeInAllLocalesAndThemes() {
        val tag = mutableStateOf("de")
        val mode = mutableStateOf(ThemeMode.SYSTEM)
        val context = compose.activity
        val connection = mutableStateOf(ConnectionState(ConnectionStatus.FAILED, error = ConnectionError.PERMISSION_REQUIRED))
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        fun configuration() = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag.value)) }
        compose.setContent {
            val localized = remember(tag.value) { context.createConfigurationContext(configuration()) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalResources provides localized.resources,
                LocalDensity provides Density(density.density, 2f)) {
                SimpleMPDPlayerTheme(mode = mode.value) {
                    Box(Modifier.width(300.dp)) { ConnectionStatusRow(connection.value, true, {}, {}) }
                }
            }
        }
        for ((index, language) in listOf("en", "de", "fr", "it", "es", "uk", "ru").withIndex()) {
            compose.runOnIdle {
                tag.value = language
                mode.value = ThemeMode.entries[index % ThemeMode.entries.size]
            }
            val resources = context.createConfigurationContext(configuration()).resources
            for ((error, action) in listOf(ConnectionError.PERMISSION_REQUIRED to R.string.allow_local_network,
                ConnectionError.LOST to R.string.reconnect)) {
                compose.runOnIdle { connection.value = connection.value.copy(error = error) }
                for (label in listOf(R.string.connection_failed, action)) {
                    val node = compose.onNodeWithText(resources.getString(label), useUnmergedTree = true).assertIsDisplayed()
                    val layout = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                    assertFalse("$language/$label", layout.single().hasVisualOverflow)
                }
                compose.onNodeWithText(resources.getString(action)).assertIsEnabled().assertHasClickAction()
            }
        }
    }

    @Test fun representativeScreensRemainAccessibleWithLongTextAndDoubleFontSize() {
        val tag = mutableStateOf("de")
        val surface = mutableStateOf("player")
        val context = compose.activity
        val server = ServerInput("Fixture 🎵", "localhost").toProfile()!!
        val song = MpdSong(1, "Music/Незмінний.mp3", "Untranslated title 🎵", listOf("Untranslated artist"))
        val mutations = mutableListOf<PlayerAction>()
        val timer = SleepTimerState(settings = TimerSettings(minutes = 22), loading = false)
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(tag.value)) }
            val localized = remember(tag.value) { context.createConfigurationContext(configuration) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(density.density, 2f)) {
                SimpleMPDPlayerTheme {
                    Box(Modifier.width(300.dp)) {
                        key(surface.value, tag.value) {
                        when (surface.value) {
                            "player" -> PlayerScreen(ServersUiState(loading = false, settings = ServerSettings(listOf(server), server.id),
                                connection = ConnectionState(ConnectionStatus.CONNECTED, snapshot = MpdSnapshot(
                                    MpdStatus(playback = PlaybackState.PAUSED, repeat = false, single = SingleMode.OFF), song, emptyList(), 0))),
                                true, mutations::add)
                            "language" -> LanguageDialog(AppLanguage.fromTags(tag.value), {}, {})
                            "about" -> AboutDialog {}
                            "timer" -> SleepTimerDialog(timer, server.name, false, {}, { false }, {}, {})
                        }
                        }
                    }
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun resource(id: Int) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag.value))
        }).getString(id)
        fun screenshot(name: String) {
            compose.waitForIdle()
            android.os.SystemClock.sleep(300) // Let window enter/exit animations finish before capture.
            val target = File(instrumentation.targetContext.cacheDir, "m72-${tag.value}-$name.png")
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        for (language in listOf("de", "fr", "ru", "uk")) {
            compose.runOnIdle { tag.value = language; surface.value = "player" }
            compose.onNodeWithText("Untranslated title 🎵", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription(resource(R.string.play)).performScrollTo().assertIsDisplayed()
            screenshot("player")
            compose.onNodeWithTag("local_listening_info").performScrollTo().assertIsDisplayed()
            screenshot("listening")
            compose.runOnIdle { surface.value = "language" }
            compose.onNodeWithTag("language_ru").performScrollTo().assertIsDisplayed()
            screenshot("language")
            compose.runOnIdle { surface.value = "timer" }
            compose.onNodeWithTag("timer_duration_dropdown").performScrollTo().assertIsDisplayed()
            screenshot("timer")
            compose.runOnIdle { surface.value = "about" }
            compose.onNodeWithText("GPL-3.0-or-later").assertIsDisplayed()
            compose.onNodeWithText(resource(R.string.open_source)).performScrollTo().assertIsDisplayed()
            screenshot("about")
        }
        compose.runOnIdle { assertTrue(mutations.isEmpty()) }
    }
}
