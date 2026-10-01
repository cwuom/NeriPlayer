package moe.ouom.neriplayer.platform.youtube.repository

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
 * File: moe.ouom.neriplayer.platform.youtube.repository/YouTubeMusicPlaybackRepository
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.platform.youtube.api.auth.hasLoginCookies
import moe.ouom.neriplayer.platform.youtube.api.auth.isUsable
import moe.ouom.neriplayer.platform.youtube.api.auth.normalized
import android.content.Context
import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.jvm.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.youtube.api.bootstrap.YouTubePlaybackBootstrapCoordinator
import moe.ouom.neriplayer.platform.youtube.api.bootstrap.YouTubePlaybackBootstrapOwner
import moe.ouom.neriplayer.platform.youtube.api.challenge.YouTubeStreamingCipherResolver
import moe.ouom.neriplayer.platform.youtube.api.challenge.createDefaultStreamingCipherResolver
import moe.ouom.neriplayer.platform.youtube.api.challenge.extractStreamQueryParameter
import moe.ouom.neriplayer.platform.youtube.api.challenge.playbackElapsedMs
import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicRequestLocale
import moe.ouom.neriplayer.platform.youtube.api.protocol.PreparedYouTubePlayerRequest
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubeAudioMetadata
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubeMusicPlaybackQuality
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableAudio
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableStreamType
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlaybackBootstrap
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlaybackSourcePreference
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayerClientProfile
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayerPlayabilityStatus
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePlayableAudioSelection
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePlaybackStreamAccessOwner
import moe.ouom.neriplayer.platform.youtube.api.playback.isTrustedYouTubeDirectUrlForStrictRecovery
import moe.ouom.neriplayer.platform.youtube.api.playback.playableAudioMimePreferenceScore
import moe.ouom.neriplayer.platform.youtube.api.playback.shouldUseAnonymousYouTubeNewPipeFallback
import moe.ouom.neriplayer.platform.youtube.api.potoken.YouTubePoTokenProvider
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_TV_CLIENT_NAME
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubeMusicLocaleResolver
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubeMusicPlaybackParser
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubePlayerRequestComposer
import moe.ouom.neriplayer.platform.youtube.api.protocol.isYouTubeGoogleVideoStream
import moe.ouom.neriplayer.platform.youtube.api.protocol.playerClientProfiles
import moe.ouom.neriplayer.platform.youtube.api.protocol.requiresGvsPoToken
import moe.ouom.neriplayer.platform.youtube.api.transport.NewPipeOkHttpDownloader
import moe.ouom.neriplayer.platform.youtube.api.transport.YOUTUBE_ERROR_RESPONSE_MAX_BYTES
import moe.ouom.neriplayer.platform.youtube.api.transport.YOUTUBE_TEXT_RESPONSE_MAX_BYTES
import moe.ouom.neriplayer.platform.youtube.api.transport.YouTubeHttpStatusException
import moe.ouom.neriplayer.platform.youtube.api.transport.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.platform.youtube.api.transport.parseRetryAfterMs
import moe.ouom.neriplayer.platform.youtube.api.transport.rateLimitBackoffMs
import moe.ouom.neriplayer.platform.youtube.api.transport.readErrorPreviewWithLimit
import moe.ouom.neriplayer.platform.youtube.api.transport.readTextWithLimit
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.network.weblogin.ForegroundWebLoginGuard
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthAutoRefreshManager
import moe.ouom.neriplayer.platform.youtube.cache.YouTubePlayableAudioCache
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureDisabledException
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

// 预热请求本身已经由单例合并, 首播不应再额外等待调度窗口
private const val YOUTUBE_PLAYBACK_WARM_BOOTSTRAP_START_DELAY_MS = 0L
// 首播更看重尽快落到可播链路, 别在 fallback 前白等太久的 PO token
// 普通播放不为低概率的后续候选逐个启动 EJS, 失败后尽快交给 TVHTML5
private const val WEB_REMIX_PLAYBACK_MAX_CANDIDATES = 2
// EJS solver 内部按 solverLock 串行, 预取并发再高也不增加求解吞吐
// 只会把用户点击前面的排队深度成倍拉长, 每首歌还要占 sig 和 n 两次
private const val MAX_CONCURRENT_PREFETCH_RESOLVES = 1

