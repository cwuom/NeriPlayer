package moe.ouom.neriplayer.core.api.youtube

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
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubeMusicPlaybackRepository
 * Updated: 2026/3/23
 */


import android.content.Context
import androidx.media3.common.MimeTypes
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.jvm.Volatile
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.data.auth.youtube.isYouTubeAuthRecoverableFailure
import moe.ouom.neriplayer.data.auth.youtube.shouldStartYouTubeWebAuthRecovery
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthAutoRefreshManager
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.YouTubeFeatureGate
import moe.ouom.neriplayer.data.platform.youtube.YouTubeFeatureDisabledException
import moe.ouom.neriplayer.data.platform.youtube.appendYouTubeConsentCookie
import moe.ouom.neriplayer.data.platform.youtube.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.effectiveCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeGoogleVideoHost
import moe.ouom.neriplayer.data.platform.youtube.resolveAuthorizationHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveBootstrapUserAgent
import moe.ouom.neriplayer.data.platform.youtube.resolveXGoogAuthUser
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_ID = "67"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_VERSION = "1.20260403.09.00"
// 预热请求本身已经由单例合并, 首播不应再额外等待调度窗口
private const val YOUTUBE_PLAYBACK_WARM_BOOTSTRAP_START_DELAY_MS = 0L
private const val YOUTUBE_PLAYER_TV_CLIENT_ID = "7"
private const val YOUTUBE_PLAYER_TV_CLIENT_NAME = "TVHTML5"
private const val YOUTUBE_PLAYER_TV_CLIENT_VERSION = "7.20260114.12.00"
private const val YOUTUBE_PLAYER_TV_USER_AGENT =
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
        "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
private const val YOUTUBE_PLAYER_TV_DOWNGRADED_CLIENT_VERSION = "5.20260114"
private const val YOUTUBE_PLAYER_TV_DOWNGRADED_USER_AGENT =
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
private const val YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_ID = "21"
private const val YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME = "ANDROID_MUSIC"
private const val YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_VERSION = "8.15.51"
private const val YOUTUBE_PLAYER_ANDROID_MUSIC_USER_AGENT =
    "com.google.android.apps.youtube.music/8.15.51 (Linux; U; Android 14) gzip"
private const val YOUTUBE_PLAYER_WEB_REMIX_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
private const val YOUTUBE_PLAYER_WEB_REMIX_ACCEPT_HEADER =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
        "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_FORM_FACTOR = "UNKNOWN_FORM_FACTOR"
private const val YOUTUBE_PLAYER_WEB_REMIX_PLAYER_TYPE = "UNIPLAYER"
private const val YOUTUBE_PLAYER_WEB_REMIX_UI_THEME = "USER_INTERFACE_THEME_LIGHT"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_SCREEN = "WATCH_FULL_SCREEN"
private const val YOUTUBE_PLAYER_WEB_REMIX_CONNECTION_TYPE = "CONN_CELLULAR_4G"
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS = 771
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS = 897
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_PIXEL_DENSITY = 1
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_DENSITY_FLOAT = 1.375
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_WIDTH = 2048
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_HEIGHT = 1152
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_WIDTH = 2048
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_HEIGHT = 1104
private const val YOUTUBE_PLAYER_WEB_REMIX_INNER_WIDTH = 757
private const val YOUTUBE_PLAYER_WEB_REMIX_COLOR_DEPTH = 32
private const val YOUTUBE_PLAYER_WEB_REMIX_BROWSER_CONNECTION = 31
private const val YOUTUBE_PLAYER_WEB_REMIX_HISTORY_LENGTH = 5
private const val YOUTUBE_PLAYER_PLAYBACK_LACT_MILLISECONDS = "9"
// 首播更看重尽快落到可播链路, 别在 fallback 前白等太久的 PO token
private const val WEB_REMIX_PO_TOKEN_PREFETCH_JOIN_TIMEOUT_MS = 150L
// 普通播放不为低概率的后续候选逐个启动 EJS, 失败后尽快交给 TVHTML5
private const val WEB_REMIX_PLAYBACK_MAX_CANDIDATES = 2
private const val PLAYABLE_URL_EXPIRY_SAFETY_MARGIN_MS = 90L * 1000L
// EJS solver 内部按 solverLock 串行, 预取并发再高也不增加求解吞吐
// 只会把用户点击前面的排队深度成倍拉长, 每首歌还要占 sig 和 n 两次
private const val MAX_CONCURRENT_PREFETCH_RESOLVES = 1

private const val NEWPIPE_FALLBACK_START_DELAY_MS = 40L
private const val CIPHER_RESOLVE_TIMEOUT_MS = 12_000L
// 脏 IP 下 429/503 退避基数与上限, 避免密集重试进一步拉高限流等级 (#Y5)
private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L
private const val RATE_LIMIT_BACKOFF_MAX_MS = 5_000L

private const val YOUTUBE_PLAYER_API_FORMAT_VERSION = "2"
private const val STREAMING_CIPHER_LOG_THRESHOLD_MS = 250L
private const val YOUTUBE_PLAYBACK_DIAG_PREFIX = "[YT-DIAG-20260530]"

private fun playbackElapsedMs(startedAtMs: Long): Long = System.currentTimeMillis() - startedAtMs

/**
 * 携带 HTTP 状态码与 Retry-After 的请求失败异常
 * 仍继承 IOException, 保持既有 catch(IOException) 与消息正则解析 (401/403/429) 兼容
 */
internal class YouTubeHttpStatusException(
    val statusCode: Int,
    val retryAfterMs: Long?,
    message: String
) : IOException(message)

/** 解析 Retry-After: 仅支持 delta-seconds 整数秒形式, HTTP-date 形式忽略并走指数退避 */
@VisibleForTesting
internal fun parseRetryAfterMs(headerValue: String?): Long? {
    val seconds = headerValue?.trim()?.toLongOrNull() ?: return null
    return if (seconds >= 0L) seconds * 1000L else null
}

/** 429/503 退避时长: 优先 Retry-After, 否则按累计命中次数指数退避, 统一封顶 */
@VisibleForTesting
internal fun rateLimitBackoffMs(error: Throwable?, priorHits: Int): Long? {
    val status = error as? YouTubeHttpStatusException ?: return null
    if (status.statusCode != 429 && status.statusCode != 503) {
        return null
    }
    status.retryAfterMs?.let { return it.coerceIn(0L, RATE_LIMIT_BACKOFF_MAX_MS) }
    return (RATE_LIMIT_BACKOFF_BASE_MS shl priorHits.coerceIn(0, 3))
        .coerceAtMost(RATE_LIMIT_BACKOFF_MAX_MS)
}

@VisibleForTesting
internal object NewPipeFallbackTracker {
    /**
     * 一次失败就够
     *
     * NewPipe 的反混淆是拿固定正则去套 player.js, 同一版地址要么匹配要么不匹配, 没有偶发;
     * 等第二次样本等于让冷启动的头两首各白付三秒多
     */
    private const val FAILURE_THRESHOLD = 1
    private val signatureFailures = ConcurrentHashMap<String, AtomicInteger>()

    private val throttlingFailures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var store: YouTubeNewPipeFallbackStore? = null

    /** 存档只在进程内挂一次, 之后每次记录都同步写回 */
    fun attachStore(newStore: YouTubeNewPipeFallbackStore) {
        if (store != null) {
            return
        }
        synchronized(this) {
            if (store != null) {
                return
            }
            store = newStore
            newStore.load()?.let { snapshot ->
                snapshot.signature.forEach { key ->
                    signatureFailures.computeIfAbsent(key) { AtomicInteger() }.set(FAILURE_THRESHOLD)
                }
                snapshot.throttling.forEach { key ->
                    throttlingFailures.computeIfAbsent(key) { AtomicInteger() }.set(FAILURE_THRESHOLD)
                }
            }
        }
    }

    fun maybeSkipSignature(playerJsUrl: String): Boolean {
        val key = playerJsUrl.ifBlank { "<unknown-signature>" }
        return signatureFailures[key]?.get() ?: 0 >= FAILURE_THRESHOLD
    }

    fun maybeSkipThrottling(playerJsUrl: String): Boolean {
        val key = playerJsUrl.ifBlank { "<unknown-throttling>" }
        return throttlingFailures[key]?.get() ?: 0 >= FAILURE_THRESHOLD
    }

    fun recordSignatureFailure(playerJsUrl: String) {
        val key = playerJsUrl.ifBlank { "<unknown-signature>" }
        signatureFailures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        persist(signatureKey = key, throttlingKey = null)
    }

    fun recordThrottlingFailure(playerJsUrl: String) {
        val key = playerJsUrl.ifBlank { "<unknown-throttling>" }
        throttlingFailures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        persist(signatureKey = null, throttlingKey = key)
    }

    private fun persist(signatureKey: String?, throttlingKey: String?) {
        val target = store ?: return
        synchronized(this) {
            val snapshot = target.load() ?: NewPipeFallbackSnapshot()
            target.save(
                snapshot.copy(
                    signature = signatureKey
                        ?.let { retainRecentNewPipeFallbackKeys(snapshot.signature, it) }
                        ?: snapshot.signature,
                    throttling = throttlingKey
                        ?.let { retainRecentNewPipeFallbackKeys(snapshot.throttling, it) }
                        ?: snapshot.throttling
                )
            )
        }
    }

    fun reset() {
        signatureFailures.clear()
        throttlingFailures.clear()
    }
}

/**
 * 记录哪些 player client 正在稳定地拒绝请求
 *
 * ANDROID_MUSIC 在部分账号和出口上恒回 400 INVALID_ARGUMENT, 换 bootstrap 也没用
 * 每次解析都白付一次往返; 连续失败到阈值后先停一段时间, 成功一次就恢复
 */
/** player.js 地址到 STS 的进程级缓存，地址带版本哈希所以无需失效 */
private val signatureTimestampCache = ConcurrentHashMap<String, Int>()

internal object PlayerClientHealthTracker {
    private const val FAILURE_THRESHOLD = 3
    private const val SUPPRESSION_WINDOW_MS = 30L * 60L * 1000L

    private val failures = ConcurrentHashMap<String, AtomicInteger>()
    private val suppressedUntilMs = ConcurrentHashMap<String, Long>()

    fun isSuppressed(clientName: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = suppressedUntilMs[clientName] ?: return false
        if (nowMs >= until) {
            suppressedUntilMs.remove(clientName)
            failures.remove(clientName)
            return false
        }
        return true
    }

    fun recordRejection(clientName: String, nowMs: Long = System.currentTimeMillis()) {
        val hits = failures.computeIfAbsent(clientName) { AtomicInteger() }.incrementAndGet()
        if (hits >= FAILURE_THRESHOLD) {
            suppressedUntilMs[clientName] = nowMs + SUPPRESSION_WINDOW_MS
        }
    }

    fun recordSuccess(clientName: String) {
        failures.remove(clientName)
        suppressedUntilMs.remove(clientName)
    }

    fun reset() {
        failures.clear()
        suppressedUntilMs.clear()
    }
}

/**
 * 全部候选都被压制时至少留一个, 否则解析会直接无路可走
 */
internal fun <T> selectUsablePlayerClients(
    profiles: List<T>,
    clientName: (T) -> String,
    isSuppressed: (String) -> Boolean
): List<T> {
    val usable = profiles.filterNot { isSuppressed(clientName(it)) }
    return usable.ifEmpty { profiles }
}

enum class YouTubePlayableStreamType {
    DIRECT,
    HLS
}

data class YouTubePlayableAudio(
    val url: String,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null
)

private enum class DirectRangeVerificationStatus {
    READABLE,
    NON_PARTIAL_CONTENT,
    EMPTY_BODY,
    NO_BYTES_READ,
    REQUEST_FAILED
}

private data class DirectRangeVerificationResult(
    val status: DirectRangeVerificationStatus,
    val httpCode: Int?,
    val bytesRead: Long,
    val elapsedMs: Long
) {
    val isReadable: Boolean
        get() = status == DirectRangeVerificationStatus.READABLE
}

private fun YouTubePlayableAudio.missingPoTokenDiagnosticMetadata(clientName: String): String {
    val audioItag = extractStreamQueryParameter(url, "itag")
        ?.takeIf { it.all(Char::isDigit) }
        ?: "<unknown>"
    return "client=$clientName " +
        "itag=$audioItag " +
        "mimeType=${mimeType ?: "<unknown>"} " +
        "bitrate=${bitrateKbps ?: "<unknown>"} " +
        "sourceKind=$streamType " +
        "contentLength=${contentLength ?: "<unknown>"}"
}

internal data class YouTubeAudioMetadata(
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null
)

/**
 * 这次解析结果是不是在把登录态往下掉
 *
 * 手里还攥着登录 cookie 却解析出游客态, 那就是服务端这一次没认出来, 不是事实;
 * 之前只在"缓存里已经有登录态"时才拦, 于是冷启动或缓存本身是游客态时这份会一路落盘,
 * 而落盘的游客态会一直粘着, 下次冷启动直接从掉登录开局
 */
internal fun demotesYouTubeLogin(
    parsedLoggedIn: Boolean,
    holdsLoginCookies: Boolean
): Boolean = holdsLoginCookies && !parsedLoggedIn

/**
 * 这份结果能不能顶掉内存里那份
 *
 * 游客态的 bootstrap 里 apiKey/visitorData/clientVersion/STS 全都是能用的, 播放照样成,
 * 所以只在已经握着一份登录态时才拒绝覆盖; 一律不缓存会让出口被判游客时缓存永远建不起来,
 * 每次播放都要现拉现解析, 那几秒会一比一落在首播上
 */
internal fun demotesCachedYouTubeLogin(
    cachedLoggedIn: Boolean?,
    parsedLoggedIn: Boolean,
    holdsLoginCookies: Boolean
): Boolean = cachedLoggedIn == true && demotesYouTubeLogin(parsedLoggedIn, holdsLoginCookies)

/**
 * 这份存档还能不能拿来垫一次播放
 *
 * 只看年龄不看指纹: 换账号时 clearAuthBoundCaches 已经把缓存清空了, 能留到这里的
 * 就是同一个身份; 指纹在启动早期会因为 auth 还在加载而短暂漂移, 为此丢掉一份好存档
 * 等于把十几秒的解析摆回首播路径上
 */
internal fun isUsableStaleBootstrap(
    bootstrap: YouTubePlaybackBootstrap,
    nowMs: Long,
    maxAgeMs: Long = BOOTSTRAP_SNAPSHOT_MAX_AGE_MS
): Boolean {
    if (bootstrap.apiKey.isBlank() || bootstrap.playerJsUrl.isBlank()) {
        return false
    }
    val ageMs = nowMs - bootstrap.fetchedAtMs
    return ageMs in 0L until maxAgeMs
}

/**
 * 已经排上一次加载时还要不要真的等它
 *
 * 只有 forceRefresh 明确要新的, 或者手上什么都没有时才值得等
 */
internal fun shouldAwaitBootstrapLoad(
    forceRefresh: Boolean,
    hasUsableStaleBootstrap: Boolean
): Boolean = forceRefresh || !hasUsableStaleBootstrap

@VisibleForTesting
internal fun resolveYouTubeSignatureTimestamp(
    bootstrapTimestamp: Int?,
    cachedTimestamp: Int?
): Int? = bootstrapTimestamp ?: cachedTimestamp

/**
 * 重试之前值不值得先换一份新 bootstrap
 *
 * 每个 client 都回了 status=OK, 只是候选流的签名解不出来, 那这份 bootstrap 没有任何问题;
 * 重拉一次要十几秒还换不来不同的结果, 等于把首播白白推后
 */
internal fun shouldRefreshBootstrapBeforePlayerRetry(
    refreshRequestedByFallback: Boolean,
    sawUndecipherableOkResponse: Boolean,
    sawBootstrapSuspectOutcome: Boolean,
    sawOkResponse: Boolean = false
): Boolean {
    if (refreshRequestedByFallback) {
        // WEB_REMIX 已经回 OK 但签名/选流失败时, TVHTML5 的终态回退只是在换 client
        // 不代表首页配置坏了, 强刷首页只会把解析成本再压回首播路径
        return !sawOkResponse || !sawUndecipherableOkResponse
    }
    // 已经拿到 OK 说明 bootstrap 与账号上下文能用, 重拉首页不会修复签名或选流问题
    if (sawOkResponse) {
        return false
    }
    if (sawBootstrapSuspectOutcome) {
        return true
    }
    // 一次 OK 响应都没见过时说明还没问到任何结论, 保持原来的重拉行为
    return !sawUndecipherableOkResponse
}

@VisibleForTesting
internal fun shouldMarkBootstrapSuspectOutcome(
    playabilityStatus: String,
    sawUndecipherableOkResponse: Boolean
): Boolean {
    return !playabilityStatus.equals("UNPLAYABLE", ignoreCase = true) ||
        !sawUndecipherableOkResponse
}

@VisibleForTesting
internal fun shouldAbortPlayerRetryAfterTerminalFallback(
    sawUndecipherableOkResponse: Boolean,
    sawTerminalFallbackOutcome: Boolean,
    sawPlayerRequestFailure: Boolean
): Boolean {
    return sawUndecipherableOkResponse &&
        sawTerminalFallbackOutcome &&
        !sawPlayerRequestFailure
}

@VisibleForTesting
internal fun shouldStopRemainingPlayerFallbackRequests(
    clientName: String,
    playabilityStatus: String,
    sawUndecipherableOkResponse: Boolean
): Boolean {
    if (!clientName.equals(YOUTUBE_PLAYER_TV_CLIENT_NAME, ignoreCase = true)) {
        return false
    }
    if (!sawUndecipherableOkResponse) {
        return false
    }
    return playabilityStatus.equals("LOGIN_REQUIRED", ignoreCase = true) ||
        playabilityStatus.equals("UNPLAYABLE", ignoreCase = true)
}

@Serializable
internal data class YouTubePlaybackBootstrap(
    val apiKey: String,
    val webRemixClientVersion: String,
    val visitorData: String,
    val playerJsUrl: String,
    /** 整串登录 cookie 不落盘, 恢复存档时按当时的 auth 重新拼一份 */
    @Transient val cookieHeader: String = "",
    val authFingerprint: String,
    val sessionIndex: String,
    val userAgent: String,
    val remoteHost: String,
    val signatureTimestamp: Int?,
    val appInstallData: String,
    val coldConfigData: String,
    val coldHashData: String,
    val hotHashData: String,
    val deviceExperimentId: String,
    val rolloutToken: String,
    val dataSyncId: String,
    val delegatedSessionId: String,
    val userSessionId: String,
    val loggedIn: Boolean,
    val fetchedAtMs: Long,
    val version: Int = BOOTSTRAP_SNAPSHOT_VERSION_CURRENT
)

/** 播放和下载共用一份 bootstrap, 避免两个仓库各自拿旧版本去请求 player */
class YouTubePlaybackBootstrapCoordinator {
    @Volatile
    internal var cache: YouTubePlaybackBootstrap? = null

    internal val requestLock = Any()
    internal val inFlightRequests =
        linkedMapOf<InFlightBootstrapRequest, Deferred<YouTubePlaybackBootstrap>>()
    internal val loadMutex = Mutex()
}

private data class YouTubeWebRemixRequestMetadata(
    val originalUrl: String,
    val watchUrl: String,
    val playlistId: String,
    val cpn: String,
    val clientScreenNonce: String
)

private data class CachedPlayableAudio(
    val audio: YouTubePlayableAudio,
    val cachedAtMs: Long,
    val expiresAtMs: Long
)

private data class InFlightPlayableAudioRequest(
    val videoId: String,
    val preferredQualityKey: String,
    val requireDirect: Boolean,
    val preferM4a: Boolean,
    val forceRefresh: Boolean,
    // 参与去重键, 否则 avoidDirect 会复用偏好直链的在途结果
    val avoidDirect: Boolean
)

/**
 * 在途解析及其认领状态
 *
 * 预取建好的解析会被后到的按需请求直接复用, 认领之后它就不该再被闸门拦着,
 * 也不该在清理排队预取时被顺手取消
 */
