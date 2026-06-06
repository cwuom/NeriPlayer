package moe.ouom.neriplayer.ui.viewmodel.tab

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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/CollectionSummaryModels
 * Created: 2026/4/6
 */

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 通用歌单摘要模型
 * 当前主要承载网易云歌单卡片数据，但字段本身不绑定具体平台
 */
@Parcelize
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val picUrl: String,
    val playCount: Long,
    val trackCount: Int
) : Parcelable

/**
 * 通用专辑摘要模型
 * 当前主要承载网易云专辑卡片数据，但定义放在共享模型中，避免与具体页面耦合
 */
@Parcelize
data class AlbumSummary(
    val id: Long,
    val name: String,
    val picUrl: String,
    val size: Int
) : Parcelable

/** Bilibili 收藏夹摘要模型 */
@Parcelize
data class BiliPlaylist(
    val mediaId: Long,
    val fid: Long,
    val mid: Long,
    val title: String,
    val count: Int,
    val coverUrl: String
) : Parcelable
