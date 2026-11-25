package com.github.jschbe.simplempdplayer.data

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.PlayerApplication
import com.github.jschbe.simplempdplayer.R
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AndroidMpdCredentialsTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PlayerApplication
    private val synthetic = "  fictitious \"音\" \\ password   "

    @Test fun platformKeystoreAndAtomicNoBackupRecordsEnforceBindingAndHandleDamage() = runBlocking(Dispatchers.IO) {
        val first = ServerInput("Keystore fixture", "LOCALHOST").toProfile()!!
            .copy(credentialRevision = UUID.randomUUID().toString())
        val second = first.copy(id = UUID.randomUUID().toString(), credentialRevision = UUID.randomUUID().toString())
        val store = AndroidMpdCredentials(app)
        profileMutationLock.lock()
        try {
            store.put(first, synthetic)
            store.put(second, synthetic)
            val reopened = AndroidMpdCredentials(app)
            assertTrue(reopened.resolve(first.copy(name = "Renamed", host = "localhost", streamPort = 2)) == synthetic)
            assertTrue(reopened.resolve(second) == synthetic)
            val directory = File(app.noBackupFilesDir, "mpd_credentials")
            val firstFile = File(directory, first.credentialRevision!!)
            val secondFile = File(directory, second.credentialRevision!!)
            assertEquals(app.noBackupFilesDir.canonicalFile, firstFile.canonicalFile.parentFile?.parentFile)
            val record1 = firstFile.readBytes()
            val record2 = secondFile.readBytes()
            assertFalse(record1.copyOfRange(1, 13).contentEquals(record2.copyOfRange(1, 13)))
            assertFalse(record1.copyOfRange(13, record1.size).contentEquals(record2.copyOfRange(13, record2.size)))
            assertFalse(record1.toString(Charsets.UTF_8).contains(synthetic))
            assertFalse(record2.toString(Charsets.UTF_8).contains(synthetic))
            for (wrong in listOf(first.copy(id = second.id), first.copy(host = "127.0.0.1"), first.copy(mpdPort = 1))) {
                assertEquals(CredentialFailure.UNREADABLE, failure { reopened.resolve(wrong) })
            }
            firstFile.writeBytes(record1.copyOf(5))
            assertEquals(CredentialFailure.UNREADABLE, failure { reopened.resolve(first) })
            firstFile.writeBytes(record1)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("mpd-password-v1-${first.credentialRevision}")
            assertEquals(CredentialFailure.MISSING, failure { reopened.resolve(first) })
            assertTrue(reopened.resolve(second) == synthetic)
        } finally {
            try {
                store.remove(first.credentialRevision!!)
                store.remove(second.credentialRevision!!)
            } finally {
                profileMutationLock.unlock()
            }
        }
    }

    @Test fun simulatedRestoreRetainsProtectedProfileWithoutAKeyOrCiphertext() = runBlocking {
        val candidate = ServerInput("Restored fixture", "127.0.0.1", "1").toProfile()!!
        val selected = app.serverSettings.settings.first().selectedId
        try {
            app.serverSettings.saveAndSelect(candidate, PasswordEdit.Replace(synthetic))
            val saved = app.serverSettings.settings.first().servers.first { it.id == candidate.id }
            val revision = requireNotNull(saved.credentialRevision)
            app.credentials.remove(revision)
            val restored = ServerSettingsStore(app.serverDataStore).settings.first()
            assertEquals(saved, restored.servers.first { it.id == saved.id })
            assertEquals(saved.id, restored.selectedId)
            val references = app.serverDataStore.data.first().asMap().values.joinToString()
            assertFalse(references.contains(synthetic))
            assertEquals(CredentialFailure.MISSING, failure { app.credentials.resolve(saved) })
            assertEquals(com.github.jschbe.simplempdplayer.mpd.ConnectionError.CREDENTIAL_MISSING,
                app.repository.testConnection(saved))
        } finally {
            app.serverSettings.delete(candidate.id)
            if (selected != null) app.serverSettings.select(selected)
        }
    }

    @Test fun backupConfigurationKeepsOrdinarySettingsAndExcludesTimerFromBothModes() {
        assertTrue(app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        val excluded = mutableSetOf<String>()
        for (id in listOf(R.xml.data_extraction_rules, R.xml.backup_rules)) {
            app.resources.getXml(id).use { xml ->
                var section: String? = null
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType != XmlPullParser.START_TAG) continue
                    when (xml.name) {
                        "cloud-backup", "device-transfer", "full-backup-content" -> section = xml.name
                        "exclude" -> {
                            assertEquals("file", xml.getAttributeValue(null, "domain"))
                            assertEquals("datastore/sleep_timer.preferences_pb", xml.getAttributeValue(null, "path"))
                            excluded.add(requireNotNull(section))
                        }
                        "include", "cross-platform-transfer" -> fail("Unexpected restriction or transfer partner")
                    }
                    if (xml.name == "cloud-backup") {
                        assertEquals("true", xml.getAttributeValue(null, "disableIfNoEncryptionCapabilities"))
                    }
                }
            }
        }
        assertEquals(setOf("cloud-backup", "device-transfer", "full-backup-content"), excluded)
    }

    private suspend fun failure(block: suspend () -> Unit): CredentialFailure {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is CredentialException)
        return (error as CredentialException).failure
    }
}