/**
 * 记录哪些 player client 正在稳定地拒绝请求
 *
 * ANDROID_MUSIC 在部分账号和出口上恒回 400 INVALID_ARGUMENT, 换 bootstrap 也没用
 * 每次解析都白付一次往返; 连续失败到阈值后先停一段时间, 成功一次就恢复
 */
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

@VisibleForTesting
internal fun shouldRetryPlayerLocaleFallback(playabilityStatus: String): Boolean {
    return !playabilityStatus.equals("LOGIN_REQUIRED", ignoreCase = true) &&
        !playabilityStatus.equals("CONTENT_CHECK_REQUIRED", ignoreCase = true) &&
        !playabilityStatus.equals("AGE_CHECK_REQUIRED", ignoreCase = true)
}

private data class InFlightPlayableAudioRequest(
    val videoId: String,
    val preferredQualityKey: String,
    val sourcePreference: YouTubePlaybackSourcePreference,
    val requireDirect: Boolean,
    val preferM4a: Boolean,
    val forceRefresh: Boolean,
    // 参与去重键, 否则不同的直链安全策略会复用不兼容的在途结果
    val avoidDirect: Boolean,
    val allowUnverifiedDirectFallback: Boolean
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

private data class PlayerAudioResolution(
    val playableAudio: YouTubePlayableAudio? = null,
    val metadata: YouTubeAudioMetadata? = null
)

class YouTubeMusicPlaybackRepository(
    private val okHttpClient: OkHttpClient,
    private val audioQualityProvider: suspend () -> String = { "high" },
    private val playbackSourceProvider: suspend () -> YouTubePlaybackSourcePreference = {
        YouTubePlaybackSourcePreference.Automatic
    },
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() },
    private val authAutoRefreshManager: YouTubeAuthAutoRefreshManager? = null,
    private val streamingCipherResolverFactory: ((String) -> YouTubeStreamingCipherResolver)? = null,
    applicationContext: Context? = null,
    poTokenProvider: YouTubePoTokenProvider? = null,
    private val bootstrapCoordinator: YouTubePlaybackBootstrapCoordinator =
        YouTubePlaybackBootstrapCoordinator()
) {
    private val downloader = NewPipeOkHttpDownloader(okHttpClient, authProvider)
    private val playableAudioCache = YouTubePlayableAudioCache()
    private val inFlightPlayableAudio = linkedMapOf<InFlightPlayableAudioRequest, InFlightPlayableAudioEntry>()
    private val inFlightPlayableAudioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefetchResolveGate = YouTubePrefetchResolveGate(MAX_CONCURRENT_PREFETCH_RESOLVES)

    private val ejsChallengeSolver = createPlaybackEjsSolver(applicationContext, okHttpClient)
    private val streamAccessOwner = YouTubePlaybackStreamAccessOwner(
        okHttpClient = okHttpClient,
        poTokenProvider = resolvePlaybackPoTokenProvider(poTokenProvider, applicationContext, authProvider),
        scope = inFlightPlayableAudioScope
    )


    init {
        // NewPipe 解不动哪版 player.js 是确定的, 记到下次冷启动免得再白付一次三秒
        attachPlaybackFallbackStore(applicationContext)
    }

    private val bootstrapOwner = YouTubePlaybackBootstrapOwner(
        okHttpClient = okHttpClient,
        authProvider = authProvider,
        authAutoRefreshManager = authAutoRefreshManager,
        ejsChallengeSolver = ejsChallengeSolver,
        applicationContext = applicationContext,
        coordinator = bootstrapCoordinator,
        scope = inFlightPlayableAudioScope
    )

    private val bootstrapCache: YouTubePlaybackBootstrap?
        get() = bootstrapOwner.current

    private val warmBootstrapLock = Any()

    @Volatile
    private var inFlightWarmBootstrap: Deferred<Unit>? = null

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
        allowUnverifiedDirectFallback: Boolean = true,
        // 投机预取不认领在途解析, 否则闸门会被自己的预取一路提升到形同虚设
        isPrefetch: Boolean = false
    ): YouTubePlayableAudio? = withContext(Dispatchers.IO) {
        if (!YouTubeFeatureGate.isEnabled()) {
            throw YouTubeFeatureDisabledException()
        }
        val resolveStartedAtMs = System.currentTimeMillis()
        syncAuthBoundCachesIfNeeded(authProvider().normalized())
        val preferredQualityKey = resolvePreferredQualityKey(preferredQualityOverride)
        val sourcePreference = resolveYouTubePlaybackSource()
        val cacheKey = playableAudioCacheKey(
            preferredQualityKey = preferredQualityKey,
            preferM4a = preferM4a,
            sourcePreference = sourcePreference
        )
        NPLogger.d(
            "YouTubeMusicPlayback",
            "getBestPlayableAudio: videoId=$videoId, quality=$preferredQualityKey, source=${sourcePreference.storageValue}, forceRefresh=$forceRefresh, requireDirect=$requireDirect, preferM4a=$preferM4a, shareInFlight=$shareInFlight, avoidDirect=$avoidDirect, allowUnverifiedDirectFallback=$allowUnverifiedDirectFallback"
        )
        if (!forceRefresh) {
            getCachedPlayableAudio(
                videoId = videoId,
                preferredQualityKey = cacheKey,
                requireDirect = requireDirect,
                avoidDirect = avoidDirect,
                allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
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
                sourcePreference = sourcePreference,
                requireDirect = requireDirect,
                logFailure = true,
                preferM4a = preferM4a,
                cacheKey = cacheKey,
                forceRefresh = forceRefresh,
                avoidDirect = avoidDirect,
                allowUnverifiedDirectFallback = allowUnverifiedDirectFallback,
                isPrefetch = isPrefetch
            )
        } else {
            resolvePlayableAudio(
                videoId = videoId,
                preferredQualityKey = preferredQualityKey,
                sourcePreference = sourcePreference,
                requireDirect = requireDirect,
                logFailure = true,
                preferM4a = preferM4a,
                cacheKey = cacheKey,
                forceRefresh = forceRefresh,
                avoidDirect = avoidDirect,
                allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
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
        val sourcePreference = resolveYouTubePlaybackSource()
        val cacheKey = playableAudioCacheKey(
            preferredQualityKey = preferredQualityKey,
            preferM4a = preferM4a,
            sourcePreference = sourcePreference
        )
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
            sourcePreference = sourcePreference,
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
        inFlightPlayableAudioScope.launch(start = CoroutineStart.UNDISPATCHED) {
            syncAuthBoundCachesIfNeeded(authProvider().normalized())
            val preferredQualityKey = preferredQualityOverride.ifBlank { "high" }
            val sourcePreference = resolveYouTubePlaybackSource()
            val cacheKey = playableAudioCacheKey(
                preferredQualityKey = preferredQualityKey,
                preferM4a = preferM4a,
                sourcePreference = sourcePreference
            )
            if (
                getCachedPlayableAudio(
                    videoId = videoId,
                    preferredQualityKey = cacheKey,
                    requireDirect = requireDirect
                ) != null
            ) {
                return@launch
            }
            startPlayableAudioResolution(
                videoId = videoId,
                preferredQualityKey = preferredQualityKey,
                sourcePreference = sourcePreference,
                requireDirect = requireDirect,
                logFailure = false,
                preferM4a = preferM4a,
                cacheKey = cacheKey,
                forceRefresh = false,
                isPrefetch = true
            )
        }
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
        streamAccessOwner.warmSessionAsync(reason)
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
                            delay(YOUTUBE_PLAYBACK_WARM_BOOTSTRAP_START_DELAY_MS.milliseconds)
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
        bootstrapOwner.clear()
        lastAuthFingerprint = null
        synchronized(playableAudioCache) {
            playableAudioCache.clear()
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
        streamAccessOwner.clearSession()
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
        sourcePreference: YouTubePlaybackSourcePreference,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false,
        allowUnverifiedDirectFallback: Boolean = true
    ): YouTubePlayableAudio? {
        val authGeneration = authCacheGeneration
        val playerResolution = resolvePlayerAudioViaPlayerApi(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            sourcePreference = sourcePreference,
            requireDirect = requireDirect,
            logFailure = logFailure,
            preferM4a = preferM4a,
            forceRefresh = forceRefresh,
            avoidDirect = avoidDirect,
            allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
        )
        playerResolution?.playableAudio?.let { playableAudio ->
            if (authGeneration == authCacheGeneration) {
                cachePlayableAudio(videoId, cacheKey, playableAudio)
            }
            return playableAudio
        }

        if (!shouldUseAnonymousYouTubeNewPipeFallback(authProvider().normalized().hasLoginCookies())) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "skip anonymous NewPipe fallback for signed-in playback: videoId=$videoId"
            )
            return null
        }
        ensureInitialized()
        return resolvePlayableAudioViaNewPipe(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            logFailure = logFailure,
            preferM4a = preferM4a
        )   // 兜底路径也不能交出直链，否则原地循环 403
            ?.takeUnless {
                (avoidDirect && it.streamType == YouTubePlayableStreamType.DIRECT) ||
                    (!allowUnverifiedDirectFallback &&
                        !isTrustedYouTubeDirectForStrictRecovery(it))
            }
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
        sourcePreference: YouTubePlaybackSourcePreference,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false,
        allowUnverifiedDirectFallback: Boolean = true,
        isPrefetch: Boolean = false
    ): YouTubePlayableAudio? {
        return startPlayableAudioResolution(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            sourcePreference = sourcePreference,
            requireDirect = requireDirect,
            logFailure = logFailure,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = forceRefresh,
            avoidDirect = avoidDirect,
            allowUnverifiedDirectFallback = allowUnverifiedDirectFallback,
            isPrefetch = isPrefetch
        ).await()
    }

    private fun startPlayableAudioResolution(
        videoId: String,
        preferredQualityKey: String,
        sourcePreference: YouTubePlaybackSourcePreference,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false,
        allowUnverifiedDirectFallback: Boolean = true,
        isPrefetch: Boolean = false
    ): Deferred<YouTubePlayableAudio?> {
        val request = InFlightPlayableAudioRequest(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            sourcePreference = sourcePreference,
            requireDirect = requireDirect,
            preferM4a = preferM4a,
            forceRefresh = forceRefresh,
            avoidDirect = avoidDirect,
            allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
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
                                    sourcePreference = sourcePreference,
                                    requireDirect = requireDirect,
                                    logFailure = logFailure,
                                    preferM4a = preferM4a,
                                    cacheKey = cacheKey,
                                    forceRefresh = forceRefresh,
                                    avoidDirect = avoidDirect,
                                    allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
                                )
                            }
                        } else {
                            resolvePlayableAudio(
                                videoId = videoId,
                                preferredQualityKey = preferredQualityKey,
                                sourcePreference = sourcePreference,
                                requireDirect = requireDirect,
                                logFailure = logFailure,
                                preferM4a = preferM4a,
                                cacheKey = cacheKey,
                                forceRefresh = forceRefresh,
                                avoidDirect = avoidDirect,
                                allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
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
        sourcePreference: YouTubePlaybackSourcePreference,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        forceRefresh: Boolean,
        avoidDirect: Boolean = false,
        allowUnverifiedDirectFallback: Boolean = true
    ): PlayerAudioResolution? {
        // 网页端未登录也能取流, 需要的是 visitorData + PoToken 而不是登录 cookie
        // 此前对无登录 cookie 直接返回 null 会把匿名用户整体推给已失效的 NewPipe 兜底
        val auth = authProvider().normalized()

        return try {
            fetchPlayerAudioViaPlayerApi(
                videoId = videoId,
                preferredQualityKey = preferredQualityKey,
                sourcePreference = sourcePreference,
                auth = auth,
                requireDirect = requireDirect,
                preferM4a = preferM4a,
                forceRefresh = forceRefresh,
                avoidDirect = avoidDirect,
                allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
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
        sourcePreference: YouTubePlaybackSourcePreference,
        auth: YouTubeAuthBundle,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false,
        forceRefresh: Boolean = false,
        avoidDirect: Boolean = false,
        allowUnverifiedDirectFallback: Boolean = true
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
            // 出现 403 后不能再拿裸网页直链赌下一次 Range；优先等当前 WEB_REMIX
            // 的 token，拿不到再继续 HLS 等非直链回退
            val allowBlockingWebRemixPoToken =
                requireDirect || !allowUnverifiedDirectFallback
            val requestLocaleCandidates = playerRequestLocaleCandidates()
            var bestPlayableAudio: YouTubePlayableAudio? = null
            var bestPlayableAudioClientName: String? = null
            var webRemixPoTokenPrefetch: Deferred<String?>? = null
            var shouldRefreshBootstrapBeforeFallback = false
            // status=OK 却解不出候选流, 说明 bootstrap 本身没毛病, 问题在签名那一步
            var sawUndecipherableOkResponse = false
            var sawOkResponse = false
            var sawBootstrapSuspectOutcome = false
            var sawTerminalFallbackOutcome = false
            var sawPlayerRequestFailure = false
            val candidateProfiles = selectUsablePlayerClients(
                profiles = playerClientProfiles(
                    sourcePreference = sourcePreference,
                    isAuthenticated = auth.hasLoginCookies()
                ),
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
                            allowUnverifiedDirectFallback &&
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
                            localeIndex < requestLocaleCandidates.lastIndex &&
                            shouldRetryPlayerLocaleFallback(playability.status)
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
                            val cipherResolver = if (profile.includeSignatureTimestamp) {
                                createStreamingCipherResolver(
                                    videoId = videoId,
                                    playerJsUrl = bootstrap.playerJsUrl
                                )
                            } else {
                                null
                            }
                            val responseWebRemixPoTokenPrefetch = if (
                                profile.requiresGvsPoToken() &&
                                shouldPrefetchWebRemixPoToken(root)
                            ) {
                                webRemixPoTokenPrefetch =
                                    webRemixPoTokenPrefetch ?: prefetchWebRemixPoToken(
                                        videoId = videoId,
                                        bootstrap = bootstrap,
                                        forceRefresh = poTokenForceRefresh
                                    )
                                webRemixPoTokenPrefetch
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
                            val attachedDirectPlayableAudio = if (avoidDirect) {
                                null
                            } else {
                                maybeAttachGvsPoToken(
                                    playableAudio = parsedDirectPlayableAudio,
                                    profile = profile,
                                    videoId = videoId,
                                    auth = auth,
                                    bootstrap = bootstrap,
                                    forceRefresh = poTokenForceRefresh,
                                    prefetchedPoToken = responseWebRemixPoTokenPrefetch,
                                    allowBlockingAcquisition = allowBlockingWebRemixPoToken,
                                    allowUnverifiedDirectFallback = allowUnverifiedDirectFallback
                                )
                            }
                            val directPlayableAudio = attachedDirectPlayableAudio?.takeIf {
                                allowUnverifiedDirectFallback ||
                                    isTrustedYouTubeDirectForStrictRecovery(profile, it)
                            }
                            if (attachedDirectPlayableAudio != null && directPlayableAudio == null) {
                                NPLogger.d(
                                    "YouTubeMusicPlayback",
                                    "reject unverified direct fallback: videoId=$videoId, " +
                                        "client=${profile.clientName}, forceRefresh=$forceRefresh"
                                )
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
                                        prefetchedPoToken = responseWebRemixPoTokenPrefetch,
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
                        if (!playability.status.equals("OK", ignoreCase = true) &&
                            !shouldRetryPlayerLocaleFallback(playability.status)
                        ) {
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "skip locale fallback after ${playability.status}: " +
                                    "videoId=$videoId, client=${profile.clientName}"
                            )
                            continue@profileLoop
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
                            delay(backoffMs.milliseconds)
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
        allowBlockingAcquisition: Boolean,
        allowUnverifiedDirectFallback: Boolean
    ): YouTubePlayableAudio? = streamAccessOwner.maybeAttachGvsPoToken(
        playableAudio, profile, videoId, auth, bootstrap, forceRefresh, prefetchedPoToken,
        allowBlockingAcquisition, allowUnverifiedDirectFallback
    )

    private fun isTrustedYouTubeDirectForStrictRecovery(
        profile: YouTubePlayerClientProfile,
        playableAudio: YouTubePlayableAudio
    ): Boolean {
        if (!isTrustedYouTubeDirectForStrictRecovery(playableAudio)) {
            return false
        }
        if (!isYouTubeGoogleVideoStream(playableAudio.url)) {
            return true
        }
        val streamClientName = extractStreamQueryParameter(playableAudio.url, "c")
            ?.trim()
            ?.uppercase(Locale.US)
        return streamClientName == profile.clientName
    }

    private fun isTrustedYouTubeDirectForStrictRecovery(
        playableAudio: YouTubePlayableAudio
    ): Boolean {
        return playableAudio.streamType == YouTubePlayableStreamType.DIRECT &&
            isTrustedYouTubeDirectUrlForStrictRecovery(playableAudio.url)
    }

    private fun prefetchWebRemixPoToken(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean
    ): Deferred<String?>? = streamAccessOwner.prefetchWebRemixPoToken(videoId, bootstrap, forceRefresh)

    private fun shouldPrefetchWebRemixPoToken(root: JSONObject): Boolean =
        streamAccessOwner.shouldPrefetchWebRemixPoToken(root)

    private fun createStreamingCipherResolver(
        videoId: String,
        playerJsUrl: String
    ): YouTubeStreamingCipherResolver {
        streamingCipherResolverFactory?.let { factory -> return factory(videoId) }
        ensureInitialized()
        return createDefaultStreamingCipherResolver(
            videoId = videoId,
            playerJsUrl = playerJsUrl,
            fallbackPlayerJsUrl = { bootstrapCache?.playerJsUrl.orEmpty() },
            ejsChallengeSolver = ejsChallengeSolver
        )
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
    ): YouTubePlayableAudio? = streamAccessOwner.resolveHlsPlayableAudio(
        root, preferredQualityKey, auth, durationMs, profile, videoId, bootstrap,
        forceRefresh, prefetchedPoToken, allowBlockingAcquisition
    )

    private fun postPlayerRequest(
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        profile: YouTubePlayerClientProfile,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        val startedAtMs = System.currentTimeMillis()
        val signatureTimestamp = playerRequestSignatureTimestamp(profile, bootstrap)
        val prepared = YouTubePlayerRequestComposer.compose(
            videoId = videoId,
            auth = auth,
            bootstrap = bootstrap,
            profile = profile,
            requestLocale = requestLocale,
            signatureTimestamp = signatureTimestamp
        )
        logWebRemixPlayerRequest(videoId, requestLocale, profile, bootstrap, signatureTimestamp, prepared)
        val root = executeJson(prepared.request)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "postPlayerRequest ok: videoId=$videoId, client=${profile.clientName}, clientVersion=${prepared.clientVersion}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return selectPlayerResponseRoot(root, profile.responseField)
    }

    private fun playerRequestSignatureTimestamp(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap
    ): Int? {
        if (!profile.includeSignatureTimestamp) return null
        return resolveYouTubeSignatureTimestamp(
            bootstrapTimestamp = bootstrap.signatureTimestamp,
            cachedTimestamp = bootstrapOwner.signatureTimestampFor(bootstrap.playerJsUrl)
        )
    }

    private fun logWebRemixPlayerRequest(
        videoId: String,
        requestLocale: YouTubeMusicRequestLocale,
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        signatureTimestamp: Int?,
        prepared: PreparedYouTubePlayerRequest
    ) {
        if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "WEB_REMIX request context: videoId=$videoId, locale=${requestLocale.gl}/${requestLocale.hl}, originalUrl=${prepared.webRemixOriginalUrl}, referer=${prepared.webRemixWatchUrl}, remoteHost=${bootstrap.remoteHost.ifBlank { "<blank>" }}, signatureTimestamp=$signatureTimestamp, clientVersion=${prepared.clientVersion}"
            )
        }
    }

    private fun selectPlayerResponseRoot(root: JSONObject, responseField: String?): JSONObject {
        if (responseField == null) return root
        return root.optJSONObject(responseField) ?: root
    }

    private suspend fun bootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean = false
    ): YouTubePlaybackBootstrap = bootstrapOwner.bootstrap(auth, forceRefresh)

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

    private suspend fun resolvePreferredQualityKey(preferredQualityOverride: String?): String {
        return preferredQualityOverride
            ?.takeIf { it.isNotBlank() }
            ?: audioQualityProvider().takeIf { it.isNotBlank() }
            ?: "high"
    }

    private suspend fun resolveYouTubePlaybackSource(): YouTubePlaybackSourcePreference {
        return playbackSourceProvider()
    }

    private fun playableAudioCacheKey(
        preferredQualityKey: String,
        preferM4a: Boolean,
        sourcePreference: YouTubePlaybackSourcePreference
    ): String {
        val qualityKey = if (preferM4a) "${preferredQualityKey}_m4a" else preferredQualityKey
        return "${sourcePreference.storageValue}|$qualityKey"
    }

    internal fun selectPreferredPlayableAudio(
        current: YouTubePlayableAudio?,
        incoming: YouTubePlayableAudio?,
        currentClientName: String? = null,
        incomingClientName: String? = null,
        preferM4a: Boolean = false,
        preferredQualityKey: String? = null
    ): YouTubePlayableAudio? = YouTubePlayableAudioSelection.selectPreferred(
        current, incoming, currentClientName, incomingClientName, preferM4a, preferredQualityKey
    )

    private fun shouldReturnPlayableAudioImmediately(
        profile: YouTubePlayerClientProfile,
        playableAudio: YouTubePlayableAudio,
        acceptedFromCurrentProfile: Boolean,
        preferredQualityKey: String,
        preferM4a: Boolean
    ): Boolean = YouTubePlayableAudioSelection.shouldReturnImmediately(
        profile, playableAudio, acceptedFromCurrentProfile, preferredQualityKey, preferM4a
    )

    private fun shouldRetryWithFreshBootstrapBeforeFallback(
        profile: YouTubePlayerClientProfile,
        playability: YouTubePlayerPlayabilityStatus,
        attempt: Int,
        forceRefresh: Boolean
    ): Boolean {
        // 先让后续 client 立即接管, 避免 WEB_REMIX 一次失败就白白多刷一轮 bootstrap
        return false
    }

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
        avoidDirect: Boolean = false,
        allowUnverifiedDirectFallback: Boolean = true
    ): YouTubePlayableAudio? = playableAudioCache.get(
        videoId, preferredQualityKey, requireDirect, avoidDirect, allowUnverifiedDirectFallback
    )

    private fun cachePlayableAudio(
        videoId: String,
        preferredQualityKey: String,
        audio: YouTubePlayableAudio
    ) = playableAudioCache.put(videoId, preferredQualityKey, audio)

    private fun currentPlayerRequestLocale(): YouTubeMusicRequestLocale {
        return YouTubeMusicLocaleResolver.preferred()
    }

    private fun playerRequestLocaleCandidates(): List<YouTubeMusicRequestLocale> {
        return YouTubeMusicLocaleResolver.requestCandidates(
            preferredLocale = currentPlayerRequestLocale()
        )
    }

    private companion object {
        const val PLAYER_REQUEST_MAX_ATTEMPTS: Int = 2
        val initializationLock = Any()

        @Volatile
        var initialized: Boolean = false
    }
}
