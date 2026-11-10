package moe.ouom.neriplayer.data.model.netease.cloud

import moe.ouom.neriplayer.data.model.SongItem

/** 云盘单页结果；[consumed] 是原始条目数，用于推进 offset。 */
data class NeteaseCloudPage(
    val songs: List<SongItem>,
    val consumed: Int,
    val totalCount: Int,
    val usedBytes: Long,
    val maxBytes: Long,
    val hasMore: Boolean
)
