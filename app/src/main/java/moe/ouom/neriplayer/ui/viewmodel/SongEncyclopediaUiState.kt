package moe.ouom.neriplayer.ui.viewmodel

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
 * File: moe.ouom.neriplayer.ui.viewmodel/SongEncyclopediaUiState
 * Created: 2025/10/16
 */

import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia

/**
 * 歌曲百科页面的界面状态。
 *
 * [Success] 携带 [SongEncyclopedia.songKey]，界面必须用 [forSongKey] 校验后再渲染，
 * 避免快速切歌时上一首的结果短暂显示在新歌页面上。
 */
sealed interface SongEncyclopediaUiState {

    /** 尚未发起请求。 */
    data object Idle : SongEncyclopediaUiState

    /** 正在请求。 */
    data object Loading : SongEncyclopediaUiState

    /** 请求成功且有可展示内容。 */
    data class Success(val data: SongEncyclopedia) : SongEncyclopediaUiState

    /** 当前歌曲没有百科数据源（本地歌曲、B 站、YouTube 等）。 */
    data object NotSupported : SongEncyclopediaUiState

    /** 数据源可达，但该歌曲暂无百科内容。 */
    data object Empty : SongEncyclopediaUiState

    /** 请求失败，可重试。 */
    data class Failed(val message: String? = null) : SongEncyclopediaUiState

    /**
     * 返回只属于 [songKey] 的状态：
     * 若当前成功结果属于其它歌曲，降级为 [Loading]，绝不把旧歌数据渲染到新歌上。
     */
    fun forSongKey(songKey: String): SongEncyclopediaUiState = when (this) {
        is Success -> if (data.songKey == songKey) this else Loading
        else -> this
    }
}
