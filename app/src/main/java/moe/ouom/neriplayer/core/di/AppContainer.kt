package moe.ouom.neriplayer.core.di

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
 * File: moe.ouom.neriplayer.core.di/AppContainer
 * Created: 2025/8/19
 */

import moe.ouom.neriplayer.platform.youtube.api.auth.hasEffectiveAuth
import moe.ouom.neriplayer.platform.youtube.api.auth.normalized
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.platform.netease.api.auth.NeteaseYdDeviceTokenProvider
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.playback.BiliClientAudioDataSource
import moe.ouom.neriplayer.platform.bilibili.playback.BiliPlaybackRepository
import moe.ouom.neriplayer.platform.bilibili.skip.sponsorblock.BiliSponsorBlockRepository
import moe.ouom.neriplayer.platform.lyrics.api.client.AmllTtmlClient
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.lyrics.repository.AmllLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.KugouLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.QQMusicLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.api.client.KugouLyricsClient
import moe.ouom.neriplayer.platform.lyrics.api.client.LrcLibClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.platform.search.api.client.CloudMusicSearchApi
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.search.api.client.QQMusicSearchApi
import moe.ouom.neriplayer.platform.lyrics.search.SearchManager
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import moe.ouom.neriplayer.platform.youtube.repository.YouTubeMusicPlaybackRepository
import moe.ouom.neriplayer.platform.youtube.api.bootstrap.YouTubePlaybackBootstrapCoordinator
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.startup.app.InstrumentationTestRuntime
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.network.weblogin.ForegroundWebLoginGuard
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthAutoRefreshManager
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRotationWorker
import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.platform.bilibili.cache.BiliCacheRepositories
import moe.ouom.neriplayer.data.local.platform.bilibili.BiliVideoSkipRepositoryProvider
import moe.ouom.neriplayer.platform.netease.NeteasePlaylistCacheRepository
import moe.ouom.neriplayer.platform.youtube.playlist.YouTubeMusicPlaylistCacheRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.traffic.TrafficStatsRepository
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.data.ltw.ListenTogetherSessionManager
import moe.ouom.neriplayer.api.ltw.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.bootstrap.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.playback.persistPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.bootstrap.readBootstrapSettingsSnapshotSync
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.bootstrap.toBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.playback.toPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeInnertubeRequestHeaders
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.platform.youtube.api.transport.isTrustedYouTubeHost
import moe.ouom.neriplayer.platform.youtube.api.transport.isYouTubeGoogleVideoHost
import moe.ouom.neriplayer.platform.youtube.api.transport.isYouTubeInnertubeHost
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureDisabledException
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.platform.comments.CommentMemoryCache
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.platform.comments.repository.CommentRepository
import moe.ouom.neriplayer.platform.comments.repository.BiliCommentRepository
import moe.ouom.neriplayer.platform.comments.repository.NeteaseCommentRepository
import moe.ouom.neriplayer.network.proxy.DynamicProxySelector
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.LazyThreadSafetyMode

private const val SHARED_HTTP_CONNECT_TIMEOUT_MS = 8_000L
private const val SHARED_HTTP_READ_TIMEOUT_MS = 20_000L
private const val SHARED_HTTP_CALL_TIMEOUT_MS = 0L
private const val SHARED_HTTP_MAX_IDLE_CONNECTIONS = 8
private const val SHARED_HTTP_KEEP_ALIVE_MINUTES = 5L

