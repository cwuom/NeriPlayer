package moe.ouom.neriplayer.core.api.youtube

import android.content.Context
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthAutoRefreshManager
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.auth.youtube.isYouTubeAuthRecoverableFailure
import moe.ouom.neriplayer.data.auth.youtube.shouldStartYouTubeWebAuthRecovery
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.appendYouTubeConsentCookie
import moe.ouom.neriplayer.data.platform.youtube.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.effectiveCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveBootstrapUserAgent
import moe.ouom.neriplayer.data.platform.youtube.resolveXGoogAuthUser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import kotlin.time.Duration.Companion.milliseconds

private const val PLAYABLE_BOOTSTRAP_TTL_MS = 10L * 60L * 1000L
private val BOOTSTRAP_PAGE_ORIGINS = listOf(YOUTUBE_MUSIC_ORIGIN, YOUTUBE_WEB_ORIGIN)

/** player.js 地址到 STS 的进程级缓存，地址带版本哈希所以无需失效 */
private val signatureTimestampCache = ConcurrentHashMap<String, Int>()

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

internal fun parseYouTubeDataSyncId(dataSyncId: String): Pair<String, String> {
    if (dataSyncId.isBlank()) return "" to ""
    val separator = dataSyncId.indexOf("||")
    if (separator < 0) return "" to dataSyncId
    val userSession = dataSyncId.substring(separator + 2)
    return if (userSession.isBlank()) {
        "" to dataSyncId.substring(0, separator)
    } else {
        dataSyncId.substring(0, separator) to userSession
    }
}

