package com.github.jschbe.simplempdplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes profile writes with the single timer claim/delivery, across store instances. */
internal val profileMutationLock = Mutex()

private val profilesKey = stringPreferencesKey("server_profiles_v1")
private val selectedKey = stringPreferencesKey("selected_server_id")
private val recoveredKey = booleanPreferencesKey("recovered_corrupt_file")
private val trackInfoKey = booleanPreferencesKey("track_info_expanded")
private val themeKey = stringPreferencesKey("theme_mode")

val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "servers",
    corruptionHandler = ReplaceFileCorruptionHandler {
        mutablePreferencesOf(recoveredKey to true)
    },
)

class ServerSettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings = dataStore.data.map(::decode).catch { error ->
        if (error is IOException) emit(ServerSettings(readFailed = true)) else throw error
    }

    suspend fun save(server: ServerProfile) = saveProfile(server, activate = false)

    suspend fun saveAndSelect(server: ServerProfile) = saveProfile(server, activate = true)

    private suspend fun saveProfile(server: ServerProfile, activate: Boolean) {
        require(server.isValid())
        change { current ->
            val servers = current.servers.toMutableList()
            val index = servers.indexOfFirst { it.id == server.id }
            if (index >= 0) servers[index] = server else servers.add(server)
            current.copy(servers = servers, selectedId = if (activate) server.id else current.selectedId)
        }
    }

    suspend fun reorder(ids: List<String>) = change { current ->
        val byId = current.servers.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull(byId::get)
        current.copy(servers = ordered + current.servers.filterNot { it.id in ids })
    }

    suspend fun setTrackInfoExpanded(expanded: Boolean) = change { it.copy(trackInfoExpanded = expanded) }

    suspend fun setTheme(mode: ThemeMode) = change { it.copy(theme = mode) }

    suspend fun select(id: String) = change { current ->
        require(current.servers.any { it.id == id })
        current.copy(selectedId = id)
    }

    suspend fun delete(id: String) = change { current ->
        current.copy(
            servers = current.servers.filterNot { it.id == id },
            selectedId = current.selectedId?.takeUnless { it == id },
        )
    }

    private suspend fun change(transform: (ServerSettings) -> ServerSettings) = profileMutationLock.withLock {
        dataStore.edit { preferences ->
            val changed = transform(decode(preferences))
            preferences[profilesKey] = ServerSettingsCodec.encode(changed.servers)
            changed.selectedId?.let { preferences[selectedKey] = it } ?: preferences.remove(selectedKey)
            preferences.remove(recoveredKey)
            preferences[themeKey] = changed.theme.name
            preferences[trackInfoKey] = changed.trackInfoExpanded
        }
    }

    private fun decode(preferences: Preferences): ServerSettings {
        // Inspect raw values so a key with the wrong persisted type cannot crash the app.
        val values = preferences.asMap().entries.associate { it.key.name to it.value }
        val profiles = values[profilesKey.name]
        val selected = values[selectedKey.name]
        val decoded = ServerSettingsCodec.decode(profiles as? String, selected as? String)
        val rawTheme = values[themeKey.name]
        val theme = ThemeMode.entries.find { it.name == rawTheme }
        val expanded = values[trackInfoKey.name]
        return decoded.copy(theme = theme ?: ThemeMode.SYSTEM, trackInfoExpanded = expanded as? Boolean ?: true, recovered = decoded.recovered ||
            (rawTheme != null && theme == null) || (expanded != null && expanded !is Boolean) || values[recoveredKey.name] == true ||
            (profiles != null && profiles !is String) || (selected != null && selected !is String))
    }
}