private class InFlightPlayableAudioEntry(
    val deferred: Deferred<YouTubePlayableAudio?>,
    val onDemandSignal: CompletableDeferred<Unit>
) {
    val isOnDemand: Boolean
        get() = onDemandSignal.isCompleted

    /** 返回 true 表示本次调用把它从预取提升成了按需 */
    fun promote(): Boolean = onDemandSignal.complete(Unit)
}

internal data class InFlightBootstrapRequest(
    val authFingerprint: String,
    val forceRefresh: Boolean
)

internal data class ChallengeCandidateResult<T>(
    val source: String,
    val value: T?,
    val elapsedMs: Long
)

private data class YouTubePlayerAudioCandidate(
    val format: JSONObject,
    val mimeType: String?,
    val bitrate: Int,
    val audioSampleRate: Int,
    val contentLength: Long?,
    val durationMs: Long
)

interface YouTubeStreamingCipherResolver {
    fun resolveSignature(encryptedSignature: String): String?
    fun resolveStreamingUrl(url: String): String

    suspend fun resolveSignatureAsync(encryptedSignature: String): String? {
        return resolveSignature(encryptedSignature)
    }

    suspend fun resolveStreamingUrlAsync(url: String): String {
        return resolveStreamingUrl(url)
    }

    /**
     * 把 sig 和 n 一次算完填进求解器缓存
     *
     * 分开求解要各建一次 isolate 再各传一遍 player.js, 固定成本付两遍;
     * 合并算一次之后, 后面两步照原样跑但直接命中缓存
     * 这一步失败什么都不做, 完整退回原来的两步路径
     */
    fun prewarmChallenges(
        encryptedSignature: String?,
        obfuscatedThrottlingParameter: String?
    ) = Unit

    suspend fun prewarmChallengesAsync(
        encryptedSignature: String?,
        obfuscatedThrottlingParameter: String?
    ) {
        prewarmChallenges(encryptedSignature, obfuscatedThrottlingParameter)
    }
}

private fun isPlayableM4aContainer(mimeType: String?): Boolean {
    return when (mimeType?.lowercase(Locale.US)) {
        "audio/mp4", "audio/m4a", "audio/aac" -> true
        else -> false
    }
}

private fun playableAudioMimePreferenceScore(mimeType: String?): Int {
    return when {
        mimeType?.lowercase(Locale.US) == MimeTypes.APPLICATION_M3U8.lowercase(Locale.US) -> 3
        isPlayableM4aContainer(mimeType) -> 2
        mimeType?.lowercase(Locale.US) == "audio/webm" -> 1
        else -> 0
    }
}

internal suspend fun <T> awaitFirstChallengeSuccess(
    candidates: List<Deferred<ChallengeCandidateResult<T>>>
): ChallengeCandidateResult<T>? = coroutineScope {
    val pending = candidates.toMutableList()
    while (pending.isNotEmpty()) {
        val (selected, candidate) = select<Pair<Deferred<ChallengeCandidateResult<T>>, ChallengeCandidateResult<T>>> {
            pending.forEach { deferred ->
                deferred.onAwait { deferred to it }
            }
        }
        pending.remove(selected)
        if (candidate.value != null) {
            pending.forEach { deferred -> deferred.cancel() }
            return@coroutineScope candidate
        }
    }
    null
}

/** 只读已完成且未取消的候选, 避免对被取消的 Deferred await 抛 CancellationException */
internal suspend fun <T> Deferred<ChallengeCandidateResult<T>>?.hasFailedChallenge(): Boolean {
    val deferred = this ?: return false
    if (!deferred.isCompleted || deferred.isCancelled) return false
    return runCatching { deferred.await().value }.getOrNull() == null
}

internal data class YouTubePlayerPlayabilityStatus(
    val status: String,
    val reason: String
)

private data class PlayerAudioResolution(
    val playableAudio: YouTubePlayableAudio? = null,
    val metadata: YouTubeAudioMetadata? = null
)

private data class YouTubePlayerClientProfile(
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
    val wrapPlayerRequest: Boolean = false
)

private enum class YouTubeMusicPlaybackQuality {
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH;

    companion object {
        fun fromSetting(settingKey: String?): YouTubeMusicPlaybackQuality {
            return when (settingKey?.lowercase(Locale.US)) {
                "low",
                "standard" -> LOW
                "medium" -> MEDIUM
                "high",
                "higher" -> HIGH
                "very_high",
                "very-high",
                "exhigh",
                "lossless",
                "hires",
                "jyeffect",
                "sky",
                "jymaster" -> VERY_HIGH
                else -> VERY_HIGH
            }
        }
    }
}

internal fun satisfiesYouTubePlaybackQuality(
    playableAudio: YouTubePlayableAudio,
    preferredQualityKey: String?
): Boolean {
    val minimumBitrateKbps = when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
        YouTubeMusicPlaybackQuality.LOW -> null
        YouTubeMusicPlaybackQuality.MEDIUM -> 96
        YouTubeMusicPlaybackQuality.HIGH -> 128
        YouTubeMusicPlaybackQuality.VERY_HIGH -> 160
    }
    return minimumBitrateKbps == null ||
        playableAudio.bitrateKbps?.let { it >= minimumBitrateKbps } == true
}

internal object YouTubeMusicPlaybackParser {
    fun parsePlayableAudio(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false,
        cipherResolver: YouTubeStreamingCipherResolver? = null,
        maxCandidateCount: Int = Int.MAX_VALUE
    ): YouTubePlayableAudio? {
        val candidates = selectCandidate(
            candidates = collectAudioCandidates(root),
            preferredQualityKey = preferredQualityKey,
            preferM4a = preferM4a
        ).take(maxCandidateCount.coerceAtLeast(0))
        val durationFallbackMs = parseDurationMs(root)
        // 丢掉一个候选就白付一次完整求解, 单条求解日志被一次性守卫盖住了看不出轮数
        var discardedCandidates = 0
        for (candidate in candidates) {
            val playableUrl = resolveFormatUrl(candidate.format, cipherResolver)
            if (playableUrl.isBlank()) {
                discardedCandidates++
                continue
            }
            if (discardedCandidates > 0) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "playable audio candidate fallback: discarded=$discardedCandidates, total=${candidates.size}, acceptedMime=${candidate.mimeType}, acceptedBitrate=${candidate.bitrate}"
                )
            }
            return YouTubePlayableAudio(
                url = playableUrl,
                durationMs = candidate.durationMs.takeIf { it > 0L } ?: durationFallbackMs,
                mimeType = candidate.mimeType,
                contentLength = candidate.contentLength,
                bitrateKbps = candidate.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 },
                sampleRateHz = candidate.audioSampleRate.takeIf { it > 0 }
            )
        }
        if (discardedCandidates > 0) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "playable audio has no usable candidate: discarded=$discardedCandidates, " +
                    "total=${candidates.size}, diagnostics=" +
                    describeAudioCandidates(root)
            )
        }
        return null
    }

    suspend fun parsePlayableAudioAsync(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false,
        cipherResolver: YouTubeStreamingCipherResolver? = null,
        maxCandidateCount: Int = Int.MAX_VALUE
    ): YouTubePlayableAudio? {
        val candidates = selectCandidate(
            candidates = collectAudioCandidates(root),
            preferredQualityKey = preferredQualityKey,
            preferM4a = preferM4a
        ).take(maxCandidateCount.coerceAtLeast(0))
        val durationFallbackMs = parseDurationMs(root)
        var discardedCandidates = 0
        for (candidate in candidates) {
            val playableUrl = resolveFormatUrlAsync(candidate.format, cipherResolver)
            if (playableUrl.isBlank()) {
                discardedCandidates++
                continue
            }
            if (discardedCandidates > 0) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "playable audio candidate fallback: discarded=$discardedCandidates, total=${candidates.size}, acceptedMime=${candidate.mimeType}, acceptedBitrate=${candidate.bitrate}"
                )
            }
            return YouTubePlayableAudio(
                url = playableUrl,
                durationMs = candidate.durationMs.takeIf { it > 0L } ?: durationFallbackMs,
                mimeType = candidate.mimeType,
                contentLength = candidate.contentLength,
                bitrateKbps = candidate.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 },
                sampleRateHz = candidate.audioSampleRate.takeIf { it > 0 }
            )
        }
        if (discardedCandidates > 0) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "playable audio has no usable candidate: discarded=$discardedCandidates, " +
                    "total=${candidates.size}, diagnostics=" +
                    describeAudioCandidates(root)
            )
        }
        return null
    }

    /** 只输出候选结构, 便于定位 player.js/格式变化而不泄露直链或签名 */
    fun describeAudioCandidates(root: JSONObject): String {
        return collectAudioCandidates(root).joinToString(separator = ";") { candidate ->
            val format = candidate.format
            val directUrl = format.optString("url").isNotBlank()
            val cipher = format.optString("signatureCipher")
                .ifBlank { format.optString("cipher") }
                .trim()
            val cipherParams = parseUrlEncodedQuery(cipher)
            val rawUrl = if (directUrl) format.optString("url") else cipherParams["url"].orEmpty()
            val hasN = extractStreamQueryParameter(rawUrl, "n") != null
            val hasS = cipherParams["s"].orEmpty().isNotBlank()
            val hasSignature = cipherParams["sig"].orEmpty().isNotBlank() ||
                cipherParams["signature"].orEmpty().isNotBlank()
            "mime=${candidate.mimeType ?: "?"},bitrate=${candidate.bitrate}," +
                "direct=$directUrl,cipher=${cipher.isNotBlank()},n=$hasN,s=$hasS,sig=$hasSignature"
        }
    }

    // 判断原始响应是否本就包含 googlevideo direct 音频流
    // 用于识别"WEB_REMIX 返回了 direct 流, 但候选因 n 无法解出被 #Y4 丢弃"的场景:
    // 这类场景换 locale 不会改变 player.js/n 的可解性, 应直接切换下一 client 而非重试当前 client 其他 locale
    fun hasDirectGoogleVideoAudioStream(root: JSONObject): Boolean {
        return collectAudioCandidates(root).any { candidate ->
            val rawUrl = resolveFormatUrl(candidate.format, cipherResolver = null)
            rawUrl.isNotBlank() && isYouTubeGoogleVideoStream(rawUrl)
        }
    }

    fun parsePreferredAudioMetadata(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false
    ): YouTubeAudioMetadata? {
        val candidates = collectAudioCandidates(root)
        val selected = selectCandidate(candidates, preferredQualityKey, preferM4a).firstOrNull()

        val durationMs = selected?.durationMs?.takeIf { it > 0L } ?: parseDurationMs(root)
        val mimeType = selected?.mimeType
        val contentLength = selected?.contentLength
        if (durationMs <= 0L && mimeType.isNullOrBlank() && contentLength == null) {
            return null
        }
        return YouTubeAudioMetadata(
            durationMs = durationMs,
            mimeType = mimeType,
            contentLength = contentLength
        )
    }

    fun parsePlayabilityStatus(root: JSONObject): YouTubePlayerPlayabilityStatus {
        val playabilityStatus = root.optJSONObject("playabilityStatus")
        return YouTubePlayerPlayabilityStatus(
            status = playabilityStatus?.optString("status").orEmpty(),
            reason = playabilityStatus?.optString("reason").orEmpty()
        )
    }

    private fun collectAudioCandidates(
        root: JSONObject
    ): List<YouTubePlayerAudioCandidate> {
        val streamingData = root.optJSONObject("streamingData") ?: return emptyList()
        val formatArrays = listOfNotNull(
            streamingData.optJSONArray("adaptiveFormats"),
            streamingData.optJSONArray("formats")
        )

        return buildList {
            formatArrays.forEach { formats ->
                for (index in 0 until formats.length()) {
                    val format = formats.optJSONObject(index) ?: continue
                    val mimeType = normalizeMimeType(format.optString("mimeType"))
                    if (mimeType?.startsWith("audio/") != true) {
                        continue
                    }
                    add(
                        YouTubePlayerAudioCandidate(
                            format = format,
                            mimeType = mimeType,
                            bitrate = parseIntLike(
                                format.opt("bitrate"),
                                format.opt("averageBitrate")
                            ),
                            audioSampleRate = parseIntLike(format.opt("audioSampleRate")),
                            contentLength = parseLongLike(format.opt("contentLength"))
                                .takeIf { it > 0L },
                            durationMs = parseLongLike(format.opt("approxDurationMs"))
                        )
                    )
                }
            }
        }
    }

    private fun resolveFormatUrl(
        format: JSONObject,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        val directUrl = format.optString("url").trim()
        if (directUrl.isNotBlank()) {
            return resolveStreamingUrl(directUrl, cipherResolver)
        }

        val cipher = format.optString("signatureCipher")
            .ifBlank { format.optString("cipher") }
            .trim()
        if (cipher.isBlank()) {
            return ""
        }

        val params = parseUrlEncodedQuery(cipher)
        val url = params["url"]?.decodeUrlComponent().orEmpty()
        if (url.isBlank()) {
            return ""
        }

        val signature = params["sig"].orEmpty().ifBlank { params["signature"].orEmpty() }
        if (signature.isBlank()) {
            if (!params.containsKey("s")) {
                return resolveStreamingUrl(url, cipherResolver)
            }
            // s 和 URL 里的 n 此刻都在手, 先合并求解一次省掉第二次的固定成本
            cipherResolver?.prewarmChallenges(
                encryptedSignature = params["s"],
                obfuscatedThrottlingParameter = extractStreamQueryParameter(url, "n")
            )
            val decryptedSignature = params["s"]
                ?.takeIf { it.isNotBlank() }
                ?.let { cipherResolver?.resolveSignature(it) }
                .orEmpty()
            if (decryptedSignature.isBlank()) {
                return ""
            }
            val signatureParameter = params["sp"].orEmpty().ifBlank { "sig" }
            return resolveStreamingUrl(
                appendQueryParameter(url, signatureParameter, decryptedSignature),
                cipherResolver
            )
        }

        val signatureParameter = params["sp"].orEmpty().ifBlank { "sig" }
        return resolveStreamingUrl(
            appendQueryParameter(url, signatureParameter, signature),
            cipherResolver
        )
    }

    private fun resolveStreamingUrl(
        url: String,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        if (url.isBlank()) {
            return ""
        }
        // 无 resolver 时保持原样返回, 不破坏正常路径
        val resolver = cipherResolver ?: return url
        // resolver 对"带 n 参数但解不出"的候选返回空串表示该候选不可用
        // 必须原样透传空串让 parsePlayableAudio 跳过该候选
        // 而非回退到带混淆 n 的原 URL (会被 googlevideo 限速, #Y4/#257)
        return resolver.resolveStreamingUrl(url)
    }

    private suspend fun resolveFormatUrlAsync(
        format: JSONObject,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        val directUrl = format.optString("url").trim()
        if (directUrl.isNotBlank()) {
            return resolveStreamingUrlAsync(directUrl, cipherResolver)
        }

        val cipher = format.optString("signatureCipher")
            .ifBlank { format.optString("cipher") }
            .trim()
        if (cipher.isBlank()) {
            return ""
        }

        val params = parseUrlEncodedQuery(cipher)
        val url = params["url"]?.decodeUrlComponent().orEmpty()
        if (url.isBlank()) {
            return ""
        }

        val signature = params["sig"].orEmpty().ifBlank { params["signature"].orEmpty() }
        if (signature.isBlank()) {
            if (!params.containsKey("s")) {
                return resolveStreamingUrlAsync(url, cipherResolver)
            }
            cipherResolver?.prewarmChallengesAsync(
                encryptedSignature = params["s"],
                obfuscatedThrottlingParameter = extractStreamQueryParameter(url, "n")
            )
            val decryptedSignature = params["s"]
                ?.takeIf { it.isNotBlank() }
                ?.let { cipherResolver?.resolveSignatureAsync(it) }
                .orEmpty()
            if (decryptedSignature.isBlank()) {
                return ""
            }
            val signatureParameter = params["sp"].orEmpty().ifBlank { "sig" }
            return resolveStreamingUrlAsync(
                appendQueryParameter(url, signatureParameter, decryptedSignature),
                cipherResolver
            )
        }

        val signatureParameter = params["sp"].orEmpty().ifBlank { "sig" }
        return resolveStreamingUrlAsync(
            appendQueryParameter(url, signatureParameter, signature),
            cipherResolver
        )
    }

    private suspend fun resolveStreamingUrlAsync(
        url: String,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        if (url.isBlank()) {
            return ""
        }
        val resolver = cipherResolver ?: return url
        return resolver.resolveStreamingUrlAsync(url)
    }

    private fun parseDurationMs(root: JSONObject): Long {
        return parseLongLike(
            root.optJSONObject("videoDetails")?.opt("lengthSeconds")
        ).takeIf { it > 0L }?.times(1000L)
            ?: parseLongLike(
                root.optJSONObject("microformat")
                    ?.optJSONObject("playerMicroformatRenderer")
                    ?.opt("lengthSeconds")
            ).takeIf { it > 0L }?.times(1000L)
            ?: 0L
    }

    private fun normalizeMimeType(rawMimeType: String): String? {
        return rawMimeType
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun isM4aAudioContainer(mimeType: String?): Boolean {
        return when (mimeType?.lowercase(Locale.US)) {
            "audio/mp4", "audio/m4a", "audio/aac" -> true
            else -> false
        }
    }

    private fun mimePreferenceScore(mimeType: String?): Int {
        return when {
            isM4aAudioContainer(mimeType) -> 2
            mimeType?.lowercase(Locale.US) == "audio/webm" -> 1
            else -> 0
        }
    }

    private fun selectCandidate(
        candidates: List<YouTubePlayerAudioCandidate>,
        preferredQualityKey: String?,
        preferM4a: Boolean = false
    ): List<YouTubePlayerAudioCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        if (!preferM4a) {
            return orderCandidatesByQualityTier(
                candidates = candidates,
                preferredQualityKey = preferredQualityKey,
                comparator = audioCandidateComparator()
            )
        }
        // preferM4a (尤其下载/requireDirect) 把容器为 m4a/mp4/aac 作为硬性首要键:
        // 先在可打标的 m4a 候选内部按画质挡位排序, 只有完全没有 m4a 时才回退 webm/opus
        // 避免下载落到无法内嵌标签的 webm (#Y3/#223)
        val comparator = compareByDescending<YouTubePlayerAudioCandidate> { mimePreferenceScore(it.mimeType) }
            .thenByDescending { it.bitrate }
            .thenByDescending { it.audioSampleRate }
            .thenByDescending { it.contentLength ?: 0L }
        val m4aCandidates = candidates.filter { isM4aAudioContainer(it.mimeType) }
        val fallbackCandidates = candidates.filterNot { isM4aAudioContainer(it.mimeType) }
        return orderCandidatesByQualityTier(m4aCandidates, preferredQualityKey, comparator) +
            orderCandidatesByQualityTier(fallbackCandidates, preferredQualityKey, comparator)
    }

    private fun orderCandidatesByQualityTier(
        candidates: List<YouTubePlayerAudioCandidate>,
        preferredQualityKey: String?,
        comparator: Comparator<YouTubePlayerAudioCandidate>
    ): List<YouTubePlayerAudioCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val sortedDescending = candidates.sortedWith(comparator)
        val sortedAscending = sortedDescending.asReversed()
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending
            YouTubeMusicPlaybackQuality.MEDIUM -> prioritizeThresholdCandidate(
                sortedAscending = sortedAscending,
                thresholdBitrate = 96_000
            )
            YouTubeMusicPlaybackQuality.HIGH -> prioritizeThresholdCandidate(
                sortedAscending = sortedAscending,
                thresholdBitrate = 128_000
            )
            YouTubeMusicPlaybackQuality.VERY_HIGH -> sortedDescending
        }
    }

    private fun prioritizeThresholdCandidate(
        sortedAscending: List<YouTubePlayerAudioCandidate>,
        thresholdBitrate: Int
    ): List<YouTubePlayerAudioCandidate> {
        val preferredIndex = sortedAscending.indexOfFirst { it.bitrate >= thresholdBitrate }
        if (preferredIndex < 0) {
            return sortedAscending.asReversed()
        }
        return buildList(sortedAscending.size) {
            addAll(sortedAscending.subList(preferredIndex, sortedAscending.size))
            addAll(sortedAscending.subList(0, preferredIndex).asReversed())
        }
    }

    private fun audioCandidateComparator(): Comparator<YouTubePlayerAudioCandidate> {
        return compareByDescending<YouTubePlayerAudioCandidate> { it.bitrate }
            .thenByDescending { it.audioSampleRate }
            .thenByDescending { mimePreferenceScore(it.mimeType) }
            .thenByDescending { it.contentLength ?: 0L }
    }

    private fun parseUrlEncodedQuery(rawQuery: String): Map<String, String> {
        return rawQuery.split('&')
            .mapNotNull { segment ->
                val key = segment.substringBefore('=').decodeUrlComponent()
                if (key.isBlank()) {
                    null
                } else {
                    key to segment.substringAfter('=', "").decodeUrlComponent()
                }
            }
            .toMap()
    }

    private fun appendQueryParameter(url: String, key: String, value: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return if (Regex("(^|[?&])${Regex.escape(key)}=").containsMatchIn(url)) {
            url
        } else {
            buildString(url.length + key.length + value.length + 2) {
                append(url)
                append(separator)
                append(key)
                append('=')
                append(value.encodeUrlComponent())
            }
        }
    }

    private fun parseLongLike(vararg values: Any?): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun parseIntLike(vararg values: Any?): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun String.decodeUrlComponent(): String {
        return URLDecoder.decode(this, Charsets.UTF_8.name())
    }

    private fun String.encodeUrlComponent(): String {
        return URLEncoder.encode(this, Charsets.UTF_8.name())
    }
}

