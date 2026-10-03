package moe.ouom.neriplayer.data.sync.store.webdav

import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient

import android.content.Context
import moe.ouom.neriplayer.data.sync.store.secure.EncryptedSyncPreferences
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.config.WebDavSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.sync.DEFAULT_SYNC_AUTO_ENABLED
import moe.ouom.neriplayer.data.sync.store.state.hasNonBlankSyncCredential
import moe.ouom.neriplayer.data.sync.store.state.SyncCompletionTimeStore
import com.google.gson.Gson
import java.io.IOException
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcJournal
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcState

class WebDavStorage internal constructor(private val encryptedPrefs: SharedPreferences) {
    constructor(context: Context) : this(EncryptedSyncPreferences.open(context, PREFS_NAME, "NERI-WebDavStorage"))

    private val completionTime = SyncCompletionTimeStore(encryptedPrefs, completionTimeChanges, configurationLock, ::getLastSyncTime)

    companion object {
        private const val PREFS_NAME = "webdav_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_BASE_PATH = "base_path"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_LAST_REMOTE_FINGERPRINT = "last_remote_fingerprint"
        private const val KEY_ARCHIVE_MAINTENANCE = "archive_maintenance_"
        private val completionTimeChanges = MutableStateFlow(0L)
        private val configurationLock = Any()
    }

    fun saveConfiguration(
        serverUrl: String,
        username: String,
        password: String,
        basePath: String
    ) {
        completionTime.editConfiguration {
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
        completionTime.notifyChanged()
    }

    fun getLastSyncTime(): Long = encryptedPrefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun saveLastCompletedSyncTime(timestamp: Long) = completionTime.save(timestamp)

    /** 配置保存或清除后，旧会话不再写入确认元数据 */
    fun captureSyncMetadataGuard(): (() -> Unit) -> Boolean = completionTime.captureConfigurationGuard()

    fun getLastCompletedSyncTime(): Long = completionTime.read()

    fun observeLastCompletedSyncTime(): Flow<Long> = completionTime.observe()

    fun setAutoSyncEnabled(enabled: Boolean) {
        encryptedPrefs.edit { putBoolean(KEY_AUTO_SYNC_ENABLED, enabled) }
    }

    fun isAutoSyncEnabled(): Boolean =
        encryptedPrefs.getBoolean(KEY_AUTO_SYNC_ENABLED, DEFAULT_SYNC_AUTO_ENABLED)

    fun saveLastRemoteFingerprint(fingerprint: String) {
        encryptedPrefs.edit { putString(KEY_LAST_REMOTE_FINGERPRINT, fingerprint) }
    }

    fun getLastRemoteFingerprint(): String? =
        encryptedPrefs.getString(KEY_LAST_REMOTE_FINGERPRINT, null)

    fun archiveLockSupported(scope: String): Boolean = encryptedPrefs.getBoolean(maintenanceKey(scope, "lock"), false)

    fun rememberArchiveLock(scope: String) {
        val editor = encryptedPrefs.edit()
        editor.putBoolean(maintenanceKey(scope, "lock"), true)
        if (!editor.commit()) {
            throw IOException("Failed to persist WebDAV archive lock capability")
        }
    }

    fun archiveGcState(scope: String): WebDavArchiveGcState {
        val raw = encryptedPrefs.getString(maintenanceKey(scope, "gc"), null) ?: return WebDavArchiveGcState()
        if (raw.length > 512 * 1024) return WebDavArchiveGcState()
        return runCatching { Gson().fromJson(raw, WebDavArchiveGcState::class.java) }
            .getOrNull()?.takeIf { runCatching { WebDavArchiveGcJournal.valid(it) }.getOrDefault(false) } ?: WebDavArchiveGcState()
    }

    fun saveArchiveGcState(scope: String, state: WebDavArchiveGcState) {
        require(WebDavArchiveGcJournal.valid(state)) { "Invalid WebDAV archive maintenance state" }
        val editor = encryptedPrefs.edit()
        editor.putString(maintenanceKey(scope, "gc"), Gson().toJson(state))
        if (!editor.commit()) {
            throw IOException("Failed to persist WebDAV archive maintenance state")
        }
    }

    private fun maintenanceKey(scope: String, kind: String): String = KEY_ARCHIVE_MAINTENANCE + scope + "_" + kind

    fun isConfigured(): Boolean {
        if (!hasNonBlankSyncCredential(getServerUrl()) ||
            !hasNonBlankSyncCredential(getUsername()) ||
            !hasNonBlankSyncCredential(getPassword())) return false
        return try {
            getRemoteFileUrl() != null
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun clearAll() {
        completionTime.editConfiguration { clear() }
        completionTime.notifyChanged()
    }

    fun snapshot(): WebDavSyncConfigSnapshot {
        return WebDavSyncConfigSnapshot(
            serverUrl = getServerUrl().orEmpty(),
            basePath = getBasePath(),
            username = getUsername().orEmpty(),
            password = getPassword().orEmpty(),
            autoSyncEnabled = isAutoSyncEnabled()
        )
    }

    fun restore(snapshot: WebDavSyncConfigSnapshot) {
        completionTime.editConfiguration {
            remove(KEY_SERVER_URL)
            remove(KEY_BASE_PATH)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_AUTO_SYNC_ENABLED)

            val normalizedServerUrl = normalizeServerUrl(snapshot.serverUrl)
            val normalizedBasePath = normalizeBasePath(snapshot.basePath)
            if (normalizedServerUrl.isNotBlank()) putString(KEY_SERVER_URL, normalizedServerUrl)
            if (normalizedBasePath.isNotBlank()) putString(KEY_BASE_PATH, normalizedBasePath)
            if (snapshot.username.isNotBlank()) putString(KEY_USERNAME, snapshot.username)
            if (snapshot.password.isNotBlank()) putString(KEY_PASSWORD, snapshot.password)
            putBoolean(KEY_AUTO_SYNC_ENABLED, snapshot.autoSyncEnabled)
        }
    }

    private fun normalizeServerUrl(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    private fun normalizeBasePath(basePath: String): String = basePath.trim().trim('/')

}
