package com.github.jschbe.simplempdplayer.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.jschbe.simplempdplayer.ServersViewModel
import com.github.jschbe.simplempdplayer.data.*
import com.github.jschbe.simplempdplayer.mpd.MpdConnection
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full UI + ViewModel with in-memory settings and fake sockets; never access installed profiles. */
@RunWith(AndroidJUnit4::class)
class ServerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private lateinit var model: ServersViewModel
    private val first = ServerInput("First", "localhost").toProfile()!!
    private val second = ServerInput("Second", "localhost").toProfile()!!
    private val connections = mutableListOf<FakeConnection>()
    private var dark = false
    private var systemDark = false
    private val failReads = MutableStateFlow(false)

    @Before fun visibleWindow() {
        compose.activityRule.scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    @After fun close() { compose.runOnUiThread { models.clear() } }

    private fun show(profiles: List<ServerProfile> = emptyList(), selected: String? = null) {
        val initial = mutablePreferencesOf(stringPreferencesKey("server_profiles_v1") to ServerSettingsCodec.encode(profiles))
        selected?.let { initial[stringPreferencesKey("selected_server_id")] = it }
        val data = object : DataStore<Preferences> {
            private val preferences = MutableStateFlow<Preferences>(initial)
            override val data = combine(preferences, failReads) { stored, failed ->
                if (failed) throw IOException("Test read failure")
                stored
            }
            private val mutex = Mutex()
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = mutex.withLock {
                transform(preferences.value).also { preferences.value = it }
            }
        }
        compose.runOnUiThread {
            model = ServersViewModel(ServerSettingsStore(data), connectionFactory = { FakeConnection().also(connections::add) })
            models.put("test", model)
        }
        compose.setContent {
            val state by model.state.collectAsState()
            val system = isSystemInDarkTheme()
            SimpleMPDPlayerTheme(mode = state.settings.theme) {
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                SideEffect { dark = isDark; systemDark = system }
                ServersApp(state, { model.openEditor() }, model::openEditor, model::dismissEditor,
                    model::save, model::delete, model::select, model::reconnect, model::reloadSettings,
                    {}, model::perform, model::test, model::reorder, model::setTheme)
            }
        }
    }

    private fun servers() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Servers").performClick()
    }

    @Test fun automaticFirstSetupCanBeCancelledWithoutReopening() {
        show()
        compose.onNodeWithText("Add server").assertIsDisplayed()
        compose.onNodeWithText("Test").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Test").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithText("Test").assertDoesNotExist()
        servers()
        compose.onNodeWithContentDescription("Add server").performClick()
        compose.onNodeWithText("Test").assertIsDisplayed()
    }

    @Test fun coldReadFailureShowsRetryAndWaitsForConfirmedSetup() {
        failReads.value = true
        show()
        compose.onNodeWithText("Could not load saved servers. Retry to load them again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add server").assertIsNotEnabled()
        compose.onNodeWithText("Test").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(connections.isEmpty())
            failReads.value = false
        }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Test").assertIsDisplayed()
        compose.onNodeWithText("Could not load saved servers. Retry to load them again.").assertDoesNotExist()
    }

    @Test fun readFailureRetainsServerAndDisablesPersistentEditsUntilRetry() {
        show(listOf(first, second), first.id)
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.runOnIdle { failReads.value = true }
        compose.onNodeWithTag("server_dropdown").assertIsNotEnabled()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithTag("track_info_toggle").assertIsNotEnabled()
        servers()
        compose.onNodeWithText("Could not load saved servers. Retry to load them again.").assertIsDisplayed()
        compose.onNodeWithTag("edit_server_${first.id}").assertIsNotEnabled()
        compose.onNodeWithTag("delete_server_${first.id}").assertIsNotEnabled()
        compose.onNodeWithTag("server_row_${second.id}").assertIsDisplayed()
        compose.runOnIdle { failReads.value = false }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithTag("edit_server_${first.id}").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(2, connections.size)
            assertTrue(connections.none { it.closed })
        }
    }

    @Test fun dialogValidatesProbeDoesNotSaveAndSaveActivates() {
        show()
        compose.onNodeWithText("Test").performClick()
        compose.onNodeWithText("Enter a name without control characters.").assertIsDisplayed()
        compose.runOnIdle { assertTrue(connections.isEmpty()) }
        compose.onNodeWithText("Name").performTextInput("Probe")
        compose.onNodeWithText("Host").performClick()
        compose.onNodeWithText("192.168.1.100").assertIsDisplayed()
        compose.runOnIdle { assertTrue(model.state.value.settings.servers.isEmpty()) }
        compose.onNodeWithText("Host").performTextInput("localhost")
        compose.onNodeWithText("Test").performClick()
        compose.runOnIdle {
            assertTrue(model.state.value.settings.servers.isEmpty())
            assertEquals(1, connections.size)
            assertTrue(connections.single().closed)
        }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { model.state.value.settings.selectedId != null && model.state.value.editor == null }
        compose.runOnIdle {
            assertEquals("Probe", model.state.value.activeServer!!.name)
            assertEquals("localhost", model.state.value.activeServer!!.host)
            assertEquals(3, connections.size)
        }
    }

    @Test fun dropdownSwitchesServerWithoutConfirmation() {
        show(listOf(first, second), first.id)
        compose.onNodeWithTag("server_dropdown").performClick()
        compose.onNodeWithTag("select_server_${second.id}").performClick()
        compose.waitUntil(5_000) { model.state.value.settings.selectedId == second.id }
        compose.runOnIdle {
            assertEquals(4, connections.size)
            assertTrue(connections.take(2).all { it.closed })
        }
        compose.onNodeWithText("Second").assertIsDisplayed()
    }

    @Test fun addAndEditRejectDuplicateNamesAndAllowOwnNameWithStableId() {
        show(listOf(first, second))
        compose.onNodeWithContentDescription("Add server").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("  FIRST  ")
        compose.onNodeWithText("A server with this name already exists.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("edit_server_${second.id}").performClick()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Name").performTextReplacement(" first ")
        compose.onNodeWithText("A server with this name already exists.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextReplacement(" SECOND ")
        compose.onNodeWithText("A server with this name already exists.").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { model.state.value.editor == null }
        compose.runOnIdle {
            assertEquals(listOf(first, second.copy(name = "SECOND")), model.state.value.settings.servers)
            assertEquals(second.id, model.state.value.settings.selectedId)
            assertEquals(2, connections.size)
        }
    }

    @Test fun legacyDuplicatesStayVisibleAndCanBeExplicitlyRenamed() {
        val duplicate = second.copy(name = " first ")
        show(listOf(first, duplicate))
        compose.onNodeWithTag("edit_server_${duplicate.id}").performClick()
        compose.onNodeWithText("A server with this name already exists.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextReplacement("Unique")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { model.state.value.editor == null }
        compose.runOnIdle {
            assertEquals(listOf(first, duplicate.copy(name = "Unique")), model.state.value.settings.servers)
            assertEquals(duplicate.id, model.state.value.settings.selectedId)
        }
    }

    @Test fun onlyLongPressOnHandleReordersAndDropdownUsesStoredOrder() {
        show(listOf(first, second), first.id)
        servers()
        val firstHandle = compose.onNodeWithTag("drag_server_${first.id}")
        val delta = compose.onNodeWithTag("drag_server_${second.id}").fetchSemanticsNode().boundsInRoot.center.y - firstHandle.fetchSemanticsNode().boundsInRoot.center.y
        compose.onNodeWithText("First").performTouchInput {
            down(center); advanceEventTime(700); moveTo(Offset(centerX, centerY + delta)); up()
        }
        compose.runOnIdle { assertEquals(listOf(first, second), model.state.value.settings.servers) }
        firstHandle.performTouchInput {
            down(center); advanceEventTime(700); moveTo(Offset(centerX, centerY + delta)); up()
        }
        compose.waitUntil(5_000) { model.state.value.settings.servers.first().id == second.id }
        compose.runOnIdle {
            assertEquals(first.id, model.state.value.settings.selectedId)
            assertEquals(2, connections.size)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("server_dropdown").performClick()
        val secondY = compose.onNodeWithTag("select_server_${second.id}").fetchSemanticsNode().boundsInRoot.center.y
        val firstY = compose.onNodeWithTag("select_server_${first.id}").fetchSemanticsNode().boundsInRoot.center.y
        assertTrue(secondY < firstY)
    }

    @Test fun confirmsDeletionAndLastServerOpensSetupOnlyOnce() {
        show(listOf(first, second), first.id)
        servers()
        compose.onNodeWithTag("delete_server_${first.id}").performClick()
        compose.onNodeWithText("Are you sure you want to delete \"First\"?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, model.state.value.settings.servers.size) }
        compose.onNodeWithTag("delete_server_${first.id}").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { model.state.value.settings.servers.size == 1 }
        compose.runOnIdle { assertNull(model.state.value.settings.selectedId) }
        compose.onNodeWithTag("delete_server_${second.id}").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Test").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNodeWithText("Test").assertDoesNotExist()
    }

    @Test fun appearanceImmediatelyAppliesDarkLightAndSystem() {
        show(listOf(first), first.id)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, model.state.value.settings.theme); assertTrue(dark) }
        compose.onNodeWithText("Light").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.LIGHT, model.state.value.settings.theme); assertFalse(dark) }
        compose.onNodeWithText("System default").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.SYSTEM, model.state.value.settings.theme); assertEquals(systemDark, dark) }
        compose.onNodeWithText("Done").performClick()
    }

    private class FakeConnection : MpdConnection {
        var closed = false
        override suspend fun connect(host: String, port: Int) {}
        override suspend fun ping() {}
        override suspend fun execute(name: String, vararg arguments: String) =
            if (name == "status") listOf("state" to "stop", "playlistlength" to "0", "playlist" to "1") else emptyList()
        override suspend fun idle(): Set<String> = awaitCancellation()
        override fun close() { closed = true }
    }
}
