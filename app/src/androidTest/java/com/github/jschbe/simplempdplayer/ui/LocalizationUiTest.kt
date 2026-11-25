package com.github.jschbe.simplempdplayer.ui

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.BuildConfig
import com.github.jschbe.simplempdplayer.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class LocalizationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val systemLanguage get() = if (Build.VERSION.SDK_INT >= 33) {
        instrumentation.targetContext.getSystemService(LocaleManager::class.java).systemLocales[0].language
    } else Resources.getSystem().configuration.locales[0].language

    @Before fun start() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            it.setShowWhenLocked(true); it.setTurnScreenOn(true)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        awaitLanguage(systemLanguage)
        // First-run editor belongs to the isolated test app only.
        compose.onAllNodesWithText(text(R.string.cancel)).fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            compose.onNodeWithText(text(R.string.cancel)).performClick()
        }
    }
    @After fun stop() {
        if (::scenario.isInitialized) {
            try {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
                awaitLanguage(systemLanguage)
            } finally {
                scenario.close()
            }
        }
    }
    private fun text(id: Int): String {
        var result = ""
        scenario.onActivity { result = it.getString(id) }
        return result
    }
    private fun open(destination: Int) {
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        compose.onNodeWithText(text(destination)).performClick()
    }
    private fun awaitLanguage(tag: String) {
        var observed = "Activity unavailable"
        try {
            compose.waitUntil(10_000) {
                var ready = false
                scenario.onActivity {
                    val decor = it.window.decorView
                    val lifecycle = it.lifecycle.currentState
                    val language = it.resources.configuration.locales[0].language
                    observed = "language=$language, lifecycle=$lifecycle, laidOut=${decor.isLaidOut}, " +
                        "layoutRequested=${decor.isLayoutRequested}, focused=${it.hasWindowFocus()}"
                    ready = lifecycle == Lifecycle.State.RESUMED && language == tag &&
                        decor.isLaidOut && !decor.isLayoutRequested
                }
                ready
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Expected language=$tag and a laid-out resumed Activity. Observed $observed", timeout)
        }
        // Compose's Espresso idle check can stall if entered during the old window's relaunch.
        compose.waitForIdle()
    }

    @Test fun defaultSelectionAndUnsupportedLanguageUseEnglishFallback() {
        scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
        open(R.string.language)
        compose.onNodeWithTag("language_system").assertIsSelected()
        val context = instrumentation.targetContext
        val unsupported = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.JAPANESE) })
        assertEquals("Language", unsupported.getString(R.string.language))
        assertEquals("Play", unsupported.getString(R.string.play))
        assertEquals("SimpleMPDPlayer", unsupported.getString(R.string.app_name))
    }

    @Test fun selectAllSevenLanguagesRestoreAndReturnToSystem() {
        open(R.string.language)
        for (tag in listOf("de", "fr", "it", "es", "uk", "ru", "en")) {
            compose.onNodeWithTag("language_$tag").performScrollTo().performClick()
            awaitLanguage(tag)
            compose.onNodeWithTag("language_$tag").performScrollTo().assertIsSelected()
            scenario.recreate()
            compose.onNodeWithTag("language_$tag").performScrollTo().assertIsSelected()
        }
        compose.onNodeWithTag("language_system").performScrollTo().performClick()
        awaitLanguage(systemLanguage)
        scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
        compose.onNodeWithTag("language_system").assertIsSelected()
    }

    @Test fun android13ExternalAppLanguageUpdatesUiAndSelection() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        open(R.string.language)
        scenario.onActivity { it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("fr") }
        awaitLanguage("fr")
        compose.onNodeWithText("Langue").assertIsDisplayed()
        compose.onNodeWithTag("language_fr").performScrollTo().assertIsSelected()
        scenario.onActivity { it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList() }
        awaitLanguage(systemLanguage)
        compose.onNodeWithTag("language_system").performScrollTo().assertIsSelected()
        // External selection can equal the device locale exactly: no resource change is needed.
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity {
            val manager = it.getSystemService(LocaleManager::class.java)
            manager.applicationLocales = manager.systemLocales
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitLanguage(systemLanguage)
        val selected = AppLanguage.fromTags(systemLanguage).tag
        compose.onNodeWithTag("language_$selected").performScrollTo().assertIsSelected()
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity { it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList() }
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitLanguage(systemLanguage)
        compose.onNodeWithTag("language_system").performScrollTo().assertIsSelected()
    }

    @Test fun aboutShowsBuildVersionGplAndOfflineLegalTexts() {
        open(R.string.about)
        compose.onNodeWithText("SimpleMPDPlayer").assertIsDisplayed()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("GPL-3.0-or-later").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.license_warranty)).assertIsDisplayed()
        compose.onNodeWithText("GNU GPL v3 or later").performClick()
        assertBundledDocument(R.raw.gpl_v3, "Version 3, 29 June 2007")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("About").assertIsDisplayed()

        compose.onNodeWithText("Third-party licenses").performScrollTo().performClick()
        compose.onNodeWithText("Third-party licenses").assertIsDisplayed()
        compose.onNodeWithText("AndroidX / Jetpack / Compose").assertIsDisplayed()
        compose.onNodeWithText("Protocol Buffers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apache-2.0, BSD-3-Clause, BSL-1.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("androidx.activity:activity-compose", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("legal_text").assertDoesNotExist()

        compose.onNodeWithText("Full license texts and notices").performScrollTo().performClick()
        assertBundledDocument(R.raw.third_party_notices, "Third-party software notices")
        pressBack()
        compose.onNodeWithText("Third-party licenses").assertIsDisplayed()
        compose.onNodeWithText("Full license texts and notices").performScrollTo().performClick()
        compose.onNodeWithTag("legal_text").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Third-party licenses").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("About").assertIsDisplayed()
    }

    @Test fun aboutOpensTheSourceRepositoryWithoutDisplayingTheAddress() {
        val repository = "https://github.com/jschbe/SimpleMPDPlayer"
        val opened = AtomicReference<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.set(intent.dataString)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            open(R.string.about)
            compose.onNodeWithText(repository, substring = true).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.open_source)).performScrollTo().performClick()
            assertEquals(repository, opened.get())
            compose.onNodeWithText(text(R.string.about)).assertIsDisplayed()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertBundledDocument(resource: Int, heading: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(heading, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(heading, substring = true).assertIsDisplayed()
        val paragraphs = instrumentation.targetContext.resources.openRawResource(resource)
            .bufferedReader().use { it.readText() }.split("\n\n")
        val last = paragraphs.indexOfLast { it.isNotBlank() }
        compose.onNodeWithTag("legal_text").performScrollToIndex(last)
        compose.onNodeWithText(paragraphs[last]).assertIsDisplayed()
    }

    @Test fun timerPluralSelectionUsesAndroidRulesForRussianAndUkrainian() {
        val context = instrumentation.targetContext
        for ((tag, forms) in listOf("ru" to listOf("минута", "минуты", "минут"), "uk" to listOf("хвилина", "хвилини", "хвилин"))) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            for ((n, i) in listOf(1 to 0, 2 to 1, 5 to 2, 11 to 2, 21 to 0, 22 to 1, 25 to 2, 1440 to 2)) {
                assertEquals("$n ${forms[i]}", localized.resources.getQuantityString(R.plurals.timer_duration_minutes, n, n))
            }
        }
    }
}
