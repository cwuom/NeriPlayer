package moe.ouom.neriplayer.core.api.search

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
 * File: moe.ouom.neriplayer.core.api.search/SearchApi
 * Created: 2025/8/17
 */

/**
 * 音乐搜索服务的统一接口
 */
interface SearchApi {

    /**
     * 根据关键字搜索歌曲
     * @param keyword 搜索词
     * @param page 页码
     * @return 包含歌曲简略信息的列表
     */
    suspend fun search(keyword: String, page: Int): List<SongSearchInfo>

    /**
     * 根据歌曲ID获取详细信息，包括封面和歌词
     * @param id 歌曲的唯一ID
     * @return 歌曲的详细信息
     */
    suspend fun getSongInfo(id: String): SongDetails
}