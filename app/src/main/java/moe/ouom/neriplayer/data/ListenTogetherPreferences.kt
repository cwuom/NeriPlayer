package moe.ouom.neriplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.listentogether.buildDefaultListenTogetherNickname
import moe.ouom.neriplayer.listentogether.buildListenTogetherUserUuid
import moe.ouom.neriplayer.listentogether.isDefaultListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.sanitizeListenTogetherNicknameOrNull

private val Context.listenTogetherDataStore by preferencesDataStore("listen_together_prefs")

object ListenTogetherPreferenceKeys {
    val WORKER_BASE_URL = stringPreferencesKey("listen_together_worker_base_url")
    val LAST_USER_ID = stringPreferencesKey("listen_together_last_user_id")
    val LAST_USER_UUID = stringPreferencesKey("listen_together_last_user_uuid")
    val LAST_NICKNAME = stringPreferencesKey("listen_together_last_nickname")
    val ALLOW_MEMBER_CONTROL = booleanPreferencesKey("listen_together_allow_member_control")
    val AUTO_PAUSE_ON_MEMBER_CHANGE = booleanPreferencesKey("listen_together_auto_pause_on_member_change")
    val SHARE_AUDIO_LINKS = booleanPreferencesKey("listen_together_share_audio_links")
}

class ListenTogetherPreferences(private val context: Context) {
    val workerBaseUrlFlow: Flow<String> =
        context.listenTogetherDataStore.data.map { prefs ->
            prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL]
                ?.trim()
                .orEmpty()
                .takeUnless { isDefaultListenTogetherBaseUrl(it) }
                .orEmpty()
        }

    val userUuidFlow: Flow<String> =
        context.listenTogetherDataStore.data.map { prefs ->
            prefs[ListenTogetherPreferenceKeys.LAST_USER_UUID]
                ?.trim()
                .orEmpty()
        }

    val nicknameFlow: Flow<String> =
        context.listenTogetherDataStore.data.map { prefs ->
            sanitizeListenTogetherNicknameOrNull(prefs[ListenTogetherPreferenceKeys.LAST_NICKNAME])
                ?: sanitizeListenTogetherNicknameOrNull(prefs[ListenTogetherPreferenceKeys.LAST_USER_ID])
                .orEmpty()
        }

    val allowMemberControlFlow: Flow<Boolean> =
        context.listenTogetherDataStore.data.map {
            it[ListenTogetherPreferenceKeys.ALLOW_MEMBER_CONTROL] ?: true
        }

    val autoPauseOnMemberChangeFlow: Flow<Boolean> =
        context.listenTogetherDataStore.data.map {
            it[ListenTogetherPreferenceKeys.AUTO_PAUSE_ON_MEMBER_CHANGE] ?: true
        }

    val shareAudioLinksFlow: Flow<Boolean> =
        context.listenTogetherDataStore.data.map {
            it[ListenTogetherPreferenceKeys.SHARE_AUDIO_LINKS] ?: true
        }

    suspend fun setWorkerBaseUrl(value: String) {
        context.listenTogetherDataStore.edit { prefs ->
            val normalized = value.trim()
            if (normalized.isBlank() || isDefaultListenTogetherBaseUrl(normalized)) {
                prefs.remove(ListenTogetherPreferenceKeys.WORKER_BASE_URL)
            } else {
                prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL] = normalized
            }
        }
    }

    suspend fun setNickname(value: String) {
        context.listenTogetherDataStore.edit { prefs ->
            val normalized = value.trim()
            if (normalized.isBlank()) {
                prefs.remove(ListenTogetherPreferenceKeys.LAST_NICKNAME)
            } else {
                prefs[ListenTogetherPreferenceKeys.LAST_NICKNAME] = normalized
            }
        }
    }

    suspend fun setUserUuid(value: String) {
        context.listenTogetherDataStore.edit { prefs ->
            val normalized = value.trim()
            if (normalized.isBlank()) {
                prefs.remove(ListenTogetherPreferenceKeys.LAST_USER_UUID)
            } else {
                prefs[ListenTogetherPreferenceKeys.LAST_USER_UUID] = normalized
            }
        }
    }

    suspend fun getOrCreateUserUuid(): String {
        var resolvedUserUuid = ""
        context.listenTogetherDataStore.edit { prefs ->
            resolvedUserUuid = prefs[ListenTogetherPreferenceKeys.LAST_USER_UUID]
                ?.trim()
                .orEmpty()
                .ifBlank(::buildListenTogetherUserUuid)
            prefs[ListenTogetherPreferenceKeys.LAST_USER_UUID] = resolvedUserUuid
        }
        return resolvedUserUuid
    }

    suspend fun resetUserUuid(): String {
        val nextUserUuid = buildListenTogetherUserUuid()
        context.listenTogetherDataStore.edit { prefs ->
            prefs[ListenTogetherPreferenceKeys.LAST_USER_UUID] = nextUserUuid
        }
        return nextUserUuid
    }

    suspend fun getOrCreateNickname(): String {
        var resolvedNickname = ""
        context.listenTogetherDataStore.edit { prefs ->
            resolvedNickname = prefs[ListenTogetherPreferenceKeys.LAST_NICKNAME]
                ?.let(::sanitizeListenTogetherNicknameOrNull)
                .orEmpty()
                .ifBlank {
                    sanitizeListenTogetherNicknameOrNull(prefs[ListenTogetherPreferenceKeys.LAST_USER_ID])
                        .orEmpty()
                }
                .ifBlank(::buildDefaultListenTogetherNickname)
            prefs[ListenTogetherPreferenceKeys.LAST_NICKNAME] = resolvedNickname
        }
        return resolvedNickname
    }

    suspend fun setAllowMemberControl(value: Boolean) {
        context.listenTogetherDataStore.edit { prefs ->
            prefs[ListenTogetherPreferenceKeys.ALLOW_MEMBER_CONTROL] = value
        }
    }

    suspend fun setAutoPauseOnMemberChange(value: Boolean) {
        context.listenTogetherDataStore.edit { prefs ->
            prefs[ListenTogetherPreferenceKeys.AUTO_PAUSE_ON_MEMBER_CHANGE] = value
        }
    }

    suspend fun setShareAudioLinks(value: Boolean) {
        context.listenTogetherDataStore.edit { prefs ->
            prefs[ListenTogetherPreferenceKeys.SHARE_AUDIO_LINKS] = value
        }
    }
}
