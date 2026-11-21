package com.github.jschbe.simplempdplayer.ui

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.jschbe.simplempdplayer.data.ThemeMode
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun liveThemeAndSystemChangesUpdateSystemBarIconContrast() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val mode = mutableStateOf(ThemeMode.SYSTEM)
        val systemDark = mutableStateOf(false)
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                SimpleMPDPlayerTheme(mode = mode.value) { Text("Theme fixture") }
            }
        }
        fun assertIcons(darkIcons: Boolean) = compose.runOnIdle {
            val window = compose.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(darkIcons, controller.isAppearanceLightStatusBars)
            assertEquals(darkIcons, controller.isAppearanceLightNavigationBars)
        }
        assertIcons(true)
        compose.runOnIdle { mode.value = ThemeMode.DARK }; assertIcons(false)
        compose.runOnIdle { mode.value = ThemeMode.LIGHT }; assertIcons(true)
        compose.runOnIdle { systemDark.value = true }; assertIcons(true)
        compose.runOnIdle { mode.value = ThemeMode.SYSTEM }; assertIcons(false)
        compose.runOnIdle { systemDark.value = false }; assertIcons(true)
    }
}
