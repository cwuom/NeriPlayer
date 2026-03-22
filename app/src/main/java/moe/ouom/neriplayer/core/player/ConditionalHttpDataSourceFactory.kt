package moe.ouom.neriplayer.core.player

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
 * File: moe.ouom.neriplayer.core.player/ConditionalHttpDataSourceFactory
 * Created: 2025/8/15
 */


import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.BiliCookieRepository
import moe.ouom.neriplayer.data.YouTubeAuthBundle
import moe.ouom.neriplayer.data.YouTubeAuthRepository
import moe.ouom.neriplayer.data.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.util.NPLogger

/**
 * 自定义的 HttpDataSource.Factory：
 * - 按 host/路径动态注入请求头（B 站 / YouTube 拉流）
 * - 监听鉴权仓库变化，实时刷新注入的 Cookie 字符串
 */
@UnstableApi
class ConditionalHttpDataSourceFactory(
    private val baseFactory: HttpDataSource.Factory,
    cookieRepo: BiliCookieRepository,
    youtubeAuthRepo: YouTubeAuthRepository
) : HttpDataSource.Factory {

    companion object {
        private const val BILI_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        private const val YOUTUBE_WEB_REFERER = "https://www.youtube.com/"
    }

    @Volatile
    private var latestCookieHeader: String = ""
    @Volatile
    private var latestYouTubeAuth: YouTubeAuthBundle = YouTubeAuthBundle()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        scope.launch {
            cookieRepo.cookieFlow.collect { cookies ->
                latestCookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }
        scope.launch {
            youtubeAuthRepo.authFlow.collect { auth ->
                latestYouTubeAuth = auth.normalized()
            }
        }
    }

    override fun createDataSource(): HttpDataSource {
        return ConditionalChunkedHttpDataSource(
            upstreamFactory = baseFactory,
            transformDataSpec = { dataSpec ->
                NPLogger.i("createDataSource", dataSpec.uri)
                when {
                    shouldInjectBiliHeaders(dataSpec.uri) -> {
                        val headers = buildBiliHeaders(dataSpec.httpRequestHeaders)
                        dataSpec.buildUpon()
                            .setHttpRequestHeaders(headers)
                            .build()
                    }
                    shouldInjectYouTubeHeaders(dataSpec.uri) -> {
                        val headers = buildYouTubeHeaders(dataSpec.httpRequestHeaders)
                        dataSpec.buildUpon()
                            .setHttpRequestHeaders(headers)
                            .build()
                    }
                    else -> dataSpec
                }
            }
        )
    }

    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        baseFactory.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }

    fun close() {
        scope.cancel()
    }

    /**
     * 是否需要为该 URI 注入 B 站拉流所需的请求头
     */
    private fun shouldInjectBiliHeaders(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host.contains("bilivideo.") || uri.toString().contains("https://upos-hz-")
    }

    private fun shouldInjectYouTubeHeaders(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (!host.contains("googlevideo.com")) return false
        val path = uri.path?.lowercase().orEmpty()
        val rawUrl = uri.toString().lowercase()
        return rawUrl.contains("source=youtube") ||
            rawUrl.contains("/api/manifest/") ||
            path.contains("/playlist/index.m3u8") ||
            path.contains("/file/seg.ts") ||
            rawUrl.contains("/videoplayback")
    }

    /**
     * 基于原始请求头构建 B 站拉流所需的头部（Referer/UA/Cookie）
     */
    private fun buildBiliHeaders(original: Map<String, String>): Map<String, String> {
        val newHeaders = LinkedHashMap<String, String>(original)
        newHeaders["Referer"] = "https://www.bilibili.com"
        newHeaders["User-Agent"] = BILI_USER_AGENT
        if (latestCookieHeader.isNotBlank()) newHeaders["Cookie"] = latestCookieHeader
        return newHeaders
    }

    private fun buildYouTubeHeaders(original: Map<String, String>): Map<String, String> {
        val refererOrigin = original["Referer"].orEmpty()
            .removeSuffix("/")
            .ifBlank { latestYouTubeAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN } }
        return latestYouTubeAuth.buildYouTubeStreamRequestHeaders(
            original = original,
            refererOrigin = refererOrigin
        )
    }
}
