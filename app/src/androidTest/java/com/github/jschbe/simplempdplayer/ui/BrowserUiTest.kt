package com.github.jschbe.simplempdplayer.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.jschbe.simplempdplayer.ServersUiState
import com.github.jschbe.simplempdplayer.data.ServerInput
import com.github.jschbe.simplempdplayer.data.ServerSettings
import com.github.jschbe.simplempdplayer.mpd.*
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled UI state only: no sockets and no installed profile storage. */
@RunWith(AndroidJUnit4::class)
class BrowserUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = ServerInput("Library fixture", "localhost").toProfile()!!
    private val folder = "Albums \"音楽\"\\Live"
    private val file = "loose track.flac"
    private val root = listOf(LibraryEntry.Directory(folder), LibraryEntry.Track(MpdSong(null, file, "Loose track")))
    private val listings = mutableMapOf(
        "" to root,
        folder to listOf(LibraryEntry.Directory("$folder/Disc 1"), LibraryEntry.Track(MpdSong(null, "$folder/song.flac", "A song", listOf("Björk"), duration = 65.0))),
        "$folder/Disc 1" to emptyList(),
    )
    private val ui = mutableStateOf(ServersUiState())
    private val reads = mutableListOf<String>()
    private val actions = mutableListOf<Triple<String, Boolean, Long>>()
    private val playerActions = mutableListOf<PlayerAction>()
    private var fallbackBacks = 0

    @Before fun visibleWindow() {
        compose.activityRule.scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun browse(path: String, generation: Long) {
        if (generation != ui.value.browser.generation) return
        reads += path
        ui.value = ui.value.copy(browser = ui.value.browser.copy(path = path, entries = listings.getValue(path), loaded = true, loading = false, error = null, listingLimited = false, hasPlaylists = path.isEmpty()))
    }

    private fun show() {
        ui.value = ServersUiState(settings = ServerSettings(listOf(server), server.id), loading = false, connection = ConnectionState(ConnectionStatus.CONNECTED), browser = BrowserState(generation = 1))
        compose.setContent {
            BackHandler { fallbackBacks++ }
            SimpleMPDPlayerTheme {
                ServersApp(
                    state = ui.value, onAdd = {}, onEdit = {}, onDismissEditor = {}, onSave = { _, _ -> }, onDelete = {}, onSelect = {},
                    onReconnect = {}, onReload = {}, onGrantNetworkAccess = {}, onAction = playerActions::add,
                    onLoadBrowser = { if (!ui.value.browser.loaded && !ui.value.browser.listingLimited) browse(ui.value.browser.path, ui.value.browser.generation) },
                    onBrowse = ::browse, onEnqueue = { uri, replace, generation -> actions += Triple(uri, replace, generation) },
                )
            }
        }
        compose.onNodeWithTag("tab_1").assertIsSelected()
        compose.runOnIdle { assertTrue(reads.isEmpty()) }
        compose.onNodeWithTag("tab_0").performClick()
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun foldersNavigateWithUpAndAndroidBackWhilePagerAndRootBackKeepTheirBehavior() {
        show()
        compose.onNodeWithText("Stored playlists are not supported yet.").assertIsDisplayed()
        compose.onNodeWithTag("browse_$folder").performClick()
        compose.onNodeWithText("Music library / $folder").assertIsDisplayed()
        compose.onNodeWithText("Björk").assertIsDisplayed()
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithTag("browse_$folder/Disc 1").performClick()
        compose.onNodeWithText("This folder is empty").assertIsDisplayed()
        back()
        compose.onNodeWithText("A song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Up one folder").performClick()
        compose.onNodeWithText("Music library").assertIsDisplayed()
        back()
        compose.runOnIdle { assertEquals(1, fallbackBacks) }
        compose.onNodeWithTag("browse_$folder").performClick()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab_1").assertIsSelected()
        back()
        compose.runOnIdle { assertEquals(2, fallbackBacks); assertEquals(folder, ui.value.browser.path) }
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNodeWithText("A song").assertIsDisplayed()
    }

    @Test fun rightSwipeGoesUpExactlyOneDirectoryAndPreservesTabNavigationFromNestedBrowser() {
        show()
        val child = "$folder/Disc 1"
        compose.onNodeWithTag("browse_$folder").performTouchInput { click() }
        compose.onNodeWithTag("browse_$child").performTouchInput { click() }
        val pager = compose.onNodeWithTag("main_pager")
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab_1").assertIsSelected()
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.onNodeWithText("Music library / $child").assertIsDisplayed()

        compose.onNodeWithTag("browser_page").performTouchInput { swipeRight() }
        compose.onNodeWithText("Music library / $folder").assertIsDisplayed()
        compose.onNodeWithText("A song").assertIsDisplayed()
        compose.onNodeWithTag("browser_page").performTouchInput { swipeRight() }
        compose.onNodeWithText("Music library").assertIsDisplayed()
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("", folder, child, folder, ""), reads) }
    }

    @Test fun rightSwipeAtRootMakesNoRequestAndBothPagerDirectionsStillWork() {
        show()
        val pager = compose.onNodeWithTag("main_pager")
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.onNodeWithText("Music library").assertIsDisplayed()

        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab_1").assertIsSelected()
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab_2").assertIsSelected()
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("tab_1").assertIsSelected()
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(""), reads) }
    }

    @Test fun diagonalDragsAndVerticalScrollingDoNotChangeTheCurrentDirectory() {
        listings[folder] = listings.getValue(folder) +
            (1..50).map { LibraryEntry.Track(MpdSong(null, "$folder/track$it.flac", "Track $it")) }
        show()
        compose.onNodeWithTag("browse_$folder").performClick()
        val list = compose.onNodeWithTag("browser_list")
        list.performTouchInput {
            val half = minOf(width, height) * 0.28f
            swipe(center + Offset(-half, half), center + Offset(half, -half))
        }
        compose.onNodeWithTag("tab_0").assertIsSelected()
        compose.onNodeWithText("Music library / $folder").assertIsDisplayed()
        list.performTouchInput { swipeUp() }
        compose.onNodeWithText("A song").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(listOf("", folder), reads) }
    }

    @Test fun rightSwipeKeepsTheParentActionAvailableWhileLoadingOrAfterAnError() {
        show()
        val page = compose.onNodeWithTag("browser_page")
        compose.runOnIdle { ui.value = ui.value.copy(browser = BrowserState(
            generation = 1, path = "$folder/Disc 1", loading = true,
        )) }
        compose.onNodeWithText("Loading folder…").assertIsDisplayed()
        page.performTouchInput { swipeRight() }
        compose.onNodeWithText("A song").assertIsDisplayed()

        compose.runOnIdle { ui.value = ui.value.copy(browser = BrowserState(
            generation = 1, path = folder, error = ConnectionError.MPD_REJECTED,
        )) }
        compose.onNodeWithText("Retry").assertIsDisplayed()
        page.performTouchInput { swipeRight() }
        compose.onNodeWithText("Music library").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("", folder, ""), reads) }
    }

    @Test fun fileTapAndFolderOverflowOfferAppendAndConfirmedReplaceWithoutPlaying() {
        show()
        compose.onNodeWithTag("browse_$file").performClick()
        compose.onNodeWithText("Append to queue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple(file, false, 1L)), actions) }
        compose.onNodeWithTag("library_actions_$folder").performClick()
        compose.onNodeWithText("Replace queue").performClick()
        compose.onNodeWithText("Replace the queue?").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", ui.value.browser.path); assertEquals(1, actions.size) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, actions.size) }
        compose.onNodeWithTag("library_actions_$folder").performClick()
        compose.onNodeWithText("Append to queue").performClick()
        compose.runOnIdle { assertEquals(Triple(folder, false, 1L), actions.last()) }
        compose.onNodeWithTag("library_actions_$folder").performClick()
        compose.onNodeWithText("Replace queue").performClick()
        compose.onNodeWithText("Replace queue").performClick()
        compose.runOnIdle { assertEquals(Triple(folder, true, 1L), actions.last()) }
        compose.onNodeWithTag("browse_$file").performClick()
        compose.onNodeWithText("Replace queue").performClick()
        compose.onNodeWithText("Replace queue").performClick()
        compose.runOnIdle { assertEquals(Triple(file, true, 1L), actions.last()); assertTrue(playerActions.isEmpty()) }
    }

    @Test fun serverSwitchOrDisconnectDismissesOldConfirmationEvenForTheSameUri() {
        show()
        compose.onNodeWithTag("browse_$file").performClick()
        compose.onNodeWithText("Replace queue").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(browser = ui.value.browser.copy(generation = 2)) }
        compose.onNodeWithText("Replace the queue?").assertDoesNotExist()
        compose.onNodeWithTag("browse_$file").performClick()
        compose.onNodeWithText("Replace queue").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(connection = ConnectionState(ConnectionStatus.FAILED)) }
        compose.onNodeWithText("Replace the queue?").assertDoesNotExist()
        compose.onNodeWithText("Connect to a server to browse its music library").assertIsDisplayed()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun emptyLoadingAndDirectoryFailureHaveUsefulStatesAndRetry() {
        show()
        compose.runOnIdle { ui.value = ui.value.copy(browser = ui.value.browser.copy(path = folder, entries = emptyList(), loading = true, loaded = false)) }
        compose.onNodeWithText("Loading folder…").assertIsDisplayed()
        compose.runOnIdle { ui.value = ui.value.copy(browser = ui.value.browser.copy(loading = false, error = ConnectionError.MPD_REJECTED)) }
        compose.onNodeWithText("Could not load this folder. It may be unavailable or access may be denied.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("A song").assertIsDisplayed()
        compose.onNodeWithTag("browse_$folder/Disc 1").performClick()
        compose.onNodeWithText("This folder is empty").assertIsDisplayed()
        compose.runOnIdle { ui.value = ui.value.copy(browser = ui.value.browser.copy(queueMessage = BrowserQueueMessage.REPLACE_PARTIAL)) }
        compose.onNodeWithText("The queue was cleared, but adding content failed or could not be confirmed. Some tracks may have been added.").assertIsDisplayed()
    }

    @Test fun oversizedFolderOffersUpAndRetryAfterRecovery() {
        show()
        compose.onNodeWithTag("browse_$folder").performClick()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ConnectionState(ConnectionStatus.FAILED, recovering = true),
                browser = BrowserState(generation = 2, path = folder, listingLimited = true))
        }
        compose.onNodeWithText("This folder listing is too large to display.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsNotEnabled()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = ConnectionState(ConnectionStatus.CONNECTED))
        }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("A song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Up one folder").performClick()
        compose.onNodeWithText("Loose track").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("", folder, folder, ""), reads) }
    }

    @Test fun returningFromQueuePreservesBrowserScrollAndDoesNotReloadTheDirectory() {
        listings[""] = root + (1..50).map { LibraryEntry.Track(MpdSong(null, "file$it.flac", "Track $it")) }
        show()
        compose.onNodeWithTag("browser_list").performScrollToIndex(20)
        compose.onNodeWithText("Track 19").assertIsDisplayed()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("tab_0").performClick()
        compose.onNodeWithText("Track 19").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(""), reads) }
    }
}
