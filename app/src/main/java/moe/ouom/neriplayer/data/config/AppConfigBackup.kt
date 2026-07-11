package moe.ouom.neriplayer.data.config

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CONFIG_FILE_PREFIX = "neriplayer_config"
private const val CONFIG_FILE_EXTENSION = ".json"
private const val CONFIG_KIND = "moe.ouom.neriplayer.config"
private const val CONFIG_FORMAT_VERSION = 1

private val configJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

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
    val autoSyncEnabled: Boolean = false,
    val playHistoryUpdateMode: String = "",
    val dataSaverMode: Boolean = true
) {
    fun hasData(): Boolean {
        return token.isNotBlank() ||
            repoOwner.isNotBlank() ||
            repoName.isNotBlank() ||
            autoSyncEnabled ||
            playHistoryUpdateMode.isNotBlank() ||
            !dataSaverMode
    }
}

@Serializable
data class WebDavSyncConfigSnapshot(
    val serverUrl: String = "",
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    val autoSyncEnabled: Boolean = false
) {
    fun hasData(): Boolean {
        return serverUrl.isNotBlank() ||
            basePath.isNotBlank() ||
            username.isNotBlank() ||
            password.isNotBlank() ||
            autoSyncEnabled
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
    val webDavSync: WebDavSyncConfigSnapshot = WebDavSyncConfigSnapshot()
) {
    fun hasRestorableContent(): Boolean {
        return settings.entryCount() > 0 ||
            listenTogether.entryCount() > 0 ||
            language.hasValue() ||
            neteaseAuth.hasData() ||
            biliAuth.hasData() ||
            youTubeAuth.hasData() ||
            gitHubSync.hasData() ||
            webDavSync.hasData()
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

object AppConfigBackupCodec {
    fun encode(payload: AppConfigBackup): String = configJson.encodeToString(AppConfigBackup.serializer(), payload)

    fun decode(raw: String): AppConfigBackup {
        val payload = configJson.decodeFromString(AppConfigBackup.serializer(), raw)
        require(payload.kind == CONFIG_KIND) { "Not a NeriPlayer config backup" }
        require(payload.formatVersion in 1..CONFIG_FORMAT_VERSION) {
            "Unsupported config backup format: ${payload.formatVersion}"
        }
        require(payload.hasRestorableContent()) { "Config backup has no restorable content" }
        return payload
    }

    fun generateFileName(now: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        return "${CONFIG_FILE_PREFIX}_${formatter.format(Date(now))}$CONFIG_FILE_EXTENSION"
    }
}