internal data class YouTubeHlsAudioPlaylist(
    val uri: String,
    val contentLength: Long? = null,
    val estimatedBitrate: Int = 0,
    val audioItag: Int? = null
)

internal object YouTubeMusicHlsManifestParser {
    fun selectAudioPlaylist(
        masterManifest: String,
        masterManifestUrl: String? = null,
        preferredQualityKey: String? = null,
        durationMs: Long = 0L
    ): YouTubeHlsAudioPlaylist? {
        val candidates = collectAudioPlaylists(
            masterManifest = masterManifest,
            masterManifestUrl = masterManifestUrl,
            durationMs = durationMs
        )
        if (candidates.isEmpty()) {
            return null
        }
        val sortedDescending = candidates.sortedWith(
            compareByDescending<YouTubeHlsAudioPlaylist> { it.estimatedBitrate }
                .thenByDescending { it.contentLength ?: 0L }
                .thenByDescending { it.audioItag ?: 0 }
        )
        val sortedAscending = sortedDescending.asReversed()
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending.first()
            YouTubeMusicPlaybackQuality.MEDIUM -> {
                sortedAscending.firstOrNull { it.estimatedBitrate >= 96_000 }
                    ?: sortedDescending.first()
            }
            YouTubeMusicPlaybackQuality.HIGH -> {
                sortedAscending.firstOrNull { it.estimatedBitrate >= 128_000 }
                    ?: sortedDescending.first()
            }
            YouTubeMusicPlaybackQuality.VERY_HIGH -> sortedDescending.first()
        }
    }

    fun collectAudioPlaylists(
        masterManifest: String,
        masterManifestUrl: String? = null,
        durationMs: Long = 0L
    ): List<YouTubeHlsAudioPlaylist> {
        return masterManifest
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .mapNotNull { line ->
                val attributes = parseAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                if (!attributes["TYPE"].equals("AUDIO", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val rawUri = attributes["URI"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val audioItag = parseAudioItag(rawUri)
                val contentLength = parseContentLength(rawUri)
                YouTubeHlsAudioPlaylist(
                    uri = resolveRelativeUri(masterManifestUrl, rawUri),
                    contentLength = contentLength,
                    estimatedBitrate = estimateBitrate(audioItag, contentLength, durationMs),
                    audioItag = audioItag
                )
            }
            .distinctBy(YouTubeHlsAudioPlaylist::uri)
            .toList()
    }

    private fun parseAttributes(rawAttributes: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
        pattern.findAll(rawAttributes).forEach { match ->
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2]
            result[key] = rawValue.trim().removeSurrounding("\"")
        }
        return result
    }

    private fun resolveRelativeUri(baseUri: String?, candidate: String): String {
        if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            return candidate
        }
        val resolvedBaseUri = baseUri?.takeIf { it.isNotBlank() } ?: return candidate
        return runCatching { URI(resolvedBaseUri).resolve(candidate).toString() }
            .getOrElse { candidate }
    }

    private fun parseAudioItag(url: String): Int? {
        val decoded = runCatching { URLDecoder.decode(url, Charsets.UTF_8.name()) }
            .getOrElse { url }
        return Regex("""(?:^|[;/?&])itag=(\d+)""")
            .findAll(decoded)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""/itag/(\d+)""")
                .find(decoded)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun parseContentLength(url: String): Long? {
        val decoded = runCatching { URLDecoder.decode(url, Charsets.UTF_8.name()) }
            .getOrElse { url }
        return Regex("""(?:^|[;/?&])clen=(\d+)""")
            .findAll(decoded)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    private fun estimateBitrate(audioItag: Int?, contentLength: Long?, durationMs: Long): Int {
        audioItagToBitrate(audioItag)?.let { return it }
        if (contentLength != null && durationMs > 0L) {
            return ((contentLength * 8_000L) / durationMs).toInt()
        }
        return 0
    }

    private fun audioItagToBitrate(itag: Int?): Int? {
        return when (itag) {
            139, 233, 249 -> 48_000
            140, 234, 250 -> 128_000
            141, 251 -> 256_000
            else -> null
        }
    }
}

/** n 和 sig 是放进 URL 查询参数的短 token, 可能携带 Base64 填充字符 */
private val CIPHER_TOKEN_PATTERN = Regex("^[A-Za-z0-9._~+\\-/=]+$")

/** JS 求值失败时的假成功返回值, 非空且与原串不同, 能骗过朴素校验 */
private val CIPHER_TOKEN_JS_JUNK = setOf(
    "undefined",
    "null",
    "nan",
    "true",
    "false",
    "[object object]"
)

/** 拦截 JS 求值的假成功返回值 */
internal fun isPlausibleCipherToken(value: String?): Boolean {
    val token = value?.trim().orEmpty()
    if (token.isEmpty()) return false
    if (token.lowercase() in CIPHER_TOKEN_JS_JUNK) return false
    return CIPHER_TOKEN_PATTERN.matches(token)
}

@VisibleForTesting
internal fun describeCipherTokenShape(value: String?): String {
    val token = value?.trim().orEmpty()
    val invalidAsciiCount = token.count { it.code < 128 && !CIPHER_TOKEN_PATTERN.matches(it.toString()) }
    val nonAsciiCount = token.count { it.code >= 128 }
    val invalidAsciiCodes = token.asSequence()
        .filter { it.code < 128 && !CIPHER_TOKEN_PATTERN.matches(it.toString()) }
        .map { it.code }
        .distinct()
        .joinToString(",")
    return "length=${token.length},invalidAscii=$invalidAsciiCount," +
        "invalidAsciiCodes=$invalidAsciiCodes,nonAscii=$nonAsciiCount"
}

/** 解密后的流地址必须带合法 n */
internal fun hasPlausibleThrottlingParameter(url: String): Boolean =
    isPlausibleCipherToken(extractStreamQueryParameter(url, "n"))

private fun extractStreamQueryParameter(url: String, key: String): String? {
    val rawQuery = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
    return rawQuery.split('&')
        .asSequence()
        .mapNotNull { segment ->
            val resolvedKey = URLDecoder.decode(
                segment.substringBefore('='),
                Charsets.UTF_8.name()
            )
            if (resolvedKey.isBlank()) {
                null
            } else {
                resolvedKey to URLDecoder.decode(
                    segment.substringAfter('=', ""),
                    Charsets.UTF_8.name()
                )
            }
        }
        .firstOrNull { (resolvedKey, _) -> resolvedKey == key }
        ?.second
}

@VisibleForTesting
internal fun resolvePlayableAudioCacheExpiresAtMs(
    url: String,
    cachedAtMs: Long,
    defaultTtlMs: Long,
    safetyMarginMs: Long = PLAYABLE_URL_EXPIRY_SAFETY_MARGIN_MS
): Long {
    val defaultExpiresAtMs = cachedAtMs + defaultTtlMs.coerceAtLeast(0L)
    val streamExpiresAtMs = extractStreamQueryParameter(url, "expire")
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { expireSeconds ->
            (expireSeconds * 1000L - safetyMarginMs.coerceAtLeast(0L))
                .coerceAtLeast(cachedAtMs)
        }
    return minOf(defaultExpiresAtMs, streamExpiresAtMs ?: defaultExpiresAtMs)
}

private fun replaceStreamQueryParameter(url: String, key: String, value: String): String {
    val pattern = Regex("([?&])${Regex.escape(key)}=[^&]*")
    return if (pattern.containsMatchIn(url)) {
        val match = pattern.find(url) ?: return url
        buildString(url.length + value.length) {
            append(url, 0, match.range.first)
            append(match.groupValues[1])
            append(key)
            append('=')
            append(URLEncoder.encode(value, Charsets.UTF_8.name()))
            append(url, match.range.last + 1, url.length)
        }
    } else {
        val separator = if (url.contains('?')) '&' else '?'
        buildString(url.length + key.length + value.length + 2) {
            append(url)
            append(separator)
            append(key)
            append('=')
            append(URLEncoder.encode(value, Charsets.UTF_8.name()))
        }
    }
}

private fun isYouTubeGoogleVideoStream(url: String): Boolean {
    val host = runCatching { URI(url).host }
        .getOrNull()
        ?.lowercase(Locale.US)
        .orEmpty()
    if (!isYouTubeGoogleVideoHost(host)) {
        return false
    }
    return extractStreamQueryParameter(url, "source")
        ?.equals("youtube", ignoreCase = true) == true
}

class YouTubeMusicPlaybackRepository(
    private val okHttpClient: OkHttpClient,
    private val settings: SettingsRepository? = null,
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() },
    private val authAutoRefreshManager: YouTubeAuthAutoRefreshManager? = null,
    private val streamingCipherResolverFactory: ((String) -> YouTubeStreamingCipherResolver)? = null,
    applicationContext: Context? = null,
    poTokenProvider: YouTubePoTokenProvider? = null,
    private val bootstrapCoordinator: YouTubePlaybackBootstrapCoordinator =
        YouTubePlaybackBootstrapCoordinator()
) {
    private val downloader = NewPipeOkHttpDownloader(okHttpClient, authProvider)
    private val playableAudioCache = linkedMapOf<String, CachedPlayableAudio>()
    private val inFlightPlayableAudio = linkedMapOf<InFlightPlayableAudioRequest, InFlightPlayableAudioEntry>()
    private val inFlightBootstrapRequests = bootstrapCoordinator.inFlightRequests
    private val inFlightPlayableAudioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefetchResolveGate = YouTubePrefetchResolveGate(MAX_CONCURRENT_PREFETCH_RESOLVES)

    private val ejsChallengeSolver = applicationContext?.let {
        YouTubeEjsChallengeSolver(it, okHttpClient)
    }
    private val poTokenProvider = poTokenProvider ?: applicationContext?.let {
        YouTubeWebPoTokenProvider(it, authProvider)
    }

    private val bootstrapStore = applicationContext?.let { YouTubeBootstrapStore(it) }

    init {
        // NewPipe 解不动哪版 player.js 是确定的, 记到下次冷启动免得再白付一次三秒
        applicationContext?.let { context ->
            runCatching { NewPipeFallbackTracker.attachStore(YouTubeNewPipeFallbackStore(context)) }
        }
    }

    private var bootstrapCache: YouTubePlaybackBootstrap?
        get() = bootstrapCoordinator.cache
        set(value) {
            bootstrapCoordinator.cache = value
        }

    /** 存档只在进程内读一次, 读不到也不必每次播放都再去碰一次磁盘 */
    @Volatile
    private var bootstrapSnapshotRestored = false

    private val bootstrapRequestLock = bootstrapCoordinator.requestLock

    /**
     * 同一时刻只允许一份 bootstrap 在解析
     *
     * 首页 HTML 是 MB 级的, 解析中途还要摊平上千条 EXPERIMENT_FLAGS; 两份一起跑会把堆压到
     * 不停触发阻塞 GC, 实测各自从一秒级劣化到二十秒
     */
    private val bootstrapLoadMutex = bootstrapCoordinator.loadMutex

    private val warmBootstrapLock = Any()

    @Volatile
    private var inFlightStaleBootstrapRefresh: Deferred<Unit>? = null

    @Volatile
    private var inFlightWarmBootstrap: Deferred<Unit>? = null

    private val inFlightSignatureTimestampWarmups = ConcurrentHashMap<String, Deferred<Int?>>()

    @Volatile
    private var authCacheGeneration: Long = 0L
    @Volatile
    private var lastAuthFingerprint: String? = null

    suspend fun getBestPlayableAudio(
        videoId: String,
        preferredQualityOverride: String? = null,
        forceRefresh: Boolean = false,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false,
        shareInFlight: Boolean = true,
        avoidDirect: Boolean = false,
        // 投机预取不认领在途解析, 否则闸门会被自己的预取一路提升到形同虚设
        isPrefetch: Boolean = false
    ): YouTubePlayableAudio? = withContext(Dispatchers.IO) {
        if (!YouTubeFeatureGate.isEnabled()) {
            throw YouTubeFeatureDisabledException()
        }
        val resolveStartedAtMs = System.currentTimeMillis()
        syncAuthBoundCachesIfNeeded(authProvider().normalized())
        val preferredQualityKey = resolvePreferredQualityKey(preferredQualityOverride)
        val cacheKey = if (preferM4a) "${preferredQualityKey}_m4a" else preferredQualityKey
        NPLogger.d(
            "YouTubeMusicPlayback",
            "getBestPlayableAudio: videoId=$videoId, quality=$preferredQualityKey, forceRefresh=$forceRefresh, requireDirect=$requireDirect, preferM4a=$preferM4a, shareInFlight=$shareInFlight, avoidDirect=$avoidDirect"
        )
        if (!forceRefresh) {
            getCachedPlayableAudio(
                videoId = videoId,
                preferredQualityKey = cacheKey,
                requireDirect = requireDirect,
                avoidDirect = avoidDirect
            )?.let { cached ->
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "getBestPlayableAudio cache hit: videoId=$videoId, type=${cached.streamType}, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
                )
                return@withContext cached
            }
        }
        if (shareInFlight) {
            resolvePlayableAudioShared(
                videoId = videoId,
                preferredQualityKey = preferredQualityKey,
                requireDirect = requireDirect,
                logFailure = true,
                preferM4a = preferM4a,
                cacheKey = cacheKey,
                forceRefresh = forceRefresh,
                avoidDirect = avoidDirect,
                isPrefetch = isPrefetch
            )
        } else {
            resolvePlayableAudio(
                videoId = videoId,
                preferredQualityKey = preferredQualityKey,
                requireDirect = requireDirect,
                logFailure = true,
                preferM4a = preferM4a,
                cacheKey = cacheKey,
                forceRefresh = forceRefresh,
                avoidDirect = avoidDirect
            )
        }
    }

    suspend fun prefetchPlayableAudioUrl(
        videoId: String,
        preferredQualityOverride: String? = null,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (!YouTubeFeatureGate.isEnabled()) {
            return@withContext
        }
        syncAuthBoundCachesIfNeeded(authProvider().normalized())
        val preferredQualityKey = resolvePreferredQualityKey(preferredQualityOverride)
        val cacheKey = if (preferM4a) "${preferredQualityKey}_m4a" else preferredQualityKey
        if (
            getCachedPlayableAudio(
                videoId = videoId,
                preferredQualityKey = cacheKey,
                requireDirect = requireDirect
            ) != null
        ) {
            return@withContext
        }
        startPlayableAudioResolution(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = false,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = false,
            isPrefetch = true
        ).await()
    }

    fun kickoffPlayableAudioPrefetch(
        videoId: String,
        preferredQualityOverride: String,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false
    ) {
        if (!YouTubeFeatureGate.isEnabled()) return
        syncAuthBoundCachesIfNeeded(authProvider().normalized())
        val preferredQualityKey = preferredQualityOverride.ifBlank { "high" }
        val cacheKey = if (preferM4a) "${preferredQualityKey}_m4a" else preferredQualityKey
        if (
            getCachedPlayableAudio(
                videoId = videoId,
                preferredQualityKey = cacheKey,
                requireDirect = requireDirect
            ) != null
        ) {
            return
        }
        startPlayableAudioResolution(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = false,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = false,
            isPrefetch = true
        )
    }

    suspend fun warmBootstrap() = withContext(Dispatchers.IO) {
        if (!YouTubeFeatureGate.isEnabled()) {
            return@withContext
        }
        if (ForegroundWebLoginGuard.isActive) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "Warm bootstrap skipped because ${ForegroundWebLoginGuard.SKIP_REASON}"
            )
            return@withContext
        }
        // 匿名用户不预热的话首播要现拉一次首页再编译 player.js
        val auth = authProvider().normalized()
        syncAuthBoundCachesIfNeeded(auth)
        // WebPo 页面和 bootstrap 请求互不依赖, 先启动才能把冷启动成本重叠起来
        warmWebPoTokenSessionAsync(reason = "playback_warm_bootstrap")
        try {
            val bootstrap = bootstrap(auth = auth, forceRefresh = false)
            ejsChallengeSolver?.let { solver ->
                runCatching { solver.warmPlayerScriptAsync(bootstrap.playerJsUrl) }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "Warm EJS player script failed",
                            error
                        )
                    }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Warm bootstrap failed",
                error
            )
        }
    }

    private fun warmWebPoTokenSessionAsync(reason: String) {
        if (!YouTubeFeatureGate.isEnabled()) return
        val provider = poTokenProvider ?: return
        inFlightPlayableAudioScope.launch {
            val startedAtMs = System.currentTimeMillis()
            runCatching { provider.warmSession() }
                .onSuccess {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "Warm WebPo session finished in background: reason=$reason, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                    NPLogger.w(
                        "YouTubeMusicPlayback",
                        "Warm WebPo session failed in background: reason=$reason",
                        error
                    )
                }
        }
    }

    fun warmBootstrapAsync() {
        if (!YouTubeFeatureGate.isEnabled()) return
        val warmTask = synchronized(warmBootstrapLock) {
            inFlightWarmBootstrap
                ?.takeUnless { it.isCompleted || it.isCancelled }
                ?: run {
                    lateinit var created: Deferred<Unit>
                    created = inFlightPlayableAudioScope.async(start = CoroutineStart.LAZY) {
                        try {
                            delay(YOUTUBE_PLAYBACK_WARM_BOOTSTRAP_START_DELAY_MS)
                            if (ForegroundWebLoginGuard.isActive) {
                                NPLogger.d(
                                    "YouTubeMusicPlayback",
                                    "Warm bootstrap skipped because ${ForegroundWebLoginGuard.SKIP_REASON}"
                                )
                                return@async
                            }
                            warmBootstrap()
                        } finally {
                            synchronized(warmBootstrapLock) {
                                if (inFlightWarmBootstrap === created) {
                                    inFlightWarmBootstrap = null
                                }
                            }
                        }
                    }
                    inFlightWarmBootstrap = created
                    created
                }
        }
        if (!warmTask.isActive && !warmTask.isCompleted && !warmTask.isCancelled) {
            warmTask.start()
        }
    }

    fun clearAuthBoundCaches(cancelInFlightPlayableAudio: Boolean = true) {
        authCacheGeneration += 1L
        bootstrapCache = null
        lastAuthFingerprint = null
        // 换了身份, 存档里的 sessionIndex/dataSyncId 全都作废
        bootstrapStore?.clear()
        bootstrapSnapshotRestored = true
        synchronized(playableAudioCache) {
            playableAudioCache.clear()
        }
        synchronized(bootstrapRequestLock) {
            val deferreds = inFlightBootstrapRequests.values.toList()
            inFlightBootstrapRequests.clear()
            deferreds.forEach { deferred ->
                deferred.cancel(CancellationException("YouTube auth updated"))
            }
            inFlightStaleBootstrapRefresh?.cancel(CancellationException("YouTube auth updated"))
            inFlightStaleBootstrapRefresh = null
        }
        synchronized(warmBootstrapLock) {
            inFlightWarmBootstrap?.cancel(CancellationException("YouTube auth updated"))
            inFlightWarmBootstrap = null
        }
        synchronized(inFlightPlayableAudio) {
            val deferreds = inFlightPlayableAudio.values.map { it.deferred }
            inFlightPlayableAudio.clear()
            if (cancelInFlightPlayableAudio) {
                deferreds.forEach { deferred ->
                    deferred.cancel(CancellationException("YouTube auth updated"))
                }
            }
        }
        poTokenProvider?.clearSession()
    }

    internal fun shouldClearAuthBoundCachesForFingerprintChange(
        previousFingerprint: String?,
        nextFingerprint: String
    ): Boolean {
        return previousFingerprint != null && previousFingerprint != nextFingerprint
    }

    private fun syncAuthBoundCachesIfNeeded(auth: YouTubeAuthBundle) {
        val fingerprint = auth.buildBootstrapAuthFingerprint(
            origin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        val previousFingerprint = lastAuthFingerprint
        if (previousFingerprint == fingerprint) {
            return
        }
        if (shouldClearAuthBoundCachesForFingerprintChange(previousFingerprint, fingerprint)) {
            clearAuthBoundCaches()
        }
        lastAuthFingerprint = fingerprint
    }

    private fun ensureInitialized() {
        if (initialized) {
            return
        }
        synchronized(initializationLock) {
            if (initialized) {
                return
            }
            val locale = Locale.getDefault()
            val preferred = YouTubeMusicLocaleResolver.preferred(locale)
            NewPipe.init(
                downloader,
                Localization(
                    preferred.hl.substringBefore('-'),
                    preferred.gl
                )
            )
            initialized = true
        }
    }

    private suspend fun resolvePlayableAudio(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false
    ): YouTubePlayableAudio? {
        val authGeneration = authCacheGeneration
        val playerResolution = resolvePlayerAudioViaPlayerApi(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = logFailure,
            preferM4a = preferM4a,
            forceRefresh = forceRefresh,
            avoidDirect = avoidDirect
        )
        playerResolution?.playableAudio?.let { playableAudio ->
            if (authGeneration == authCacheGeneration) {
                cachePlayableAudio(videoId, cacheKey, playableAudio)
            }
            return playableAudio
        }

        ensureInitialized()
        return resolvePlayableAudioViaNewPipe(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            logFailure = logFailure,
            preferM4a = preferM4a
        )   // 兜底路径也不能交出直链，否则原地循环 403
            ?.takeUnless { avoidDirect && it.streamType == YouTubePlayableStreamType.DIRECT }
            ?.mergeMetadataFrom(playerResolution?.metadata)
            ?.also { playableAudio ->
            if (authGeneration == authCacheGeneration) {
                cachePlayableAudio(videoId, cacheKey, playableAudio)
            }
        }
    }

    private suspend fun resolvePlayableAudioShared(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false,
        isPrefetch: Boolean = false
    ): YouTubePlayableAudio? {
        return startPlayableAudioResolution(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = logFailure,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = forceRefresh,
            avoidDirect = avoidDirect,
            isPrefetch = isPrefetch
        ).await()
    }

    private fun startPlayableAudioResolution(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false,
        isPrefetch: Boolean = false
    ): Deferred<YouTubePlayableAudio?> {
        val request = InFlightPlayableAudioRequest(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            preferM4a = preferM4a,
            forceRefresh = forceRefresh,
            avoidDirect = avoidDirect
        )
        val onDemandSignal = CompletableDeferred<Unit>()
        val entry = synchronized(inFlightPlayableAudio) {
            inFlightPlayableAudio[request]?.also {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "join in-flight playable audio resolve: videoId=$videoId, quality=$preferredQualityKey, forceRefresh=$forceRefresh, requireDirect=$requireDirect, preferM4a=$preferM4a"
                )
            } ?: run {
                val created: Deferred<YouTubePlayableAudio?> = inFlightPlayableAudioScope.async(start = CoroutineStart.LAZY) {
                    val startedAtMs = System.currentTimeMillis()
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "start playable audio resolve: videoId=$videoId, quality=$preferredQualityKey, forceRefresh=$forceRefresh, requireDirect=$requireDirect, preferM4a=$preferM4a"
                    )
                    try {
                        // 按需解析不受闸门约束, 避免被排队中的预取堵住
                        val resolved = if (isPrefetch) {
                            prefetchResolveGate.withPrefetchSlot(onDemandSignal) {
                                resolvePlayableAudio(
                                    videoId = videoId,
                                    preferredQualityKey = preferredQualityKey,
                                    requireDirect = requireDirect,
                                    logFailure = logFailure,
                                    preferM4a = preferM4a,
                                    cacheKey = cacheKey,
                                    forceRefresh = forceRefresh,
                                    avoidDirect = avoidDirect
                                )
                            }
                        } else {
                            resolvePlayableAudio(
                                videoId = videoId,
                                preferredQualityKey = preferredQualityKey,
                                requireDirect = requireDirect,
                                logFailure = logFailure,
                                preferM4a = preferM4a,
                                cacheKey = cacheKey,
                                forceRefresh = forceRefresh,
                                avoidDirect = avoidDirect
                            )
                        }
                        NPLogger.d(
                            "YouTubeMusicPlayback",
                            "finish playable audio resolve: videoId=$videoId, success=${resolved != null}, type=${resolved?.streamType}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                        )
                        resolved
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "playable audio resolve failed: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, error=${error.message}"
                        )
                        throw error
                    }
                }
                val createdEntry = InFlightPlayableAudioEntry(created, onDemandSignal)
                created.invokeOnCompletion {
                    synchronized(inFlightPlayableAudio) {
                        if (inFlightPlayableAudio[request] === createdEntry) {
                            inFlightPlayableAudio.remove(request)
                        }
                    }
                }
                inFlightPlayableAudio[request] = createdEntry
                createdEntry
            }
        }
        // 复用的如果是预取解析, 认领后它才能绕开闸门插到队首
        if (!isPrefetch && entry.promote()) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "promote prefetch resolve to on-demand: videoId=$videoId, quality=$preferredQualityKey"
            )
        }
        val deferred = entry.deferred
        if (!deferred.isActive && !deferred.isCompleted && !deferred.isCancelled) {
            deferred.start()
        }
        return deferred
    }

    /**
     * 丢掉还没被认领的排队预取解析
     *
     * 用户已经点了别的歌, 这些预取继续占着闸门只会把真正要播的那条往后压
     */
    fun cancelPendingPrefetchResolves(exceptVideoId: String?) {
        val discarded = synchronized(inFlightPlayableAudio) {
            inFlightPlayableAudio
                .filter { (request, entry) ->
                    request.videoId != exceptVideoId && !entry.isOnDemand
                }
                .map { (request, entry) -> request.videoId to entry.deferred }
        }
        if (discarded.isEmpty()) {
            return
        }
        discarded.forEach { (_, deferred) -> deferred.cancel() }
        NPLogger.d(
            "YouTubeMusicPlayback",
            "cancel pending prefetch resolves: except=$exceptVideoId, ids=${discarded.joinToString { it.first }}"
        )
    }

    private suspend fun resolvePlayerAudioViaPlayerApi(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false
    ): PlayerAudioResolution? {
        // 网页端未登录也能取流, 需要的是 visitorData + PoToken 而不是登录 cookie
        // 此前对无登录 cookie 直接返回 null 会把匿名用户整体推给已失效的 NewPipe 兜底
        val auth = authProvider().normalized()

        return try {
            fetchPlayerAudioViaPlayerApi(
                videoId = videoId,
                preferredQualityKey = preferredQualityKey,
                auth = auth,
                requireDirect = requireDirect,
                preferM4a = preferM4a,
                forceRefresh = forceRefresh,
                avoidDirect = avoidDirect
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (logFailure) {
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "player API resolve failed for $videoId (authUsable=${auth.isUsable()}, hasLoginCookies=${auth.hasLoginCookies()})",
                    error
                )
            }
            null
        }
    }

    private suspend fun fetchPlayerAudioViaPlayerApi(
        videoId: String,
        preferredQualityKey: String,
        auth: YouTubeAuthBundle,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false,
        forceRefresh: Boolean = false,
        avoidDirect: Boolean = false
    ): PlayerAudioResolution {
        val resolveStartedAtMs = System.currentTimeMillis()
        val bootstrapStartedAtMs = System.currentTimeMillis()
        // 不把流级 forceRefresh 传下去, 切音质或重试都会命中这里
        // 而重新拉一次首页再解析要好几秒; bootstrap 真过期由 TTL 和
        // authFingerprint 判定, 请求失败后下面的重试分支会强刷
        var bootstrap = bootstrap(auth)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "player bootstrap ready: videoId=$videoId, forceRefresh=$forceRefresh, elapsedMs=${playbackElapsedMs(bootstrapStartedAtMs)}"
        )
        var lastError: IOException? = null
        var bestMetadata: YouTubeAudioMetadata? = null
        // 跨 client/locale/attempt 累计 429/503 命中次数, 用于指数退避 (#Y5)
        var rateLimitBackoffHits = 0
        // repeat 是 lambda 不能 break
        var abortRemainingAttempts = false
        // 同一份 bootstrap 下 EJS 结果不会因重复请求 WEB_REMIX 改变, 重试时直接交给 TV fallback
        val retrySkippedClientNames = mutableSetOf<String>()

        repeat(PLAYER_REQUEST_MAX_ATTEMPTS) { attempt ->
            if (abortRemainingAttempts) {
                return@repeat
            }
            val poTokenForceRefresh = forceRefresh || attempt > 0
            val allowBlockingWebRemixPoToken = requireDirect
            val cipherResolver = createStreamingCipherResolver(
                videoId = videoId,
                playerJsUrl = bootstrap.playerJsUrl
            )
            val requestLocaleCandidates = playerRequestLocaleCandidates()
            var bestPlayableAudio: YouTubePlayableAudio? = null
            var bestPlayableAudioClientName: String? = null
            var shouldRefreshBootstrapBeforeFallback = false
            // status=OK 却解不出候选流, 说明 bootstrap 本身没毛病, 问题在签名那一步
            var sawUndecipherableOkResponse = false
            var sawOkResponse = false
            var sawBootstrapSuspectOutcome = false
            var sawTerminalFallbackOutcome = false
            var sawPlayerRequestFailure = false
            // 缺 PoToken 被跳过验证的直链, sig 和 n 都已经解完了, 留着当最后兜底
            var unverifiedDirectFallback: YouTubePlayableAudio? = null
            val candidateProfiles = selectUsablePlayerClients(
                profiles = playerClientProfiles(),
                clientName = { it.clientName },
                isSuppressed = PlayerClientHealthTracker::isSuppressed
            )
            val usableProfiles = candidateProfiles
                .filterNot { attempt > 0 && it.clientName in retrySkippedClientNames }
                .ifEmpty { candidateProfiles }
            profileLoop@ for (profile in usableProfiles) {
                for ((localeIndex, requestLocale) in requestLocaleCandidates.withIndex()) {
                    try {
                        val root = postPlayerRequest(
                            videoId = videoId,
                            auth = auth,
                            bootstrap = bootstrap,
                            profile = profile,
                            requestLocale = requestLocale
                        )
                        PlayerClientHealthTracker.recordSuccess(profile.clientName)
                        val playability = YouTubeMusicPlaybackParser.parsePlayabilityStatus(root)
                        if (playability.status.equals("OK", ignoreCase = true)) {
                            sawOkResponse = true
                        }
                        val shouldStopRemainingFallbackRequests =
                            shouldStopRemainingPlayerFallbackRequests(
                                clientName = profile.clientName,
                                playabilityStatus = playability.status,
                                sawUndecipherableOkResponse = sawUndecipherableOkResponse
                            )
                        if (shouldStopRemainingFallbackRequests) {
                            sawTerminalFallbackOutcome = true
                        }
                        NPLogger.d(
                            "YouTubeMusicPlayback",
                            "player client response: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, status=${playability.status}, reason=${playability.reason.take(80)}"
                        )
                        if (shouldStopRemainingFallbackRequests) {
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "stop remaining TV fallback requests: videoId=$videoId, " +
                                    "status=${playability.status}, locale=${requestLocale.gl}/${requestLocale.hl}"
                            )
                            break@profileLoop
                        }
                        if (!playability.status.equals("OK", ignoreCase = true) &&
                            localeIndex < requestLocaleCandidates.lastIndex
                        ) {
                            val fallbackLocale = requestLocaleCandidates[localeIndex + 1]
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "retry player locale fallback: videoId=$videoId, client=${profile.clientName}, from=${requestLocale.gl}/${requestLocale.hl}, to=${fallbackLocale.gl}/${fallbackLocale.hl}, status=${playability.status}"
                            )
                            continue
                        }

                        val metadata = YouTubeMusicPlaybackParser.parsePreferredAudioMetadata(
                            root = root,
                            preferredQualityKey = preferredQualityKey,
                            preferM4a = preferM4a
                        )
                        bestMetadata = bestMetadata.mergePreferred(metadata)
                        var shouldContinueWithNextPlayerClient = false
                        val playableAudio = if (playability.status == "OK") {
                            val webRemixPoTokenPrefetch = if (
                                profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME &&
                                shouldPrefetchWebRemixPoToken(root)
                            ) {
                                prefetchWebRemixPoToken(
                                    videoId = videoId,
                                    bootstrap = bootstrap,
                                    forceRefresh = poTokenForceRefresh
                                )
                            } else {
                                null
                            }
                            val parsedDirectPlayableAudio =
                                YouTubeMusicPlaybackParser.parsePlayableAudioAsync(
                                    root = root,
                                    preferredQualityKey = preferredQualityKey,
                                    preferM4a = preferM4a,
                                    cipherResolver = cipherResolver,
                                    maxCandidateCount = if (
                                        profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME &&
                                            !requireDirect &&
                                            !preferM4a
                                    ) {
                                        WEB_REMIX_PLAYBACK_MAX_CANDIDATES
                                    } else {
                                        Int.MAX_VALUE
                                    }
                                )
                            // 放弃直链候选, 顺带省掉一次必然被丢弃的 GVS PoToken 铸造
                            val directPlayableAudio = if (avoidDirect) {
                                null
                            } else {
                                maybeAttachGvsPoToken(
                                    playableAudio = parsedDirectPlayableAudio,
                                    profile = profile,
                                    videoId = videoId,
                                    auth = auth,
                                    bootstrap = bootstrap,
                                    forceRefresh = poTokenForceRefresh,
                                    prefetchedPoToken = webRemixPoTokenPrefetch,
                                    allowBlockingAcquisition = allowBlockingWebRemixPoToken
                                )
                            }
                            // 缺 PoToken 就整份丢掉的话, 这条已经解完签名的直链会连同那几秒一起作废,
                            // 后面的 client 还得从头再解一遍; 留下来只在所有 client 都空手时才用
                            if (directPlayableAudio == null && parsedDirectPlayableAudio != null) {
                                unverifiedDirectFallback = selectPreferredPlayableAudio(
                                    current = unverifiedDirectFallback,
                                    incoming = parsedDirectPlayableAudio,
                                    currentClientName = null,
                                    incomingClientName = profile.clientName,
                                    preferM4a = preferM4a,
                                    preferredQualityKey = preferredQualityKey
                                ) ?: unverifiedDirectFallback
                            }
                            val hlsPlayableAudio = try {
                                if (
                                    requireDirect ||
                                    // 直链候选随后会被丢弃, 这里再因已有直链跳过 manifest
                                    // 脏节点下就一个候选都拿不到
                                    (!avoidDirect && directPlayableAudio != null) ||
                                    (!avoidDirect &&
                                        bestPlayableAudio?.streamType == YouTubePlayableStreamType.DIRECT)
                                ) {
                                    null
                                } else {
                                    // 已有 direct 候选时不再额外拉 manifest, 减少无效请求和风控暴露面
                                    resolveHlsPlayableAudio(
                                        root = root,
                                        preferredQualityKey = preferredQualityKey,
                                        auth = auth,
                                        durationMs = metadata?.durationMs ?: 0L,
                                        profile = profile,
                                        videoId = videoId,
                                        bootstrap = bootstrap,
                                        forceRefresh = forceRefresh || attempt > 0,
                                        prefetchedPoToken = webRemixPoTokenPrefetch,
                                        allowBlockingAcquisition = allowBlockingWebRemixPoToken
                                    )
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                lastError = error as? IOException ?: IOException(error)
                                null
                            }
                            shouldContinueWithNextPlayerClient =
                                shouldSkipRemainingLocalesAfterWebRemixDirectFallback(
                                    profile = profile,
                                    root = root,
                                    parsedDirectPlayableAudio = parsedDirectPlayableAudio,
                                    directPlayableAudio = directPlayableAudio,
                                    hlsPlayableAudio = hlsPlayableAudio
                                )
                            selectPreferredPlayableAudio(
                                current = hlsPlayableAudio,
                                incoming = directPlayableAudio,
                                currentClientName = profile.clientName,
                                incomingClientName = profile.clientName,
                                preferM4a = preferM4a,
                                preferredQualityKey = preferredQualityKey
                            )
                        } else {
                            null
                        }
                        if (playability.status == "OK" && playableAudio != null) {
                            val resolvedPlayableAudio = selectPreferredPlayableAudio(
                                current = bestPlayableAudio,
                                incoming = playableAudio,
                                currentClientName = bestPlayableAudioClientName,
                                incomingClientName = profile.clientName,
                                preferM4a = preferM4a,
                                preferredQualityKey = preferredQualityKey
                            ) ?: continue@profileLoop
                            if (resolvedPlayableAudio === playableAudio) {
                                bestPlayableAudioClientName = profile.clientName
                            }
                            bestPlayableAudio = resolvedPlayableAudio
                            if (shouldReturnPlayableAudioImmediately(
                                    profile = profile,
                                    playableAudio = resolvedPlayableAudio,
                                    acceptedFromCurrentProfile = resolvedPlayableAudio === playableAudio,
                                    preferredQualityKey = preferredQualityKey,
                                    preferM4a = preferM4a
                                )
                            ) {
                                // 播放首帧比跨 client 继续比质量更重要, direct 命中后直接交给播放器
                                NPLogger.d(
                                    "YouTubeMusicPlayback",
                                    "player resolve satisfied by ${profile.clientName} direct: videoId=$videoId, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
                                )
                                return PlayerAudioResolution(
                                    playableAudio = resolvedPlayableAudio.mergeMetadataFrom(bestMetadata),
                                    metadata = bestMetadata
                                )
                            }
                            continue@profileLoop
                        }
                        if (playability.status == "OK" && shouldContinueWithNextPlayerClient) {
                            // 原始响应已经是 OK, 只是当前 client 的签名或 n 解不开;
                            // 记录这个结论, 下一轮应复用 bootstrap, 不要再付一次首页解析
                            sawUndecipherableOkResponse = true
                            retrySkippedClientNames += profile.clientName
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "skip remaining ${profile.clientName} locales after direct stream fallback: videoId=$videoId, locale=${requestLocale.gl}/${requestLocale.hl}, retrySkip=true"
                            )
                            continue@profileLoop
                        }

                        // status=OK 却拿不到流时要能分清是压根没解析出格式, 还是选流阶段把它筛掉了
                        if (playability.status == "OK") {
                            sawUndecipherableOkResponse = true
                            NPLogger.w(
                                "YouTubeMusicPlayback",
                                "player client yielded no usable audio: videoId=$videoId, client=${profile.clientName}, parsedAny=${playableAudio != null}, bestSoFar=${bestPlayableAudio?.streamType}, lastError=${lastError?.message}"
                            )
                        } else {
                            // WEB_REMIX 已经给出 OK 后, TVHTML5 的 UNPLAYABLE 通常只是
                            // client policy 拒绝, 重拉同一份 bootstrap 不会改变结果
                            if (shouldMarkBootstrapSuspectOutcome(
                                    playabilityStatus = playability.status,
                                    sawUndecipherableOkResponse = sawUndecipherableOkResponse
                                )
                            ) {
                                sawBootstrapSuspectOutcome = true
                            }
                        }
                        val description = buildString {
                            append("YouTube player unavailable via ")
                            append(profile.clientName)
                            append(" @ ")
                            append(requestLocale.gl)
                            append('/')
                            append(requestLocale.hl)
                            if (playability.status.isNotBlank()) {
                                append(": ")
                                append(playability.status)
                            }
                            if (playability.reason.isNotBlank()) {
                                append(" (")
                                append(playability.reason)
                                append(')')
                            }
                        }
                        lastError = IOException(description)
                        if (shouldRetryWithFreshBootstrapBeforeFallback(
                                profile = profile,
                                playability = playability,
                                attempt = attempt,
                                forceRefresh = forceRefresh
                            )
                        ) {
                            shouldRefreshBootstrapBeforeFallback = true
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "refresh bootstrap before fallback: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, status=${playability.status}, reason=${playability.reason.take(80)}"
                            )
                            break
                        }
                    } catch (error: IOException) {
                        lastError = error
                        sawPlayerRequestFailure = true
                        sawBootstrapSuspectOutcome = true
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "player client request failed: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, error=${error.message}"
                        )
                        if (error.isNonRetryablePlayerClientError()) {
                            PlayerClientHealthTracker.recordRejection(profile.clientName)
                        }
                        // 脏 IP 下 429/503 先退避再继续下一 client/locale/attempt, 避免密集重试加剧限流 (#Y5)
                        val backoffMs = rateLimitBackoffMs(error, rateLimitBackoffHits)
                        if (backoffMs != null) {
                            rateLimitBackoffHits++
                            NPLogger.w(
                                "YouTubeMusicPlayback",
                                "rate limited (HTTP ${(error as? YouTubeHttpStatusException)?.statusCode}) for $videoId, backoff=${backoffMs}ms"
                            )
                            delay(backoffMs)
                        }
                        if (shouldRetryWithFreshBootstrapAfterRequestFailure(
                                profile = profile,
                                attempt = attempt,
                                forceRefresh = forceRefresh
                            )
                        ) {
                            shouldRefreshBootstrapBeforeFallback = true
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "refresh bootstrap after request failure: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, error=${error.message}"
                            )
                            break
                        }
                    }
                }
                if (shouldRefreshBootstrapBeforeFallback) {
                    break
                }
            }

            if (bestPlayableAudio != null) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "player resolve finished: videoId=$videoId, type=${bestPlayableAudio.streamType}, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
                )
                return PlayerAudioResolution(
                    playableAudio = bestPlayableAudio.mergeMetadataFrom(bestMetadata),
                    metadata = bestMetadata
                )
            }

            // 所有 client 都空手时才动这份: 它缺 PoToken 没做验证, 但签名已经解完,
            // 丢掉它就是让下一轮把同一条流重新解一遍, 那几秒直接落在首播上;
            // 真被 CDN 拒了还有播放器那边的回退兜着
            unverifiedDirectFallback?.let { fallback ->
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "player resolve falls back to the unverified direct stream: videoId=$videoId, bitrateKbps=${fallback.bitrateKbps}, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
                )
                return PlayerAudioResolution(
                    playableAudio = fallback.mergeMetadataFrom(bestMetadata),
                    metadata = bestMetadata
                )
            }

            if (
                shouldAbortPlayerRetryAfterTerminalFallback(
                    sawUndecipherableOkResponse = sawUndecipherableOkResponse,
                    sawTerminalFallbackOutcome = sawTerminalFallbackOutcome,
                    sawPlayerRequestFailure = sawPlayerRequestFailure
                )
            ) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "player resolve abort retry after terminal TV fallback: videoId=$videoId"
                )
                abortRemainingAttempts = true
                return@repeat
            }

            // 4xx 是请求本身不被接受, 换新 bootstrap 改变不了结果
            // 白付一次 fetch + parse 会把解析推过下载超时窗口
            if (lastError.isNonRetryablePlayerClientError()) {
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "player resolve abort retry: videoId=$videoId, non-retryable ${lastError?.message.orEmpty().take(120)}"
                )
                abortRemainingAttempts = true
                return@repeat
            }
            if (attempt < PLAYER_REQUEST_MAX_ATTEMPTS - 1) {
                if (shouldRefreshBootstrapBeforeFallback) {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "player resolve refresh bootstrap immediately: videoId=$videoId, attempt=${attempt + 1}, error=${lastError?.message.orEmpty()}"
                    )
                } else {
                    NPLogger.w(
                        "YouTubeMusicPlayback",
                        "player resolve retry: videoId=$videoId, attempt=${attempt + 1}, error=${lastError?.message.orEmpty()}"
                    )
                }
                if (shouldRefreshBootstrapBeforePlayerRetry(
                                refreshRequestedByFallback = shouldRefreshBootstrapBeforeFallback,
                                sawUndecipherableOkResponse = sawUndecipherableOkResponse,
                                sawBootstrapSuspectOutcome = sawBootstrapSuspectOutcome,
                                sawOkResponse = sawOkResponse
                            )
                ) {
                    bootstrap = bootstrap(auth, forceRefresh = true)
                } else {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "player resolve keeps bootstrap: videoId=$videoId, attempt=${attempt + 1}, every client answered OK"
                    )
                }
            }
        }

        if (bestMetadata != null) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "player resolve returned metadata only: videoId=$videoId, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
            )
            return PlayerAudioResolution(metadata = bestMetadata)
        }
        throw lastError ?: IOException("YouTube Music player request failed")
    }

    private fun shouldSkipRemainingLocalesAfterWebRemixDirectFallback(
        profile: YouTubePlayerClientProfile,
        root: JSONObject,
        parsedDirectPlayableAudio: YouTubePlayableAudio?,
        directPlayableAudio: YouTubePlayableAudio?,
        hlsPlayableAudio: YouTubePlayableAudio?
    ): Boolean {
        if (profile.clientName != YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) return false
        if (directPlayableAudio != null || hlsPlayableAudio != null) return false
        val parsedDirect = parsedDirectPlayableAudio
        if (parsedDirect?.streamType == YouTubePlayableStreamType.DIRECT) {
            // 解析出 direct 候选但缺 pot: 播放路径跳过阻塞校验后回退, 无需再试其他 locale
            val streamUrl = parsedDirect.url
            return isYouTubeGoogleVideoStream(streamUrl) &&
                extractStreamQueryParameter(streamUrl, "pot").isNullOrBlank()
        }
        // 候选因 n 解不出被 #Y4 丢弃时 parsedDirect 为 null:
        // 若原始响应本就是 googlevideo direct 音频, 换 locale 无益, 直接切下一 client 省一次 player 请求
        return YouTubeMusicPlaybackParser.hasDirectGoogleVideoAudioStream(root)
    }

    private suspend fun maybeAttachGvsPoToken(
        playableAudio: YouTubePlayableAudio?,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>? = null,
        allowBlockingAcquisition: Boolean
    ): YouTubePlayableAudio? {
        if (playableAudio == null ||
            playableAudio.streamType != YouTubePlayableStreamType.DIRECT ||
            profile.clientName != YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME
        ) {
            return playableAudio
        }

        val startedAtMs = System.currentTimeMillis()
        val streamUrl = playableAudio.url
        if (!isYouTubeGoogleVideoStream(streamUrl)) {
            return playableAudio
        }

        val existingPoToken = extractStreamQueryParameter(streamUrl, "pot")
        if (!existingPoToken.isNullOrBlank()) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "reuse existing stream PO token: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, forceRefresh=$forceRefresh"
            )
            return playableAudio
        }

        val poToken = resolveWebRemixPoToken(
            videoId = videoId,
            bootstrap = bootstrap,
            forceRefresh = forceRefresh,
            prefetchedPoToken = prefetchedPoToken,
            allowBlockingAcquisition = allowBlockingAcquisition
        )
            .orEmpty()
            .ifBlank {
                if (existingPoToken.isNullOrBlank()) {
                    if (!allowBlockingAcquisition) {
                        NPLogger.d(
                            "YouTubeMusicPlayback",
                            "$YOUTUBE_PLAYBACK_DIAG_PREFIX missing_pot_webremix_direct_fast_fallback " +
                                "videoId=$videoId ${playableAudio.missingPoTokenDiagnosticMetadata(profile.clientName)} " +
                                "fallbackReason=missing_pot_skip_blocking_verification " +
                                "fallbackPath=return_unverified_direct " +
                                "branchElapsedMs=${playbackElapsedMs(startedAtMs)} " +
                                "forceRefresh=$forceRefresh"
                        )
                        // 普通播放已有播放器侧 403 重解析, 不应为一个可用直链再等待验证
                        prefetchedPoToken?.cancel()
                        return playableAudio
                    }
                    val verification = verifyDirectRangeReadable(
                        streamUrl,
                        buildBootstrapRequestAuth(auth, bootstrap)
                    )
                    if (verification.isReadable) {
                        NPLogger.d(
                            "YouTubeMusicPlayback",
                            "$YOUTUBE_PLAYBACK_DIAG_PREFIX missing_pot_webremix_direct_verification " +
                                "videoId=$videoId ${playableAudio.missingPoTokenDiagnosticMetadata(profile.clientName)} " +
                                "status=${verification.status} httpCode=${verification.httpCode ?: "<none>"} " +
                                "bytesRead=${verification.bytesRead} elapsedMs=${verification.elapsedMs} " +
                                "candidateDecision=accepted"
                        )
                        return playableAudio
                    }
                    NPLogger.w(
                        "YouTubeMusicPlayback",
                        "$YOUTUBE_PLAYBACK_DIAG_PREFIX missing_pot_webremix_direct_verification " +
                            "videoId=$videoId ${playableAudio.missingPoTokenDiagnosticMetadata(profile.clientName)} " +
                            "status=${verification.status} httpCode=${verification.httpCode ?: "<none>"} " +
                            "bytesRead=${verification.bytesRead} elapsedMs=${verification.elapsedMs} " +
                            "candidateDecision=rejected " +
                            "fallbackReason=missing_pot_range_verification_failed " +
                            "fallbackPath=continue_player_clients " +
                            "branchElapsedMs=${playbackElapsedMs(startedAtMs)}"
                    )
                    return null
                }
                return playableAudio
            }

        NPLogger.d(
            "YouTubeMusicPlayback",
            "attached stream PO token: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, forceRefresh=$forceRefresh"
        )
        return playableAudio.copy(
            url = replaceStreamQueryParameter(streamUrl, "pot", poToken)
        )
    }

    private fun verifyDirectRangeReadable(
        streamUrl: String,
        auth: YouTubeAuthBundle
    ): DirectRangeVerificationResult {
        val startedAtMs = System.currentTimeMillis()
        val request = buildYouTubeStreamRequest(streamUrl, auth)
            .newBuilder()
            .header("Range", "bytes=0-0")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code != 206) {
                    return@use DirectRangeVerificationResult(
                        status = DirectRangeVerificationStatus.NON_PARTIAL_CONTENT,
                        httpCode = response.code,
                        bytesRead = 0L,
                        elapsedMs = playbackElapsedMs(startedAtMs)
                    )
                }
                val bytesRead = response.body.source().read(Buffer(), 1L).coerceAtLeast(0L)
                DirectRangeVerificationResult(
                    status = if (bytesRead > 0L) {
                        DirectRangeVerificationStatus.READABLE
                    } else {
                        DirectRangeVerificationStatus.NO_BYTES_READ
                    },
                    httpCode = response.code,
                    bytesRead = bytesRead,
                    elapsedMs = playbackElapsedMs(startedAtMs)
                )
            }
        } catch (_: Exception) {
            DirectRangeVerificationResult(
                status = DirectRangeVerificationStatus.REQUEST_FAILED,
                httpCode = null,
                bytesRead = 0L,
                elapsedMs = playbackElapsedMs(startedAtMs)
            )
        }
    }

    private fun prefetchWebRemixPoToken(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean
    ): Deferred<String?>? {
        val provider = poTokenProvider ?: return null
        if (bootstrap.visitorData.isBlank()) {
            return null
        }
        return inFlightPlayableAudioScope.async {
            val startedAtMs = System.currentTimeMillis()
            runCatching {
                provider.getWebRemixGvsPoToken(
                    videoId = videoId,
                    visitorData = bootstrap.visitorData,
                    remoteHost = bootstrap.remoteHost,
                    forceRefresh = forceRefresh
                )
            }.onSuccess { token ->
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "prefetch GVS PO token finished: videoId=$videoId, hasToken=${!token.isNullOrBlank()}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "prefetch GVS PO token failed: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, error=${error.message}"
                )
            }.getOrNull()
        }
    }

    private fun shouldPrefetchWebRemixPoToken(root: JSONObject): Boolean {
        val streamingData = root.optJSONObject("streamingData") ?: return false
        val hlsManifestUrl = streamingData.optString("hlsManifestUrl").trim()
        if (hlsManifestUrl.isNotBlank()) {
            return !hasWebRemixManifestPoToken(hlsManifestUrl)
        }

        val formatArrays = listOfNotNull(
            streamingData.optJSONArray("adaptiveFormats"),
            streamingData.optJSONArray("formats")
        )
        formatArrays.forEach { formats ->
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val mimeType = format.optString("mimeType")
                    .substringBefore(';')
                    .trim()
                if (!mimeType.startsWith("audio/")) {
                    continue
                }

                val directUrl = format.optString("url").trim()
                if (directUrl.isNotBlank()) {
                    if (
                        isYouTubeGoogleVideoStream(directUrl) &&
                        extractStreamQueryParameter(directUrl, "pot").isNullOrBlank()
                    ) {
                        return true
                    }
                    continue
                }

                val cipher = format.optString("signatureCipher")
                    .ifBlank { format.optString("cipher") }
                    .trim()
                if (cipher.isNotBlank()) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun awaitPrefetchedWebRemixPoToken(
        videoId: String,
        prefetchedPoToken: Deferred<String?>?,
        timeoutMs: Long? = null
    ): String? {
        return prefetchedPoToken
            ?.let { deferred ->
                runCatching {
                    when {
                        timeoutMs == null -> deferred.await()
                        deferred.isCompleted -> deferred.await()
                        else -> withTimeoutOrNull(timeoutMs) { deferred.await() }
                    }
                }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "Await prefetched GVS PO token failed: videoId=$videoId, error=${error.message}"
                        )
                    }
                    .getOrNull()
            }
    }

    private suspend fun resolveWebRemixPoToken(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>?,
        allowBlockingAcquisition: Boolean
    ): String {
        val prefetchedToken = awaitPrefetchedWebRemixPoToken(
            videoId = videoId,
            prefetchedPoToken = prefetchedPoToken,
            timeoutMs = if (allowBlockingAcquisition) {
                null
            } else {
                WEB_REMIX_PO_TOKEN_PREFETCH_JOIN_TIMEOUT_MS
            }
        ).orEmpty()
        if (prefetchedToken.isNotBlank()) {
            return prefetchedToken
        }
        if (!allowBlockingAcquisition) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "skip blocking GVS PO token mint for fallback-eligible request: videoId=$videoId"
            )
            return ""
        }
        return poTokenProvider?.getWebRemixGvsPoToken(
            videoId = videoId,
            visitorData = bootstrap.visitorData,
            remoteHost = bootstrap.remoteHost,
            forceRefresh = forceRefresh
        ).orEmpty()
    }

    private fun createStreamingCipherResolver(
        videoId: String,
        playerJsUrl: String
    ): YouTubeStreamingCipherResolver {
        streamingCipherResolverFactory?.let { factory ->
            return factory(videoId)
        }
        ensureInitialized()

        val signatureErrorLogged = AtomicBoolean(false)
        val throttlingErrorLogged = AtomicBoolean(false)
        val signatureEjsFallbackLogged = AtomicBoolean(false)
        val throttlingEjsFallbackLogged = AtomicBoolean(false)
        val signatureResolutionLogged = AtomicBoolean(false)
        val throttlingResolutionLogged = AtomicBoolean(false)
        val throttlingUnresolvedDropLogged = AtomicBoolean(false)
        val prewarmedSignatures = ConcurrentHashMap<String, String>()
        val prewarmedThrottlingParameters = ConcurrentHashMap<String, String>()

        fun maybeLogResolution(
            challengeType: String,
            source: String,
            elapsedMs: Long,
            logged: AtomicBoolean
        ) {
            if (elapsedMs >= STREAMING_CIPHER_LOG_THRESHOLD_MS || logged.compareAndSet(false, true)) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "Resolved $challengeType via $source for $videoId elapsedMs=$elapsedMs"
                )
            }
        }

        return object : YouTubeStreamingCipherResolver {
            override fun prewarmChallenges(
                encryptedSignature: String?,
                obfuscatedThrottlingParameter: String?
            ) {
                // 同步解析入口只允许读取已有缓存, 不能再次阻塞调用线程
            }

            override suspend fun prewarmChallengesAsync(
                encryptedSignature: String?,
                obfuscatedThrottlingParameter: String?
            ) {
                val solver = ejsChallengeSolver ?: return
                // 只有两个都在才值得合并, 单个的话走原路径一样是一次求解
                val signature = encryptedSignature?.takeIf { it.isNotBlank() } ?: return
                val throttling = obfuscatedThrottlingParameter?.takeIf { it.isNotBlank() } ?: return
                val resolvedPlayerJsUrl = playerJsUrl.ifBlank { bootstrapCache?.playerJsUrl.orEmpty() }
                if (resolvedPlayerJsUrl.isBlank()) {
                    return
                }
                val startedAtMs = System.currentTimeMillis()
                val prewarmed = runCatching {
                    solver.solveDetailedAsync(
                        playerJsUrl = resolvedPlayerJsUrl,
                        encryptedSignature = signature,
                        throttlingParameter = throttling
                    )
                }.onFailure { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                }.getOrNull()
                if (prewarmed?.status == YouTubeJsChallengeSolveStatus.SUCCESS) {
                    val solvedSignature = prewarmed.solution.signature
                    val solvedThrottling = prewarmed.solution.throttlingParameter
                    val storedSignature = solvedSignature
                        ?.takeIf(::isPlausibleCipherToken)
                        ?.also { prewarmedSignatures[signature] = it }
                    val storedThrottling = solvedThrottling
                        ?.takeIf(::isPlausibleCipherToken)
                        ?.also { prewarmedThrottlingParameters[throttling] = it }
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "prewarmed sig and n together for $videoId " +
                            "hasSignature=${solvedSignature != null} " +
                            "storedSignature=${storedSignature != null} " +
                            "hasThrottling=${solvedThrottling != null} " +
                            "storedThrottling=${storedThrottling != null} " +
                            "elapsedMs=${playbackElapsedMs(startedAtMs)}"
                    )
                }
            }

            override fun resolveSignature(encryptedSignature: String): String? {
                return prewarmedSignatures[encryptedSignature]
            }

            override suspend fun resolveSignatureAsync(encryptedSignature: String): String? {
                prewarmedSignatures[encryptedSignature]?.let { resolved ->
                    maybeLogResolution(
                        challengeType = "signature",
                        source = "PREWARM_CACHE",
                        elapsedMs = 0L,
                        logged = signatureResolutionLogged
                    )
                    return resolved
                }
                val resolvedPlayerJsUrl = playerJsUrl.ifBlank { bootstrapCache?.playerJsUrl.orEmpty() }
                val skipSignatureNewPipe = NewPipeFallbackTracker.maybeSkipSignature(resolvedPlayerJsUrl)
                if (skipSignatureNewPipe && signatureErrorLogged.compareAndSet(false, true)) {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "Skip NewPipe signature for $videoId because player.js is already flagged"
                    )
                }
                return coroutineScope {
                    withTimeoutOrNull(CIPHER_RESOLVE_TIMEOUT_MS) {
                        val newPipeDeferred = if (skipSignatureNewPipe) {
                            null
                        } else {
                            async(Dispatchers.Default) {
                                delay(NEWPIPE_FALLBACK_START_DELAY_MS)
                                val startedAtMs = System.currentTimeMillis()
                                val resolvedByNewPipe = runCatching {
                                    runInterruptible(Dispatchers.IO) {
                                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                                            videoId,
                                            encryptedSignature
                                        )
                                    }
                                }.onFailure { error ->
                                    if (error is CancellationException) {
                                        throw error
                                    }
                                    if (signatureErrorLogged.compareAndSet(false, true)) {
                                        NPLogger.w(
                                            "YouTubeMusicPlayback",
                                            "Failed to deobfuscate streaming signature for $videoId via NewPipe elapsedMs=${playbackElapsedMs(startedAtMs)}",
                                            error
                                        )
                                    }
                                }.getOrNull()?.takeIf {
                                    it != encryptedSignature && isPlausibleCipherToken(it)
                                }
                                ChallengeCandidateResult(
                                    source = "NEWPIPE",
                                    value = resolvedByNewPipe,
                                    elapsedMs = playbackElapsedMs(startedAtMs)
                                )
                            }
                        }
                        val ejsDeferred = if (resolvedPlayerJsUrl.isBlank()) {
                            null
                        } else {
                            async(Dispatchers.IO) {
                                val startedAtMs = System.currentTimeMillis()
                                val ejsResult = runCatching {
                                    ejsChallengeSolver?.solveDetailedAsync(
                                        playerJsUrl = resolvedPlayerJsUrl,
                                        encryptedSignature = encryptedSignature
                                    )
                                }.getOrElse { error ->
                                    if (error is CancellationException) {
                                        throw error
                                    }
                                    YouTubeJsChallengeSolveResult(
                                        status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                        detail = "solveDetailed threw unexpectedly",
                                        cause = error
                                    )
                                } ?: YouTubeJsChallengeSolveResult(
                                    status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                    detail = "ejsChallengeSolver is unavailable"
                                )
                                val elapsedMs = playbackElapsedMs(startedAtMs)
                                val resolvedByEjs = ejsResult.solution.signature
                                    ?.takeIf {
                                        it != encryptedSignature && isPlausibleCipherToken(it)
                                    }
                                if (resolvedByEjs == null &&
                                    ejsResult.status == YouTubeJsChallengeSolveStatus.SUCCESS &&
                                    ejsResult.solution.signature != null &&
                                    signatureEjsFallbackLogged.compareAndSet(false, true)
                                ) {
                                    NPLogger.w(
                                        "YouTubeMusicPlayback",
                                        "EJS signature result rejected by token validation for " +
                                            "$videoId: status=${ejsResult.status}, " +
                                            "hasValue=true, " +
                                            "shape=${describeCipherTokenShape(ejsResult.solution.signature)}, " +
                                            "elapsedMs=$elapsedMs"
                                    )
                                }
                                if (resolvedByEjs == null &&
                                    ejsResult.status != YouTubeJsChallengeSolveStatus.SUCCESS &&
                                    signatureEjsFallbackLogged.compareAndSet(false, true)
                                ) {
                                    NPLogger.w(
                                        "YouTubeMusicPlayback",
                                        "EJS signature fallback failed for $videoId: ${ejsResult.summary()}, elapsedMs=$elapsedMs",
                                        ejsResult.cause
                                    )
                                }
                                ChallengeCandidateResult(
                                    source = "EJS_FALLBACK",
                                    value = resolvedByEjs,
                                    elapsedMs = elapsedMs
                                )
                            }
                        }
                        val winner = awaitFirstChallengeSuccess(listOfNotNull(newPipeDeferred, ejsDeferred))
                        if (winner != null) {
                            // 同 throttling, 不记失败 EJS 会一直白等启动延迟
                            if (!skipSignatureNewPipe &&
                                winner.source != "NEWPIPE" &&
                                newPipeDeferred.hasFailedChallenge()
                            ) {
                                NewPipeFallbackTracker.recordSignatureFailure(resolvedPlayerJsUrl)
                            }
                            maybeLogResolution(
                                challengeType = "signature",
                                source = winner.source,
                                elapsedMs = winner.elapsedMs,
                                logged = signatureResolutionLogged
                            )
                            return@withTimeoutOrNull winner.value
                        }
                        val newPipeResult = newPipeDeferred?.await()
                        if (!skipSignatureNewPipe && newPipeResult?.value == null) {
                            NewPipeFallbackTracker.recordSignatureFailure(resolvedPlayerJsUrl)
                        }
                        return@withTimeoutOrNull null
                    }
                }
            }

            override fun resolveStreamingUrl(url: String): String {
                val obfuscatedN = extractStreamQueryParameter(url, "n") ?: return url
                prewarmedThrottlingParameters[obfuscatedN]?.let { resolved ->
                    maybeLogResolution(
                        challengeType = "throttling",
                        source = "PREWARM_CACHE",
                        elapsedMs = 0L,
                        logged = throttlingResolutionLogged
                    )
                    return replaceStreamQueryParameter(url, "n", resolved)
                }
                return ""
            }

            override suspend fun resolveStreamingUrlAsync(url: String): String {
                val obfuscatedN = extractStreamQueryParameter(url, "n") ?: return url
                prewarmedThrottlingParameters[obfuscatedN]?.let { resolved ->
                    maybeLogResolution(
                        challengeType = "throttling",
                        source = "PREWARM_CACHE",
                        elapsedMs = 0L,
                        logged = throttlingResolutionLogged
                    )
                    return replaceStreamQueryParameter(url, "n", resolved)
                }
                val resolvedPlayerJsUrl = playerJsUrl.ifBlank { bootstrapCache?.playerJsUrl.orEmpty() }
                val skipThrottlingNewPipe = NewPipeFallbackTracker.maybeSkipThrottling(resolvedPlayerJsUrl)
                if (skipThrottlingNewPipe && throttlingErrorLogged.compareAndSet(false, true)) {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "Skip NewPipe throttling for $videoId because player.js is already flagged"
                    )
                }
                return coroutineScope {
                    withTimeoutOrNull(CIPHER_RESOLVE_TIMEOUT_MS) {
                        val newPipeDeferred = if (skipThrottlingNewPipe) {
                            null
                        } else {
                            async(Dispatchers.Default) {
                                delay(NEWPIPE_FALLBACK_START_DELAY_MS)
                                val startedAtMs = System.currentTimeMillis()
                                val resolvedByNewPipe = runCatching {
                                    runInterruptible(Dispatchers.IO) {
                                        YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                                            videoId,
                                            url
                                        )
                                    }
                                }.onFailure { error ->
                                    if (error is CancellationException) {
                                        throw error
                                    }
                                    if (throttlingErrorLogged.compareAndSet(false, true)) {
                                        NPLogger.w(
                                            "YouTubeMusicPlayback",
                                            "Failed to deobfuscate throttling parameter for $videoId via NewPipe elapsedMs=${playbackElapsedMs(startedAtMs)}",
                                            error
                                        )
                                    }
                                }.getOrNull()?.takeIf { candidateUrl ->
                                    // player.js 变更后 NewPipe 会返回 n=[object Object]
                                    // 非空且与原串不同, 只判这两条会放行必然 403 的地址
                                    val accepted = candidateUrl.isNotBlank() &&
                                        candidateUrl != url &&
                                        hasPlausibleThrottlingParameter(candidateUrl)
                                    if (!accepted &&
                                        candidateUrl.isNotBlank() &&
                                        candidateUrl != url &&
                                        throttlingErrorLogged.compareAndSet(false, true)
                                    ) {
                                        NPLogger.w(
                                            "YouTubeMusicPlayback",
                                            "Reject NewPipe throttling result for $videoId: " +
                                                "n=${extractStreamQueryParameter(candidateUrl, "n")}"
                                        )
                                    }
                                    accepted
                                }
                                ChallengeCandidateResult(
                                    source = "NEWPIPE",
                                    value = resolvedByNewPipe,
                                    elapsedMs = playbackElapsedMs(startedAtMs)
                                )
                            }
                        }
                        val ejsDeferred = if (resolvedPlayerJsUrl.isBlank()) {
                            null
                        } else {
                            async(Dispatchers.IO) {
                                val startedAtMs = System.currentTimeMillis()
                                val ejsResult = runCatching {
                                    ejsChallengeSolver?.solveDetailedAsync(
                                        playerJsUrl = resolvedPlayerJsUrl,
                                        throttlingParameter = obfuscatedN
                                    )
                                }.getOrElse { error ->
                                    if (error is CancellationException) {
                                        throw error
                                    }
                                    YouTubeJsChallengeSolveResult(
                                        status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                        detail = "solveDetailed threw unexpectedly",
                                        cause = error
                                    )
                                } ?: YouTubeJsChallengeSolveResult(
                                    status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                    detail = "ejsChallengeSolver is unavailable"
                                )
                                val elapsedMs = playbackElapsedMs(startedAtMs)
                                val resolvedByEjs = ejsResult.solution.throttlingParameter
                                    ?.takeIf { it != obfuscatedN && isPlausibleCipherToken(it) }
                                    ?.let { replaceStreamQueryParameter(url, "n", it) }
                                if (resolvedByEjs == null &&
                                    ejsResult.status == YouTubeJsChallengeSolveStatus.SUCCESS &&
                                    ejsResult.solution.throttlingParameter != null &&
                                    throttlingEjsFallbackLogged.compareAndSet(false, true)
                                ) {
                                    NPLogger.w(
                                        "YouTubeMusicPlayback",
                                        "EJS throttling result rejected by token validation for " +
                                            "$videoId: status=${ejsResult.status}, " +
                                            "hasValue=true, " +
                                            "shape=${describeCipherTokenShape(ejsResult.solution.throttlingParameter)}, " +
                                            "elapsedMs=$elapsedMs"
                                    )
                                }
                                if (resolvedByEjs == null &&
                                    ejsResult.status != YouTubeJsChallengeSolveStatus.SUCCESS &&
                                    throttlingEjsFallbackLogged.compareAndSet(false, true)
                                ) {
                                    NPLogger.w(
                                        "YouTubeMusicPlayback",
                                        "EJS throttling fallback failed for $videoId: ${ejsResult.summary()}, elapsedMs=$elapsedMs",
                                        ejsResult.cause
                                    )
                                }
                                ChallengeCandidateResult(
                                    source = "EJS_FALLBACK",
                                    value = resolvedByEjs,
                                    elapsedMs = elapsedMs
                                )
                            }
                        }
                        val winner = awaitFirstChallengeSuccess(listOfNotNull(newPipeDeferred, ejsDeferred))
                        if (winner != null) {
                            // 不记失败的话 player.js 变更后 NewPipe 永远不被标记
                            // NewPipe 每次都要白等启动延迟, EJS 已经先行
                            if (!skipThrottlingNewPipe &&
                                winner.source != "NEWPIPE" &&
                                newPipeDeferred.hasFailedChallenge()
                            ) {
                                NewPipeFallbackTracker.recordThrottlingFailure(resolvedPlayerJsUrl)
                            }
                            maybeLogResolution(
                                challengeType = "throttling",
                                source = winner.source,
                                elapsedMs = winner.elapsedMs,
                                logged = throttlingResolutionLogged
                            )
                            return@withTimeoutOrNull winner.value ?: url
                        }
                        val newPipeResult = newPipeDeferred?.await()
                        if (!skipThrottlingNewPipe && newPipeResult?.value == null) {
                            NewPipeFallbackTracker.recordThrottlingFailure(resolvedPlayerJsUrl)
                        }
                        // n 参数存在但 NewPipe 与 EJS 都解不出: 返回空串标记该候选不可用
                        // 让上层继续下一候选/下一 client, 避免返回带混淆 n 的限速 URL (#Y4)
                        if (throttlingUnresolvedDropLogged.compareAndSet(false, true)) {
                            NPLogger.w(
                                "YouTubeMusicPlayback",
                                "drop stream candidate: throttling n unresolved for $videoId, skip to next candidate/client"
                            )
                        }
                        return@withTimeoutOrNull ""
                    } ?: ""
                }
            }
        }
    }

    private suspend fun resolveHlsPlayableAudio(
        root: JSONObject,
        preferredQualityKey: String,
        auth: YouTubeAuthBundle,
        durationMs: Long,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>? = null,
        allowBlockingAcquisition: Boolean
    ): YouTubePlayableAudio? {
        val hlsManifestUrl = root.optJSONObject("streamingData")
            ?.optString("hlsManifestUrl")
            .orEmpty()
            .trim()
        if (hlsManifestUrl.isBlank()) {
            return null
        }
        val resolvedManifestUrl = if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            if (hasWebRemixManifestPoToken(hlsManifestUrl)) {
                hlsManifestUrl
            } else {
                val poToken = resolveWebRemixPoToken(
                    videoId = videoId,
                    bootstrap = bootstrap,
                    forceRefresh = forceRefresh,
                    prefetchedPoToken = prefetchedPoToken,
                    allowBlockingAcquisition = allowBlockingAcquisition
                )
                if (poToken.isBlank()) {
                    if (poTokenProvider == null) {
                        hlsManifestUrl
                    } else {
                        return null
                    }
                } else {
                    appendWebRemixManifestPoToken(hlsManifestUrl, poToken)
                }
            }
        } else {
            hlsManifestUrl
        }

        val requestAuth = buildBootstrapRequestAuth(auth = auth, bootstrap = bootstrap)
        val masterManifest = executeText(buildYouTubeStreamRequest(resolvedManifestUrl, requestAuth))
        val selectedAudioPlaylist = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = masterManifest,
            masterManifestUrl = resolvedManifestUrl,
            preferredQualityKey = preferredQualityKey,
            durationMs = durationMs
        ) ?: return null

        return YouTubePlayableAudio(
            url = if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
                carryForwardWebRemixManifestPoToken(
                    masterManifestUrl = resolvedManifestUrl,
                    playlistUrl = selectedAudioPlaylist.uri
                )
            } else {
                selectedAudioPlaylist.uri
            },
            durationMs = durationMs,
            mimeType = MimeTypes.APPLICATION_M3U8,
            contentLength = selectedAudioPlaylist.contentLength,
            streamType = YouTubePlayableStreamType.HLS,
            bitrateKbps = selectedAudioPlaylist.estimatedBitrate
                .takeIf { it > 0 }
                ?.let { (it + 500) / 1000 }
        )
    }

    private fun appendWebRemixManifestPoToken(
        manifestUrl: String,
        poToken: String
    ): String {
        if (manifestUrl.isBlank() || poToken.isBlank() || hasWebRemixManifestPoToken(manifestUrl)) {
            return manifestUrl
        }
        val uri = runCatching { URI(manifestUrl) }.getOrNull()
            ?: return replaceStreamQueryParameter(manifestUrl, "pot", poToken)
        val rawPath = uri.rawPath.orEmpty()
        if (!rawPath.contains("/api/manifest/")) {
            return replaceStreamQueryParameter(manifestUrl, "pot", poToken)
        }
        val resolvedPath = if (rawPath.endsWith("/")) {
            "${rawPath}pot/$poToken"
        } else {
            "$rawPath/pot/$poToken"
        }
        return runCatching {
            URI(
                uri.scheme,
                uri.rawAuthority,
                resolvedPath,
                uri.rawQuery,
                uri.rawFragment
            ).toString()
        }.getOrElse {
            replaceStreamQueryParameter(manifestUrl, "pot", poToken)
        }
    }

    private fun hasWebRemixManifestPoToken(manifestUrl: String): Boolean {
        if (manifestUrl.isBlank()) {
            return false
        }
        return !extractStreamQueryParameter(manifestUrl, "pot").isNullOrBlank() ||
            "/pot/" in manifestUrl
    }

    private fun carryForwardWebRemixManifestPoToken(
        masterManifestUrl: String,
        playlistUrl: String
    ): String {
        if (playlistUrl.isBlank()) {
            return playlistUrl
        }
        val existingPoToken = extractStreamQueryParameter(playlistUrl, "pot")
        if (!existingPoToken.isNullOrBlank() || "/pot/" in playlistUrl) {
            return playlistUrl
        }
        val poTokenFromQuery = extractStreamQueryParameter(masterManifestUrl, "pot")
        if (!poTokenFromQuery.isNullOrBlank()) {
            return replaceStreamQueryParameter(playlistUrl, "pot", poTokenFromQuery)
        }
        val poTokenFromPath = Regex("/pot/([^/?#]+)")
            .find(masterManifestUrl)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        if (poTokenFromPath.isBlank()) {
            return playlistUrl
        }
        return appendWebRemixManifestPoToken(playlistUrl, poTokenFromPath)
    }

    private fun buildYouTubeStreamRequest(
        url: String,
        auth: YouTubeAuthBundle
    ): Request {
        val headers = auth.buildYouTubeStreamRequestHeaders(
            refererOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
            streamUrl = url
        )
        return Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .build()
    }

    private fun buildBootstrapRequestAuth(
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

    private fun postPlayerRequest(
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        profile: YouTubePlayerClientProfile,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        val startedAtMs = System.currentTimeMillis()
        val requestUrl = resolvePlayerRequestUrl(profile, bootstrap, videoId)
        val origin = resolvePlayerRequestOrigin(profile)
        val requestAuth = buildBootstrapRequestAuth(
            auth = auth,
            bootstrap = bootstrap,
            origin = origin
        )
        val clientVersion = resolvePlayerClientVersion(profile, bootstrap)
        val userAgent = resolvePlayerRequestUserAgent(profile, bootstrap)
        val webRemixMetadata = if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            buildWebRemixRequestMetadata(videoId)
        } else {
            null
        }
        val signatureTimestamp = resolveYouTubeSignatureTimestamp(
            bootstrapTimestamp = bootstrap.signatureTimestamp,
            cachedTimestamp = signatureTimestampCache[bootstrap.playerJsUrl]
        )
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
        val requestHeaders = linkedMapOf(
            "Cookie" to bootstrap.cookieHeader,
            "User-Agent" to userAgent,
            "Accept-Language" to requestLocale.acceptLanguage,
            "Content-Type" to "application/json",
            "X-Goog-AuthUser" to requestAuth.resolveXGoogAuthUser(
                fallback = bootstrap.sessionIndex
            ),
            "X-Goog-Visitor-Id" to bootstrap.visitorData,
            "X-YouTube-Client-Name" to profile.clientId,
            "X-YouTube-Client-Version" to clientVersion
        )
        if (profile.clientName != "WEB_REMIX") {
            requestHeaders["X-Goog-Api-Format-Version"] = YOUTUBE_PLAYER_API_FORMAT_VERSION
        }
        requestHeaders["Origin"] = origin
        if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            requestHeaders["X-YouTube-Bootstrap-Logged-In"] = requestAuth.hasLoginCookies().toString()
            NPLogger.d(
                "YouTubeMusicPlayback",
                "WEB_REMIX request context: videoId=$videoId, locale=${requestLocale.gl}/${requestLocale.hl}, originalUrl=${webRemixMetadata?.originalUrl.orEmpty()}, referer=${webRemixMetadata?.watchUrl.orEmpty()}, remoteHost=${bootstrap.remoteHost.ifBlank { "<blank>" }}, signatureTimestamp=$signatureTimestamp, clientVersion=$clientVersion"
            )
        }
        requestHeaders["Referer"] = webRemixMetadata?.watchUrl ?: "$origin/"

        val userSessionId = bootstrap.userSessionId.takeIf { bootstrap.loggedIn }.orEmpty()
        requestAuth.resolveAuthorizationHeader(origin = origin, userSessionId = userSessionId)
            .takeIf { it.isNotBlank() }
            ?.let {
                requestHeaders["Authorization"] = it
                requestHeaders["X-Origin"] = origin
            }
        if (profile.clientName == YOUTUBE_PLAYER_TV_CLIENT_NAME) {
            bootstrap.delegatedSessionId
                .takeIf { it.isNotBlank() }
                ?.let { requestHeaders["X-Goog-PageId"] = it }
            if (bootstrap.loggedIn) {
                requestHeaders["X-Youtube-Bootstrap-Logged-In"] = "true"
            }
        }

        val request = Request.Builder()
            .url(requestUrl)
            .apply {
                requestHeaders.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val root = executeJson(request)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "postPlayerRequest ok: videoId=$videoId, client=${profile.clientName}, clientVersion=$clientVersion, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return profile.responseField
            ?.let { root.optJSONObject(it) ?: root }
            ?: root
    }

    private fun buildPlayerRequestBody(
        videoId: String,
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        requestLocale: YouTubeMusicRequestLocale,
        clientVersion: String,
        userAgent: String,
        webRemixMetadata: YouTubeWebRemixRequestMetadata? = null,
        signatureTimestamp: Int? = null
    ): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", clientVersion)
            .put("platform", profile.platform)
            .put("hl", requestLocale.hl)
            .put("gl", requestLocale.gl)
            .put("utcOffsetMinutes", utcOffsetMinutes())
        if (profile.clientScreen.isNotBlank()) {
            clientContext.put("clientScreen", profile.clientScreen)
        }
        if (bootstrap.visitorData.isNotBlank()) {
            clientContext.put("visitorData", bootstrap.visitorData)
        }
        if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME && userAgent.isNotBlank()) {
            // WEB_REMIX 需要更接近浏览器 watch 页的 client 上下文, 避免退回到风险更高的移动端直链
            clientContext.put("deviceMake", "")
            clientContext.put("deviceModel", "")
            clientContext.put("userAgent", ensureGfeUserAgent(userAgent))
            clientContext.put("browserName", resolveBrowserName(userAgent))
            clientContext.put("browserVersion", resolveBrowserVersion(userAgent))
            clientContext.put("timeZone", currentTimeZoneId())
            clientContext.put("originalUrl", webRemixMetadata?.originalUrl.orEmpty())
            clientContext.put("acceptHeader", YOUTUBE_PLAYER_WEB_REMIX_ACCEPT_HEADER)
            clientContext.put("clientFormFactor", YOUTUBE_PLAYER_WEB_REMIX_CLIENT_FORM_FACTOR)
            clientContext.put("playerType", YOUTUBE_PLAYER_WEB_REMIX_PLAYER_TYPE)
            clientContext.put("userInterfaceTheme", YOUTUBE_PLAYER_WEB_REMIX_UI_THEME)
            clientContext.put("connectionType", YOUTUBE_PLAYER_WEB_REMIX_CONNECTION_TYPE)
            clientContext.put("screenWidthPoints", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS)
            clientContext.put("screenHeightPoints", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS)
            clientContext.put("screenPixelDensity", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_PIXEL_DENSITY)
            clientContext.put("screenDensityFloat", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_DENSITY_FLOAT)
            clientContext.put(
                "tvAppInfo",
                JSONObject().put("livingRoomAppMode", "LIVING_ROOM_APP_MODE_UNSPECIFIED")
            )
            val configInfo = JSONObject()
            bootstrap.appInstallData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("appInstallData", it)
            }
            bootstrap.coldConfigData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("coldConfigData", it)
            }
            bootstrap.coldHashData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("coldHashData", it)
            }
            bootstrap.hotHashData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("hotHashData", it)
            }
            clientContext.put("configInfo", configInfo)
            bootstrap.rolloutToken.takeIf { it.isNotBlank() }?.let {
                clientContext.put("rolloutToken", it)
            }
            bootstrap.deviceExperimentId.takeIf { it.isNotBlank() }?.let {
                clientContext.put("deviceExperimentId", it)
            }
            bootstrap.remoteHost.takeIf { it.isNotBlank() }?.let { remoteHost ->
                clientContext.put("remoteHost", remoteHost)
            }
        }
        profile.deviceMake?.let { clientContext.put("deviceMake", it) }
        profile.deviceModel?.let { clientContext.put("deviceModel", it) }
        profile.osName?.let { clientContext.put("osName", it) }
        profile.osVersion?.let { clientContext.put("osVersion", it) }
        profile.androidSdkVersion?.let { clientContext.put("androidSdkVersion", it) }

        val requestContext = JSONObject()
            .put("useSsl", true)
            .put("internalExperimentFlags", JSONArray())
            .put("consistencyTokenJars", JSONArray())

        val context = JSONObject()
            .put("client", clientContext)
            .put("request", requestContext)
            .put("user", JSONObject().put("lockedSafetyMode", false))
        webRemixMetadata?.let { metadata ->
            context.put("clientScreenNonce", metadata.clientScreenNonce)
            context.put("clickTracking", JSONObject().put("clickTrackingParams", ""))
            context.put("adSignalsInfo", buildWebRemixAdSignalsInfo())
        }

        return JSONObject()
            .put("context", context)
            .apply {
                if (signatureTimestamp != null || webRemixMetadata != null) {
                    put(
                        "playbackContext",
                        buildPlayerPlaybackContext(
                            refererUrl = webRemixMetadata?.originalUrl,
                            signatureTimestamp = signatureTimestamp
                        )
                    )
                }
                webRemixMetadata?.let { metadata ->
                    put("cpn", metadata.cpn)
                    put("captionParams", JSONObject())
                    put("playlistId", metadata.playlistId)
                }
                if (profile.wrapPlayerRequest) {
                    put(
                        "playerRequest",
                        JSONObject()
                            .put("videoId", videoId)
                            .put("contentCheckOk", true)
                            .put("racyCheckOk", true)
                    )
                    put("disablePlayerResponse", false)
                } else {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
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

    private fun resolvePlayerJavaScriptUrl(rawUrl: String): String {
        return when {
            rawUrl.startsWith("https://") || rawUrl.startsWith("http://") -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "$YOUTUBE_MUSIC_ORIGIN$rawUrl"
            else -> "$YOUTUBE_MUSIC_ORIGIN/$rawUrl"
        }
    }

    private suspend fun bootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean = false
    ): YouTubePlaybackBootstrap {
        val startedAtMs = System.currentTimeMillis()
        val requestAuth = authProvider().normalized().takeIf { it.hasLoginCookies() } ?: auth
        val requestAuthFingerprint = requestAuth.buildBootstrapAuthFingerprint(
            origin = requestAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        val cached = bootstrapCache
            ?: restoreBootstrapSnapshotIfNeeded(
                authFingerprint = requestAuthFingerprint,
                cookieHeader = appendYouTubeConsentCookie(requestAuth.effectiveCookieHeader())
            )
        if (!forceRefresh &&
            cached != null &&
            cached.authFingerprint == requestAuthFingerprint
        ) {
            val ageMs = System.currentTimeMillis() - cached.fetchedAtMs
            if (ageMs < PLAYABLE_BOOTSTRAP_TTL_MS) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "bootstrap cache hit: forceRefresh=$forceRefresh, ageMs=$ageMs, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                )
                return cached
            }
            // 过了新鲜期不等于已经失效, 重拉一次要十几秒, 先拿旧的开播, 刷新丢后台;
            // 真过期了播放器那边会带 forceRefresh 再走一遍, 那条路才需要等
            if (ageMs < BOOTSTRAP_SNAPSHOT_MAX_AGE_MS) {
                refreshStaleBootstrapAsync(auth)
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "bootstrap stale hit: ageMs=$ageMs, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                )
                return cached
            }
        }

        val requestKey = InFlightBootstrapRequest(
            authFingerprint = requestAuthFingerprint,
            forceRefresh = forceRefresh
        )
        var joinedInFlight = false
        val deferred = synchronized(bootstrapRequestLock) {
            inFlightBootstrapRequests[requestKey]?.takeUnless { it.isCompleted || it.isCancelled }?.also {
                joinedInFlight = true
            } ?: run {
                lateinit var created: Deferred<YouTubePlaybackBootstrap>
                created = inFlightPlayableAudioScope.async(start = CoroutineStart.LAZY) {
                    try {
                        loadBootstrap(auth = auth, forceRefresh = forceRefresh)
                    } finally {
                        synchronized(bootstrapRequestLock) {
                            if (inFlightBootstrapRequests[requestKey] === created) {
                                inFlightBootstrapRequests.remove(requestKey)
                            }
                        }
                    }
                }
                inFlightBootstrapRequests[requestKey] = created
                created
            }
        }
        if (joinedInFlight) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "join in-flight bootstrap: forceRefresh=$forceRefresh, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
        }
        if (!deferred.isActive && !deferred.isCompleted && !deferred.isCancelled) {
            deferred.start()
        }
        // 加载已经排上了, 但手里还有份能用的旧 bootstrap 时等它没有意义,
        // 解析慢的那十几秒会一比一变成首播延迟
        val staleFallback = cached?.takeIf {
            isUsableStaleBootstrap(it, System.currentTimeMillis())
        }
        if (!shouldAwaitBootstrapLoad(forceRefresh, staleFallback != null)) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "serve stale bootstrap instead of waiting: ageMs=${System.currentTimeMillis() - (staleFallback?.fetchedAtMs ?: 0L)}, fingerprintMatched=${staleFallback?.authFingerprint == requestAuthFingerprint}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
            return staleFallback!!
        }
        return deferred.await()
    }

    /**
     * 冷启动第一次要 bootstrap 时把上次的存档捞回来
     *
     * cookieHeader 没有落盘, 这里按当前 auth 重拼一份; 指纹对得上就说明还是同一个身份,
     * 拼出来的和当初存的是同一串
     */
    private fun restoreBootstrapSnapshotIfNeeded(
        authFingerprint: String,
        cookieHeader: String
    ): YouTubePlaybackBootstrap? {
        val store = synchronized(bootstrapRequestLock) {
            if (bootstrapSnapshotRestored) {
                return null
            }
            bootstrapSnapshotRestored = true
            bootstrapStore
        } ?: return null

        val snapshot = store.load()
        if (!isYouTubeBootstrapSnapshotUsable(
                snapshot = snapshot,
                authFingerprint = authFingerprint,
                nowMs = System.currentTimeMillis()
            )
        ) {
            return null
        }
        // 旧版本可能已经把一份游客态写进存档了, 拿它开局就是直接掉登录
        if (snapshot != null &&
            demotesYouTubeLogin(
                parsedLoggedIn = snapshot.loggedIn,
                holdsLoginCookies = authProvider().normalized().hasLoginCookies()
            )
        ) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "drop anonymous bootstrap snapshot while login cookies are present"
            )
            store.clear()
            return null
        }
        val restored = (snapshot ?: return null).copy(cookieHeader = cookieHeader)
        val authGeneration = authCacheGeneration
        synchronized(bootstrapRequestLock) {
            if (bootstrapCache == null && authGeneration == authCacheGeneration) {
                bootstrapCache = restored
            }
        }
        NPLogger.d(
            "YouTubeMusicPlayback",
            "bootstrap snapshot restored: ageMs=${System.currentTimeMillis() - restored.fetchedAtMs}, loggedIn=${restored.loggedIn}"
        )
        return restored
    }

    /**
     * 旧 bootstrap 还能用的时候, 刷新不该占着播放这条路
     *
     * 同一时刻只留一个刷新在跑, 否则连点几首歌就会并发拉好几份首页, 那正是首播被拖慢的原因
     */
    private fun refreshStaleBootstrapAsync(auth: YouTubeAuthBundle) {
        val refreshTask = synchronized(bootstrapRequestLock) {
            inFlightStaleBootstrapRefresh
                ?.takeUnless { it.isCompleted || it.isCancelled }
                ?: run {
                    lateinit var created: Deferred<Unit>
                    created = inFlightPlayableAudioScope.async(start = CoroutineStart.LAZY) {
                        try {
                            loadBootstrap(auth = auth, forceRefresh = true)
                            return@async
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            NPLogger.w(
                                "YouTubeMusicPlayback",
                                "stale bootstrap refresh failed: ${error.message}"
                            )
                        } finally {
                            synchronized(bootstrapRequestLock) {
                                if (inFlightStaleBootstrapRefresh === created) {
                                    inFlightStaleBootstrapRefresh = null
                                }
                            }
                        }
                    }
                    inFlightStaleBootstrapRefresh = created
                    created
                }
        }
        if (!refreshTask.isActive && !refreshTask.isCompleted && !refreshTask.isCancelled) {
            refreshTask.start()
        }
    }

    private suspend fun loadBootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean
    ): YouTubePlaybackBootstrap = bootstrapLoadMutex.withLock {
        val requestAuth = authProvider().normalized().takeIf { it.hasLoginCookies() } ?: auth
        val requestAuthFingerprint = requestAuth.buildBootstrapAuthFingerprint(
            origin = requestAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        // 排队期间前一个可能已经解析好了, 再解析一遍只是重复付这十几秒
        if (!forceRefresh) {
            bootstrapCache
                ?.takeIf { it.authFingerprint == requestAuthFingerprint }
                ?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < PLAYABLE_BOOTSTRAP_TTL_MS }
                ?.let { cached ->
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "bootstrap load coalesced: ageMs=${System.currentTimeMillis() - cached.fetchedAtMs}"
                    )
                    return@withLock cached
                }
        }
        loadBootstrapLocked(auth = auth, forceRefresh = forceRefresh)
    }

    private suspend fun loadBootstrapLocked(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean
    ): YouTubePlaybackBootstrap {
        val startedAtMs = System.currentTimeMillis()
        var workingAuth = authProvider().normalized().takeIf { it.hasLoginCookies() } ?: auth
        var userAgent = workingAuth.resolveBootstrapUserAgent()
        var authFingerprint = workingAuth.buildBootstrapAuthFingerprint(
            origin = workingAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        var cookieHeader = appendYouTubeConsentCookie(workingAuth.effectiveCookieHeader())
        if (cookieHeader.isBlank()) {
            throw IOException("YouTube Music auth cookies missing")
        }

        val authGeneration = authCacheGeneration
        val homeHtml = try {
            fetchBootstrapHtml(
                auth = workingAuth,
                userAgent = userAgent,
                cookieHeader = cookieHeader
            )
        } catch (error: IOException) {
            if (isYouTubeAuthRecoverableFailure(error)) {
                if (shouldStartYouTubeWebAuthRecovery(error)) {
                    authAutoRefreshManager?.refreshIfNeeded(
                        reason = "playback_bootstrap_http_recoverable",
                        force = true
                    )
                }
                workingAuth = authProvider().normalized()
                cookieHeader = appendYouTubeConsentCookie(workingAuth.effectiveCookieHeader())
                if (cookieHeader.isBlank()) {
                    throw error
                }
                userAgent = workingAuth.resolveBootstrapUserAgent()
                authFingerprint = workingAuth.buildBootstrapAuthFingerprint(
                    origin = workingAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
                )
                fetchBootstrapHtml(
                    auth = workingAuth,
                    userAgent = userAgent,
                    cookieHeader = cookieHeader
                )
            } else {
                throw error
            }
        }
        val fetchedAtMs = System.currentTimeMillis()
        val bootstrapSource = YouTubeBootstrapHtmlSource(homeHtml)
        // 解析这段实测三到十四秒而下载只占一秒, 分段计时才知道是摊平 ytcfg 还是补 STS
        val ytcfgStartedAtMs = System.currentTimeMillis()
        val dataSyncId = bootstrapSource.optionalString("DATASYNC_ID", "datasyncId")
        val ytcfgElapsedMs = playbackElapsedMs(ytcfgStartedAtMs)
        val (derivedDelegatedSessionId, derivedUserSessionId) = parseDataSyncId(dataSyncId)
        val playerJsUrl = resolvePlayerJavaScriptUrl(
            bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "jsUrl"
            )
        )
        val cached = bootstrapCache
        val cachedSignatureTimestamp = cached
            ?.takeIf { it.playerJsUrl == playerJsUrl }
            ?.signatureTimestamp
        val bootstrapSignatureTimestamp = bootstrapSource.optionalNumber("STS", "signatureTimestamp")
            .toIntOrNull()
        val signatureTimestamp = bootstrapSignatureTimestamp
            ?: signatureTimestampCache[playerJsUrl]
            ?: if (forceRefresh) {
                // 强制刷新仍然要给 TV fallback 一份最新 STS, 普通首播交给后台补齐
                val stsStartedAtMs = System.currentTimeMillis()
                fetchPlayerSignatureTimestamp(playerJsUrl, userAgent)
                    .also {
                        NPLogger.d(
                            "YouTubeMusicPlayback",
                            "bootstrap fetched signature timestamp from player.js: elapsedMs=${playbackElapsedMs(stsStartedAtMs)}"
                        )
                    }
            } else {
                null
            }
        val parsedBootstrap = YouTubePlaybackBootstrap(
            apiKey = bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "INNERTUBE_API_KEY",
                "innertubeApiKey"
            ),
            webRemixClientVersion = bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "INNERTUBE_CLIENT_VERSION",
                "INNERTUBE_CONTEXT_CLIENT_VERSION",
                "innertubeContextClientVersion"
            ),
            visitorData = bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "VISITOR_DATA",
                "visitorData"
            ),
            playerJsUrl = playerJsUrl,
            cookieHeader = cookieHeader,
            authFingerprint = authFingerprint,
            sessionIndex = workingAuth.resolveXGoogAuthUser(
                fallback = bootstrapSource.optionalNumber("SESSION_INDEX").ifBlank { "0" }
            ),
            userAgent = userAgent,
            remoteHost = bootstrapSource.optionalString("remoteHost"),
            signatureTimestamp = signatureTimestamp ?: cachedSignatureTimestamp,
            appInstallData = bootstrapSource.optionalString("appInstallData"),
            coldConfigData = bootstrapSource.optionalString("coldConfigData"),
            coldHashData = bootstrapSource.optionalString(
                "coldHashData",
                "SERIALIZED_COLD_HASH_DATA"
            ),
            hotHashData = bootstrapSource.optionalString(
                "hotHashData",
                "SERIALIZED_HOT_HASH_DATA"
            ),
            deviceExperimentId = bootstrapSource.optionalString("deviceExperimentId"),
            rolloutToken = bootstrapSource.optionalString("rolloutToken"),
            dataSyncId = dataSyncId,
            delegatedSessionId = bootstrapSource.optionalString("DELEGATED_SESSION_ID")
                .ifBlank { derivedDelegatedSessionId },
            userSessionId = bootstrapSource.optionalString("USER_SESSION_ID")
                .ifBlank { derivedUserSessionId },
            loggedIn = bootstrapSource.optionalBoolean("LOGGED_IN")
                .equals("true", ignoreCase = true),
            fetchedAtMs = fetchedAtMs
        )
        if (cached != null && cached.webRemixClientVersion != parsedBootstrap.webRemixClientVersion) {
            YoutubeJavaScriptPlayerManager.clearAllCaches()
        }
        return parsedBootstrap.also { parsed ->
            NPLogger.d(
                "YouTubeMusicPlayback",
                "bootstrap parsed: forceRefresh=$forceRefresh, loggedIn=${parsed.loggedIn}, ytcfgMs=$ytcfgElapsedMs, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
            if (cached?.playerJsUrl != parsed.playerJsUrl) {
                inFlightPlayableAudioScope.launch {
                    runCatching {
                        ejsChallengeSolver?.warmPlayerScriptAsync(parsed.playerJsUrl)
                    }.onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "Warm player script cache failed: ${error.message}"
                        )
                    }
                }
            }
            if (parsed.signatureTimestamp == null) {
                warmSignatureTimestampAsync(
                    playerJsUrl = parsed.playerJsUrl,
                    userAgent = parsed.userAgent
                )
            }
            val holdsLoginCookies = workingAuth.hasLoginCookies()
            val demotesLogin = demotesCachedYouTubeLogin(
                cachedLoggedIn = cached?.loggedIn,
                parsedLoggedIn = parsed.loggedIn,
                holdsLoginCookies = holdsLoginCookies
            )
            if (demotesLogin) {
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "keep logged-in bootstrap: parsed came back anonymous while login cookies are present"
                )
            }
            if (authGeneration == authCacheGeneration && !demotesLogin) {
                bootstrapCache = parsed
                // 存档只收登录态: 出口被判游客时服务端会连着回 loggedIn=false,
                // 那份能用来播放但不能留到下次冷启动, 否则一开局就是掉登录
                if (!demotesYouTubeLogin(parsed.loggedIn, holdsLoginCookies)) {
                    bootstrapStore?.save(parsed)
                } else {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "cache anonymous bootstrap for playback but keep it out of the archive"
                    )
                }
            }
        }
    }

    private suspend fun fetchBootstrapHtml(
        auth: YouTubeAuthBundle,
        userAgent: String,
        cookieHeader: String
    ): String {
        var lastError: IOException? = null
        val requestLocale = currentPlayerRequestLocale()
        for ((index, origin) in BOOTSTRAP_PAGE_ORIGINS.withIndex()) {
            // 前一个 origin 若因 429/503 失败, 换 origin 前先退避, 避免脏 IP 下无延迟连打 (#Y5)
            if (index > 0) {
                rateLimitBackoffMs(lastError, index - 1)?.let { backoffMs ->
                    NPLogger.w(
                        "YouTubeMusicPlayback",
                        "bootstrap rate limited, backoff=${backoffMs}ms before origin=$origin"
                    )
                    delay(backoffMs)
                }
            }
            val startedAtMs = System.currentTimeMillis()
            val requestHeaders = auth.buildYouTubePageRequestHeaders(
                original = linkedMapOf(
                    "Accept-Language" to requestLocale.acceptLanguage
                ),
                userAgent = userAgent
            )
            val request = Request.Builder()
                .url("$origin/")
                .apply {
                    requestHeaders.forEach { (name, value) ->
                        header(name, value)
                    }
                    header("Cookie", cookieHeader)
                }
                .build()
            try {
                return executeText(request).also {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "fetchBootstrapHtml ok: origin=$origin, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                    )
                }
            } catch (error: IOException) {
                lastError = error
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "fetchBootstrapHtml failed: origin=$origin, elapsedMs=${playbackElapsedMs(startedAtMs)}, error=${error.message}"
                )
            }
        }
        throw lastError ?: IOException("YouTube Music bootstrap request failed")
    }

    private fun executeJson(request: Request): JSONObject {
        return JSONObject(executeText(request))
    }

    private fun executeText(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val preview = response.body
                    .readErrorPreviewWithLimit(YOUTUBE_ERROR_RESPONSE_MAX_BYTES)
                // 保留 "request failed: <code>" 消息格式以兼容 extractYouTubeRequestFailureCode 正则
                // 额外携带状态码与 Retry-After, 供 429/503 退避使用 (#Y5)
                throw YouTubeHttpStatusException(
                    statusCode = response.code,
                    retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                    message = "YouTube Music request failed: ${response.code} $preview"
                )
            }
            return response.body.readTextWithLimit(YOUTUBE_TEXT_RESPONSE_MAX_BYTES)
        }
    }

    private fun fetchPlayerSignatureTimestamp(
        playerJsUrl: String,
        userAgent: String
    ): Int? {
        if (playerJsUrl.isBlank()) {
            return null
        }
        // player.js 地址自带版本哈希，同一个地址的 STS 不会变，
        // 而这里要整份拉下约 2MB 才能取出一个数字，重复付这笔钱会把首播拖成秒级
        signatureTimestampCache[playerJsUrl]?.let { return it }
        val request = Request.Builder()
            .url(playerJsUrl)
            .header("User-Agent", userAgent)
            .build()
        return runCatching {
            val playerJs = executeText(request)
            Regex("""(?:signatureTimestamp|sts)\s*:\s*(\d{5})""")
                .find(playerJs)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }.onFailure { error ->
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Failed to fetch player signature timestamp",
                error
            )
        }.getOrNull()?.also { timestamp ->
            signatureTimestampCache[playerJsUrl] = timestamp
        }
    }

    private fun warmSignatureTimestampAsync(
        playerJsUrl: String,
        userAgent: String
    ) {
        if (playerJsUrl.isBlank() || signatureTimestampCache.containsKey(playerJsUrl)) {
            return
        }
        val created = inFlightPlayableAudioScope.async {
            val startedAtMs = System.currentTimeMillis()
            val timestamp = fetchPlayerSignatureTimestamp(playerJsUrl, userAgent)
            NPLogger.d(
                "YouTubeMusicPlayback",
                "background player signature timestamp warmup finished: hasValue=${timestamp != null}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
            timestamp
        }
        val task = inFlightSignatureTimestampWarmups.putIfAbsent(playerJsUrl, created)
        if (task != null) {
            created.cancel()
            return
        }
        created.invokeOnCompletion {
            inFlightSignatureTimestampWarmups.remove(playerJsUrl, created)
        }
    }

    private fun resolvePlayableAudioViaNewPipe(
        videoId: String,
        preferredQualityKey: String,
        logFailure: Boolean,
        preferM4a: Boolean
    ): YouTubePlayableAudio? {
        return runCatching {
            val streamInfo = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )
            selectPlayableAudio(streamInfo, preferredQualityKey, preferM4a)
        }.onFailure { error ->
            if (logFailure) {
                val auth = authProvider().normalized()
                NPLogger.e(
                    "YouTubeMusicPlayback",
                    "extract stream failed for $videoId (authUsable=${auth.isUsable()}, hasLoginCookies=${auth.hasLoginCookies()})",
                    error
                )
            }
        }.getOrNull()
    }

    private fun selectPlayableAudio(
        streamInfo: StreamInfo,
        preferredQualityKey: String,
        preferM4a: Boolean
    ): YouTubePlayableAudio? {
        val sortedStreams = streamInfo.audioStreams
            .asSequence()
            .filter { it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .thenByDescending { if (preferM4a) playableAudioMimePreferenceScore(it.format?.mimeType) else 0 }
                    .thenByDescending { it.averageBitrate }
                    .thenByDescending { it.bitrate }
            )
            .filter { it.content.isNotBlank() }
            .toList()
        val selectedStream = selectAudioStreamByQuality(
            streams = sortedStreams,
            preferredQualityKey = preferredQualityKey
        )
            ?: return null

        val resolvedDurationMs = streamInfo.duration
            .takeIf { it > 0L }
            ?.times(1000L)
            ?: 0L

        return YouTubePlayableAudio(
            url = selectedStream.content,
            durationMs = resolvedDurationMs,
            mimeType = selectedStream.format?.mimeType,
            contentLength = null,
            bitrateKbps = selectedStream.averageBitrate
                .takeIf { it > 0 }
                ?.let { (it + 500) / 1000 }
                ?: selectedStream.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 }
        )
    }

    private fun selectAudioStreamByQuality(
        streams: List<AudioStream>,
        preferredQualityKey: String
    ): AudioStream? {
        if (streams.isEmpty()) {
            return null
        }
        val sortedAscending = streams.asReversed()
        fun AudioStream.effectiveBitrate(): Int {
            return averageBitrate.takeIf { it > 0 } ?: bitrate
        }
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending.firstOrNull()
            YouTubeMusicPlaybackQuality.MEDIUM -> {
                sortedAscending.firstOrNull { it.effectiveBitrate() >= 96_000 }
                    ?: streams.firstOrNull()
            }
            YouTubeMusicPlaybackQuality.HIGH -> {
                sortedAscending.firstOrNull { it.effectiveBitrate() >= 128_000 }
                    ?: streams.firstOrNull()
            }
            YouTubeMusicPlaybackQuality.VERY_HIGH -> streams.firstOrNull()
        }
    }

    private fun YouTubeAudioMetadata?.mergePreferred(
        incoming: YouTubeAudioMetadata?
    ): YouTubeAudioMetadata? {
        if (incoming == null) {
            return this
        }
        if (this == null) {
            return incoming
        }
        return when {
            incoming.contentLength != null && this.contentLength == null -> incoming
            incoming.durationMs > this.durationMs -> incoming
            incoming.mimeType == "audio/mp4" && this.mimeType != "audio/mp4" -> incoming
            else -> this
        }
    }

    private fun YouTubePlayableAudio.mergeMetadataFrom(
        metadata: YouTubeAudioMetadata?
    ): YouTubePlayableAudio {
        if (metadata == null) {
            return this
        }
        return copy(
            durationMs = durationMs.takeIf { it > 0L } ?: metadata.durationMs,
            mimeType = mimeType ?: metadata.mimeType,
            contentLength = contentLength ?: metadata.contentLength
        )
    }

    private fun playerClientProfiles(): List<YouTubePlayerClientProfile> {
        return listOf(
            YouTubePlayerClientProfile(
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
            YouTubePlayerClientProfile(
                clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_TV_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_TV_USER_AGENT,
                endpointPath = "player",
                platform = "TV"
            ),
            YouTubePlayerClientProfile(
                clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_TV_DOWNGRADED_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_TV_DOWNGRADED_USER_AGENT,
                endpointPath = "player",
                platform = "TV"
            ),
            // ANDROID_MUSIC 需要 Android OAuth, 拿网页 cookie 只会回 400 INVALID_ARGUMENT
            // 或 LOGIN_REQUIRED, 还会把解析拖进强制刷新 bootstrap 并连累登录态
        )
    }

    private suspend fun resolvePreferredQualityKey(preferredQualityOverride: String?): String {
        return preferredQualityOverride
            ?.takeIf { it.isNotBlank() }
            ?: settings?.youtubeAudioQualityFlow?.first()?.takeIf { it.isNotBlank() }
            ?: "high"
    }

    internal fun selectPreferredPlayableAudio(
        current: YouTubePlayableAudio?,
        incoming: YouTubePlayableAudio?,
        currentClientName: String? = null,
        incomingClientName: String? = null,
        preferM4a: Boolean = false,
        preferredQualityKey: String? = null
    ): YouTubePlayableAudio? {
        if (incoming == null) {
            return current
        }
        if (current == null) {
            return incoming
        }
        if (
            preferM4a &&
            isPlayableM4aContainer(incoming.mimeType) != isPlayableM4aContainer(current.mimeType)
        ) {
            // 下载路径必须保留可写入标签的 m4a，即使另一 client 提供了更高码率 webm
            return if (isPlayableM4aContainer(incoming.mimeType)) incoming else current
        }
        val incomingSatisfiesPreferredQuality =
            satisfiesYouTubePlaybackQuality(incoming, preferredQualityKey)
        val currentSatisfiesPreferredQuality =
            satisfiesYouTubePlaybackQuality(current, preferredQualityKey)
        if (incomingSatisfiesPreferredQuality != currentSatisfiesPreferredQuality) {
            return if (incomingSatisfiesPreferredQuality) incoming else current
        }
        val qualityComparison = comparePlayableAudioQuality(incoming, current)
        return when {
            incoming.streamType != current.streamType -> {
                // 优先 progressive 直链, seek 更快且能绕过数据中心 IP 下的 HLS/SABR 403
                if (incoming.streamType == YouTubePlayableStreamType.DIRECT) incoming else current
            }
            qualityComparison != 0 -> {
                if (qualityComparison > 0) {
                    incoming
                } else {
                    current
                }
            }
            currentClientName != incomingClientName -> {
                val incomingClientScore = playbackClientPreferenceScore(
                    clientName = incomingClientName,
                    streamType = incoming.streamType
                )
                val currentClientScore = playbackClientPreferenceScore(
                    clientName = currentClientName,
                    streamType = current.streamType
                )
                if (incomingClientScore > currentClientScore) {
                    incoming
                } else {
                    current
                }
            }
            else -> current
        }
    }

    private fun playbackClientPreferenceScore(
        clientName: String?,
        streamType: YouTubePlayableStreamType
    ): Int {
        return when (streamType) {
            YouTubePlayableStreamType.DIRECT -> when {
                clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME -> 30
                clientName?.startsWith(YOUTUBE_PLAYER_TV_CLIENT_NAME, ignoreCase = true) == true -> 20
                clientName == YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME -> 10
                clientName.isNullOrBlank() -> 0
                else -> 5
            }
            YouTubePlayableStreamType.HLS -> when {
                clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME -> 20
                clientName?.startsWith(YOUTUBE_PLAYER_TV_CLIENT_NAME, ignoreCase = true) == true -> 5
                else -> 0
            }
        }
    }

    private fun shouldReturnPlayableAudioImmediately(
        profile: YouTubePlayerClientProfile,
        playableAudio: YouTubePlayableAudio,
        acceptedFromCurrentProfile: Boolean,
        preferredQualityKey: String,
        preferM4a: Boolean
    ): Boolean {
        if (!acceptedFromCurrentProfile) {
            return false
        }
        if (playableAudio.streamType != YouTubePlayableStreamType.DIRECT) {
            return false
        }
        if (
            !satisfiesYouTubePlaybackQuality(playableAudio, preferredQualityKey) &&
            !(preferM4a && isPlayableM4aContainer(playableAudio.mimeType))
        ) {
            return false
        }
        return profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME ||
            profile.clientName == YOUTUBE_PLAYER_TV_CLIENT_NAME ||
            profile.clientName == YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME
    }

    private fun comparePlayableAudioQuality(
        incoming: YouTubePlayableAudio,
        current: YouTubePlayableAudio
    ): Int {
        val incomingBitrate = incoming.bitrateKbps ?: 0
        val currentBitrate = current.bitrateKbps ?: 0
        if (incomingBitrate != currentBitrate) {
            return incomingBitrate.compareTo(currentBitrate)
        }

        val incomingSampleRate = incoming.sampleRateHz ?: 0
        val currentSampleRate = current.sampleRateHz ?: 0
        if (incomingSampleRate != currentSampleRate) {
            return incomingSampleRate.compareTo(currentSampleRate)
        }

        val incomingMimeScore = playableAudioMimePreferenceScore(incoming.mimeType)
        val currentMimeScore = playableAudioMimePreferenceScore(current.mimeType)
        if (incomingMimeScore != currentMimeScore) {
            return incomingMimeScore.compareTo(currentMimeScore)
        }

        val incomingContentLength = incoming.contentLength ?: 0L
        val currentContentLength = current.contentLength ?: 0L
        if (incomingContentLength != currentContentLength) {
            return incomingContentLength.compareTo(currentContentLength)
        }

        return incoming.durationMs.compareTo(current.durationMs)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun shouldRetryWithFreshBootstrapBeforeFallback(
        profile: YouTubePlayerClientProfile,
        playability: YouTubePlayerPlayabilityStatus,
        attempt: Int,
        forceRefresh: Boolean
    ): Boolean {
        // 先让后续 client 立即接管, 避免 WEB_REMIX 一次失败就白白多刷一轮 bootstrap
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    /** 429/408 可重试, 其余 4xx 表示请求参数不被接受, 强刷 bootstrap 无用 */
    private fun Throwable?.isNonRetryablePlayerClientError(): Boolean {
        val status = (this as? YouTubeHttpStatusException)?.statusCode ?: return false
        if (status == 429 || status == 408) {
            return false
        }
        return status in 400..499
    }

    private fun shouldRetryWithFreshBootstrapAfterRequestFailure(
        profile: YouTubePlayerClientProfile,
        attempt: Int,
        forceRefresh: Boolean
    ): Boolean {
        // 同一轮里先跑完 fallback, 下一轮再统一强刷 bootstrap
        return false
    }

    private fun getCachedPlayableAudio(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean = false,
        avoidDirect: Boolean = false
    ): YouTubePlayableAudio? {
        val cacheKey = playableAudioCacheKey(videoId, preferredQualityKey)
        synchronized(playableAudioCache) {
            val cached = playableAudioCache[cacheKey] ?: return null
            val nowMs = System.currentTimeMillis()
            if (nowMs >= cached.expiresAtMs) {
                playableAudioCache.remove(cacheKey)
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "drop expired playable audio cache: videoId=$videoId, quality=$preferredQualityKey, ageMs=${nowMs - cached.cachedAtMs}, expiresInMs=${cached.expiresAtMs - nowMs}"
                )
                return null
            }
            val acceptsM4aDownloadCache = preferredQualityKey.endsWith("_m4a") &&
                isPlayableM4aContainer(cached.audio.mimeType)
            if (!acceptsM4aDownloadCache && !satisfiesYouTubePlaybackQuality(
                    playableAudio = cached.audio,
                    preferredQualityKey = preferredQualityKey.removeSuffix("_m4a")
                )
            ) {
                playableAudioCache.remove(cacheKey)
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "drop cached playable audio below selected quality: " +
                        "videoId=$videoId, quality=$preferredQualityKey, bitrate=${cached.audio.bitrateKbps}"
                )
                return null
            }
            if (requireDirect && cached.audio.streamType != YouTubePlayableStreamType.DIRECT) {
                return null
            }
            // 命中缓存里的直链会拿回同一条 403 地址
            if (avoidDirect && cached.audio.streamType == YouTubePlayableStreamType.DIRECT) {
                return null
            }
            return cached.audio
        }
    }

    private fun cachePlayableAudio(
        videoId: String,
        preferredQualityKey: String,
        audio: YouTubePlayableAudio
    ) {
        val cacheKey = playableAudioCacheKey(videoId, preferredQualityKey)
        synchronized(playableAudioCache) {
            val nowMs = System.currentTimeMillis()
            playableAudioCache.remove(cacheKey)
            playableAudioCache[cacheKey] = CachedPlayableAudio(
                audio = audio,
                cachedAtMs = nowMs,
                expiresAtMs = resolvePlayableAudioCacheExpiresAtMs(
                    url = audio.url,
                    cachedAtMs = nowMs,
                    defaultTtlMs = PLAYABLE_URL_CACHE_TTL_MS
                )
            )
            while (playableAudioCache.size > PLAYABLE_URL_CACHE_MAX_SIZE) {
                val eldestKey = playableAudioCache.entries.firstOrNull()?.key ?: break
                playableAudioCache.remove(eldestKey)
            }
        }
    }

    private fun playableAudioCacheKey(videoId: String, preferredQualityKey: String): String {
        return "$videoId|${preferredQualityKey.lowercase(Locale.US)}"
    }

    private fun currentPlayerRequestLocale(): YouTubeMusicRequestLocale {
        return YouTubeMusicLocaleResolver.preferred()
    }

    private fun playerRequestLocaleCandidates(): List<YouTubeMusicRequestLocale> {
        return YouTubeMusicLocaleResolver.requestCandidates(
            preferredLocale = currentPlayerRequestLocale()
        )
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

    private fun findRequired(source: String, vararg patterns: String): String {
        return findOptional(source, *patterns).ifBlank {
            throw IOException("YouTube bootstrap parse failed: ${patterns.firstOrNull().orEmpty()}")
        }
    }

    private fun findOptional(source: String, vararg patterns: String): String {
        patterns.forEach { pattern ->
            val match = Regex(pattern).find(source)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                return match
            }
        }
        return ""
    }

    private fun parseDataSyncId(dataSyncId: String): Pair<String, String> {
        if (dataSyncId.isBlank()) {
            return "" to ""
        }
        val (first, second) = dataSyncId.split("||", limit = 2).let { parts ->
            parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
        }
        return if (second.isNotBlank()) {
            first to second
        } else {
            "" to first
        }
    }

    private companion object {
        val BOOTSTRAP_PAGE_ORIGINS: List<String> = listOf(
            YOUTUBE_MUSIC_ORIGIN,
            YOUTUBE_WEB_ORIGIN
        )
        const val PLAYABLE_URL_CACHE_TTL_MS: Long = 8L * 60L * 1000L
        const val PLAYABLE_BOOTSTRAP_TTL_MS: Long = 10L * 60L * 1000L
        const val PLAYABLE_URL_CACHE_MAX_SIZE: Int = 64
        const val PLAYER_REQUEST_MAX_ATTEMPTS: Int = 2
        val initializationLock = Any()

        @Volatile
        var initialized: Boolean = false
    }
}
