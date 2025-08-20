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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.BiliCookieRepository
import android.net.Uri

/**
 * 自定义的 HttpDataSource.Factory：
 * - 按 host/路径动态注入请求头（B 站拉流需要 Referer/UA/Cookie）
 * - 监听 Cookie 仓库的变化，实时刷新注入的 Cookie 字符串
 */
@UnstableApi
class ConditionalHttpDataSourceFactory(
    private val defaultFactory: DefaultHttpDataSource.Factory,
    cookieRepo: BiliCookieRepository // 接收 CookieRepository 作为依赖
) : HttpDataSource.Factory {

    companion object {
        private const val BILI_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

    @Volatile
    private var latestCookieHeader: String = ""
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        scope.launch {
            cookieRepo.cookieFlow.collect { cookies ->
                latestCookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }
    }

    override fun createDataSource(): HttpDataSource {
        val defaultDataSource = defaultFactory.createDataSource()
        return object : HttpDataSource by defaultDataSource {

            override fun open(dataSpec: DataSpec): Long {
                val finalSpec = if (shouldInjectBiliHeaders(dataSpec.uri)) {
                    val headers = buildBiliHeaders(dataSpec.httpRequestHeaders)
                    dataSpec.buildUpon()
                        .setHttpRequestHeaders(headers)
                        .build()
                } else dataSpec

                return defaultDataSource.open(finalSpec)
            }
        }
    }

    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        defaultFactory.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }

    /**
     * 是否需要为该 URI 注入 B 站拉流所需的请求头
     */
    private fun shouldInjectBiliHeaders(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host.contains("bilivideo.") || uri.toString().contains("https://upos-hz-")
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
}