package moe.ouom.neriplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.listentogether.buildDefaultListenTogetherNickname
import moe.ouom.neriplayer.listentogether.buildListenTogetherUserUuid
import moe.ouom.neriplayer.listentogether.configuredListenTogetherBaseUrlOrNull
import moe.ouom.neriplayer.listentogether.isDefaultListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.sanitizeListenTogetherNicknameOrNull
import moe.ouom.neriplayer.data.config.ListenTogetherConfigSnapshot

private val Context.listenTogetherDataStore by preferencesDataStore("listen_together_prefs")

object ListenTogetherPreferenceKeys {
    val WORKER_BASE_URL = stringPreferencesKey("listen_together_worker_base_url")
    val WORKER_BASE_URL_INPUT = stringPreferencesKey("listen_together_worker_base_url_input")
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
            configuredListenTogetherBaseUrlOrNull(
                prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL]
            )
                .takeUnless { isDefaultListenTogetherBaseUrl(it) }
                .orEmpty()
        }

    val workerBaseUrlInputFlow: Flow<String> =
        context.listenTogetherDataStore.data.map { prefs ->
            val savedInput = prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT]
                ?.trim()
                .orEmpty()
            if (savedInput.isNotBlank()) {
                savedInput
            } else {
                configuredListenTogetherBaseUrlOrNull(
                    prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL]
                )
                    .takeUnless { isDefaultListenTogetherBaseUrl(it) }
                    .orEmpty()
            }
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
            val savedInput = prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT]
                ?.trim()
                .orEmpty()
            if (savedInput.isBlank()) {
                val currentCustomServer = configuredListenTogetherBaseUrlOrNull(
                    prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL]
                )
                    .takeUnless { isDefaultListenTogetherBaseUrl(it) }
                    .orEmpty()
                if (currentCustomServer.isNotBlank()) {
                    prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT] = currentCustomServer
                }
            }
            val normalized = configuredListenTogetherBaseUrlOrNull(value).orEmpty()
            if (normalized.isBlank() || isDefaultListenTogetherBaseUrl(normalized)) {
                prefs.remove(ListenTogetherPreferenceKeys.WORKER_BASE_URL)
            } else {
                prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL] = normalized
            }
        }
    }

    suspend fun setWorkerBaseUrlInput(value: String) {
        context.listenTogetherDataStore.edit { prefs ->
            val trimmed = value.trim()
            val normalized = configuredListenTogetherBaseUrlOrNull(trimmed)
            if (
                trimmed.isBlank() ||
                (normalized != null && isDefaultListenTogetherBaseUrl(normalized))
            ) {
                prefs.remove(ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT)
            } else {
                prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT] = trimmed
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

    suspend fun snapshot(): ListenTogetherConfigSnapshot {
        val prefs = context.listenTogetherDataStore.data.first()
        val workerBaseUrl = configuredListenTogetherBaseUrlOrNull(
            prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL]
        )
            .takeUnless { isDefaultListenTogetherBaseUrl(it) }
            .orEmpty()
        val workerBaseUrlInput = prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT]
            ?.trim()
            .orEmpty()
            .ifBlank { workerBaseUrl }
        return ListenTogetherConfigSnapshot(
            workerBaseUrl = workerBaseUrl,
            workerBaseUrlInput = workerBaseUrlInput,
            userUuid = prefs[ListenTogetherPreferenceKeys.LAST_USER_UUID]
                ?.trim()
                .orEmpty(),
            nickname = sanitizeListenTogetherNicknameOrNull(
                prefs[ListenTogetherPreferenceKeys.LAST_NICKNAME]
            ).orEmpty(),
            allowMemberControl = prefs[ListenTogetherPreferenceKeys.ALLOW_MEMBER_CONTROL] ?: true,
            autoPauseOnMemberChange =
                prefs[ListenTogetherPreferenceKeys.AUTO_PAUSE_ON_MEMBER_CHANGE] ?: true,
            shareAudioLinks = prefs[ListenTogetherPreferenceKeys.SHARE_AUDIO_LINKS] ?: true
        )
    }

    suspend fun restore(snapshot: ListenTogetherConfigSnapshot) {
        context.listenTogetherDataStore.edit { prefs ->
            val normalizedWorkerBaseUrl = configuredListenTogetherBaseUrlOrNull(snapshot.workerBaseUrl)
                .takeUnless { isDefaultListenTogetherBaseUrl(it) }
                .orEmpty()
            val normalizedInput = snapshot.workerBaseUrlInput.trim()

            if (normalizedWorkerBaseUrl.isBlank()) {
                prefs.remove(ListenTogetherPreferenceKeys.WORKER_BASE_URL)
            } else {
                prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL] = normalizedWorkerBaseUrl
            }

            if (
                normalizedInput.isBlank() ||
                normalizedInput == normalizedWorkerBaseUrl ||
                configuredListenTogetherBaseUrlOrNull(normalizedInput)
                    ?.let(::isDefaultListenTogetherBaseUrl) == true
            ) {
                prefs.remove(ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT)
            } else {
                prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT] = normalizedInput
            }

            val normalizedUserUuid = snapshot.userUuid.trim()
            if (normalizedUserUuid.isBlank()) {
                prefs.remove(ListenTogetherPreferenceKeys.LAST_USER_UUID)
            } else {
                prefs[ListenTogetherPreferenceKeys.LAST_USER_UUID] = normalizedUserUuid
            }

            val normalizedNickname = sanitizeListenTogetherNicknameOrNull(snapshot.nickname).orEmpty()
            if (normalizedNickname.isBlank()) {
                prefs.remove(ListenTogetherPreferenceKeys.LAST_NICKNAME)
            } else {
                prefs[ListenTogetherPreferenceKeys.LAST_NICKNAME] = normalizedNickname
            }

            prefs[ListenTogetherPreferenceKeys.ALLOW_MEMBER_CONTROL] = snapshot.allowMemberControl
            prefs[ListenTogetherPreferenceKeys.AUTO_PAUSE_ON_MEMBER_CHANGE] =
                snapshot.autoPauseOnMemberChange
            prefs[ListenTogetherPreferenceKeys.SHARE_AUDIO_LINKS] = snapshot.shareAudioLinks
        }
    }
}
