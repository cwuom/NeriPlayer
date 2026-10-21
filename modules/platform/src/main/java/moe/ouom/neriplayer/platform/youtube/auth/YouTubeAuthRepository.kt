@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.youtube.auth

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
 */

import moe.ouom.neriplayer.platform.youtube.api.auth.fromJson
import moe.ouom.neriplayer.platform.youtube.api.auth.hasSavedAuthMaterial
import moe.ouom.neriplayer.platform.youtube.api.auth.normalized
import moe.ouom.neriplayer.platform.youtube.api.auth.toJson
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeAuthProvider
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthHealth
import moe.ouom.neriplayer.platform.youtube.api.auth.evaluateYouTubeAuthHealth
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener

private const val YOUTUBE_AUTH_PREFS = "youtube_auth_secure_prefs"
private const val YOUTUBE_AUTH_RECOVERY_PREFS = "youtube_auth_secure_prefs_recovery"
private const val YOUTUBE_AUTH_RECOVERY_MASTER_KEY_ALIAS =
    "youtube_auth_recovery_master_key"
private const val KEY_YOUTUBE_AUTH_BUNDLE = "youtube_auth_bundle"

class YouTubeAuthRepository(private val context: Context) : YouTubeAuthProvider {
    private var encryptedPrefs: SharedPreferences
    private var usingRecoveryStorage = false
    private var usingCompatibilityStorage = false
    private val _authFlow: MutableStateFlow<YouTubeAuthBundle>
    private val _authHealthFlow: MutableStateFlow<YouTubeAuthHealth>
    private val authMutationLock = Any()

    val authFlow: StateFlow<YouTubeAuthBundle>
        get() = _authFlow.asStateFlow()

    val authHealthFlow: StateFlow<YouTubeAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    val requiresCompatibilityStorageWarning: Boolean
        get() = AccountPreferencesOpener.isCompatibilityStorage(encryptedPrefs)

