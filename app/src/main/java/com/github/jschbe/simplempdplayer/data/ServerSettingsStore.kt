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
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

class ServerSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val credentials: MpdCredentialStore,
    private val invalidateCredential: suspend (ServerProfile) -> Unit = {},
) {
    constructor(dataStore: DataStore<Preferences>) : this(dataStore, NoMpdCredentials)

    val settings = dataStore.data.map(::decode).catch { error ->
        if (error is IOException) emit(ServerSettings(readFailed = true)) else throw error
    }

    suspend fun save(server: ServerProfile) = saveProfile(server, PasswordEdit.Keep, activate = false)

    suspend fun saveAndSelect(server: ServerProfile, password: PasswordEdit = PasswordEdit.Keep) =
        saveProfile(server, password, activate = true)

    private suspend fun saveProfile(server: ServerProfile, password: PasswordEdit, activate: Boolean) {
        require(server.isValid())
        profileMutationLock.withLock {
            val current = decode(dataStore.data.first())
            if (current.servers.hasDuplicateName(server.name, server.id)) throw DuplicateServerNameException()
            val servers = current.servers.toMutableList()
            val index = servers.indexOfFirst { it.id == server.id }
            val old = servers.getOrNull(index)
            val updated = when (password) {
                PasswordEdit.Keep -> keepCredential(server, old)
                PasswordEdit.Remove -> server.copy(credentialRevision = null)
                is PasswordEdit.Replace -> server.copy(credentialRevision = UUID.randomUUID().toString()).also {
                    if (!validMpdPassword(password.password)) throw CredentialException(CredentialFailure.INVALID)
                    credentials.put(it, password.password)
                }
            }
            if (index >= 0) servers[index] = updated else servers.add(updated)
            currentCoroutineContext().ensureActive()
            // Once the commit begins, finish its session invalidation even if the editor goes away.
            // An ambiguous write leaves both revisions intact for the next confirmed settings read.
            withContext(NonCancellable) {
                write { it.copy(servers = servers, selectedId = if (activate) server.id else it.selectedId) }
                if (old != null && old.credentialRevision != updated.credentialRevision) retire(old)
            }
        }
    }

    internal suspend fun testProfile(server: ServerProfile, original: ServerProfile?): ServerProfile {
        val current = if (original == null) null else settings.first().let { snapshot ->
            if (snapshot.readFailed) throw CredentialException(CredentialFailure.UNREADABLE)
            snapshot.servers.find { it.id == original.id }?.also {
                if (it.credentialRevision != original.credentialRevision || !it.sameControlEndpoint(original)) {
                    throw CredentialException(CredentialFailure.REENTRY)
                }
            } ?: throw CredentialException(CredentialFailure.REENTRY)
        }
        return keepCredential(server, current)
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

    suspend fun delete(id: String) = profileMutationLock.withLock {
        val old = decode(dataStore.data.first()).servers.find { it.id == id }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            write { current ->
                current.copy(servers = current.servers.filterNot { it.id == id },
                    selectedId = current.selectedId?.takeUnless { it == id })
            }
            if (old != null) retire(old)
        }
    }

    internal suspend fun pruneCredentials() = profileMutationLock.withLock {
        val current = settings.first()
        if (!current.readFailed && !current.recovered) {
            cleanUp { credentials.prune(current.servers.mapNotNull { it.credentialRevision }.toSet()) }
        }
    }

    private suspend fun retire(old: ServerProfile) {
        invalidateCredential(old)
        old.credentialRevision?.let { revision -> cleanUp { credentials.remove(revision) } }
    }

    private suspend inline fun cleanUp(operation: () -> Unit) {
        // An obsolete revision is unreachable after commit. Retry cleanup on the next process start.
        try { operation() } catch (_: IOException) { }
    }

    private suspend fun change(transform: (ServerSettings) -> ServerSettings) = profileMutationLock.withLock {
        write(transform)
    }

    private suspend fun write(transform: (ServerSettings) -> ServerSettings) {
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
