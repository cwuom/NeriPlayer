package moe.ouom.neriplayer.core.api.youtube

import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.resolveAuthorizationHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveXGoogAuthUser
import moe.ouom.neriplayer.data.settings.YouTubePlaybackSourcePreference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_ID = "67"
internal const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
internal const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_VERSION = "1.20260403.09.00"
internal const val YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_ID = "62"
internal const val YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME = "WEB_CREATOR"
internal const val YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_VERSION = "1.20260114.05.00"
internal const val YOUTUBE_PLAYER_VISIONOS_CLIENT_ID = "101"
internal const val YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME = "VISIONOS"
internal const val YOUTUBE_PLAYER_VISIONOS_CLIENT_VERSION = "0.1"
internal const val YOUTUBE_PLAYER_VISIONOS_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/18.0 Safari/605.1.15"
internal const val YOUTUBE_PLAYER_ANDROID_VR_CLIENT_ID = "28"
internal const val YOUTUBE_PLAYER_ANDROID_VR_CLIENT_NAME = "ANDROID_VR"
internal const val YOUTUBE_PLAYER_ANDROID_VR_CLIENT_VERSION = "1.65.10"
internal const val YOUTUBE_PLAYER_ANDROID_VR_USER_AGENT =
    "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
        "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
internal const val YOUTUBE_PLAYER_TV_CLIENT_ID = "7"
internal const val YOUTUBE_PLAYER_TV_CLIENT_NAME = "TVHTML5"
internal const val YOUTUBE_PLAYER_TV_CLIENT_VERSION = "7.20260114.12.00"
internal const val YOUTUBE_PLAYER_TV_USER_AGENT =
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
        "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
internal const val YOUTUBE_PLAYER_TV_DOWNGRADED_CLIENT_VERSION = "5.20260114"
internal const val YOUTUBE_PLAYER_TV_DOWNGRADED_USER_AGENT =
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
internal const val YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME = "ANDROID_MUSIC"
internal const val YOUTUBE_PLAYER_WEB_REMIX_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
internal const val YOUTUBE_PLAYER_WEB_REMIX_ACCEPT_HEADER =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
        "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
internal const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_FORM_FACTOR = "UNKNOWN_FORM_FACTOR"
internal const val YOUTUBE_PLAYER_WEB_REMIX_PLAYER_TYPE = "UNIPLAYER"
internal const val YOUTUBE_PLAYER_WEB_REMIX_UI_THEME = "USER_INTERFACE_THEME_LIGHT"
internal const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_SCREEN = "WATCH_FULL_SCREEN"
internal const val YOUTUBE_PLAYER_WEB_REMIX_CONNECTION_TYPE = "CONN_CELLULAR_4G"
internal const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS = 771
internal const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS = 897
internal const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_PIXEL_DENSITY = 1
internal const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_DENSITY_FLOAT = 1.375
internal const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_WIDTH = 2048
internal const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_HEIGHT = 1152
internal const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_WIDTH = 2048
internal const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_HEIGHT = 1104
internal const val YOUTUBE_PLAYER_WEB_REMIX_INNER_WIDTH = 757
internal const val YOUTUBE_PLAYER_WEB_REMIX_COLOR_DEPTH = 32
internal const val YOUTUBE_PLAYER_WEB_REMIX_BROWSER_CONNECTION = 31
internal const val YOUTUBE_PLAYER_WEB_REMIX_HISTORY_LENGTH = 5
internal const val YOUTUBE_PLAYER_PLAYBACK_LACT_MILLISECONDS = "9"
internal const val YOUTUBE_PLAYER_API_FORMAT_VERSION = "2"

private data class YouTubeWebRemixRequestMetadata(
    val originalUrl: String,
    val watchUrl: String,
    val playlistId: String,
    val cpn: String,
    val clientScreenNonce: String
)

internal data class YouTubePlayerClientProfile(
    val clientId: String,
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val endpointPath: String,
    val responseField: String? = null,
    val platform: String = "MOBILE",
    val clientScreen: String = "WATCH",
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
    val wrapPlayerRequest: Boolean = false,
    val supportsAuthenticatedContext: Boolean = true,
    val includeUserAgentInContext: Boolean = false,
    val includeSignatureTimestamp: Boolean = true
)

