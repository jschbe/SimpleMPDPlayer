package com.github.jschbe.simplempdplayer.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.MainActivity
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.data.PasswordEdit
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.mpd.ConnectionError
import com.github.jschbe.simplempdplayer.timer.TimerMpdFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerPasswordUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PlayerApplication
    private val candidate = ServerInput("UI password fixture", "127.0.0.1", "1").toProfile()!!
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var selected: String? = null

    @Before fun showRestoredProfile() {
        runBlocking {
            selected = app.serverSettings.settings.first().selectedId
            app.serverSettings.saveAndSelect(candidate, PasswordEdit.Replace("synthetic old UI password"))
            val restored = app.serverSettings.settings.first().servers.first { it.id == candidate.id }
            app.credentials.remove(requireNotNull(restored.credentialRevision))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
        }
    }

    @After fun cleanUp() {
        if (::scenario.isInitialized) scenario.close()
        runBlocking {
            app.serverSettings.delete(candidate.id)
            selected?.let { app.serverSettings.select(it) }
        }
    }

    @Test fun restoredPasswordStatusOpensEditorAndProtectedDraftDoesNotSurviveRecreation() {
        compose.onNodeWithText("MPD password needed").assertIsDisplayed()
        compose.onNodeWithTag("connection_progress").assertDoesNotExist()
        compose.onNodeWithText("Edit server").performClick()
        compose.onNodeWithTag("password_keep").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("mpd_password").assertDoesNotExist()
        compose.onNodeWithTag("password_replace").performScrollTo().performClick()
        val field = compose.onNodeWithTag("mpd_password").performScrollTo()
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertTrue(field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
        val synthetic = "synthetic draft ♫"
        field.performTextInput(synthetic)
        val semantics = field.fetchSemanticsNode().config
        assertEquals("•".repeat(synthetic.length), semantics[SemanticsProperties.EditableText].text)
        assertEquals("•".repeat(synthetic.length), semantics[SemanticsProperties.InputText].text)
        scenario.recreate()
        compose.onNodeWithTag("password_keep").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("password_replace").performScrollTo().performClick()
        assertTrue(compose.onNodeWithTag("mpd_password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
        runBlocking {
            val persisted = app.serverSettings.settings.first().servers.first { it.id == candidate.id }
            assertNotNull(persisted.credentialRevision)
        }
    }

    @Test fun changedControlPortRequiresExplicitReplacementOrRemoval() {
        compose.onNodeWithText("Edit server").performClick()
        compose.onNodeWithText("MPD port").performScrollTo().performTextReplacement("2")
        compose.onNodeWithText("Enter the password for this MPD host and port or choose to use no password.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Test").assertIsNotEnabled()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("password_remove").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            runBlocking {
                val saved = app.serverSettings.settings.first().servers.first { it.id == candidate.id }
                saved.mpdPort == 2 && saved.credentialRevision == null
            }
        }
    }

    @Test fun rejectedSavedPasswordRemainsEditableAndProbeDoesNotReplaceIt() {
        TimerMpdFixture().use { mpd ->
            val rejected = "synthetic wrong password"
            val accepted = " synthetic \"accepted\" \\ ♫ "
            mpd.requiredPassword = accepted
            compose.onNodeWithText("Edit server").performClick()
            compose.onNodeWithText("Host").performScrollTo().performTextReplacement(mpd.host)
            compose.onNodeWithText("MPD port").performScrollTo().performTextReplacement(mpd.port.toString())
            compose.onNodeWithTag("password_replace").performScrollTo().performClick()
            compose.onNodeWithTag("mpd_password").performScrollTo().performTextInput(rejected)
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5_000) { app.repository.state.value.error == ConnectionError.PASSWORD_REJECTED }
            compose.onNodeWithText("MPD password rejected").assertIsDisplayed()
            compose.onNodeWithTag("connection_progress").assertDoesNotExist()
            val before = runBlocking { app.serverSettings.settings.first().servers.first { it.id == candidate.id } }
            assertTrue(runBlocking { app.credentials.resolve(before) == rejected })
            assertEquals(listOf("password"), mpd.allCommands)
            compose.onNodeWithText("Edit server").performClick()
            compose.onNodeWithText("MPD rejected the password. Check it in Edit server.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("password_replace").performScrollTo().performClick()
            compose.onNodeWithTag("mpd_password").performScrollTo().performTextInput(accepted)
            compose.onNodeWithText("Test").performClick()
            compose.waitUntil(5_000) { mpd.allCommands.contains("status") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Testing…").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf("password", "password", "status"), mpd.allCommands)
            assertEquals(before, runBlocking { app.serverSettings.settings.first().servers.first { it.id == candidate.id } })
            assertTrue(runBlocking { app.credentials.resolve(before) == rejected })
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5_000) { app.repository.state.value.idleConnected }
            val after = runBlocking { app.serverSettings.settings.first().servers.first { it.id == candidate.id } }
            assertEquals(before.id, after.id)
            assertNotEquals(before.credentialRevision, after.credentialRevision)
            assertTrue(runBlocking { app.credentials.resolve(after) == accepted })
            assertEquals(4, mpd.allCommands.count { it == "password" })
            assertTrue(mpd.mutations.isEmpty())
        }
    }
}
