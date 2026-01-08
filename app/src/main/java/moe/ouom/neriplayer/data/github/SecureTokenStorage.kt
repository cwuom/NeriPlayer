package moe.ouom.neriplayer.data.github

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.github/SecureTokenStorage
 * Created: 2025/1/7
 */

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * GitHub Token安全存储
 * 使用Android Keystore + EncryptedSharedPreferences加密存储
 */
class SecureTokenStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "github_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_LAST_REMOTE_SHA = "last_remote_sha"
        private const val KEY_PLAY_HISTORY_UPDATE_MODE = "play_history_update_mode"
        private const val KEY_DELETED_PLAYLIST_IDS = "deleted_playlist_ids"
        private const val KEY_TOKEN_WARNING_DISMISSED = "token_warning_dismissed"
        private const val KEY_DATA_SAVER_MODE = "data_saver_mode"
    }

    /** 播放历史更新模式 */
    enum class PlayHistoryUpdateMode {
        IMMEDIATE,  // 立即更新
        BATCHED     // 批量更新（每10分钟）
    }

    /** 保存GitHub Token */
    fun saveToken(token: String) {
        encryptedPrefs.edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

    /** 获取GitHub Token */
    fun getToken(): String? {
        return encryptedPrefs.getString(KEY_GITHUB_TOKEN, null)
    }

    /** 删除Token */
    fun clearToken() {
        encryptedPrefs.edit().remove(KEY_GITHUB_TOKEN).apply()
    }

    /** 保存仓库信息 */
    fun saveRepository(owner: String, name: String) {
        encryptedPrefs.edit()
            .putString(KEY_REPO_OWNER, owner)
            .putString(KEY_REPO_NAME, name)
            .apply()
    }

    /** 获取仓库Owner */
    fun getRepoOwner(): String? {
        return encryptedPrefs.getString(KEY_REPO_OWNER, null)
    }

    /** 获取仓库Name */
    fun getRepoName(): String? {
        return encryptedPrefs.getString(KEY_REPO_NAME, null)
    }

    /** 保存设备ID */
    fun saveDeviceId(deviceId: String) {
        encryptedPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    /** 获取设备ID */
    fun getDeviceId(): String? {
        return encryptedPrefs.getString(KEY_DEVICE_ID, null)
    }

    /** 保存最后同步时间 */
    fun saveLastSyncTime(timestamp: Long) {
        encryptedPrefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    /** 获取最后同步时间 */
    fun getLastSyncTime(): Long {
        return encryptedPrefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    /** 设置自动同步开关 */
    fun setAutoSyncEnabled(enabled: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
    }

    /** 获取自动同步开关状态 */
    fun isAutoSyncEnabled(): Boolean {
        return encryptedPrefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)
    }

    /** 保存上次同步的远程SHA */
    fun saveLastRemoteSha(sha: String) {
        encryptedPrefs.edit().putString(KEY_LAST_REMOTE_SHA, sha).apply()
    }

    /** 获取上次同步的远程SHA */
    fun getLastRemoteSha(): String? {
        return encryptedPrefs.getString(KEY_LAST_REMOTE_SHA, null)
    }

    /** 设置播放历史更新模式 */
    fun setPlayHistoryUpdateMode(mode: PlayHistoryUpdateMode) {
        encryptedPrefs.edit().putString(KEY_PLAY_HISTORY_UPDATE_MODE, mode.name).apply()
    }

    /** 获取播放历史更新模式 */
    fun getPlayHistoryUpdateMode(): PlayHistoryUpdateMode {
        val modeName = encryptedPrefs.getString(KEY_PLAY_HISTORY_UPDATE_MODE, PlayHistoryUpdateMode.IMMEDIATE.name)
        return try {
            PlayHistoryUpdateMode.valueOf(modeName ?: PlayHistoryUpdateMode.IMMEDIATE.name)
        } catch (e: Exception) {
            PlayHistoryUpdateMode.IMMEDIATE
        }
    }

    /** 检查是否已配置 */
    fun isConfigured(): Boolean {
        return !getToken().isNullOrEmpty() &&
               !getRepoOwner().isNullOrEmpty() &&
               !getRepoName().isNullOrEmpty()
    }

    /** 清除所有配置 */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }

    /** 添加已删除的歌单ID */
    fun addDeletedPlaylistId(playlistId: Long) {
        val current = getDeletedPlaylistIds().toMutableSet()
        current.add(playlistId)
        encryptedPrefs.edit().putString(KEY_DELETED_PLAYLIST_IDS, current.joinToString(",")).apply()
    }

    /** 获取所有已删除的歌单ID */
    fun getDeletedPlaylistIds(): Set<Long> {
        val idsString = encryptedPrefs.getString(KEY_DELETED_PLAYLIST_IDS, "") ?: ""
        return if (idsString.isEmpty()) {
            emptySet()
        } else {
            idsString.split(",").mapNotNull { it.toLongOrNull() }.toSet()
        }
    }

    /** 清除已删除的歌单ID列表 */
    fun clearDeletedPlaylistIds() {
        encryptedPrefs.edit().remove(KEY_DELETED_PLAYLIST_IDS).apply()
    }

    /** 设置Token警告已忽略 */
    fun setTokenWarningDismissed(dismissed: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_TOKEN_WARNING_DISMISSED, dismissed).apply()
    }

    /** 获取Token警告是否已忽略 */
    fun isTokenWarningDismissed(): Boolean {
        return encryptedPrefs.getBoolean(KEY_TOKEN_WARNING_DISMISSED, false)
    }

    /** 设置省流模式 */
    fun setDataSaverMode(enabled: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_DATA_SAVER_MODE, enabled).apply()
    }

    /** 获取省流模式状态 */
    fun isDataSaverMode(): Boolean {
        return encryptedPrefs.getBoolean(KEY_DATA_SAVER_MODE, true)
    }
}