internal fun YouTubePlayerClientProfile.requiresGvsPoToken(): Boolean {
    return clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME ||
        clientName == YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME ||
        clientName == YOUTUBE_PLAYER_TV_CLIENT_NAME
}

internal data class PreparedYouTubePlayerRequest(
    val request: Request,
    val clientVersion: String,
    val webRemixOriginalUrl: String,
    val webRemixWatchUrl: String
)

internal fun buildBootstrapRequestAuth(
    auth: YouTubeAuthBundle,
    bootstrap: YouTubePlaybackBootstrap,
    origin: String = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
): YouTubeAuthBundle {
    return auth.copy(
        cookieHeader = bootstrap.cookieHeader,
        cookies = emptyMap(),
        authorization = auth.authorization,
        xGoogAuthUser = bootstrap.sessionIndex,
        origin = origin,
        userAgent = bootstrap.userAgent.ifBlank { auth.userAgent }
    ).normalized(savedAt = auth.savedAt)
}

internal fun playerClientProfiles(
    sourcePreference: YouTubePlaybackSourcePreference,
    isAuthenticated: Boolean
): List<YouTubePlayerClientProfile> {
    val profiles = mapOf(
        YouTubePlayerClientSource.VISION_OS to YouTubePlayerClientProfile(
            clientId = YOUTUBE_PLAYER_VISIONOS_CLIENT_ID,
            clientName = YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME,
            clientVersion = YOUTUBE_PLAYER_VISIONOS_CLIENT_VERSION,
            userAgent = YOUTUBE_PLAYER_VISIONOS_USER_AGENT,
            endpointPath = "player",
            platform = "MOBILE",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            osName = "visionOS",
            osVersion = "1.3.21O771",
            supportsAuthenticatedContext = false,
            includeSignatureTimestamp = false
        ),
        YouTubePlayerClientSource.ANDROID_VR to YouTubePlayerClientProfile(
            clientId = YOUTUBE_PLAYER_ANDROID_VR_CLIENT_ID,
            clientName = YOUTUBE_PLAYER_ANDROID_VR_CLIENT_NAME,
            clientVersion = YOUTUBE_PLAYER_ANDROID_VR_CLIENT_VERSION,
            userAgent = YOUTUBE_PLAYER_ANDROID_VR_USER_AGENT,
            endpointPath = "player",
            platform = "MOBILE",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            supportsAuthenticatedContext = false,
            includeUserAgentInContext = true,
            includeSignatureTimestamp = false
        ),
        YouTubePlayerClientSource.WEB_REMIX to YouTubePlayerClientProfile(
            clientId = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_ID,
            clientName = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
            clientVersion = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_VERSION,
            userAgent = YOUTUBE_PLAYER_WEB_REMIX_USER_AGENT,
            endpointPath = "player",
            platform = "DESKTOP",
            clientScreen = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_SCREEN,
            osName = "Windows",
            osVersion = "10.0"
        ),
        YouTubePlayerClientSource.TV_HTML5 to YouTubePlayerClientProfile(
            clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
            clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
            clientVersion = YOUTUBE_PLAYER_TV_CLIENT_VERSION,
            userAgent = YOUTUBE_PLAYER_TV_USER_AGENT,
            endpointPath = "player",
            platform = "TV",
            includeUserAgentInContext = true
        ),
        YouTubePlayerClientSource.WEB_CREATOR to YouTubePlayerClientProfile(
            clientId = YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_ID,
            clientName = YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME,
            clientVersion = YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_VERSION,
            userAgent = YOUTUBE_PLAYER_WEB_REMIX_USER_AGENT,
            endpointPath = "player",
            platform = "DESKTOP",
            osName = "Windows",
            osVersion = "10.0"
        ),
        YouTubePlayerClientSource.TV_HTML5_LEGACY to YouTubePlayerClientProfile(
            clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
            clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
            clientVersion = YOUTUBE_PLAYER_TV_DOWNGRADED_CLIENT_VERSION,
            userAgent = YOUTUBE_PLAYER_TV_DOWNGRADED_USER_AGENT,
            endpointPath = "player",
            platform = "TV"
        )
    )
    return resolveYouTubePlayerClientOrder(
        preference = sourcePreference,
        preferAuthenticatedWebPlayback = isAuthenticated
    ).map(profiles::getValue)
}

