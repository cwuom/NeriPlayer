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

import android.annotation.SuppressLint
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.BiliClientAudioDataSource
import moe.ouom.neriplayer.core.api.bili.BiliPlaybackRepository
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.CloudMusicSearchApi
import moe.ouom.neriplayer.core.api.search.QQMusicSearchApi
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicClient
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicPlaybackRepository
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.ListenTogetherPreferences
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthAutoRefreshManager
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRepository
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.listentogether.ListenTogetherApi
import moe.ouom.neriplayer.listentogether.ListenTogetherSessionManager
import moe.ouom.neriplayer.listentogether.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeInnertubeRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.isTrustedYouTubeHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeGoogleVideoHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeInnertubeHost
import moe.ouom.neriplayer.util.DynamicProxySelector
import okhttp3.OkHttpClient
import okhttp3.Request

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
    bundle: moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle,
    clearBootstrapCache: () -> Unit,
    clearPlaybackAuthBoundCaches: (Boolean) -> Unit,
    evictConnections: () -> Unit,
    warmBootstrapAsync: () -> Unit
) {
    clearBootstrapCache()
    // 只移除旧请求引用，避免 auth 恢复成功时把当前播放请求自己取消掉
    clearPlaybackAuthBoundCaches(false)
    evictConnections()
    if (bundle.hasLoginCookies()) {
        warmBootstrapAsync()
    }
}

/**
 * 全局依赖容器，使用 Service Locator 模式管理 App 的单例
 */
object AppContainer {
    private lateinit var application: Application
    val applicationContext: Application
        get() = application

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 基础 Repo
    val settingsRepo by lazy { SettingsRepository(application) }
    val listenTogetherPreferences by lazy { ListenTogetherPreferences(application) }
    val neteaseCookieRepo by lazy { NeteaseCookieRepository(application) }
    val biliCookieRepo by lazy { BiliCookieRepository(application) }
    val youtubeAuthRepo by lazy { YouTubeAuthRepository(application) }
    private val youtubeAuthAutoRefreshManager by lazy {
        YouTubeAuthAutoRefreshManager(
            context = application,
            authProvider = youtubeAuthRepo::getAuthOnce,
            authHealthProvider = youtubeAuthRepo::getAuthHealthOnce,
            authUpdater = youtubeAuthRepo::saveAuth
        )
    }


    @SuppressLint("StaticFieldLeak")
    lateinit var playHistoryRepo: PlayHistoryRepository
        private set
    @SuppressLint("StaticFieldLeak")
    lateinit var playlistUsageRepo: PlaylistUsageRepository
        private set


    // 共享 OkHttpClient：受 DynamicProxySelector 管理
    val sharedOkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(DynamicProxySelector)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host.lowercase()
                if (!isYouTubeHost(host)) {
                    return@addInterceptor chain.proceed(request)
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
                chain.proceed(builder.build())
            }
            .build()
    }

    // 网络客户端
    val neteaseClient by lazy {
        NeteaseClient().also { client ->
            val cookies = neteaseCookieRepo.cookieFlow.value.toMutableMap()
            cookies.putIfAbsent("os", "pc")
            client.setPersistedCookies(cookies)
        }
    }

    val biliClient by lazy { BiliClient(biliCookieRepo, client = sharedOkHttpClient) }
    val youtubeMusicClient by lazy {
        YouTubeMusicClient(
            authRepo = youtubeAuthRepo,
            okHttpClient = sharedOkHttpClient,
            authAutoRefreshManager = youtubeAuthAutoRefreshManager
        )
    }

    // 功能 Repo 和 API
    val biliPlaybackRepository by lazy {
        val dataSource = BiliClientAudioDataSource(biliClient)
        BiliPlaybackRepository(dataSource, settingsRepo)
    }
    val youtubeMusicPlaybackRepository by lazy {
        YouTubeMusicPlaybackRepository(
            okHttpClient = sharedOkHttpClient,
            settings = settingsRepo,
            authProvider = youtubeAuthRepo::getAuthOnce,
            authAutoRefreshManager = youtubeAuthAutoRefreshManager,
            applicationContext = application
        )
    }

    val cloudMusicSearchApi by lazy { CloudMusicSearchApi(neteaseClient) }
    val qqMusicSearchApi by lazy { QQMusicSearchApi() }
    val lrcLibClient by lazy { moe.ouom.neriplayer.core.api.lyrics.LrcLibClient(sharedOkHttpClient) }
    val listenTogetherApi by lazy { ListenTogetherApi(sharedOkHttpClient) }
    val listenTogetherWebSocketClient by lazy { ListenTogetherWebSocketClient(sharedOkHttpClient) }
    val listenTogetherSessionManager by lazy {
        ListenTogetherSessionManager(
            api = listenTogetherApi,
            webSocketClient = listenTogetherWebSocketClient
        )
    }

    fun launchBackgroundIo(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)


    fun initialize(app: Application) {
        this.application = app
        primeProxySetting()
        startCookieObserver()
        startYouTubeAuthObserver()
        startSettingsObserver()
        // 把 YouTube Music 的 bootstrap / Web PO 冷启动成本前移，减少首次播放等待
        youtubeMusicPlaybackRepository.warmBootstrapAsync()
        playHistoryRepo = PlayHistoryRepository.getInstance(app)
        playlistUsageRepo = PlaylistUsageRepository(app)
    }

    private fun primeProxySetting() {
        DynamicProxySelector.bypassProxy = resolveInitialBypassProxy(
            currentValue = DynamicProxySelector.bypassProxy
        ) {
            runBlocking {
                settingsRepo.bypassProxyFlow.first()
            }
        }

        val initialManagedDownloadSettings = resolveInitialManagedDownloadSettings(
            loadDirectoryUri = {
                runBlocking {
                    settingsRepo.downloadDirectoryUriFlow.first()
                }
            },
            loadDirectoryLabel = {
                runBlocking {
                    settingsRepo.downloadDirectoryLabelFlow.first()
                }
            },
            loadFileNameTemplate = {
                runBlocking {
                    settingsRepo.downloadFileNameTemplateFlow.first()
                }
            }
        )
        ManagedDownloadStorage.primeSettings(
            directoryUri = initialManagedDownloadSettings.directoryUri,
            directoryLabel = initialManagedDownloadSettings.directoryLabel,
            fileNameTemplate = initialManagedDownloadSettings.fileNameTemplate
        )
    }

    private fun startCookieObserver() {
        neteaseCookieRepo.cookieFlow
            .onEach { cookies ->
                val mutableCookies = cookies.toMutableMap()
                mutableCookies.putIfAbsent("os", "pc")

                neteaseClient.setPersistedCookies(mutableCookies)
            }
            .launchIn(scope)
    }

    private fun startYouTubeAuthObserver() {
        youtubeAuthRepo.authFlow
            .drop(1)
            .onEach { bundle ->
                handleYouTubeAuthStateChanged(
                    bundle = bundle,
                    clearBootstrapCache = youtubeMusicClient::clearBootstrapCache,
                    clearPlaybackAuthBoundCaches = youtubeMusicPlaybackRepository::clearAuthBoundCaches,
                    evictConnections = sharedOkHttpClient.connectionPool::evictAll,
                    warmBootstrapAsync = youtubeMusicPlaybackRepository::warmBootstrapAsync
                )
            }
            .launchIn(scope)
    }

    private fun startSettingsObserver() {
        settingsRepo.bypassProxyFlow
            .onEach { enabled ->
                DynamicProxySelector.bypassProxy = enabled
                sharedOkHttpClient.connectionPool.evictAll()
                neteaseClient.evictConnections()
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
