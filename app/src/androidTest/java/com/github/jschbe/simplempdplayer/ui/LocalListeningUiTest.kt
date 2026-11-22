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
                ServersApp(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {}, commands::add,
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

    @Test fun exhaustedIdleRecoveryShowsClearErrorAndRetainsManualReconnect() {
        state.value = state.value.copy(connection = ConnectionState(ConnectionStatus.CONNECTED,
            error = ConnectionError.LOST, failureSource = ConnectionFailureSource.IDLE,
            retryAttempt = 5, retriesExhausted = true))
        show()
        compose.onNodeWithText("Automatic recovery stopped after 5 retries. Tap Reconnect to try again.").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").assertIsEnabled()
        compose.onNodeWithText("Live MPD updates interrupted; the control connection is retained").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(connection = state.value.connection.copy(recovering = true,
                retriesExhausted = false, waitingForNetwork = true))
        }
        compose.onNodeWithText("Network unavailable").assertIsDisplayed()
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
