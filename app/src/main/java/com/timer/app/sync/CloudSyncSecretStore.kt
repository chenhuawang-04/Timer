package com.timer.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class CloudSyncSecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { appContext.preferencesDataStoreFile("cloud_sync_secrets.preferences_pb") }
    )

    val credentialState: Flow<CloudSyncCredentialSnapshot> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            CloudSyncCredentialSnapshot(hasToken = !prefs[Keys.encryptedAccessToken].isNullOrBlank())
        }

    suspend fun readAccessToken(): String? {
        val encoded = store.data
            .map { it[Keys.encryptedAccessToken] }
            .catch { error ->
                if (error is IOException) emit(null) else throw error
            }
            .map { token ->
                token?.takeIf(String::isNotBlank)
            }
        return decrypt(encoded.first())
    }

    suspend fun hasUsableToken(): Boolean = !readAccessToken().isNullOrBlank()

    suspend fun updateAccessToken(token: String?) {
        store.edit { prefs ->
            val normalized = token?.trim().orEmpty()
            if (normalized.isBlank()) {
                prefs.remove(Keys.encryptedAccessToken)
            } else {
                prefs[Keys.encryptedAccessToken] = encrypt(normalized)
            }
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return base64(cipher.iv) + DELIMITER + base64(encrypted)
    }

    private fun decrypt(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            val parts = payload.split(DELIMITER, limit = 2)
            require(parts.size == 2) { "Malformed encrypted payload" }
            val iv = Base64.getDecoder().decode(parts[0])
            val encrypted = Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(encrypted)
            plainBytes.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(parameterSpec)
        return generator.generateKey()
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private object Keys {
        val encryptedAccessToken = stringPreferencesKey("encrypted_access_token")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "timer.cloud.sync.token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DELIMITER = ":"
    }
}
