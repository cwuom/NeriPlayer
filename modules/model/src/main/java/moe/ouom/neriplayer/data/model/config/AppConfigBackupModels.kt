package moe.ouom.neriplayer.data.model.config

import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.data.model.sync.DEFAULT_SYNC_AUTO_ENABLED

@Serializable
data class TypedPreferenceSnapshot(
    val booleans: Map<String, Boolean> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val strings: Map<String, String> = emptyMap()
) {
    fun entryCount(): Int {
        return booleans.size + floats.size + ints.size + longs.size + strings.size
    }
}

@Serializable
data class ListenTogetherConfigSnapshot(
    val workerBaseUrl: String = "",
    val workerBaseUrlInput: String = "",
    val userUuid: String = "",
    val nickname: String = "",
    val allowMemberControl: Boolean = true,
    val autoPauseOnMemberChange: Boolean = true,
    val shareAudioLinks: Boolean = true
) {
    fun entryCount(): Int {
        var count = 0
        if (workerBaseUrl.isNotBlank()) count++
        if (workerBaseUrlInput.isNotBlank()) count++
        if (userUuid.isNotBlank()) count++
        if (nickname.isNotBlank()) count++
        count += 3
        return count
    }
}

@Serializable
data class LanguageConfigSnapshot(
    val code: String = ""
) {
    fun hasValue(): Boolean = code.isNotBlank()
}

@Serializable
data class SavedCookieConfigSnapshot(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasData(): Boolean = cookies.isNotEmpty()
}

@Serializable
data class YouTubeAuthConfigSnapshot(
    val cookieHeader: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val authorization: String = "",
    val xGoogAuthUser: String = "",
    val origin: String = "",
    val userAgent: String = "",
    val savedAt: Long = 0L
) {
    fun hasData(): Boolean {
        return cookieHeader.isNotBlank() ||
            cookies.isNotEmpty() ||
            authorization.isNotBlank() ||
            xGoogAuthUser.isNotBlank() ||
            origin.isNotBlank() ||
            userAgent.isNotBlank()
    }
}

@Serializable
data class GitHubSyncConfigSnapshot(
    val token: String = "",
    val repoOwner: String = "",
    val repoName: String = "",
    val autoSyncEnabled: Boolean = DEFAULT_SYNC_AUTO_ENABLED,
    val playHistoryUpdateMode: String = "",
    val dataSaverMode: Boolean = true
) {
    fun hasData(): Boolean {
        return token.isNotBlank() ||
            repoOwner.isNotBlank() ||
            repoName.isNotBlank() ||
            autoSyncEnabled != DEFAULT_SYNC_AUTO_ENABLED ||
            playHistoryUpdateMode.isNotBlank() ||
            !dataSaverMode
    }
}

@Serializable
data class SyncPreferencesConfigSnapshot(
    val playHistoryUpdateMode: String = ""
) {
    fun hasData(): Boolean {
        return playHistoryUpdateMode.isNotBlank()
    }
}

@Serializable
data class WebDavSyncConfigSnapshot(
    val serverUrl: String = "",
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    val autoSyncEnabled: Boolean = DEFAULT_SYNC_AUTO_ENABLED
) {
    fun hasData(): Boolean {
        return serverUrl.isNotBlank() ||
            basePath.isNotBlank() ||
            username.isNotBlank() ||
            password.isNotBlank() ||
            autoSyncEnabled != DEFAULT_SYNC_AUTO_ENABLED
    }
}

@Serializable
data class AppConfigBackup(
    val kind: String = CONFIG_KIND,
    val formatVersion: Int = CONFIG_FORMAT_VERSION,
    val exportedAt: Long = 0L,
    val settings: TypedPreferenceSnapshot = TypedPreferenceSnapshot(),
    val listenTogether: ListenTogetherConfigSnapshot = ListenTogetherConfigSnapshot(),
    val language: LanguageConfigSnapshot = LanguageConfigSnapshot(),
    val neteaseAuth: SavedCookieConfigSnapshot = SavedCookieConfigSnapshot(),
    val biliAuth: SavedCookieConfigSnapshot = SavedCookieConfigSnapshot(),
    val youTubeAuth: YouTubeAuthConfigSnapshot = YouTubeAuthConfigSnapshot(),
    val gitHubSync: GitHubSyncConfigSnapshot = GitHubSyncConfigSnapshot(),
    val webDavSync: WebDavSyncConfigSnapshot = WebDavSyncConfigSnapshot(),
    val syncPreferences: SyncPreferencesConfigSnapshot = SyncPreferencesConfigSnapshot()
) {
    fun hasRestorableContent(): Boolean {
        return settings.entryCount() > 0 ||
            listenTogether.entryCount() > 0 ||
            language.hasValue() ||
            neteaseAuth.hasData() ||
            biliAuth.hasData() ||
            youTubeAuth.hasData() ||
            gitHubSync.hasData() ||
            webDavSync.hasData() ||
            syncPreferences.hasData()
    }
}

data class AppConfigImportResult(
    val restoredSettingsCount: Int,
    val restoredListenTogetherCount: Int,
    val restoredAuthCount: Int,
    val restoredSyncCount: Int,
    val warnings: List<String> = emptyList(),
    val requiresActivityRecreate: Boolean = false
)

const val CONFIG_KIND = "moe.ouom.neriplayer.config"
const val CONFIG_FORMAT_VERSION = 1
