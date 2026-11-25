package com.github.jschbe.simplempdplayer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Immutable files and keys per committed revision. The settings reference is the commit point. */
internal class AndroidMpdCredentials(context: Context) : MpdCredentialStore {
    private val directory by lazy { File(context.noBackupFilesDir, "mpd_credentials") }
    private val readers = CredentialReadWorker()

    override suspend fun resolve(profile: ServerProfile): String? {
        val revision = profile.credentialRevision ?: return null
        return readers.read { storage(CredentialFailure.UNREADABLE) { read(profile, revision) } }
    }

    private fun read(profile: ServerProfile, revision: String): String {
        val record = try {
            recordFile(revision).openRead().use { input ->
                val buffer = ByteArray(1 + IV_BYTES + MAX_MPD_PASSWORD_BYTES + TAG_BYTES + 1)
                var offset = 0
                while (offset < buffer.size) {
                    val count = input.read(buffer, offset, buffer.size - offset)
                    if (count == -1) break
                    offset += count
                }
                buffer.copyOf(offset)
            }
        } catch (_: FileNotFoundException) {
            throw CredentialException(CredentialFailure.MISSING)
        }
        if (record.size !in (1 + IV_BYTES + TAG_BYTES + 1)..(1 + IV_BYTES + MAX_MPD_PASSWORD_BYTES + TAG_BYTES) ||
            record[0] != FORMAT
        ) throw CredentialException(CredentialFailure.UNREADABLE)
        val key = keyStore().getKey(alias(revision), null) as? SecretKey
            ?: throw CredentialException(CredentialFailure.MISSING)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, record, 1, IV_BYTES))
        cipher.updateAAD(binding(profile, revision))
        val bytes = cipher.doFinal(record, 1 + IV_BYTES, record.size - 1 - IV_BYTES)
        return try {
            val password = bytes.toString(Charsets.UTF_8)
            if (!validMpdPassword(password)) throw CredentialException(CredentialFailure.UNREADABLE)
            password
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun put(profile: ServerProfile, password: String) = withContext(Dispatchers.IO) {
        if (!validMpdPassword(password)) throw CredentialException(CredentialFailure.INVALID)
        storage(CredentialFailure.SAVE) {
            val revision = requireNotNull(profile.credentialRevision)
            val file = recordFile(revision)
            val alias = alias(revision)
            if (keyStore().containsAlias(alias) || file.baseFile.exists()) throw IOException()
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(binding(profile, revision))
            val bytes = password.toByteArray(Charsets.UTF_8)
            val ciphertext = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
            val iv = cipher.iv
            if (iv.size != IV_BYTES) throw IOException()
            val output = file.startWrite()
            try {
                output.write(FORMAT.toInt())
                output.write(iv)
                output.write(ciphertext)
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            // AtomicFile can log a failed rename without throwing. Verify the staged record
            // before allowing a settings commit to reference it.
            if (read(profile, revision) != password) throw IOException()
        }
    }

    override suspend fun remove(revision: String) = withContext(Dispatchers.IO) {
        storage(CredentialFailure.UNREADABLE) {
            try {
                keyStore().deleteEntry(alias(revision))
            } finally {
                recordFile(revision).delete()
            }
        }
    }

    override suspend fun prune(referenced: Set<String>) = withContext(Dispatchers.IO) {
        storage(CredentialFailure.UNREADABLE) {
            val keys = keyStore()
            val keyRevisions = keys.aliases().toList().mapNotNull {
                it.takeIf { name -> name.startsWith(ALIAS_PREFIX) }?.removePrefix(ALIAS_PREFIX)
            }
            val fileRevisions = directory.list().orEmpty().map { it.substringBefore('.') }
            for (revision in (keyRevisions + fileRevisions).toSet()) {
                if (isCanonicalUuid(revision) && revision !in referenced) {
                    keys.deleteEntry(alias(revision))
                    recordFile(revision).delete()
                }
            }
        }
    }

    private fun recordFile(revision: String): AtomicFile {
        require(isCanonicalUuid(revision))
        return AtomicFile(File(directory, revision))
    }

    private fun alias(revision: String) = ALIAS_PREFIX + revision

    private fun binding(profile: ServerProfile, revision: String) =
        "mpd-password-v1\u0000${profile.id}\u0000${profile.controlHost}\u0000${profile.mpdPort}\u0000$revision"
            .toByteArray(Charsets.UTF_8)

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private inline fun <T> storage(failure: CredentialFailure, block: () -> T): T = try {
        block()
    } catch (error: CredentialException) {
        // Verification during staging is part of Save even if a staged key or file disappeared.
        throw if (failure == CredentialFailure.SAVE) CredentialException(failure) else error
    } catch (_: IOException) {
        throw CredentialException(failure)
    } catch (_: GeneralSecurityException) {
        throw CredentialException(failure)
    } catch (_: ProviderException) {
        throw CredentialException(failure)
    } catch (_: SecurityException) {
        throw CredentialException(failure)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "mpd-password-v1-"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val FORMAT: Byte = 1
    }
}
