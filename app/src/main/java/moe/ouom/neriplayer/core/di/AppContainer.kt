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

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.BiliClientAudioDataSource
import moe.ouom.neriplayer.core.api.bili.BiliPlaybackRepository
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.CloudMusicSearchApi
import moe.ouom.neriplayer.core.api.search.QQMusicSearchApi
import moe.ouom.neriplayer.data.BiliCookieRepository
import moe.ouom.neriplayer.data.NeteaseCookieRepository
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.util.DynamicProxySelector
import okhttp3.OkHttpClient

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

    // 共享 OkHttpClient：受 DynamicProxySelector 管理
    val sharedOkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(DynamicProxySelector)
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

    // 功能 Repo 和 API
    val biliPlaybackRepository by lazy {
        val dataSource = BiliClientAudioDataSource(biliClient)
        BiliPlaybackRepository(dataSource, settingsRepo)
    }

    val cloudMusicSearchApi by lazy { CloudMusicSearchApi(neteaseClient) }
    val qqMusicSearchApi by lazy { QQMusicSearchApi() }


    fun initialize(app: Application) {
        this.application = app
        startCookieObserver()
        startSettingsObserver()
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
            }
            .launchIn(scope)
    }
}