package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.sync.DEFAULT_SYNC_AUTO_ENABLED
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistoryUpdateMode

internal class GitHubSyncConfigurationStore(private val encryptedPrefs: SharedPreferences) {
    private val completionTime = SyncCompletionTimeStore(encryptedPrefs, completionTimeChanges, configurationLock, ::getLastSyncTime)

    private companion object {
        val completionTimeChanges = MutableStateFlow(0L)
        val configurationLock = Any()
    }

    fun saveToken(token: String) {
        completionTime.editConfiguration { putString(KEY_GITHUB_TOKEN, token) }
    }

    fun getToken(): String? {
        return encryptedPrefs.getString(KEY_GITHUB_TOKEN, null)
    }

    fun clearToken() {
        completionTime.editConfiguration { remove(KEY_GITHUB_TOKEN) }
    }

    fun saveRepository(owner: String, name: String) {
        completionTime.editConfiguration {
            putString(KEY_REPO_OWNER, owner)
                .putString(KEY_REPO_NAME, name)
        }
    }

    fun getRepoOwner(): String? {
        return encryptedPrefs.getString(KEY_REPO_OWNER, null)
    }

    fun getRepoName(): String? {
        return encryptedPrefs.getString(KEY_REPO_NAME, null)
    }

    fun saveLastSyncTime(timestamp: Long) {
        encryptedPrefs.edit { putLong(KEY_LAST_SYNC_TIME, timestamp) }
        completionTime.notifyChanged()
    }

    fun getLastSyncTime(): Long {
        return encryptedPrefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun saveLastCompletedSyncTime(timestamp: Long) = completionTime.save(timestamp)

    fun captureSyncMetadataGuard(): (() -> Unit) -> Boolean = completionTime.captureConfigurationGuard()

    fun getLastCompletedSyncTime(): Long = completionTime.read()

    fun observeLastCompletedSyncTime(): Flow<Long> = completionTime.observe()

    fun setAutoSyncEnabled(enabled: Boolean) {
        encryptedPrefs.edit { putBoolean(KEY_AUTO_SYNC_ENABLED, enabled) }
    }

    fun isAutoSyncEnabled(): Boolean {
        return encryptedPrefs.getBoolean(KEY_AUTO_SYNC_ENABLED, DEFAULT_SYNC_AUTO_ENABLED)
    }

    fun saveLastRemoteSha(sha: String) {
        encryptedPrefs.edit { putString(KEY_LAST_REMOTE_SHA, sha) }
    }

    fun getLastRemoteSha(): String? {
        return encryptedPrefs.getString(KEY_LAST_REMOTE_SHA, null)
    }

    fun setPlayHistoryUpdateMode(mode: PlayHistoryUpdateMode) {
        encryptedPrefs.edit { putString(KEY_PLAY_HISTORY_UPDATE_MODE, mode.name) }
    }

    fun getPlayHistoryUpdateMode(): PlayHistoryUpdateMode {
        return PlayHistoryUpdateMode.fromStoredName(getLegacyPlayHistoryUpdateModeName())
            ?: PlayHistoryUpdateMode.IMMEDIATE
    }

    internal fun getLegacyPlayHistoryUpdateModeName(): String? {
        return encryptedPrefs.getString(KEY_PLAY_HISTORY_UPDATE_MODE, null)
    }

    fun isConfigured(): Boolean {
        return hasSyncCredential(getToken()) &&
            hasSyncCredential(getRepoOwner()) &&
            hasSyncCredential(getRepoName())
    }

    fun clearAll() {
        completionTime.editConfiguration {
            remove(KEY_GITHUB_TOKEN)
            remove(KEY_REPO_OWNER)
            remove(KEY_REPO_NAME)
            remove(KEY_LAST_SYNC_TIME)
            remove(KEY_LAST_REMOTE_SHA)
            remove(KEY_AUTO_SYNC_ENABLED)
            remove(KEY_TOKEN_WARNING_DISMISSED)
            remove(KEY_DATA_SAVER_MODE)
            completionTime.clear(this)
        }
        completionTime.notifyChanged()
    }

    fun setTokenWarningDismissed(dismissed: Boolean) {
        encryptedPrefs.edit { putBoolean(KEY_TOKEN_WARNING_DISMISSED, dismissed) }
    }

    fun isTokenWarningDismissed(): Boolean {
        return encryptedPrefs.getBoolean(KEY_TOKEN_WARNING_DISMISSED, false)
    }

    fun setDataSaverMode(enabled: Boolean) {
        encryptedPrefs.edit { putBoolean(KEY_DATA_SAVER_MODE, enabled) }
    }

    fun isDataSaverMode(): Boolean {
        return encryptedPrefs.getBoolean(KEY_DATA_SAVER_MODE, true)
    }

    fun snapshot(): GitHubSyncConfigSnapshot {
        return GitHubSyncConfigSnapshot(
            token = getToken().orEmpty(),
            repoOwner = getRepoOwner().orEmpty(),
            repoName = getRepoName().orEmpty(),
            autoSyncEnabled = isAutoSyncEnabled(),
            dataSaverMode = isDataSaverMode()
        )
    }

    fun restore(snapshot: GitHubSyncConfigSnapshot) {
        completionTime.editConfiguration {
            remove(KEY_GITHUB_TOKEN)
            remove(KEY_REPO_OWNER)
            remove(KEY_REPO_NAME)
            remove(KEY_AUTO_SYNC_ENABLED)
            remove(KEY_PLAY_HISTORY_UPDATE_MODE)
            remove(KEY_DATA_SAVER_MODE)

            if (snapshot.token.isNotBlank()) putString(KEY_GITHUB_TOKEN, snapshot.token)
            if (snapshot.repoOwner.isNotBlank()) putString(KEY_REPO_OWNER, snapshot.repoOwner)
            if (snapshot.repoName.isNotBlank()) putString(KEY_REPO_NAME, snapshot.repoName)
            putBoolean(KEY_AUTO_SYNC_ENABLED, snapshot.autoSyncEnabled)
            putBoolean(KEY_DATA_SAVER_MODE, snapshot.dataSaverMode)
        }
    }
}
