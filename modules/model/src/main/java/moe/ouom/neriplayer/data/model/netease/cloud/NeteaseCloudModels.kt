package moe.ouom.neriplayer.data.model.netease.cloud

import moe.ouom.neriplayer.data.model.SongItem

data class NeteaseCloudPage(
    val songs: List<SongItem>,
    val consumed: Int,
    val totalCount: Int,
    val usedBytes: Long,
    val maxBytes: Long,
    val hasMore: Boolean
)
