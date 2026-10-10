package moe.ouom.neriplayer.data.listentogether

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.listentogether.profile.buildDefaultListenTogetherNickname
import moe.ouom.neriplayer.listentogether.profile.buildListenTogetherUserUuid
import moe.ouom.neriplayer.api.ltw.http.configuredListenTogetherBaseUrlOrNull
import moe.ouom.neriplayer.api.ltw.http.isDefaultListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.profile.sanitizeListenTogetherNicknameOrNull
import moe.ouom.neriplayer.data.model.config.ListenTogetherConfigSnapshot

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
            customWorkerBaseUrl(prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL])
        }

    val workerBaseUrlInputFlow: Flow<String> =
        context.listenTogetherDataStore.data.map { prefs ->
            prefs.trimmed(ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT).ifBlank {
                customWorkerBaseUrl(prefs[ListenTogetherPreferenceKeys.WORKER_BASE_URL])
            }
        }

    val userUuidFlow: Flow<String> =
        context.listenTogetherDataStore.data.map { prefs ->
            prefs.trimmed(ListenTogetherPreferenceKeys.LAST_USER_UUID)
        }

    val nicknameFlow: Flow<String> =
        context.listenTogetherDataStore.data.map { prefs ->
            sanitizeListenTogetherNicknameOrNull(prefs[ListenTogetherPreferenceKeys.LAST_NICKNAME])
                ?: sanitizeListenTogetherNicknameOrNull(prefs[ListenTogetherPreferenceKeys.LAST_USER_ID])
                .orEmpty()
        }

    val allowMemberControlFlow: Flow<Boolean> =
        context.listenTogetherDataStore.data.map {
            it.enabledByDefault(ListenTogetherPreferenceKeys.ALLOW_MEMBER_CONTROL)
        }

    val autoPauseOnMemberChangeFlow: Flow<Boolean> =
        context.listenTogetherDataStore.data.map {
            it.enabledByDefault(ListenTogetherPreferenceKeys.AUTO_PAUSE_ON_MEMBER_CHANGE)
        }

    val shareAudioLinksFlow: Flow<Boolean> =
        context.listenTogetherDataStore.data.map {
            it.enabledByDefault(ListenTogetherPreferenceKeys.SHARE_AUDIO_LINKS)
        }

    suspend fun setWorkerBaseUrl(value: String) {
        context.listenTogetherDataStore.edit { prefs ->
            prefs.keepCustomServerAsInput()
            prefs.putOrRemove(ListenTogetherPreferenceKeys.WORKER_BASE_URL, customWorkerBaseUrl(value))
        }
    }

    suspend fun setWorkerBaseUrlInput(value: String) {
        context.listenTogetherDataStore.edit { prefs ->
            val trimmed = value.trim()
            prefs.putOrRemove(
                ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT,
                trimmed.takeUnless(::namesDefaultServer).orEmpty()
            )
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
            resolvedUserUuid = prefs.trimmed(ListenTogetherPreferenceKeys.LAST_USER_UUID)
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
            resolvedNickname = prefs.storedNickname().ifBlank(::buildDefaultListenTogetherNickname)
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
        return context.listenTogetherDataStore.data.first().toConfigSnapshot()
    }

    suspend fun restore(snapshot: ListenTogetherConfigSnapshot) {
        context.listenTogetherDataStore.edit { prefs -> prefs.restoreFrom(snapshot) }
    }
}

private fun customWorkerBaseUrl(value: String?): String =
    configuredListenTogetherBaseUrlOrNull(value)
        .takeUnless { isDefaultListenTogetherBaseUrl(it) }
        .orEmpty()

private fun namesDefaultServer(input: String): Boolean =
    configuredListenTogetherBaseUrlOrNull(input)?.let(::isDefaultListenTogetherBaseUrl) == true

private fun restorableWorkerBaseUrlInput(input: String, workerBaseUrl: String): String =
    input.takeUnless { it == workerBaseUrl || namesDefaultServer(it) }.orEmpty()

private fun Preferences.trimmed(key: Preferences.Key<String>): String = this[key]?.trim().orEmpty()

private fun Preferences.enabledByDefault(key: Preferences.Key<Boolean>): Boolean = this[key] ?: true

private fun Preferences.storedNickname(): String =
    this[ListenTogetherPreferenceKeys.LAST_NICKNAME]
        ?.let(::sanitizeListenTogetherNicknameOrNull)
        .orEmpty()
        .ifBlank { sanitizeListenTogetherNicknameOrNull(this[ListenTogetherPreferenceKeys.LAST_USER_ID]).orEmpty() }

private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String) {
    if (value.isBlank()) remove(key) else this[key] = value
}

private fun MutablePreferences.keepCustomServerAsInput() {
    if (trimmed(ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT).isNotBlank()) return
    val currentCustomServer = customWorkerBaseUrl(this[ListenTogetherPreferenceKeys.WORKER_BASE_URL])
    if (currentCustomServer.isNotBlank()) {
        this[ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT] = currentCustomServer
    }
}

private fun Preferences.toConfigSnapshot(): ListenTogetherConfigSnapshot {
    val workerBaseUrl = customWorkerBaseUrl(this[ListenTogetherPreferenceKeys.WORKER_BASE_URL])
    return ListenTogetherConfigSnapshot(
        workerBaseUrl = workerBaseUrl,
        workerBaseUrlInput = trimmed(ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT).ifBlank { workerBaseUrl },
        userUuid = trimmed(ListenTogetherPreferenceKeys.LAST_USER_UUID),
        nickname = sanitizeListenTogetherNicknameOrNull(this[ListenTogetherPreferenceKeys.LAST_NICKNAME]).orEmpty(),
        allowMemberControl = enabledByDefault(ListenTogetherPreferenceKeys.ALLOW_MEMBER_CONTROL),
        autoPauseOnMemberChange = enabledByDefault(ListenTogetherPreferenceKeys.AUTO_PAUSE_ON_MEMBER_CHANGE),
        shareAudioLinks = enabledByDefault(ListenTogetherPreferenceKeys.SHARE_AUDIO_LINKS)
    )
}

private fun MutablePreferences.restoreFrom(snapshot: ListenTogetherConfigSnapshot) {
    val workerBaseUrl = customWorkerBaseUrl(snapshot.workerBaseUrl)
    putOrRemove(ListenTogetherPreferenceKeys.WORKER_BASE_URL, workerBaseUrl)
    putOrRemove(
        ListenTogetherPreferenceKeys.WORKER_BASE_URL_INPUT,
        restorableWorkerBaseUrlInput(snapshot.workerBaseUrlInput.trim(), workerBaseUrl)
    )
    putOrRemove(ListenTogetherPreferenceKeys.LAST_USER_UUID, snapshot.userUuid.trim())
    putOrRemove(
        ListenTogetherPreferenceKeys.LAST_NICKNAME,
        sanitizeListenTogetherNicknameOrNull(snapshot.nickname).orEmpty()
    )
    this[ListenTogetherPreferenceKeys.ALLOW_MEMBER_CONTROL] = snapshot.allowMemberControl
    this[ListenTogetherPreferenceKeys.AUTO_PAUSE_ON_MEMBER_CHANGE] = snapshot.autoPauseOnMemberChange
    this[ListenTogetherPreferenceKeys.SHARE_AUDIO_LINKS] = snapshot.shareAudioLinks
}
