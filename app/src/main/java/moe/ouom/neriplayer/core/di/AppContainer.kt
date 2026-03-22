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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.BiliClientAudioDataSource
import moe.ouom.neriplayer.core.api.bili.BiliPlaybackRepository
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.CloudMusicSearchApi
import moe.ouom.neriplayer.core.api.search.QQMusicSearchApi
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicClient
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicPlaybackRepository
import moe.ouom.neriplayer.data.BiliCookieRepository
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.data.PlayHistoryRepository
import moe.ouom.neriplayer.data.PlaylistUsageRepository
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.data.YouTubeAuthRepository
import moe.ouom.neriplayer.data.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.buildYouTubeInnertubeRequestHeaders
import moe.ouom.neriplayer.data.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.data.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.util.DynamicProxySelector
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 全局依赖容器，使用 Service Locator 模式管理 App 的单例
 */
object AppContainer {

    private lateinit var application: Application

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 基础 Repo
    val settingsRepo by lazy { SettingsRepository(application) }
    val neteaseCookieRepo by lazy { NeteaseCookieRepository(application) }
    val biliCookieRepo by lazy { BiliCookieRepository(application) }
    val youtubeAuthRepo by lazy { YouTubeAuthRepository(application) }


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
                    isYouTubeInnertubeRequest(request) -> auth.buildYouTubeInnertubeRequestHeaders(
                        original = originalHeaders,
                        authorizationOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
                        includeAuthorization = true
                    )
                    isYouTubeStreamRequest(request) -> auth.buildYouTubeStreamRequestHeaders(
                        original = originalHeaders,
                        refererOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
                    )
                    else -> auth.buildYouTubePageRequestHeaders(
                        original = originalHeaders
                    )
                }
                val builder = request.newBuilder()
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
    val youtubeMusicClient by lazy { YouTubeMusicClient(youtubeAuthRepo, sharedOkHttpClient) }

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
            applicationContext = application
        )
    }

    val cloudMusicSearchApi by lazy { CloudMusicSearchApi(neteaseClient) }
    val qqMusicSearchApi by lazy { QQMusicSearchApi() }
    val lrcLibClient by lazy { moe.ouom.neriplayer.core.api.lyrics.LrcLibClient(sharedOkHttpClient) }


    fun initialize(app: Application) {
        this.application = app
        primeProxySetting()
        startCookieObserver()
        startSettingsObserver()
        playHistoryRepo = PlayHistoryRepository.getInstance(app)
        playlistUsageRepo = PlaylistUsageRepository(app)
    }

    private fun primeProxySetting() {
        DynamicProxySelector.bypassProxy = runCatching {
            runBlocking(Dispatchers.IO) {
                settingsRepo.bypassProxyFlow.first()
            }
        }.getOrDefault(DynamicProxySelector.bypassProxy)
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

    private fun startSettingsObserver() {
        settingsRepo.bypassProxyFlow
            .onEach { enabled ->
                DynamicProxySelector.bypassProxy = enabled
                sharedOkHttpClient.connectionPool.evictAll()
                neteaseClient.evictConnections()
            }
            .launchIn(scope)
    }

    private fun isYouTubeHost(host: String): Boolean {
        return host.contains("youtube") ||
            host == "youtu.be" ||
            host.contains("googlevideo.com")
    }

    private fun isYouTubeInnertubeRequest(request: Request): Boolean {
        val host = request.url.host.lowercase()
        val path = request.url.encodedPath.lowercase()
        return host == "youtubei.googleapis.com" || path.startsWith("/youtubei/")
    }

    private fun isYouTubeStreamRequest(request: Request): Boolean {
        val host = request.url.host.lowercase()
        if (!host.contains("googlevideo.com")) {
            return false
        }
        val path = request.url.encodedPath.lowercase()
        val rawUrl = request.url.toString().lowercase()
        return rawUrl.contains("source=youtube") ||
            rawUrl.contains("/api/manifest/") ||
            path.contains("/playlist/index.m3u8") ||
            path.contains("/file/seg.ts") ||
            rawUrl.contains("/videoplayback")
    }
}
