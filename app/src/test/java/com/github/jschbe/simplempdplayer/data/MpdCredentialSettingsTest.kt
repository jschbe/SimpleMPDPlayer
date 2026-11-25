package com.github.jschbe.simplempdplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.jschbe.simplempdplayer.timer.SleepTimerStore
import com.github.jschbe.simplempdplayer.timer.TimerRecord
import com.github.jschbe.simplempdplayer.timer.TimerSettings
import com.github.jschbe.simplempdplayer.timer.TimerStatus
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MpdCredentialSettingsTest {
    private val one = ServerInput("First", "fixture.invalid").toProfile()!!
    private val two = ServerInput("Second", "localhost").toProfile()!!
    private val firstPassword = " synthetic \"old\" "
    private val nextPassword = " synthetic \\new "

    @Test fun legacyRowsAndRestoredReferencesPreserveIdentityAndNeverBecomeGuests() = runTest {
        val backing = SettingsData()
        val secrets = Credentials()
        val store = ServerSettingsStore(backing, secrets)
        store.saveAndSelect(one, PasswordEdit.Replace(firstPassword))
        store.save(two)
        store.reorder(listOf(two.id, one.id))
        store.setTheme(ThemeMode.DARK)
        store.setTrackInfoExpanded(false)
        val snapshot = store.settings.first()
        val restored = ServerSettingsStore(backing)
        assertEquals(snapshot, restored.settings.first())
        assertEquals(listOf(two.id, one.id), snapshot.servers.map { it.id })
        assertEquals(one.id, snapshot.selectedId)
        assertNotNull(snapshot.servers.last().credentialRevision)
        assertNull(snapshot.servers.first().credentialRevision)
        val rows = backing.data.value[stringPreferencesKey("server_profiles_v1")]!!.lines()
        assertEquals(5, rows.first().split('\t').size)
        assertEquals(6, rows.last().split('\t').size)
        assertFalse(rows.any { firstPassword in it })
        assertNull(NoMpdCredentials.resolve(snapshot.servers.first()))
        assertEquals(CredentialFailure.MISSING, failure { NoMpdCredentials.resolve(snapshot.servers.last()) })
        assertFalse(snapshot.toString().contains(firstPassword))
    }

    @Test fun keepRenameReorderAndStreamChangeRetainCredentialButHostAndPortNeedChoice() = runTest {
        val backing = SettingsData()
        val secrets = Credentials()
        val invalidated = mutableListOf<String>()
        val store = ServerSettingsStore(backing, secrets) { invalidated += it.id }
        store.saveAndSelect(one, PasswordEdit.Replace(firstPassword))
        store.saveAndSelect(two, PasswordEdit.Replace(nextPassword))
        val original = store.settings.first().servers.first()
        val renamed = one.copy(name = "Renamed", streamPort = 8999, host = "FIXTURE.invalid")
        store.saveAndSelect(renamed)
        store.reorder(listOf(two.id, one.id))
        val current = store.settings.first().servers.last()
        assertEquals(original.credentialRevision, current.credentialRevision)
        assertTrue(secrets.resolve(current) == firstPassword)
        assertTrue(invalidated.isEmpty())
        for (candidate in listOf(current.copy(host = "other.invalid"), current.copy(mpdPort = 6601))) {
            assertEquals(CredentialFailure.REENTRY, failure { store.testProfile(candidate, current) })
            assertEquals(CredentialFailure.REENTRY, failure { store.saveAndSelect(candidate) })
            assertEquals(current, store.settings.first().servers.last())
        }
        assertEquals(current, store.testProfile(renamed, current))
        val replacementEndpoint = current.copy(mpdPort = 6601)
        store.saveAndSelect(replacementEndpoint, PasswordEdit.Replace(nextPassword))
        val changed = store.settings.first().servers.last()
        assertNotEquals(original.credentialRevision, changed.credentialRevision)
        assertEquals(CredentialFailure.REENTRY, failure { store.testProfile(current, current) })
        assertTrue(secrets.resolve(changed) == nextPassword)
        assertEquals(CredentialFailure.MISSING, failure { secrets.resolve(original) })
        store.saveAndSelect(changed, PasswordEdit.Remove)
        assertNull(store.settings.first().servers.last().credentialRevision)
        store.delete(one.id)
        assertEquals(listOf(one.id, one.id, one.id), invalidated)
        assertTrue(secrets.resolve(store.settings.first().servers.single()) == nextPassword)
        store.delete(two.id)
        assertTrue(secrets.records.isEmpty())
    }

    @Test fun interruptedStagingAndProfileWritesKeepReferencedRevisionAndPruneOrphans() = runTest {
        val backing = SettingsData()
        val secrets = Credentials()
        val invalidated = mutableListOf<ServerProfile>()
        val store = ServerSettingsStore(backing, secrets) { invalidated.add(it) }
        store.saveAndSelect(one, PasswordEdit.Replace(firstPassword))
        val original = store.settings.first().servers.single()
        secrets.failStage = true
        assertEquals(CredentialFailure.SAVE, failure { store.saveAndSelect(one, PasswordEdit.Replace(nextPassword)) })
        assertEquals(original, store.settings.first().servers.single())
        assertTrue(secrets.resolve(original) == firstPassword)
        secrets.failStage = false
        backing.failWriteBeforeCommit = true
        assertTrue(runCatching { store.saveAndSelect(one, PasswordEdit.Replace(nextPassword)) }.exceptionOrNull() is IOException)
        assertEquals(original, store.settings.first().servers.single())
        assertEquals(3, secrets.records.size)
        assertTrue(invalidated.isEmpty())
        backing.failWriteBeforeCommit = false
        assertTrue(store.tryPruneCredentials { false })
        assertEquals(setOf(original.credentialRevision), secrets.records.keys)
        backing.failWriteAfterCommit = true
        assertTrue(runCatching { store.saveAndSelect(one, PasswordEdit.Replace(nextPassword)) }.exceptionOrNull() is IOException)
        val replacement = store.settings.first().servers.single()
        assertNotEquals(original.credentialRevision, replacement.credentialRevision)
        assertTrue(secrets.resolve(replacement) == nextPassword)
        assertTrue(secrets.resolve(original) == firstPassword)
        backing.failWriteAfterCommit = false
        assertTrue(store.tryPruneCredentials { false })
        assertEquals(setOf(replacement.credentialRevision), secrets.records.keys)
    }

    @Test fun optionalCleanupDefersForPendingTimerOrBusyMutationLock() = runTest {
        val secrets = Credentials()
        val store = ServerSettingsStore(SettingsData(), secrets)
        val timer = SleepTimerStore(SettingsData())
        store.saveAndSelect(one, PasswordEdit.Replace(firstPassword))
        val referenced = store.settings.first().servers.single().credentialRevision
        val orphan = UUID.randomUUID().toString()
        secrets.records[orphan] = nextPassword
        val record = TimerRecord(UUID.randomUUID().toString(), one, boot = 1, started = 100, deadline = 200)
        for (status in listOf(TimerStatus.WAITING, TimerStatus.ARMING, TimerStatus.ACTIVE, TimerStatus.DELIVERING)) {
            timer.write(TimerSettings(record = record.copy(
                status = status, deadline = record.deadline.takeUnless { status == TimerStatus.WAITING })))
            assertFalse(store.tryPruneCredentials(timer::hasPendingTimer))
            assertEquals(setOf(referenced, orphan), secrets.records.keys)
        }
        timer.write(TimerSettings())
        profileMutationLock.withLock {
            assertFalse(store.tryPruneCredentials { error("Optional cleanup waited on the mutation lock") })
        }
        assertTrue(store.tryPruneCredentials(timer::hasPendingTimer))
        assertEquals(setOf(referenced), secrets.records.keys)
    }

    private suspend fun failure(block: suspend () -> Unit): CredentialFailure {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is CredentialException)
        return (error as CredentialException).failure
    }

    private class SettingsData : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var failWriteBeforeCommit = false
        var failWriteAfterCommit = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(data.value)
            if (failWriteBeforeCommit) throw IOException()
            data.value = updated
            if (failWriteAfterCommit) throw IOException()
            return updated
        }
    }

    private class Credentials : MpdCredentialStore {
        val records = mutableMapOf<String, String>()
        var failStage = false
        override suspend fun resolve(profile: ServerProfile): String? = profile.credentialRevision?.let {
            records[it] ?: throw CredentialException(CredentialFailure.MISSING)
        }
        override suspend fun put(profile: ServerProfile, password: String) {
            records[requireNotNull(profile.credentialRevision)] = password
            if (failStage) throw CredentialException(CredentialFailure.SAVE)
        }
        override suspend fun remove(revision: String) { records.remove(revision) }
        override suspend fun prune(referenced: Set<String>) { records.keys.retainAll(referenced) }
    }
}
