package moe.ouom.neriplayer.data.sync.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class WebDavStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "webdav_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_BASE_PATH = "base_path"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_LAST_REMOTE_FINGERPRINT = "last_remote_fingerprint"
    }

    fun saveConfiguration(
        serverUrl: String,
        username: String,
        password: String,
        basePath: String
    ) {
        encryptedPrefs.edit {
            putString(KEY_SERVER_URL, normalizeServerUrl(serverUrl))
            putString(KEY_BASE_PATH, normalizeBasePath(basePath))
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
        }
    }

    fun getServerUrl(): String? = encryptedPrefs.getString(KEY_SERVER_URL, null)

    fun getBasePath(): String = encryptedPrefs.getString(KEY_BASE_PATH, null).orEmpty()

    fun getUsername(): String? = encryptedPrefs.getString(KEY_USERNAME, null)

    fun getPassword(): String? = encryptedPrefs.getString(KEY_PASSWORD, null)

    fun getRemoteFileUrl(): String? {
        val serverUrl = getServerUrl()?.takeIf { it.isNotBlank() } ?: return null
        return WebDavApiClient.buildRemoteFileUrl(serverUrl, getBasePath())
    }

    fun saveLastSyncTime(timestamp: Long) {
        encryptedPrefs.edit { putLong(KEY_LAST_SYNC_TIME, timestamp) }
    }

    fun getLastSyncTime(): Long = encryptedPrefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun setAutoSyncEnabled(enabled: Boolean) {
        encryptedPrefs.edit { putBoolean(KEY_AUTO_SYNC_ENABLED, enabled) }
    }

    fun isAutoSyncEnabled(): Boolean = encryptedPrefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)

    fun saveLastRemoteFingerprint(fingerprint: String) {
        encryptedPrefs.edit { putString(KEY_LAST_REMOTE_FINGERPRINT, fingerprint) }
    }

    fun getLastRemoteFingerprint(): String? =
        encryptedPrefs.getString(KEY_LAST_REMOTE_FINGERPRINT, null)

    fun isConfigured(): Boolean {
        return !getServerUrl().isNullOrBlank() &&
            !getUsername().isNullOrBlank() &&
            !getPassword().isNullOrBlank()
    }

    fun clearAll() {
        encryptedPrefs.edit { clear() }
    }

    private fun normalizeServerUrl(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    private fun normalizeBasePath(basePath: String): String = basePath.trim().trim('/')

}
