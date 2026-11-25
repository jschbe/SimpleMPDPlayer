package com.github.jschbe.simplempdplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.audio.*
import com.github.jschbe.simplempdplayer.data.*
import com.github.jschbe.simplempdplayer.mpd.*
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class LocalListeningUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = ServerInput("Test", "localhost").toProfile()!!
    private val listening = mutableListOf<Boolean>()
    private val commands = mutableListOf<PlayerAction>()
    private var reconnects = 0
    private var permissionRequests = 0
    private val state = mutableStateOf(ServersUiState(
        loading = false, settings = ServerSettings(listOf(server), server.id),
        connection = ConnectionState(ConnectionStatus.CONNECTED),
    ))

    private fun show() {
        compose.activityRule.scenario.onActivity {
            it.setShowWhenLocked(true); it.setTurnScreenOn(true)
        }
        compose.setContent {
            SimpleMPDPlayerTheme {
                ServersApp(state.value, {}, {}, {}, { _, _ -> }, {}, {}, { reconnects++ }, {}, { permissionRequests++ }, commands::add,
                    onLocalListening = listening::add)
            }
        }
    }

    @Test fun localSwitchAndRetryAreSeparateFromMpdTransport() {
        show()
        compose.onNodeWithText("Listen on phone").assertIsDisplayed()
        compose.onNodeWithTag("local_listening_switch").assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), listening)
            assertTrue(commands.isEmpty())
            state.value = state.value.copy(localStream = StreamState(StreamPhase.ACTIVE))
        }
        compose.onNodeWithTag("local_listening_switch").assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true, false), listening)
            state.value = state.value.copy(localStream = StreamState(StreamPhase.ERROR, StreamError.UNAVAILABLE))
        }
        compose.onNodeWithText("Retry local audio").performClick()
        compose.runOnIdle { assertEquals(listOf(true, false, true), listening); assertTrue(commands.isEmpty()) }
    }

    @Test fun compactStatusShowsProgressDuringSetupAndRecoveryUntilExhaustion() {
        state.value = state.value.copy(connection = ConnectionState(ConnectionStatus.CONNECTING))
        show()
        val row = compose.onNodeWithTag("connection_status_row")
        val spinner = compose.onNodeWithTag("connection_progress")
        val height = row.getUnclippedBoundsInRoot().run { bottom - top }
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        spinner.assertIsDisplayed()

        val idleFailure = ConnectionState(ConnectionStatus.CONNECTED,
            error = ConnectionError.LOST, failureSource = ConnectionFailureSource.IDLE,
            retryAttempt = 2, recovering = true)
        compose.runOnIdle { state.value = state.value.copy(connection = idleFailure) }
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        spinner.assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connection = idleFailure.copy(waitingForNetwork = true)) }
        compose.onNodeWithText("Network unavailable").assertIsDisplayed()
        spinner.assertIsDisplayed()

        compose.runOnIdle { state.value = state.value.copy(connection = idleFailure.copy(
            recovering = false, retryAttempt = 5, retriesExhausted = true, waitingForNetwork = true)) }
        compose.onNodeWithText("Connection failed").assertIsDisplayed()
        spinner.assertDoesNotExist()
        compose.onNodeWithText("Automatic recovery stopped after 5 retries. Tap Reconnect to try again.").assertDoesNotExist()
        compose.onNodeWithText("Live MPD updates interrupted; the control connection is retained").assertDoesNotExist()
        compose.onNodeWithText("Reconnect").assertIsEnabled().performClick()
        assertEquals(height, row.getUnclippedBoundsInRoot().run { bottom - top })

        compose.runOnIdle {
            assertEquals(1, reconnects)
            state.value = state.value.copy(connection = ConnectionState(ConnectionStatus.CONNECTED))
        }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        spinner.assertDoesNotExist()
        compose.onNodeWithText("Reconnect").assertDoesNotExist()
        assertEquals(height, row.getUnclippedBoundsInRoot().run { bottom - top })
    }

    @Test fun terminalIdleFailureKeepsControlAndPermissionFailureOffersResolution() {
        state.value = state.value.copy(connection = ConnectionState(ConnectionStatus.CONNECTED,
            error = ConnectionError.INVALID_RESPONSE, failureSource = ConnectionFailureSource.IDLE))
        show()
        compose.onNodeWithText("Connection failed").assertIsDisplayed()
        compose.onNodeWithTag("local_listening_switch").assertIsEnabled()
        compose.onNodeWithText("Reconnect").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, reconnects)
            state.value = state.value.copy(connection = ConnectionState(ConnectionStatus.FAILED,
                error = ConnectionError.PERMISSION_REQUIRED, waitingForNetwork = true))
        }
        compose.onNodeWithText("Connection failed").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertDoesNotExist()
        compose.onNodeWithTag("connection_progress").assertDoesNotExist()
        compose.onNodeWithText("Allow local network").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests) }
    }

    @Test fun disconnectedCannotEnableAudioButAnActiveReceiverCanAlwaysBeTurnedOff() {
        state.value = state.value.copy(connection = ConnectionState())
        show()
        compose.onNodeWithTag("local_listening_switch").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(localStream = StreamState(StreamPhase.BUFFERING)) }
        compose.onNodeWithTag("local_listening_switch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(false), listening); assertTrue(commands.isEmpty()) }
    }
}