    val isAuthStoragePersistent: Boolean
        get() = SecurePreferencesOpener.isPersistent(encryptedPrefs)

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _authHealthFlow = MutableStateFlow(
            evaluateYouTubeAuthHealth(initialBundle)
        )
    }

    override fun getAuthOnce(): YouTubeAuthBundle = _authFlow.value

    override fun getAuthHealthOnce(): YouTubeAuthHealth = _authHealthFlow.value

    fun getAuthHealth(
        now: Long = System.currentTimeMillis()
    ): YouTubeAuthHealth = evaluateYouTubeAuthHealth(_authFlow.value, now)

    fun saveAuth(bundle: YouTubeAuthBundle) {
        saveAuth(bundle, allowCompatibilityStorage = true)
    }

    fun saveAuth(bundle: YouTubeAuthBundle, allowCompatibilityStorage: Boolean) {
        synchronized(authMutationLock) {
            // 与写入共用账号锁，避免预检查后退出操作切换到兼容存储
            if (!allowCompatibilityStorage && requiresCompatibilityStorageWarning && bundle.hasSavedAuthMaterial()) {
                throw IOException("YouTube compatibility account storage requires confirmation")
            }
            saveAuthLocked(bundle)
        }
    }

    /** 在现有快照上合并轮换值, 防止并发页面刷新覆盖其它最新字段 */
    fun mergeRotatedCookies(rotatedCookies: Map<String, String>): YouTubeAuthBundle {
        synchronized(authMutationLock) {
            val merged = mergeYouTubeAuthBundle(
                base = _authFlow.value,
                observedCookies = rotatedCookies,
                savedAt = System.currentTimeMillis()
            )
            saveAuthLocked(merged)
            return merged
        }
    }

    private fun saveAuthLocked(bundle: YouTubeAuthBundle) {
        val normalized = bundle.normalized(
            savedAt = bundle.savedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        persistAuthBundle(normalized)
        _authFlow.value = normalized
        _authHealthFlow.value = evaluateYouTubeAuthHealth(normalized)
    }

    fun mergeCookieUpdates(setCookieHeaders: Iterable<String>): Boolean {
        synchronized(authMutationLock) {
            val merged = mergeYouTubeAuthCookieUpdates(
                base = _authFlow.value,
                setCookieHeaders = setCookieHeaders
            ) ?: return false
            saveAuthLocked(merged)
            return true
        }
    }

    fun clear() {
        synchronized(authMutationLock) {
            val cleared = YouTubeAuthBundle()
            val serialized = cleared.toJson()
            if (!isAuthStoragePersistent) throw IOException("YouTube account storage is unavailable")
            if (usingCompatibilityStorage) {
                requireAuthBundleCommit(serialized)
            } else {
                val activeClear = runCatching { commitAuthBundle(encryptedPrefs, serialized) }
                val otherClear = runCatching {
                    val otherPrefs = if (usingRecoveryStorage) {
                        createEncryptedPrefs(YOUTUBE_AUTH_PREFS)
                    } else {
                        createRecoveryEncryptedPrefs()
                    }
                    commitAuthBundle(otherPrefs, serialized)
                }
                // 无法清除的旧副本必须由兼容存储墓碑遮挡，避免重启后再次登录
                if (activeClear.getOrNull() != true || otherClear.getOrNull() != true) {
                    switchToCompatibilityStorage()
                    requireAuthBundleCommit(serialized)
                }
            }
            _authFlow.value = cleared
            _authHealthFlow.value = evaluateYouTubeAuthHealth(cleared)
        }
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateYouTubeAuthHealth(
            bundle = _authFlow.value,
            now = now
        )
    }

    private fun loadAuthBundle(): YouTubeAuthBundle {
        val primaryRead = runCatching {
            encryptedPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
        }
        if (primaryRead.isFailure) {
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to read YouTube account storage; original storage was preserved.",
                primaryRead.exceptionOrNull()
            )
            if (!usingCompatibilityStorage) {
                if (!usingRecoveryStorage && switchToRecoveryStorage()) return loadAuthBundle()
                switchToCompatibilityStorage()
                return loadAuthBundle()
            }
            return YouTubeAuthBundle()
        }

        val raw = primaryRead.getOrNull().orEmpty()
        if (raw.isBlank() && !usingRecoveryStorage && !usingCompatibilityStorage) {
            // 旧的恢复存储可能是在一次加密异常后写入的, 优先取它以免恢复成功后又显示游客态
            runCatching { createRecoveryEncryptedPrefs() }
                .onSuccess { recoveryPrefs ->
                    val recoveryRaw = runCatching {
                        recoveryPrefs.getString(KEY_YOUTUBE_AUTH_BUNDLE, null).orEmpty()
                    }.getOrDefault("")
                    if (recoveryRaw.isNotBlank()) {
                        encryptedPrefs = recoveryPrefs
                        usingRecoveryStorage = true
                        return YouTubeAuthBundle.fromJson(recoveryRaw)
                    }
                }
        }
        if (raw.isBlank()) {
            return YouTubeAuthBundle()
        }
        return YouTubeAuthBundle.fromJson(raw)
    }

    private fun switchToRecoveryStorage(): Boolean {
        val recoveryPrefs = runCatching { createRecoveryEncryptedPrefs() }
            .onFailure { error ->
                NPLogger.e(
                    "NERI-YouTubeAuthRepo",
                    "Failed to open YouTube recovery secure prefs; original storage was preserved.",
                    error
                )
            }
            .getOrNull()
            ?: return false
        encryptedPrefs = recoveryPrefs
        usingRecoveryStorage = true
        return true
    }

    private fun persistAuthBundle(bundle: YouTubeAuthBundle) {
        if (!isAuthStoragePersistent) throw IOException("YouTube account storage is unavailable")
        val serialized = bundle.toJson()
        val primaryWrite = runCatching {
            commitAuthBundle(encryptedPrefs, serialized)
        }
        if (primaryWrite.getOrNull() != true) {
            if (usingCompatibilityStorage) {
                throw IOException("Failed to save YouTube compatibility account storage", primaryWrite.exceptionOrNull())
            }
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to commit primary YouTube secure prefs, preserving it and switching to recovery storage.",
                primaryWrite.exceptionOrNull()
            )
            if (!usingRecoveryStorage && switchToRecoveryStorage()) {
                val recoveryWrite = runCatching { commitAuthBundle(encryptedPrefs, serialized) }
                if (recoveryWrite.getOrNull() == true) return
            }
            // 写入期间才发现系统密钥失败时，需重新经过登录前的兼容存储确认
            throw IOException("Failed to save YouTube secure account storage", primaryWrite.exceptionOrNull())
        }

        if (usingCompatibilityStorage || usingRecoveryStorage) return
        // 主存储正常时也保留独立加密副本, 主文件损坏后仍能恢复最近一次登录
        runCatching {
            val recoveryPrefs = createRecoveryEncryptedPrefs()
            if (!commitAuthBundle(recoveryPrefs, serialized)) {
                NPLogger.w(
                    "NERI-YouTubeAuthRepo",
                    "Failed to commit YouTube recovery backup; primary auth remains available"
                )
            }
        }.onFailure { error ->
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to update YouTube recovery backup; primary auth remains available.",
                error
            )
        }
    }

    private fun commitAuthBundle(
        prefs: SharedPreferences,
        serialized: String
    ): Boolean = prefs.commitEdit {
        putString(KEY_YOUTUBE_AUTH_BUNDLE, serialized)
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences {
        AccountPreferencesOpener.existing(context, YOUTUBE_AUTH_PREFS)?.let { preferences ->
            usingCompatibilityStorage = true
            return preferences
        }
        return runCatching {
            createEncryptedPrefs(YOUTUBE_AUTH_PREFS)
        }.getOrElse { error ->
            NPLogger.w(
                "NERI-YouTubeAuthRepo",
                "Failed to open primary YouTube secure prefs, preserving it and using recovery storage.",
                error
            )
            switchToRecoveryStorageOrCompatibility()
        }
    }

    private fun switchToRecoveryStorageOrCompatibility(): SharedPreferences {
        val recoveryPrefs = runCatching { createRecoveryEncryptedPrefs() }
            .onFailure { recoveryError ->
                NPLogger.e(
                    "NERI-YouTubeAuthRepo",
                    "Unable to open either YouTube secure storage; original storage was preserved.",
                    recoveryError
                )
            }
            .getOrElse {
                usingCompatibilityStorage = true
                return AccountPreferencesOpener.fallback(context, YOUTUBE_AUTH_PREFS)
            }
        usingRecoveryStorage = true
        return recoveryPrefs
    }

    private fun switchToCompatibilityStorage() {
        usingCompatibilityStorage = true
        usingRecoveryStorage = false
        encryptedPrefs = AccountPreferencesOpener.fallback(context, YOUTUBE_AUTH_PREFS)
    }

    private fun requireAuthBundleCommit(serialized: String) {
        if (!isAuthStoragePersistent) throw IOException("YouTube account storage is unavailable")
        val result = runCatching { commitAuthBundle(encryptedPrefs, serialized) }
        if (result.getOrNull() != true) {
            throw IOException("Failed to commit YouTube account storage", result.exceptionOrNull())
        }
    }

    private fun createRecoveryEncryptedPrefs(): SharedPreferences {
        return createEncryptedPrefs(
            name = YOUTUBE_AUTH_RECOVERY_PREFS,
            masterKeyAlias = YOUTUBE_AUTH_RECOVERY_MASTER_KEY_ALIAS
        )
    }

    private fun createEncryptedPrefs(
        name: String,
        masterKeyAlias: String? = null
    ): SharedPreferences {
        val masterKeyBuilder = if (masterKeyAlias.isNullOrBlank()) {
            MasterKey.Builder(context)
        } else {
            MasterKey.Builder(context, masterKeyAlias)
        }
        val masterKey = masterKeyBuilder
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

@SuppressLint("UseKtx")
private inline fun SharedPreferences.commitEdit(
    action: SharedPreferences.Editor.() -> Unit
): Boolean {
    // androidx edit(commit = true) does not expose commit failure
    val editor = edit()
    editor.action()
    return editor.commit()
}