internal fun resolveYouTubePlayerJavaScriptUrl(rawUrl: String): String = when {
    rawUrl.startsWith("https://") || rawUrl.startsWith("http://") -> rawUrl
    rawUrl.startsWith("//") -> "https:$rawUrl"
    rawUrl.startsWith("/") -> "$YOUTUBE_MUSIC_ORIGIN$rawUrl"
    else -> "$YOUTUBE_MUSIC_ORIGIN/$rawUrl"
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

internal data class InFlightBootstrapRequest(
    val authFingerprint: String,
    val forceRefresh: Boolean
)

internal class YouTubePlaybackBootstrapOwner(
    private val okHttpClient: OkHttpClient,
    private val authProvider: () -> YouTubeAuthBundle,
    private val authAutoRefreshManager: YouTubeAuthAutoRefreshManager?,
    private val ejsChallengeSolver: YouTubeEjsChallengeSolver?,
    applicationContext: Context?,
    private val coordinator: YouTubePlaybackBootstrapCoordinator,
    private val scope: CoroutineScope
) {
    private val bootstrapStore = applicationContext?.let(::YouTubeBootstrapStore)
    private val bootstrapRequestLock = coordinator.requestLock
    private val inFlightBootstrapRequests = coordinator.inFlightRequests
    private val bootstrapLoadMutex = coordinator.loadMutex
    private val inFlightSignatureTimestampWarmups = ConcurrentHashMap<String, Deferred<Int?>>()
    @Volatile private var bootstrapSnapshotRestored = false
    @Volatile private var inFlightStaleBootstrapRefresh: Deferred<Unit>? = null
    @Volatile private var authCacheGeneration = 0L

    val current: YouTubePlaybackBootstrap?
        get() = coordinator.cache

    fun signatureTimestampFor(playerJsUrl: String): Int? = signatureTimestampCache[playerJsUrl]

    private var bootstrapCache: YouTubePlaybackBootstrap?
        get() = coordinator.cache
        set(value) { coordinator.cache = value }

    fun clear() {
        authCacheGeneration += 1L
        bootstrapCache = null
        bootstrapStore?.clear()
        bootstrapSnapshotRestored = true
        synchronized(bootstrapRequestLock) {
            val deferreds = inFlightBootstrapRequests.values.toList()
            inFlightBootstrapRequests.clear()
            deferreds.forEach { it.cancel(CancellationException("YouTube auth updated")) }
            inFlightStaleBootstrapRefresh?.cancel(CancellationException("YouTube auth updated"))
            inFlightStaleBootstrapRefresh = null
        }
    }

    suspend fun bootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean = false
    ): YouTubePlaybackBootstrap {
        val startedAtMs = System.currentTimeMillis()
        val requestAuth = authProvider().normalized().takeIf { it.hasLoginCookies() } ?: auth
        val fingerprint = requestAuth.buildBootstrapAuthFingerprint(
            origin = requestAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        val cached = bootstrapCache ?: restoreBootstrapSnapshotIfNeeded(
            authFingerprint = fingerprint,
            cookieHeader = appendYouTubeConsentCookie(requestAuth.effectiveCookieHeader())
        )
        serveCachedBootstrap(cached, fingerprint, auth, forceRefresh, startedAtMs)?.let { return it }
        return awaitCoalescedBootstrap(auth, fingerprint, cached, forceRefresh, startedAtMs)
    }

    private fun serveCachedBootstrap(
        cached: YouTubePlaybackBootstrap?,
        fingerprint: String,
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean,
        startedAtMs: Long
    ): YouTubePlaybackBootstrap? {
        val validCache = cached ?: return null
        val ageMs = cachedBootstrapAge(validCache, fingerprint, forceRefresh) ?: return null
        if (ageMs < PLAYABLE_BOOTSTRAP_TTL_MS) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "bootstrap cache hit: forceRefresh=$forceRefresh, ageMs=$ageMs, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
            return validCache
        }
        if (ageMs >= BOOTSTRAP_SNAPSHOT_MAX_AGE_MS) return null
        refreshStaleBootstrapAsync(auth)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "bootstrap stale hit: ageMs=$ageMs, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return validCache
    }

    private fun cachedBootstrapAge(
        cached: YouTubePlaybackBootstrap,
        fingerprint: String,
        forceRefresh: Boolean
    ): Long? {
        if (forceRefresh || cached.authFingerprint != fingerprint) return null
        return System.currentTimeMillis() - cached.fetchedAtMs
    }

    private suspend fun awaitCoalescedBootstrap(
        auth: YouTubeAuthBundle,
        fingerprint: String,
        cached: YouTubePlaybackBootstrap?,
        forceRefresh: Boolean,
        startedAtMs: Long
    ): YouTubePlaybackBootstrap = bootstrapResultDeferred(
        auth, fingerprint, cached, forceRefresh, startedAtMs
    ).await()

    private fun bootstrapResultDeferred(
        auth: YouTubeAuthBundle,
        fingerprint: String,
        cached: YouTubePlaybackBootstrap?,
        forceRefresh: Boolean,
        startedAtMs: Long
    ): Deferred<YouTubePlaybackBootstrap> {
        val deferred = getOrCreateBootstrapLoad(auth, fingerprint, forceRefresh, startedAtMs)
        deferred.start()
        val staleFallback = usableStaleBootstrap(cached)
        if (shouldAwaitBootstrapLoad(forceRefresh, staleFallback != null)) return deferred
        return completedStaleBootstrap(staleFallback, fingerprint, startedAtMs)
    }

    private fun completedStaleBootstrap(
        staleFallback: YouTubePlaybackBootstrap?,
        fingerprint: String,
        startedAtMs: Long
    ): Deferred<YouTubePlaybackBootstrap> {
        val stale = checkNotNull(staleFallback)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "serve stale bootstrap instead of waiting: ageMs=${System.currentTimeMillis() - stale.fetchedAtMs}, fingerprintMatched=${stale.authFingerprint == fingerprint}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return CompletableDeferred(stale)
    }

    private fun usableStaleBootstrap(cached: YouTubePlaybackBootstrap?): YouTubePlaybackBootstrap? =
        cached?.takeIf { isUsableStaleBootstrap(it, System.currentTimeMillis()) }

    private fun getOrCreateBootstrapLoad(
        auth: YouTubeAuthBundle,
        fingerprint: String,
        forceRefresh: Boolean,
        startedAtMs: Long
    ): Deferred<YouTubePlaybackBootstrap> {
        val key = InFlightBootstrapRequest(fingerprint, forceRefresh)
        synchronized(bootstrapRequestLock) {
            val existing = activeBootstrapLoad(key)
            if (existing != null) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "join in-flight bootstrap: forceRefresh=$forceRefresh, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                )
                return existing
            }
            val created = createBootstrapLoad(key, auth, forceRefresh)
            inFlightBootstrapRequests[key] = created
            return created
        }
    }

    private fun activeBootstrapLoad(key: InFlightBootstrapRequest): Deferred<YouTubePlaybackBootstrap>? {
        val task = inFlightBootstrapRequests[key] ?: return null
        return if (task.isCompleted || task.isCancelled) null else task
    }

    private fun createBootstrapLoad(
        key: InFlightBootstrapRequest,
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean
    ): Deferred<YouTubePlaybackBootstrap> {
        lateinit var created: Deferred<YouTubePlaybackBootstrap>
        created = scope.async(start = CoroutineStart.LAZY) {
            try {
                loadBootstrap(auth = auth, forceRefresh = forceRefresh)
            } finally {
                synchronized(bootstrapRequestLock) {
                    if (inFlightBootstrapRequests[key] === created) inFlightBootstrapRequests.remove(key)
                }
            }
        }
        return created
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
        val generation = authCacheGeneration
        val store = claimSnapshotStore() ?: return null
        val snapshot = validStoredSnapshot(store, authFingerprint) ?: return null
        if (discardAnonymousSnapshot(store, snapshot)) return null
        val restored = snapshot.copy(cookieHeader = cookieHeader)
        publishRestoredSnapshot(restored, generation)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "bootstrap snapshot restored: ageMs=${System.currentTimeMillis() - restored.fetchedAtMs}, loggedIn=${restored.loggedIn}"
        )
        return restored
    }

    private fun validStoredSnapshot(
        store: YouTubeBootstrapStore,
        authFingerprint: String
    ): YouTubePlaybackBootstrap? {
        val snapshot = store.load() ?: return null
        return snapshot.takeIf {
            isYouTubeBootstrapSnapshotUsable(it, authFingerprint, System.currentTimeMillis())
        }
    }

    private fun discardAnonymousSnapshot(
        store: YouTubeBootstrapStore,
        snapshot: YouTubePlaybackBootstrap
    ): Boolean {
        if (!demotesYouTubeLogin(snapshot.loggedIn, authProvider().normalized().hasLoginCookies())) return false
        NPLogger.w(
            "YouTubeMusicPlayback",
            "drop anonymous bootstrap snapshot while login cookies are present"
        )
        store.clear()
        return true
    }

    private fun claimSnapshotStore(): YouTubeBootstrapStore? = synchronized(bootstrapRequestLock) {
        if (bootstrapSnapshotRestored) return@synchronized null
        bootstrapSnapshotRestored = true
        bootstrapStore
    }

    internal fun publishRestoredSnapshot(restored: YouTubePlaybackBootstrap, generation: Long) {
        synchronized(bootstrapRequestLock) {
            if (bootstrapCache == null && generation == authCacheGeneration) {
                bootstrapCache = restored
            }
        }
    }

    /**
     * 旧 bootstrap 还能用的时候, 刷新不该占着播放这条路
     *
     * 同一时刻只留一个刷新在跑, 否则连点几首歌就会并发拉好几份首页, 那正是首播被拖慢的原因
     */
    private fun refreshStaleBootstrapAsync(auth: YouTubeAuthBundle) {
        val task = synchronized(bootstrapRequestLock) {
            activeStaleRefresh() ?: createStaleRefresh(auth).also {
                inFlightStaleBootstrapRefresh = it
            }
        }
        task.start()
    }

    private fun activeStaleRefresh(): Deferred<Unit>? {
        val task = inFlightStaleBootstrapRefresh ?: return null
        return if (task.isCompleted || task.isCancelled) null else task
    }

    private fun createStaleRefresh(auth: YouTubeAuthBundle): Deferred<Unit> {
        val created = scope.async(start = CoroutineStart.LAZY) { refreshStaleSafely(auth) }
        created.invokeOnCompletion {
            synchronized(bootstrapRequestLock) {
                if (inFlightStaleBootstrapRefresh === created) {
                    inFlightStaleBootstrapRefresh = null
                }
            }
        }
        return created
    }

    private suspend fun refreshStaleSafely(auth: YouTubeAuthBundle) {
        try {
            loadBootstrap(auth = auth, forceRefresh = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w("YouTubeMusicPlayback", "stale bootstrap refresh failed: ${error.message}")
        }
    }

    private suspend fun loadBootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean
    ): YouTubePlaybackBootstrap = bootstrapLoadMutex.withLock {
        val cached = cachedBootstrapForLoad(auth, forceRefresh)
        cached ?: loadBootstrapLocked(auth = auth, forceRefresh = forceRefresh)
    }

    private fun cachedBootstrapForLoad(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean
    ): YouTubePlaybackBootstrap? {
        if (forceRefresh) return null
        val cached = freshCachedBootstrap(auth) ?: return null
        NPLogger.d(
            "YouTubeMusicPlayback",
            "bootstrap load coalesced: ageMs=${System.currentTimeMillis() - cached.fetchedAtMs}"
        )
        return cached
    }

    private fun freshCachedBootstrap(auth: YouTubeAuthBundle): YouTubePlaybackBootstrap? {
        val cached = bootstrapCache ?: return null
        return if (isCurrentFreshBootstrap(cached, auth)) cached else null
    }

    private fun isCurrentFreshBootstrap(cached: YouTubePlaybackBootstrap, auth: YouTubeAuthBundle): Boolean {
        if (cached.authFingerprint != fingerprintOf(preferredBootstrapAuth(auth))) return false
        return isFreshBootstrap(cached)
    }

    private fun preferredBootstrapAuth(auth: YouTubeAuthBundle): YouTubeAuthBundle {
        val current = authProvider().normalized()
        return if (current.hasLoginCookies()) current else auth
    }

    private fun fingerprintOf(auth: YouTubeAuthBundle): String =
        auth.buildBootstrapAuthFingerprint(origin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN })

    private fun isFreshBootstrap(cached: YouTubePlaybackBootstrap): Boolean =
        System.currentTimeMillis() - cached.fetchedAtMs < PLAYABLE_BOOTSTRAP_TTL_MS

    private data class BootstrapAuthContext(
        val auth: YouTubeAuthBundle,
        val userAgent: String,
        val fingerprint: String,
        val cookieHeader: String
    )

    private data class FetchedBootstrapHtml(
        val context: BootstrapAuthContext,
        val html: String,
        val fetchedAtMs: Long
    )

    private suspend fun loadBootstrapLocked(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean
    ): YouTubePlaybackBootstrap {
        val startedAtMs = System.currentTimeMillis()
        val authGeneration = authCacheGeneration
        val fetched = fetchBootstrapHtmlWithRecovery(auth)
        val cached = bootstrapCache
        val parseStartedAtMs = System.currentTimeMillis()
        val parsed = parseBootstrap(fetched, cached, forceRefresh)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "bootstrap parsed: forceRefresh=$forceRefresh, loggedIn=${parsed.loggedIn}, ytcfgMs=${playbackElapsedMs(parseStartedAtMs)}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        publishBootstrap(parsed, cached, fetched.context.auth, authGeneration)
        return parsed
    }

    private fun authContext(auth: YouTubeAuthBundle): BootstrapAuthContext = BootstrapAuthContext(
        auth = auth,
        userAgent = auth.resolveBootstrapUserAgent(),
        fingerprint = auth.buildBootstrapAuthFingerprint(
            origin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        ),
        cookieHeader = appendYouTubeConsentCookie(auth.effectiveCookieHeader())
    )

    private suspend fun fetchBootstrapHtmlWithRecovery(auth: YouTubeAuthBundle): FetchedBootstrapHtml {
        val initialAuth = authProvider().normalized().takeIf { it.hasLoginCookies() } ?: auth
        val initial = authContext(initialAuth)
        if (initial.cookieHeader.isBlank()) throw IOException("YouTube Music auth cookies missing")
        val response = try {
            initial to fetchBootstrapHtml(initial.auth, initial.userAgent, initial.cookieHeader)
        } catch (error: IOException) {
            recoverBootstrapHtml(error)
        }
        return FetchedBootstrapHtml(response.first, response.second, System.currentTimeMillis())
    }

    private suspend fun recoverBootstrapHtml(error: IOException): Pair<BootstrapAuthContext, String> {
        val refreshed = recoveredAuthContext(error)
        return refreshed to fetchBootstrapHtml(refreshed.auth, refreshed.userAgent, refreshed.cookieHeader)
    }

    private suspend fun recoveredAuthContext(error: IOException): BootstrapAuthContext {
        if (!isYouTubeAuthRecoverableFailure(error)) throw error
        refreshRecoverableWebAuth(error)
        val refreshed = authContext(authProvider().normalized())
        if (refreshed.cookieHeader.isBlank()) throw error
        return refreshed
    }

    private suspend fun refreshRecoverableWebAuth(error: IOException) {
        if (!shouldStartYouTubeWebAuthRecovery(error)) return
        authAutoRefreshManager?.refreshIfNeeded(
            reason = "playback_bootstrap_http_recoverable",
            force = true
        )
    }

    private fun parseBootstrap(
        fetched: FetchedBootstrapHtml,
        cached: YouTubePlaybackBootstrap?,
        forceRefresh: Boolean
    ): YouTubePlaybackBootstrap {
        val bootstrapSource = YouTubeBootstrapHtmlSource(fetched.html)
        val dataSyncId = bootstrapSource.optionalString("DATASYNC_ID", "datasyncId")
        val (derivedDelegatedSessionId, derivedUserSessionId) = parseYouTubeDataSyncId(dataSyncId)
        val playerJsUrl = resolveYouTubePlayerJavaScriptUrl(
            bootstrapSource.requireString("YouTube bootstrap parse failed", "jsUrl")
        )
        val signatureTimestamp = resolveSignatureTimestamp(
            bootstrapSource, playerJsUrl, fetched.context.userAgent, forceRefresh
        ) ?: cachedTimestampForPlayer(cached, playerJsUrl)
        return YouTubePlaybackBootstrap(
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
            cookieHeader = fetched.context.cookieHeader,
            authFingerprint = fetched.context.fingerprint,
            sessionIndex = parsedSessionIndex(fetched.context.auth, bootstrapSource),
            userAgent = fetched.context.userAgent,
            remoteHost = bootstrapSource.optionalString("remoteHost"),
            signatureTimestamp = signatureTimestamp,
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
            delegatedSessionId = optionalOrDerived(bootstrapSource, "DELEGATED_SESSION_ID", derivedDelegatedSessionId),
            userSessionId = optionalOrDerived(bootstrapSource, "USER_SESSION_ID", derivedUserSessionId),
            loggedIn = bootstrapSource.optionalBoolean("LOGGED_IN")
                .equals("true", ignoreCase = true),
            fetchedAtMs = fetched.fetchedAtMs
        )
    }

    private fun cachedTimestampForPlayer(cached: YouTubePlaybackBootstrap?, playerJsUrl: String): Int? {
        val previous = cached ?: return null
        return if (previous.playerJsUrl == playerJsUrl) previous.signatureTimestamp else null
    }

    private fun parsedSessionIndex(auth: YouTubeAuthBundle, source: YouTubeBootstrapHtmlSource): String =
        auth.resolveXGoogAuthUser(fallback = source.optionalNumber("SESSION_INDEX").ifBlank { "0" })

    private fun optionalOrDerived(source: YouTubeBootstrapHtmlSource, name: String, derived: String): String =
        source.optionalString(name).ifBlank { derived }

    private fun resolveSignatureTimestamp(
        source: YouTubeBootstrapHtmlSource,
        playerJsUrl: String,
        userAgent: String,
        forceRefresh: Boolean
    ): Int? {
        source.optionalNumber("STS", "signatureTimestamp").toIntOrNull()?.let { return it }
        signatureTimestampCache[playerJsUrl]?.let { return it }
        if (!forceRefresh) return null
        val startedAtMs = System.currentTimeMillis()
        return fetchPlayerSignatureTimestamp(playerJsUrl, userAgent).also {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "bootstrap fetched signature timestamp from player.js: elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
        }
    }

    private fun publishBootstrap(
        parsed: YouTubePlaybackBootstrap,
        cached: YouTubePlaybackBootstrap?,
        auth: YouTubeAuthBundle,
        authGeneration: Long
    ) {
        preparePlayerCaches(parsed, cached)
        val holdsLoginCookies = auth.hasLoginCookies()
        if (rejectDemotedBootstrap(cached, parsed, holdsLoginCookies)) return
        if (authGeneration != authCacheGeneration) return
        bootstrapCache = parsed
        archiveBootstrapIfAuthenticated(parsed, holdsLoginCookies)
    }

    private fun rejectDemotedBootstrap(
        cached: YouTubePlaybackBootstrap?,
        parsed: YouTubePlaybackBootstrap,
        holdsLoginCookies: Boolean
    ): Boolean {
        if (!demotesCachedYouTubeLogin(cached?.loggedIn, parsed.loggedIn, holdsLoginCookies)) return false
        NPLogger.w(
            "YouTubeMusicPlayback",
            "keep logged-in bootstrap: parsed came back anonymous while login cookies are present"
        )
        return true
    }

    private fun preparePlayerCaches(
        parsed: YouTubePlaybackBootstrap,
        cached: YouTubePlaybackBootstrap?
    ) {
        clearOldPlayerCache(cached, parsed)
        warmPlayerScriptIfChanged(cached, parsed)
        warmMissingSignatureTimestamp(parsed)
    }

    private fun clearOldPlayerCache(cached: YouTubePlaybackBootstrap?, parsed: YouTubePlaybackBootstrap) {
        if (cached == null) return
        if (cached.webRemixClientVersion != parsed.webRemixClientVersion) YoutubeJavaScriptPlayerManager.clearAllCaches()
    }

    private fun warmPlayerScriptIfChanged(cached: YouTubePlaybackBootstrap?, parsed: YouTubePlaybackBootstrap) {
        if (cached?.playerJsUrl != parsed.playerJsUrl) warmPlayerScriptAsync(parsed.playerJsUrl)
    }

    private fun warmMissingSignatureTimestamp(parsed: YouTubePlaybackBootstrap) {
        if (parsed.signatureTimestamp == null) warmSignatureTimestampAsync(parsed.playerJsUrl, parsed.userAgent)
    }

    private fun archiveBootstrapIfAuthenticated(parsed: YouTubePlaybackBootstrap, holdsLoginCookies: Boolean) {
        if (!demotesYouTubeLogin(parsed.loggedIn, holdsLoginCookies)) {
            bootstrapStore?.save(parsed)
            return
        }
        NPLogger.d(
            "YouTubeMusicPlayback",
            "cache anonymous bootstrap for playback but keep it out of the archive"
        )
    }

    private fun warmPlayerScriptAsync(playerJsUrl: String) {
        scope.launch { warmPlayerScript(playerJsUrl) }
    }

    private suspend fun warmPlayerScript(playerJsUrl: String) {
        try {
            ejsChallengeSolver?.warmPlayerScriptAsync(playerJsUrl)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NPLogger.w("YouTubeMusicPlayback", "Warm player script cache failed: ${error.message}")
        }
    }

    private suspend fun fetchBootstrapHtml(
        auth: YouTubeAuthBundle,
        userAgent: String,
        cookieHeader: String
    ): String {
        var lastError: IOException? = null
        val requestLocale = YouTubeMusicLocaleResolver.preferred()
        for ((index, origin) in BOOTSTRAP_PAGE_ORIGINS.withIndex()) {
            // 前一个 origin 若因 429/503 失败, 换 origin 前先退避, 避免脏 IP 下无延迟连打 (#Y5)
            if (index > 0) {
                rateLimitBackoffMs(lastError, index - 1)?.let { backoffMs ->
                    NPLogger.w(
                        "YouTubeMusicPlayback",
                        "bootstrap rate limited, backoff=${backoffMs}ms before origin=$origin"
                    )
                    delay(backoffMs.milliseconds)
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

    private fun executeText(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val preview = response.body.readErrorPreviewWithLimit(YOUTUBE_ERROR_RESPONSE_MAX_BYTES)
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
        if (playerJsUrl.isBlank()) return null
        // player.js 地址自带版本哈希，同一个地址的 STS 不会变，
        // 而这里要整份拉下约 2MB 才能取出一个数字，重复付这笔钱会把首播拖成秒级
        signatureTimestampCache[playerJsUrl]?.let { return it }
        return fetchUncachedSignatureTimestamp(playerJsUrl, userAgent)
    }

    private fun fetchUncachedSignatureTimestamp(playerJsUrl: String, userAgent: String): Int? {
        val request = Request.Builder()
            .url(playerJsUrl)
            .header("User-Agent", userAgent)
            .build()
        return runCatching {
            parsePlayerSignatureTimestamp(executeText(request))
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

    internal fun parsePlayerSignatureTimestamp(playerJs: String): Int? =
        Regex("""(?:signatureTimestamp|sts)\s*:\s*(\d{5})""")
            .find(playerJs)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun warmSignatureTimestampAsync(
        playerJsUrl: String,
        userAgent: String
    ) {
        if (!shouldWarmSignatureTimestamp(playerJsUrl)) return
        val created = scope.async { loadWarmSignatureTimestamp(playerJsUrl, userAgent) }
        registerSignatureWarmup(playerJsUrl, created)
    }

    private fun shouldWarmSignatureTimestamp(playerJsUrl: String): Boolean {
        if (playerJsUrl.isBlank()) return false
        return !signatureTimestampCache.containsKey(playerJsUrl)
    }

    private fun registerSignatureWarmup(playerJsUrl: String, created: Deferred<Int?>) {
        val task = inFlightSignatureTimestampWarmups.putIfAbsent(playerJsUrl, created)
        if (task != null) {
            created.cancel()
            return
        }
        created.invokeOnCompletion {
            inFlightSignatureTimestampWarmups.remove(playerJsUrl, created)
        }
    }

    private fun loadWarmSignatureTimestamp(playerJsUrl: String, userAgent: String): Int? {
        val startedAtMs = System.currentTimeMillis()
        val timestamp = fetchPlayerSignatureTimestamp(playerJsUrl, userAgent)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "background player signature timestamp warmup finished: hasValue=${timestamp != null}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return timestamp
    }

}
