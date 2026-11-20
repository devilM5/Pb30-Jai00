package com.example.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshConnectionProfile

/**
 * Stores SSH connection details (Host, Port, Username, AuthMethod) locally using
 * Jetpack Security EncryptedSharedPreferences (AES-256-SIV keys, AES-256-GCM values).
 *
 * Security guarantee: Passwords and passphrases are NEVER persisted to disk.
 */
class SecureSshCredentialStore internal constructor(
    private val context: Context,
    private val prefsOverride: SharedPreferences? = null
) {
    constructor(context: Context) : this(context, null)

    @Volatile
    private var encryptedPrefsActive: Boolean = prefsOverride != null

    private val encryptedPrefs: SharedPreferences? by lazy {
        if (prefsOverride != null) {
            encryptedPrefsActive = true
            return@lazy prefsOverride
        }
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encryptedPrefsActive = true
            prefs
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable (${e.javaClass.simpleName})")
            encryptedPrefsActive = false
            null
        }
    }

    fun isEncryptedStorageReady(): Boolean {
        encryptedPrefs
        return encryptedPrefsActive
    }

    /**
     * Persists only non-password SSH connection details (host, port, username, authMethod).
     * Never writes passwords, private keys, or passphrases to storage.
     */
    fun saveConnectionDetails(
        host: String,
        port: Int,
        username: String,
        authMethod: SshAuthMethod = SshAuthMethod.PASSWORD
    ): Boolean {
        val prefs = encryptedPrefs ?: return false
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port.coerceIn(1, 65535))
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_AUTH_METHOD, authMethod.name)
            // Guarantee passwords/passphrases are never persisted
            .remove(KEY_LEGACY_PASSWORD)
            .remove(KEY_LEGACY_PRIVATE_KEY)
            .remove(KEY_LEGACY_PASSPHRASE)
            .apply()
        return true
    }

    fun saveProfile(profile: SshConnectionProfile, persistConnectionDetails: Boolean): Boolean {
        if (!persistConnectionDetails) {
            clearAll()
            return true
        }
        return saveConnectionDetails(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMethod = profile.authMethod
        )
    }

    fun loadProfile(): SshConnectionProfile {
        val prefs = encryptedPrefs ?: return SshConnectionProfile()
        val host = prefs.getString(KEY_HOST, "").orEmpty()
        val port = prefs.getInt(KEY_PORT, 22).coerceIn(1, 65535)
        val username = prefs.getString(KEY_USERNAME, "").orEmpty()
        val authMethod = SshAuthMethod.fromRaw(
            prefs.getString(KEY_AUTH_METHOD, SshAuthMethod.PASSWORD.name).orEmpty()
        )
        return SshConnectionProfile(
            host = host,
            port = port,
            username = username,
            authMethod = authMethod,
            // Passwords & passphrases are never persisted on disk
            password = "",
            privateKeyPem = "",
            keyPassphrase = "",
            persistSecretsEncrypted = host.isNotBlank() || username.isNotBlank()
        )
    }

    fun clearAll() {
        encryptedPrefs?.edit()?.clear()?.apply()
    }

    private companion object {
        const val TAG = "SecureSshStore"
        const val PREFS_NAME = "friday_remote_server_encrypted_prefs"

        const val KEY_HOST = "ssh_host"
        const val KEY_PORT = "ssh_port"
        const val KEY_USERNAME = "ssh_username"
        const val KEY_AUTH_METHOD = "ssh_auth_method"

        const val KEY_LEGACY_PASSWORD = "ssh_enc_password"
        const val KEY_LEGACY_PRIVATE_KEY = "ssh_enc_private_key"
        const val KEY_LEGACY_PASSPHRASE = "ssh_enc_passphrase"
    }
}
