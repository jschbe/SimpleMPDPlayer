package com.github.jschbe.simplempdplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val profile = ServerInput("Test", "localhost").toProfile()!!

    @Test
    fun orderedProfilesAndThemeSurviveDiskReloadWithoutRetargeting() = runBlocking {
        val file = folder.newFolder().resolve("test.preferences_pb")
        val second = ServerInput("Second", "localhost").toProfile()!!
        withStore(file) { store, _ ->
            store.saveAndSelect(profile)
            store.save(second)
            store.reorder(listOf(second.id, profile.id))
            store.setTheme(ThemeMode.DARK)
        }
        withStore(file) { store, _ ->
            val settings = store.settings.first()
            assertEquals(listOf(second, profile), settings.servers)
            assertEquals(profile.id, settings.selectedId)
            assertEquals(ThemeMode.DARK, settings.theme)
            for (mode in ThemeMode.entries) {
                store.setTheme(mode)
                assertEquals(mode, store.settings.first().theme)
            }
        }
    }

    @Test fun trackInfoExpansionDefaultsToTrueAndSurvivesReloadAndUnrelatedWrites() = runBlocking {
        val file = folder.newFolder().resolve("track.preferences_pb")
        withStore(file) { store, _ ->
            assertTrue(store.settings.first().trackInfoExpanded)
            store.saveAndSelect(profile)
            store.setTrackInfoExpanded(false)
            store.setTheme(ThemeMode.DARK)
        }
        withStore(file) { store, _ ->
            assertFalse(store.settings.first().trackInfoExpanded)
            store.save(profile.copy(name = "Renamed"))
            assertFalse(store.settings.first().trackInfoExpanded)
            assertEquals(profile.id, store.settings.first().selectedId)
            store.setTrackInfoExpanded(true)
        }
        withStore(file) { store, _ -> assertTrue(store.settings.first().trackInfoExpanded) }
    }

    @Test fun malformedTrackInfoPreferenceRecoversWithoutLosingProfiles() = runBlocking {
        withStore { store, data ->
            store.saveAndSelect(profile)
            data.edit { it[stringPreferencesKey("track_info_expanded")] = "invalid" }
            val saved = store.settings.first()
            assertTrue(saved.trackInfoExpanded)
            assertTrue(saved.recovered)
            assertEquals(listOf(profile), saved.servers)
            store.setTrackInfoExpanded(false)
            assertFalse(store.settings.first().recovered)
        }
    }

    @Test
    fun staleReorderCannotLoseNewProfilesOrRestoreDeletedProfiles() = runBlocking {
        withStore { store, _ ->
            val second = ServerInput("Second", "localhost").toProfile()!!
            val third = ServerInput("Third", "localhost").toProfile()!!
            store.saveAndSelect(profile)
            store.save(second)
            store.save(third)
            store.delete(second.id)
            store.reorder(listOf(second.id, profile.id, profile.id))
            assertEquals(listOf(profile, third), store.settings.first().servers)
            assertEquals(profile.id, store.settings.first().selectedId)
        }
    }

    @Test
    fun savesEditsSelectionAndActiveDeletionAtomically() = runBlocking {
        withStore { store, _ ->
            store.save(profile)
            store.select(profile.id)
            val edited = profile.copy(name = "Renamed", mpdPort = 6601)
            store.save(edited)
            assertEquals(listOf(edited), store.settings.first().servers)
            assertEquals(profile.id, store.settings.first().selectedId)
            store.delete(profile.id)
            assertEquals(ServerSettings(), store.settings.first())
        }
    }

    @Test
    fun persistenceSurvivesStoreRecreation() = runBlocking {
        val file = folder.newFolder().resolve("test.preferences_pb")
        withStore(file) { store, _ ->
            store.save(profile)
            store.select(profile.id)
        }
        withStore(file) { store, _ ->
            assertEquals(listOf(profile), store.settings.first().servers)
            assertEquals(profile.id, store.settings.first().selectedId)
        }
    }

    @Test
    fun wrongPreferenceTypesAreRecoveredAndNextEditSanitizesThem() = runBlocking {
        withStore { store, dataStore ->
            dataStore.edit {
                it[intPreferencesKey("server_profiles_v1")] = 42
                it[stringPreferencesKey("selected_server_id")] = "missing"
            }
            val recovered = store.settings.first()
            assertTrue(recovered.recovered)
            assertTrue(recovered.servers.isEmpty())
            assertNull(recovered.selectedId)
            store.save(profile)
            assertFalse(store.settings.first().recovered)
            assertEquals(listOf(profile), store.settings.first().servers)
        }
    }

    @Test
    fun readFailureIsVisibleAndNeverWrittenBackAsEmptyData() = runBlocking {
        var wrote = false
        val broken = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("Read failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                wrote = true
                throw IOException("Write failed")
            }
        }
        assertTrue(ServerSettingsStore(broken).settings.first().readFailed)
        assertFalse(wrote)
    }

    private suspend fun withStore(
        file: File = folder.newFolder().resolve("test.preferences_pb"),
        block: suspend (ServerSettingsStore, DataStore<Preferences>) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            block(ServerSettingsStore(dataStore), dataStore)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
