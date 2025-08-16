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

/**
 * 一个自定义的 HttpDataSource.Factory，
 * 它可以根据请求的 URL host 来动态添加不同的请求头，包括 Cookie。
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
                val isBiliRequest = dataSpec.uri.host?.contains("bilivideo.") == true

                val finalDataSpec = if (isBiliRequest) {
                    val originalHeaders = dataSpec.httpRequestHeaders
                    val newHeaders = mutableMapOf<String, String>()
                    newHeaders.putAll(originalHeaders)

                    newHeaders["Referer"] = "https://www.bilibili.com"
                    newHeaders["User-Agent"] = BILI_USER_AGENT
                    if (latestCookieHeader.isNotBlank()) {
                        newHeaders["Cookie"] = latestCookieHeader
                    }

                    dataSpec.buildUpon()
                        .setHttpRequestHeaders(newHeaders)
                        .build()
                } else {
                    dataSpec
                }

                return defaultDataSource.open(finalDataSpec)
            }
        }
    }

    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        defaultFactory.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }
}