package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAccountProfile
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.youtube.api.auth.normalized
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeAccountProfileClient
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject

internal data class SettingsAccountProfile(
    val nickname: String,
    val avatarUrl: String?
)

internal data class SettingsAccountProfileRequest(
    val hasSavedAuthorization: Boolean,
    val savedAt: Long,
    val authorizationIdentity: String? = null
)

internal class SettingsAccountAuthorizationSnapshot(cookies: Map<String, String>) {
    private val values = cookies.toMap()
    val identity: String = authorizationIdentity(values)
    val biliUserId: Long? = values["DedeUserID"]?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    fun matches(cookies: Map<String, String>): Boolean = identity == authorizationIdentity(cookies)

    fun newNeteaseProfileClient(): NeteaseClient = NeteaseClient { "" }.also {
        it.setPersistedCookies(values)
    }

    override fun equals(other: Any?): Boolean =
        other is SettingsAccountAuthorizationSnapshot && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String = "SettingsAccountAuthorizationSnapshot"
}

private fun authorizationIdentity(cookies: Map<String, String>): String =
    cookies.entries.sortedBy { it.key }.joinToString(separator = "") { (key, value) ->
        "${key.length}:$key${value.length}:$value"
    }.encodeUtf8().sha256().hex()

internal class SettingsYouTubeAccountAuthorizationSnapshot(auth: YouTubeAuthBundle) {
    private val values = auth.normalized()
    val savedAt: Long = values.savedAt
    val identity: String = listOf(
        authorizationIdentity(values.cookies),
        values.authorization,
        values.xGoogAuthUser,
        values.origin,
        values.userAgent,
        values.savedAt.toString()
    ).joinToString(separator = "") { "${it.length}:$it" }.encodeUtf8().sha256().hex()

    fun matches(auth: YouTubeAuthBundle): Boolean = identity == SettingsYouTubeAccountAuthorizationSnapshot(auth).identity

    suspend fun loadProfile(client: YouTubeAccountProfileClient): YouTubeAccountProfile? =
        client.getAccountProfile(values)

    override fun equals(other: Any?): Boolean =
        other is SettingsYouTubeAccountAuthorizationSnapshot && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String = "SettingsYouTubeAccountAuthorizationSnapshot"
}

internal data class SettingsAccountProfileState(
    val profile: SettingsAccountProfile? = null,
    val loading: Boolean = false
)

@Composable
internal fun rememberSettingsAccountProfile(
    request: SettingsAccountProfileRequest,
    isActive: Boolean,
    loadProfile: suspend () -> SettingsAccountProfile?
): SettingsAccountProfileState {
    var state by remember(request) { mutableStateOf(SettingsAccountProfileState()) }
    var loaded by remember(request) { mutableStateOf(false) }
    var activeLoad by remember(request) { mutableStateOf<Any?>(null) }
    val currentLoader by rememberUpdatedState(loadProfile)

    LaunchedEffect(request, isActive) {
        if (!isActive || !request.hasSavedAuthorization) {
            activeLoad = null
            state = state.copy(loading = false)
            return@LaunchedEffect
        }
        if (loaded) return@LaunchedEffect
        val loadToken = Any()
        activeLoad = loadToken
        state = state.copy(loading = true)
        try {
            val profile = loadSettingsAccountProfileSafely(currentLoader)
            currentCoroutineContext().ensureActive()
            state = SettingsAccountProfileState(profile = profile)
            loaded = profile != null
        } finally {
            if (activeLoad === loadToken) {
                activeLoad = null
                state = state.copy(loading = false)
            }
        }
    }
    return state
}

internal suspend fun loadSettingsAccountProfileSafely(
    loadProfile: suspend () -> SettingsAccountProfile?
): SettingsAccountProfile? = try {
    loadProfile()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // 资料读取失败不代表本地授权已被清除
    null
}

internal suspend fun loadBiliAccountProfile(
    authorization: SettingsAccountAuthorizationSnapshot
): SettingsAccountProfile? {
    val userId = authorization.biliUserId ?: return null
    if (!authorization.matches(AppContainer.biliCookieRepo.getCookiesOnce())) return null
    val profile = AppContainer.biliClient.getUploaderProfile(userId)
    if (profile.mid != userId || !authorization.matches(AppContainer.biliCookieRepo.getCookiesOnce())) return null
    return settingsAccountProfile(profile.name, profile.faceUrl)
}

internal suspend fun loadNeteaseAccountProfile(
    authorization: SettingsAccountAuthorizationSnapshot
): SettingsAccountProfile? =
    withContext(Dispatchers.IO) {
        if (!authorization.matches(AppContainer.neteaseCookieRepo.getCookiesOnce())) return@withContext null
        // 独立只读会话避免共享客户端尚未切换授权时读到旧账号
        val client = authorization.newNeteaseProfileClient()
        try {
            val profile = parseNeteaseAccountProfile(client.getCurrentUserAccount())
            currentCoroutineContext().ensureActive()
            profile.takeIf { authorization.matches(AppContainer.neteaseCookieRepo.getCookiesOnce()) }
        } finally {
            client.evictConnections()
        }
    }

internal suspend fun loadYouTubeAccountProfile(
    authorization: SettingsYouTubeAccountAuthorizationSnapshot
): SettingsAccountProfile? = loadYouTubeAccountProfileIfCurrent(
    authorization = authorization,
    readCurrentAuth = AppContainer.youtubeAuthRepo::getAuthOnce,
    isEnabled = YouTubeFeatureGate::isEnabled
) { authorization.loadProfile(AppContainer.youtubeAccountProfileClient) }

internal suspend fun loadYouTubeAccountProfileIfCurrent(
    authorization: SettingsYouTubeAccountAuthorizationSnapshot,
    readCurrentAuth: () -> YouTubeAuthBundle,
    isEnabled: () -> Boolean,
    loadProfile: suspend () -> YouTubeAccountProfile?
): SettingsAccountProfile? {
    if (!isEnabled() || !authorization.matches(readCurrentAuth())) return null
    val profile = loadProfile() ?: return null
    currentCoroutineContext().ensureActive()
    if (!isEnabled() || !authorization.matches(readCurrentAuth())) return null
    return settingsAccountProfile(profile.nickname, profile.avatarUrl.orEmpty())
}

internal fun parseNeteaseAccountProfile(raw: String): SettingsAccountProfile? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    val profile = root.optJSONObject("profile") ?: return null
    if (profile.optLong("userId", 0L) <= 0L) return null
    return settingsAccountProfile(
        nickname = profile.optString("nickname"),
        avatarUrl = profile.optString("avatarUrl")
    )
}

internal fun settingsAccountProfile(nickname: String, avatarUrl: String): SettingsAccountProfile? {
    val name = nickname.trim().takeUnless { it.isEmpty() || it == "null" } ?: return null
    val avatar = avatarUrl.trim().let { url ->
        when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("https://") -> url
            else -> null
        }
    }
    return SettingsAccountProfile(nickname = name, avatarUrl = avatar)
}