internal fun configureSharedOkHttpClient(
    builder: OkHttpClient.Builder,
    connectionPool: ConnectionPool = ConnectionPool(
        SHARED_HTTP_MAX_IDLE_CONNECTIONS,
        SHARED_HTTP_KEEP_ALIVE_MINUTES,
        TimeUnit.MINUTES
    )
): OkHttpClient.Builder {
    assert(SHARED_HTTP_CONNECT_TIMEOUT_MS > 0L) { "connect timeout must be positive" }
    assert(SHARED_HTTP_READ_TIMEOUT_MS >= SHARED_HTTP_CONNECT_TIMEOUT_MS) {
        "read timeout must not be shorter than connect timeout"
    }
    assert(SHARED_HTTP_CALL_TIMEOUT_MS == 0L) {
        "shared client call timeout must stay disabled"
    }

    return builder
        .connectTimeout(SHARED_HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SHARED_HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // 播放, 同步与 WebSocket 共用此客户端, 不能用总时限截断长请求
        .callTimeout(SHARED_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectionPool(connectionPool)
        .retryOnConnectionFailure(true)
}

internal fun resolveInitialBypassProxy(
    currentValue: Boolean,
    loadPersistedValue: () -> Boolean
): Boolean = runCatching(loadPersistedValue).getOrDefault(currentValue)

internal data class InitialManagedDownloadSettings(
    val directoryUri: String? = null,
    val directoryLabel: String? = null,
    val fileNameTemplate: String? = null
)

internal fun resolveInitialManagedDownloadSettings(
    currentDirectoryUri: String? = null,
    currentDirectoryLabel: String? = null,
    currentFileNameTemplate: String? = null,
    loadDirectoryUri: () -> String?,
    loadDirectoryLabel: () -> String?,
    loadFileNameTemplate: () -> String?
): InitialManagedDownloadSettings {
    return InitialManagedDownloadSettings(
        directoryUri = runCatching(loadDirectoryUri).getOrDefault(currentDirectoryUri),
        directoryLabel = runCatching(loadDirectoryLabel).getOrDefault(currentDirectoryLabel),
        fileNameTemplate = runCatching(loadFileNameTemplate).getOrDefault(currentFileNameTemplate)
    ).let { resolved ->
        InitialManagedDownloadSettings(
            directoryUri = resolved.directoryUri?.takeIf(String::isNotBlank),
            directoryLabel = resolved.directoryLabel?.takeIf(String::isNotBlank),
            fileNameTemplate = resolved.fileNameTemplate?.takeIf(String::isNotBlank)
        )
    }
}

internal fun handleYouTubeAuthStateChanged(
    bundle: moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle,
    clearBootstrapCache: () -> Unit,
    clearPlaybackAuthBoundCaches: (Boolean) -> Unit,
    evictConnections: () -> Unit,
    youtubeEnabled: Boolean = true,
    warmBootstrapAsync: () -> Unit
) {
    if (!youtubeEnabled) {
        return
    }
    clearBootstrapCache()
    // 只移除旧请求引用, 避免 auth 恢复成功时把当前播放请求自己取消掉
    clearPlaybackAuthBoundCaches(false)
    evictConnections()
    warmYouTubePlaybackIfEnabled(
        youtubeEnabled = youtubeEnabled,
        warmBootstrapAsync = warmBootstrapAsync
    )
}

/**
 * 未登录也要预热
 *
 * bootstrap 和 player.js 这两笔匿名播放照样要付, 而且匿名用户没有任何别的流程会顺带
 * 把它们捂热; 以前卡在登录判断上, 结果最需要预热的那批人反而一次都热不到
 */
internal fun warmYouTubePlaybackIfEnabled(
    youtubeEnabled: Boolean = true,
    backgroundWarmupAllowed: Boolean = !InstrumentationTestRuntime.isActive,
    warmBootstrapAsync: () -> Unit
) {
    if (youtubeEnabled && backgroundWarmupAllowed && !ForegroundWebLoginGuard.isActive) {
        warmBootstrapAsync()
    }
}

private data class YouTubeAuthWarmBootstrapKey(
    val hasEffectiveAuth: Boolean,
    val authorization: String,
    val xGoogAuthUser: String,
    val origin: String,
    val userAgent: String,
)

private fun moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle.toWarmBootstrapKey():
    YouTubeAuthWarmBootstrapKey {
    val normalized = normalized()
    return YouTubeAuthWarmBootstrapKey(
        hasEffectiveAuth = normalized.hasEffectiveAuth(),
        authorization = normalized.authorization,
        xGoogAuthUser = normalized.xGoogAuthUser,
        origin = normalized.origin,
        userAgent = normalized.userAgent
    )
}

/**
 * 全局依赖容器, 使用 Service Locator 模式管理 App 的单例
 */
object AppContainer {
    private lateinit var application: Application
    @Volatile
    private var initialized = false
    val applicationContext: Application
        get() = application

    internal fun isInitialized(): Boolean = initialized

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val YOUTUBE_DOWNLOAD_PLAYBACK_CALL_TIMEOUT_MS = 20_000L

    // 基础 Repo
    val settingsRepo by lazy { SettingsRepository(application) }
    val listenTogetherPreferences by lazy { ListenTogetherPreferences(application) }
    val neteaseCookieRepo by lazy { NeteaseCookieRepository(application) }
    val biliCookieRepo by lazy { BiliCookieRepository(application) }
    val youtubeAuthRepo by lazy { YouTubeAuthRepository(application) }
    internal val youtubeAuthAutoRefreshManager by lazy {
        YouTubeAuthAutoRefreshManager(
            httpClientProvider = { sharedOkHttpClient },
            context = application,
            authProvider = youtubeAuthRepo::getAuthOnce,
            authHealthProvider = youtubeAuthRepo::getAuthHealthOnce,
            authUpdater = youtubeAuthRepo::saveAuth,
            rotatedCookieUpdater = youtubeAuthRepo::mergeRotatedCookies
        )
    }

    private val youtubePlaybackBootstrapCoordinator = YouTubePlaybackBootstrapCoordinator()


    val playHistoryRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlayHistoryRepository.getInstance(application)
    }
    val playbackStatsRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackStatsRepository.getInstance(application)
    }
    val trafficStatsRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TrafficStatsRepository.getInstance(application)
    }
    val playlistUsageRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaylistUsageRepository.getInstance(application)
    }
    val localPlaylistPlaybackStatsRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalPlaylistPlaybackStatsRepository.getInstance(application)
    }
    val biliFavoriteFolderCacheRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BiliCacheRepositories.createFavoriteFolder(application)
    }
    val biliArchiveCacheRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BiliCacheRepositories.createArchive(application)
    }
    val neteasePlaylistCacheRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NeteasePlaylistCacheRepository(application)
    }
    val youtubeMusicPlaylistCacheRepo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        YouTubeMusicPlaylistCacheRepository(application)
    }


    // 共享 OkHttpClient: 受 DynamicProxySelector 管理
    val sharedOkHttpClient by lazy {
        val clientBuilder = OkHttpClient.Builder()
            .proxySelector(DynamicProxySelector)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host.lowercase()
                if (!isYouTubeHost(host)) {
                    return@addInterceptor chain.proceed(request)
                }
                if (!YouTubeFeatureGate.isEnabled()) {
                    throw YouTubeFeatureDisabledException()
                }

                val auth = youtubeAuthRepo.getAuthOnce().normalized()
                val originalHeaders = linkedMapOf<String, String>().apply {
                    request.headers.names().forEach { name ->
                        request.header(name)?.let { value -> put(name, value) }
                    }
                }
                val resolvedHeaders = when {
                    isYouTubeGoogleVideoHost(host) -> auth.buildYouTubeStreamRequestHeaders(
                        original = originalHeaders,
                        refererOrigin = request.header("Referer")
                            .orEmpty()
                            .removeSuffix("/")
                            .ifBlank {
                                request.header("Origin")
                                    .orEmpty()
                                    .removeSuffix("/")
                            }
                            .ifBlank { auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN } },
                        streamUrl = request.url.toString()
                    )
                    isYouTubeInnertubeRequest(request) -> auth.buildYouTubeInnertubeRequestHeaders(
                        original = originalHeaders,
                        authorizationOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
                        includeAuthorization = true
                    )
                    else -> auth.buildYouTubePageRequestHeaders(
                        original = originalHeaders
                    )
                }
                val builder = request.newBuilder()
                request.headers.names().forEach { name ->
                    builder.removeHeader(name)
                }
                resolvedHeaders.forEach { (name, value) ->
                    builder.header(name, value)
                }
                val response = chain.proceed(builder.build())
                val setCookieHeaders = response.headers.values("Set-Cookie")
                if (setCookieHeaders.isNotEmpty()) {
                    runCatching {
                        youtubeAuthRepo.mergeCookieUpdates(setCookieHeaders)
                    }.onFailure { error ->
                        NPLogger.w("AppContainer", "Failed to merge YouTube Set-Cookie headers", error)
                    }
                }
                response
            }
        configureSharedOkHttpClient(clientBuilder).build()
    }

    // 网络客户端
    val neteaseClient by lazy {
        NeteaseClient {
            NeteaseYdDeviceTokenProvider(applicationContext)
                .getCommentToken()
        }.also { client ->
            neteaseCookieRepo.withCurrentCookies { cookies ->
                client.setPersistedCookies(cookies)
            }
        }
    }

    val biliClient by lazy { BiliClient(biliCookieRepo, client = sharedOkHttpClient) }

    private val commentCache = CommentMemoryCache()
    private val neteaseComments = NeteaseCommentRepository(commentCache) { neteaseClient }
    private val biliComments = BiliCommentRepository(commentCache) { biliClient }

    internal fun commentRepositoryFor(platform: CommentPlatform): CommentRepository = when (platform) {
        CommentPlatform.NETEASE -> neteaseComments
        CommentPlatform.BILIBILI -> biliComments
    }
    internal val biliSponsorBlockRepository by lazy { BiliSponsorBlockRepository(sharedOkHttpClient) }
    internal val biliVideoSkipRepository by lazy { BiliVideoSkipRepositoryProvider.getInstance(application) }
    private val youtubeMusicClientDelegate = lazy {
        YouTubeMusicClient(
            authRepo = youtubeAuthRepo,
            okHttpClient = sharedOkHttpClient,
            authAutoRefreshManager = youtubeAuthAutoRefreshManager
        )
    }
    val youtubeMusicClient: YouTubeMusicClient
        get() = youtubeMusicClientDelegate.value

    // 功能 Repo 和 API
    val biliPlaybackRepository by lazy {
        val dataSource = BiliClientAudioDataSource(biliClient)
        BiliPlaybackRepository(dataSource) { settingsRepo.biliAudioQualityFlow.first() }
    }
    private val youtubeMusicPlaybackRepositoryDelegate = lazy {
        YouTubeMusicPlaybackRepository(
            okHttpClient = sharedOkHttpClient,
            audioQualityProvider = { settingsRepo.youtubeAudioQualityFlow.first() },
            playbackSourceProvider = { settingsRepo.youtubePlaybackSourceFlow.first() },
            authProvider = youtubeAuthRepo::getAuthOnce,
            authAutoRefreshManager = youtubeAuthAutoRefreshManager,
            applicationContext = application,
            bootstrapCoordinator = youtubePlaybackBootstrapCoordinator
        )
    }
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
        get() = youtubeMusicPlaybackRepositoryDelegate.value
    private val youtubeMusicDownloadPlaybackRepositoryDelegate = lazy {
        YouTubeMusicPlaybackRepository(
            okHttpClient = sharedOkHttpClient.newBuilder()
                .callTimeout(YOUTUBE_DOWNLOAD_PLAYBACK_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build(),
            audioQualityProvider = { settingsRepo.youtubeAudioQualityFlow.first() },
            playbackSourceProvider = { settingsRepo.youtubePlaybackSourceFlow.first() },
            authProvider = youtubeAuthRepo::getAuthOnce,
            authAutoRefreshManager = youtubeAuthAutoRefreshManager,
            applicationContext = application,
            bootstrapCoordinator = youtubePlaybackBootstrapCoordinator
        )
    }
    val youtubeMusicDownloadPlaybackRepository: YouTubeMusicPlaybackRepository
        get() = youtubeMusicDownloadPlaybackRepositoryDelegate.value

    val cloudMusicSearchApi by lazy {
        CloudMusicSearchApi(neteaseClient, sharedOkHttpClient, debugLogging = BuildConfig.DEBUG)
    }
    val qqMusicSearchApi by lazy {
        QQMusicLyricsRepository(
            api = QQMusicSearchApi(sharedOkHttpClient, debugLogging = BuildConfig.DEBUG),
            amllTtmlClient = amllTtmlClient,
            amllLyricsEnabledProvider = { settingsRepo.amllLyricsEnabledFlow.first() }
        )
    }
    val searchManager by lazy {
        SearchManager { platform ->
            when (platform) {
                MusicPlatform.CLOUD_MUSIC -> cloudMusicSearchApi
                MusicPlatform.QQ_MUSIC -> qqMusicSearchApi
            }
        }
    }
    val lrcLibClient by lazy { LrcLibLyricsRepository(LrcLibClient(sharedOkHttpClient)) }
    val amllTtmlClient by lazy { AmllLyricsRepository(AmllTtmlClient(sharedOkHttpClient)) }
    val kugouLyricsClient by lazy { KugouLyricsRepository(KugouLyricsClient(sharedOkHttpClient)) }
    val editableLyricsMatcher by lazy {
        EditableLyricsMatcher(
            cloudMusicSearchApi = cloudMusicSearchApi,
            qqMusicSearchApi = qqMusicSearchApi,
            kugouLyricsClient = kugouLyricsClient,
            lrcLibClient = lrcLibClient,
            amllTtmlClient = amllTtmlClient,
            youtubeMusicClient = youtubeMusicClient
        )
    }
    val listenTogetherApi by lazy { ListenTogetherApi(sharedOkHttpClient) }
    private val listenTogetherOkHttpClient by lazy {
        sharedOkHttpClient.newBuilder()
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
    val listenTogetherWebSocketClient by lazy { ListenTogetherWebSocketClient(listenTogetherOkHttpClient) }
    val listenTogetherSessionManager by lazy {
        ListenTogetherSessionManager(
            api = listenTogetherApi,
            webSocketClient = listenTogetherWebSocketClient,
            playback = moe.ouom.neriplayer.core.player.ltw.PlayerManagerListenTogetherHost,
            platform = moe.ouom.neriplayer.core.di.ltw.AppListenTogetherPlatform,
            songMapper = moe.ouom.neriplayer.core.player.ltw.PlayerListenTogetherSongMapper
        )
    }

    fun launchBackgroundIo(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    fun pauseYouTubeBackgroundWebWorkForForegroundLogin() {
        if (!::application.isInitialized) {
            return
        }
        if (youtubeMusicPlaybackRepositoryDelegate.isInitialized()) {
            youtubeMusicPlaybackRepositoryDelegate.value.clearAuthBoundCaches(
                cancelInFlightPlayableAudio = false
            )
        }
        if (youtubeMusicDownloadPlaybackRepositoryDelegate.isInitialized()) {
            youtubeMusicDownloadPlaybackRepositoryDelegate.value.clearAuthBoundCaches(
                cancelInFlightPlayableAudio = false
            )
        }
    }

    fun initialize(app: Application) {
        this.application = app
        initialized = true
        moe.ouom.neriplayer.core.integration.download.installDownloadDependencies()
        moe.ouom.neriplayer.core.di.player.installPlayerDependencies(app)
        moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess.bind(
            downloads = moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads,
            covers = moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers,
            crashLogs = moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup(
                moe.ouom.neriplayer.core.crash.ExceptionHandler::clearCrashLogs
            )
        )
        moe.ouom.neriplayer.data.network.DataHttpClients.bind { sharedOkHttpClient }
        moe.ouom.neriplayer.data.sync.host.SyncPlaybackActivity.bind {
            moe.ouom.neriplayer.core.player.PlayerManager.playbackControlPlayingFlow.value
        }
        moe.ouom.neriplayer.data.auth.youtube.YouTubeRotationHosts.bind(
            object : moe.ouom.neriplayer.data.auth.youtube.YouTubeRotationHost {
                override fun isInitialized() = AppContainer.isInitialized()
                override suspend fun isYouTubeEnabled() = settingsRepo.youtubeEnabledFlow.first()
                override fun readAuth() = youtubeAuthRepo.getAuthOnce()
                override fun persistRotatedCookies(cookies: Map<String, String>) {
                    youtubeAuthRepo.mergeRotatedCookies(cookies)
                }
            }
        )
        moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests.bind(
            moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler::schedule
        )
        AudioDownloadManager.initialize(app)
        warmLocalPlaylistRepository()
        warmBiliVideoSkipRepository()
        warmCoverUrlMapper()
        primeProxySetting()
        startCookieObserver()
        startYouTubeAuthObserver()
        startSettingsObserver()
        warmYouTubePlaybackOnAppStart()
    }

    private fun warmLocalPlaylistRepository() {
        scope.launch {
            runCatching {
                val repository = LocalPlaylistRepository.getInstance(application)
                if (!repository.awaitInitialized()) {
                    NPLogger.e("AppContainer", "Local playlist preload failed")
                }
            }.onFailure { error ->
                NPLogger.e("AppContainer", "Failed to preload local playlists", error)
            }
        }
    }

    private fun warmBiliVideoSkipRepository() {
        scope.launch {
            runCatching {
                BiliVideoSkipRepositoryProvider.getInstance(application)
            }.onFailure { error ->
                NPLogger.e("AppContainer", "Failed to preload Bili video skip rules", error)
            }
        }
    }

    private fun warmCoverUrlMapper() {
        scope.launch {
            runCatching {
                CoverUrlMapper.getInstance(application)
            }.onFailure { error ->
                NPLogger.e("AppContainer", "Failed to preload cover URL mappings", error)
            }
        }
    }

    private fun primeProxySetting() {
        val initialBootstrapSettings = readBootstrapSettingsSnapshotSync(application)
        DynamicProxySelector.bypassProxy = initialBootstrapSettings.bypassProxy
        YouTubeFeatureGate.update(initialBootstrapSettings.youtubeEnabled)
        ManagedDownloadStorage.primeSettings(
            directoryUri = initialBootstrapSettings.downloadDirectoryUri,
            directoryLabel = initialBootstrapSettings.downloadDirectoryLabel,
            fileNameTemplate = initialBootstrapSettings.downloadFileNameTemplate
        )
    }

    private fun startCookieObserver() {
        neteaseCookieRepo.cookieFlow
            .onEach { cookies ->
                neteaseCookieRepo.withCurrentCookiesIfMatches(cookies) { currentCookies ->
                    neteaseClient.setPersistedCookies(currentCookies)
                }
            }
            .launchIn(scope)
    }

    private fun startYouTubeAuthObserver() {
        youtubeAuthRepo.authFlow
            .drop(1)
            .distinctUntilChangedBy { bundle -> bundle.toWarmBootstrapKey() }
            .onEach { bundle ->
                if (!YouTubeFeatureGate.isEnabled()) {
                    return@onEach
                }
                handleYouTubeAuthStateChanged(
                    bundle = bundle,
                    clearBootstrapCache = youtubeMusicClient::clearBootstrapCache,
                    clearPlaybackAuthBoundCaches = { cancelInFlight ->
                        youtubeMusicPlaybackRepository.clearAuthBoundCaches(cancelInFlight)
                        youtubeMusicDownloadPlaybackRepository.clearAuthBoundCaches(cancelInFlight)
                    },
                    evictConnections = sharedOkHttpClient.connectionPool::evictAll,
                    youtubeEnabled = YouTubeFeatureGate.isEnabled(),
                    warmBootstrapAsync = youtubeMusicPlaybackRepository::warmBootstrapAsync
                )
            }
            .launchIn(scope)
    }

    private fun startSettingsObserver() {
        application.dataStore.data
            .onEach { preferences ->
                persistBootstrapSettingsSnapshot(
                    application,
                    preferences.toBootstrapSettingsSnapshot()
                )
                persistPlaybackPreferenceSnapshot(
                    application,
                    preferences.toPlaybackPreferenceSnapshot()
                )
            }
            .launchIn(scope)

        settingsRepo.bypassProxyFlow
            .onEach { enabled ->
                DynamicProxySelector.bypassProxy = enabled
                sharedOkHttpClient.connectionPool.evictAll()
                neteaseClient.evictConnections()
                AudioDownloadManager.notifyRecoveryOpportunity("proxy_changed")
            }
            .launchIn(scope)

        settingsRepo.downloadDirectoryUriFlow
            .onEach { uri ->
                ManagedDownloadStorage.updateCustomDirectoryUri(uri)
            }
            .launchIn(scope)

        settingsRepo.downloadDirectoryLabelFlow
            .onEach { label ->
                ManagedDownloadStorage.updateCustomDirectoryLabel(label)
            }
            .launchIn(scope)

        settingsRepo.downloadFileNameTemplateFlow
            .onEach { template ->
                ManagedDownloadStorage.updateDownloadFileNameTemplate(template)
            }
            .launchIn(scope)

        settingsRepo.youtubeEnabledFlow
            .onEach { enabled ->
                val wasEnabled = YouTubeFeatureGate.isEnabled()
                YouTubeFeatureGate.update(enabled)
                if (wasEnabled && !enabled) {
                    YouTubeAuthRotationWorker.cancelPeriodicRotation(application)
                    if (youtubeMusicClientDelegate.isInitialized()) {
                        youtubeMusicClientDelegate.value.clearBootstrapCache()
                    }
                    if (youtubeMusicPlaybackRepositoryDelegate.isInitialized()) {
                        youtubeMusicPlaybackRepositoryDelegate.value.clearAuthBoundCaches()
                    }
                    if (youtubeMusicDownloadPlaybackRepositoryDelegate.isInitialized()) {
                        youtubeMusicDownloadPlaybackRepositoryDelegate.value.clearAuthBoundCaches()
                    }
                    AudioDownloadManager.cancelActiveYouTubeDownloads()
                    cancelYouTubeCalls()
                } else if (!wasEnabled && enabled) {
                    YouTubeAuthRotationWorker.schedulePeriodicRotation(application)
                    warmYouTubePlaybackOnAppStart()
                }
            }
            .launchIn(scope)
    }

    private fun warmYouTubePlaybackOnAppStart() {
        if (!YouTubeFeatureGate.isEnabled()) {
            return
        }
        warmYouTubePlaybackIfEnabled(
            youtubeEnabled = YouTubeFeatureGate.isEnabled(),
            warmBootstrapAsync = youtubeMusicPlaybackRepository::warmBootstrapAsync
        )
    }

    private fun cancelYouTubeCalls() {
        val calls = sharedOkHttpClient.dispatcher.queuedCalls() +
            sharedOkHttpClient.dispatcher.runningCalls()
        calls.filter { call -> isYouTubeHost(call.request().url.host) }
            .forEach { call -> call.cancel() }
    }

    private fun isYouTubeHost(host: String): Boolean {
        return isTrustedYouTubeHost(host)
    }

    private fun isYouTubeInnertubeRequest(request: Request): Boolean {
        val host = request.url.host.lowercase()
        val path = request.url.encodedPath.lowercase()
        return isYouTubeInnertubeHost(host) || path.startsWith("/youtubei/")
    }
}
