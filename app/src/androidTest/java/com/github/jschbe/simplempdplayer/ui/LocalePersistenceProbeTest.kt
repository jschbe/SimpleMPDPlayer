package com.github.jschbe.simplempdplayer.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, host-driven two-process probe; run only in the isolated test app. */
class LocalePersistenceProbeTest {
    @Test fun explicitLanguageAndUnrelatedPreferencesSurviveProcessDeath() {
        val phase = InstrumentationRegistry.getArguments().getString("localeProbe")
        assumeTrue("Host-driven locale process probe", phase in listOf("seed", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicetest"))
        val store = ServerSettingsStore(context.serverDataStore)
        val profile = ServerProfile("00000000-0000-0000-0000-000000000072", "Locale fixture 🎵", "localhost", 6600, 8000)
        if (phase == "seed") runBlocking { store.save(profile); store.setTheme(ThemeMode.DARK) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            if (phase == "seed") {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de")) }
                scenario.recreate()
                scenario.onActivity { assertEquals("de", it.resources.configuration.locales[0].language) }
            } else {
                scenario.onActivity {
                    assertEquals("de", AppCompatDelegate.getApplicationLocales()[0]?.language)
                    assertEquals("de", it.resources.configuration.locales[0].language)
                }
                val settings = runBlocking { store.settings.first() }
                assertEquals(profile, settings.servers.single { it.id == profile.id })
                assertEquals(ThemeMode.DARK, settings.theme)
                assertNull(settings.selectedId)
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
                runBlocking { store.delete(profile.id); store.setTheme(ThemeMode.SYSTEM) }
            }
        }
    }
}