internal object YouTubePlayerRequestComposer {
    fun compose(
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        profile: YouTubePlayerClientProfile,
        requestLocale: YouTubeMusicRequestLocale,
        signatureTimestamp: Int?
    ): PreparedYouTubePlayerRequest {
        val origin = resolvePlayerRequestOrigin(profile)
        val clientVersion = resolvePlayerClientVersion(profile, bootstrap)
        val userAgent = resolvePlayerRequestUserAgent(profile, bootstrap)
        val requestAuth = if (profile.supportsAuthenticatedContext) {
            buildBootstrapRequestAuth(auth, bootstrap, origin)
        } else {
            YouTubeAuthBundle(origin = origin, userAgent = userAgent)
        }
        val webRemixMetadata = if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            buildWebRemixRequestMetadata(videoId)
        } else {
            null
        }
        val body = buildPlayerRequestBody(
            videoId = videoId,
            profile = profile,
            bootstrap = bootstrap,
            requestLocale = requestLocale,
            clientVersion = clientVersion,
            userAgent = userAgent,
            webRemixMetadata = webRemixMetadata,
            signatureTimestamp = signatureTimestamp
        )
        val headers = buildPlayerRequestHeaders(
            profile = profile,
            requestLocale = requestLocale,
            bootstrap = bootstrap,
            requestAuth = requestAuth,
            origin = origin,
            clientVersion = clientVersion,
            userAgent = userAgent,
            webRemixMetadata = webRemixMetadata
        )
        val request = Request.Builder()
            .url(resolvePlayerRequestUrl(profile, bootstrap, videoId))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return PreparedYouTubePlayerRequest(
            request = request,
            clientVersion = clientVersion,
            webRemixOriginalUrl = webRemixMetadata?.originalUrl.orEmpty(),
            webRemixWatchUrl = webRemixMetadata?.watchUrl.orEmpty()
        )
    }

    private fun buildPlayerRequestHeaders(
        profile: YouTubePlayerClientProfile,
        requestLocale: YouTubeMusicRequestLocale,
        bootstrap: YouTubePlaybackBootstrap,
        requestAuth: YouTubeAuthBundle,
        origin: String,
        clientVersion: String,
        userAgent: String,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to requestLocale.acceptLanguage,
            "Content-Type" to "application/json",
            "X-Goog-Visitor-Id" to bootstrap.visitorData,
            "X-YouTube-Client-Name" to profile.clientId,
            "X-YouTube-Client-Version" to clientVersion,
            "Origin" to origin,
            "Referer" to (webRemixMetadata?.watchUrl ?: "$origin/")
        )
        if (profile.clientName != YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            headers["X-Goog-Api-Format-Version"] = YOUTUBE_PLAYER_API_FORMAT_VERSION
        }
        if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            headers["X-YouTube-Bootstrap-Logged-In"] = requestAuth.hasLoginCookies().toString()
        }
        if (profile.supportsAuthenticatedContext) {
            addAuthenticatedHeaders(headers, requestAuth, bootstrap, origin)
        }
        if (profile.clientName == YOUTUBE_PLAYER_TV_CLIENT_NAME) {
            addTvHeaders(headers, bootstrap)
        }
        return headers
    }

    private fun addAuthenticatedHeaders(
        headers: MutableMap<String, String>,
        requestAuth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        origin: String
    ) {
        headers["Cookie"] = bootstrap.cookieHeader
        headers["X-Goog-AuthUser"] = requestAuth.resolveXGoogAuthUser(bootstrap.sessionIndex)
        addAuthorizationHeader(headers, requestAuth, bootstrap, origin)
    }

    private fun addAuthorizationHeader(
        headers: MutableMap<String, String>,
        requestAuth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        origin: String
    ) {
        val userSessionId = if (bootstrap.loggedIn) bootstrap.userSessionId else ""
        val authorization = requestAuth.resolveAuthorizationHeader(
            origin = origin,
            userSessionId = userSessionId
        )
        if (authorization.isBlank()) return
        headers["Authorization"] = authorization
        headers["X-Origin"] = origin
    }

    private fun addTvHeaders(
        headers: MutableMap<String, String>,
        bootstrap: YouTubePlaybackBootstrap
    ) {
        putNonBlankHeader(headers, "X-Goog-PageId", bootstrap.delegatedSessionId)
        if (bootstrap.loggedIn) {
            headers["X-Youtube-Bootstrap-Logged-In"] = "true"
        }
    }

    private fun putNonBlankHeader(headers: MutableMap<String, String>, key: String, value: String) {
        if (value.isNotBlank()) headers[key] = value
    }

    private fun buildPlayerRequestBody(
        videoId: String,
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        requestLocale: YouTubeMusicRequestLocale,
        clientVersion: String,
        userAgent: String,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?,
        signatureTimestamp: Int?
    ): JSONObject {
        val clientContext = buildClientContext(
            profile, bootstrap, requestLocale, clientVersion, userAgent, webRemixMetadata
        )
        val requestContext = JSONObject()
            .put("useSsl", true)
            .put("internalExperimentFlags", JSONArray())
            .put("consistencyTokenJars", JSONArray())
        val context = JSONObject()
            .put("client", clientContext)
            .put("request", requestContext)
            .put("user", JSONObject().put("lockedSafetyMode", false))
        addWebRemixTrackingContext(context, webRemixMetadata)
        return buildPlayerPayload(context, videoId, profile, webRemixMetadata, signatureTimestamp)
    }

    private fun buildClientContext(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        requestLocale: YouTubeMusicRequestLocale,
        clientVersion: String,
        userAgent: String,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?
    ): JSONObject {
        val client = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", clientVersion)
            .put("platform", profile.platform)
            .put("hl", requestLocale.hl)
            .put("gl", requestLocale.gl)
            .put("utcOffsetMinutes", utcOffsetMinutes())
        putNonBlank(client, "clientScreen", profile.clientScreen)
        putNonBlank(client, "visitorData", bootstrap.visitorData)
        addOptionalClientUserAgent(client, profile, userAgent)
        addOptionalWebRemixContext(client, profile, bootstrap, userAgent, webRemixMetadata)
        client.putOpt("deviceMake", profile.deviceMake)
        client.putOpt("deviceModel", profile.deviceModel)
        client.putOpt("osName", profile.osName)
        client.putOpt("osVersion", profile.osVersion)
        client.putOpt("androidSdkVersion", profile.androidSdkVersion)
        return client
    }

    private fun addOptionalClientUserAgent(
        client: JSONObject,
        profile: YouTubePlayerClientProfile,
        userAgent: String
    ) {
        if (profile.includeUserAgentInContext && userAgent.isNotBlank()) {
            client.put("userAgent", ensureGfeUserAgent(userAgent))
        }
    }

    private fun addOptionalWebRemixContext(
        client: JSONObject,
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        userAgent: String,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?
    ) {
        if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME && userAgent.isNotBlank()) {
            addWebRemixBrowserContext(client, bootstrap, userAgent, webRemixMetadata)
        }
    }

    private fun addWebRemixBrowserContext(
        client: JSONObject,
        bootstrap: YouTubePlaybackBootstrap,
        userAgent: String,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?
    ) {
        // web remix 使用浏览器 watch 页的上下文，降低退回移动端直链的概率
        client.put("deviceMake", "")
        client.put("deviceModel", "")
        client.put("userAgent", ensureGfeUserAgent(userAgent))
        client.put("browserName", resolveBrowserName(userAgent))
        client.put("browserVersion", resolveBrowserVersion(userAgent))
        client.put("timeZone", currentTimeZoneId())
        client.put("originalUrl", webRemixMetadata?.originalUrl.orEmpty())
        client.put("acceptHeader", YOUTUBE_PLAYER_WEB_REMIX_ACCEPT_HEADER)
        client.put("clientFormFactor", YOUTUBE_PLAYER_WEB_REMIX_CLIENT_FORM_FACTOR)
        client.put("playerType", YOUTUBE_PLAYER_WEB_REMIX_PLAYER_TYPE)
        client.put("userInterfaceTheme", YOUTUBE_PLAYER_WEB_REMIX_UI_THEME)
        client.put("connectionType", YOUTUBE_PLAYER_WEB_REMIX_CONNECTION_TYPE)
        client.put("screenWidthPoints", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS)
        client.put("screenHeightPoints", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS)
        client.put("screenPixelDensity", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_PIXEL_DENSITY)
        client.put("screenDensityFloat", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_DENSITY_FLOAT)
        client.put("tvAppInfo", JSONObject().put("livingRoomAppMode", "LIVING_ROOM_APP_MODE_UNSPECIFIED"))
        client.put("configInfo", buildWebRemixConfigInfo(bootstrap))
        putNonBlank(client, "rolloutToken", bootstrap.rolloutToken)
        putNonBlank(client, "deviceExperimentId", bootstrap.deviceExperimentId)
        putNonBlank(client, "remoteHost", bootstrap.remoteHost)
    }

    private fun buildWebRemixConfigInfo(bootstrap: YouTubePlaybackBootstrap): JSONObject {
        val config = JSONObject()
        putNonBlank(config, "appInstallData", bootstrap.appInstallData)
        putNonBlank(config, "coldConfigData", bootstrap.coldConfigData)
        putNonBlank(config, "coldHashData", bootstrap.coldHashData)
        putNonBlank(config, "hotHashData", bootstrap.hotHashData)
        return config
    }

    private fun putNonBlank(target: JSONObject, key: String, value: String) {
        if (value.isNotBlank()) target.put(key, value)
    }

    private fun addWebRemixTrackingContext(
        context: JSONObject,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?
    ) {
        webRemixMetadata ?: return
        context.put("clientScreenNonce", webRemixMetadata.clientScreenNonce)
        context.put("clickTracking", JSONObject().put("clickTrackingParams", ""))
        context.put("adSignalsInfo", buildWebRemixAdSignalsInfo())
    }

    private fun buildPlayerPayload(
        context: JSONObject,
        videoId: String,
        profile: YouTubePlayerClientProfile,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?,
        signatureTimestamp: Int?
    ): JSONObject {
        val body = JSONObject().put("context", context)
        if (signatureTimestamp != null || webRemixMetadata != null) {
            body.put(
                "playbackContext",
                buildPlayerPlaybackContext(webRemixMetadata?.originalUrl, signatureTimestamp)
            )
        }
        addWebRemixPayload(body, webRemixMetadata)
        addVideoPayload(body, videoId, profile.wrapPlayerRequest)
        return body
    }

    private fun addWebRemixPayload(
        body: JSONObject,
        webRemixMetadata: YouTubeWebRemixRequestMetadata?
    ) {
        webRemixMetadata ?: return
        body.put("cpn", webRemixMetadata.cpn)
        body.put("captionParams", JSONObject())
        body.put("playlistId", webRemixMetadata.playlistId)
    }

    private fun addVideoPayload(body: JSONObject, videoId: String, wrapPlayerRequest: Boolean) {
        val videoRequest = JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (wrapPlayerRequest) {
            body.put("playerRequest", videoRequest)
            body.put("disablePlayerResponse", false)
        } else {
            body.put("videoId", videoId)
            body.put("contentCheckOk", true)
            body.put("racyCheckOk", true)
        }
    }

    private fun resolvePlayerRequestOrigin(profile: YouTubePlayerClientProfile): String {
        return if (profile.clientName == "WEB_REMIX") YOUTUBE_MUSIC_ORIGIN else YOUTUBE_WEB_ORIGIN
    }

    private fun resolvePlayerRequestUrl(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        videoId: String
    ): String {
        val baseUrl = if (profile.clientName == "WEB_REMIX") {
            "$YOUTUBE_MUSIC_ORIGIN/youtubei/v1/${profile.endpointPath}"
        } else {
            "$YOUTUBE_WEB_ORIGIN/youtubei/v1/${profile.endpointPath}"
        }
        return buildString {
            append(baseUrl)
            append("?prettyPrint=false")
            if (profile.clientName != "WEB_REMIX") {
                append("&id=")
                append(videoId)
            }
            append("&key=")
            append(bootstrap.apiKey)
            if (profile.responseField != null) {
                append("&fields=")
                append(profile.responseField)
            }
        }
    }

    private fun resolvePlayerClientVersion(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap
    ): String {
        return if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            bootstrap.webRemixClientVersion.ifBlank { profile.clientVersion }
        } else {
            profile.clientVersion
        }
    }

    private fun resolvePlayerRequestUserAgent(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap
    ): String {
        return if (profile.clientName == "WEB_REMIX") {
            bootstrap.userAgent.ifBlank { profile.userAgent }
        } else {
            profile.userAgent
        }
    }

    private fun utcOffsetMinutes(): Int {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (60 * 1000)
    }

    private fun currentTimeZoneId(): String = TimeZone.getDefault().id

    private fun buildWebRemixRequestMetadata(videoId: String): YouTubeWebRemixRequestMetadata {
        val playlistId = "RDAMVM$videoId"
        val watchUrl = buildWebRemixWatchUrl(videoId, playlistId)
        return YouTubeWebRemixRequestMetadata(
            originalUrl = "$YOUTUBE_MUSIC_ORIGIN/",
            watchUrl = watchUrl,
            playlistId = playlistId,
            cpn = generateRequestNonce(),
            clientScreenNonce = generateRequestNonce()
        )
    }

    private fun buildWebRemixWatchUrl(videoId: String, playlistId: String): String {
        return buildString {
            append(YOUTUBE_MUSIC_ORIGIN)
            append("/watch?v=")
            append(URLEncoder.encode(videoId, Charsets.UTF_8.name()))
            append("&list=")
            append(URLEncoder.encode(playlistId, Charsets.UTF_8.name()))
        }
    }

    private fun buildPlayerPlaybackContext(
        refererUrl: String?,
        signatureTimestamp: Int?
    ): JSONObject {
        val contentPlaybackContext = JSONObject()
            .put("html5Preference", "HTML5_PREF_WANTS")
            .put("lactMilliseconds", YOUTUBE_PLAYER_PLAYBACK_LACT_MILLISECONDS)
            .put("autonavState", "STATE_OFF")
            .put("autoCaptionsDefaultOn", false)
            .put("mdxContext", JSONObject())
            .put("vis", 10)
        refererUrl?.takeIf { it.isNotBlank() }?.let {
            contentPlaybackContext.put("referer", it)
        }
        signatureTimestamp?.let { contentPlaybackContext.put("signatureTimestamp", it) }
        return JSONObject()
            .put("contentPlaybackContext", contentPlaybackContext)
            .put(
                "devicePlaybackCapabilities",
                JSONObject()
                    .put("supportsVp9Encoding", true)
                    .put("supportXhr", true)
            )
    }

    private fun buildWebRemixAdSignalsInfo(): JSONObject {
        val params = listOf(
            "dt" to System.currentTimeMillis().toString(),
            "flash" to "0",
            "frm" to "0",
            "u_tz" to utcOffsetMinutes().toString(),
            "u_his" to YOUTUBE_PLAYER_WEB_REMIX_HISTORY_LENGTH.toString(),
            "u_h" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_HEIGHT.toString(),
            "u_w" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_WIDTH.toString(),
            "u_ah" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_HEIGHT.toString(),
            "u_aw" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_WIDTH.toString(),
            "u_cd" to YOUTUBE_PLAYER_WEB_REMIX_COLOR_DEPTH.toString(),
            "bc" to YOUTUBE_PLAYER_WEB_REMIX_BROWSER_CONNECTION.toString(),
            "bih" to YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS.toString(),
            "biw" to YOUTUBE_PLAYER_WEB_REMIX_INNER_WIDTH.toString(),
            "brdim" to "0,0,0,0,${YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_WIDTH},0," +
                "${YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_WIDTH}," +
                "${YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_HEIGHT}," +
                "${YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS}," +
                YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS,
            "vis" to "1",
            "wgl" to "true",
            "ca_type" to "image"
        )
        return JSONObject().put(
            "params",
            JSONArray().apply {
                params.forEach { (key, value) ->
                    put(
                        JSONObject()
                            .put("key", key)
                            .put("value", value)
                    )
                }
            }
        )
    }

    private fun resolveBrowserName(userAgent: String): String {
        val lowerCaseUserAgent = userAgent.lowercase(Locale.US)
        return when {
            "edg/" in lowerCaseUserAgent -> "Edge"
            "chrome/" in lowerCaseUserAgent -> "Chrome"
            "firefox/" in lowerCaseUserAgent -> "Firefox"
            else -> "Chrome"
        }
    }

    private fun resolveBrowserVersion(userAgent: String): String {
        val patterns = listOf("Edg/([\\d.]+)", "Chrome/([\\d.]+)", "Firefox/([\\d.]+)")
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern).find(userAgent)?.groupValues?.getOrNull(1)
        }.orEmpty()
    }

    private fun ensureGfeUserAgent(userAgent: String): String {
        return if (userAgent.contains("gzip(gfe)")) {
            userAgent
        } else {
            "$userAgent,gzip(gfe)"
        }
    }

    private fun generateRequestNonce(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString(16) {
            repeat(16) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }
}
