package moe.ouom.neriplayer.core.api.bili

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
 * File: moe.ouom.neriplayer.core.api.bili/BiliPlaybackRepository
 * Created: 2025/8/14
 */

import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.SettingsRepository
import moe.ouom.neriplayer.data.selectStreamByPreference

interface BiliAudioDataSource {
    val client: BiliClient
        get() = AppContainer.biliClient

    /**
     * 获取当前视频的所有可用音频流
     */
    suspend fun fetchAudioStreams(
        bvid: String,
        cid: Long
    ): List<BiliAudioStreamInfo>
}

/**
 * 播放仓库：按设置的默认音质选择音轨，失败则逐级降级直到有可用
 */
class BiliPlaybackRepository(
    private val source: BiliAudioDataSource,
    private val settings: SettingsRepository
) {
    suspend fun getBestPlayableAudio(
        bvid: String,
        cid: Long,
        preferredKeyOverride: String? = null
    ): BiliAudioStreamInfo? {
        val streams = source.fetchAudioStreams(bvid, cid)
        val prefKey = preferredKeyOverride ?: settings.biliAudioQualityFlow.first()
        return selectStreamByPreference(streams, prefKey)
    }

    suspend fun getAudioWithDecision(
        bvid: String,
        cid: Long,
        preferredKeyOverride: String? = null
    ): Pair<List<BiliAudioStreamInfo>, BiliAudioStreamInfo?> {
        val streams = source.fetchAudioStreams(bvid, cid)
        val prefKey = preferredKeyOverride ?: settings.biliAudioQualityFlow.first()
        val chosen = selectStreamByPreference(streams, prefKey)
        return streams to chosen
    }
}
