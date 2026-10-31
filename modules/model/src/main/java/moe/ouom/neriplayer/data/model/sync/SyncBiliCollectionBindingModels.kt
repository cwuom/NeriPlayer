@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.model.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBinding
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBindingTrack

@Serializable
data class SyncBiliCollectionBindingTrack(
    @ProtoNumber(1) val songId: Long = 0L,
    @ProtoNumber(2) val songName: String = "",
    @ProtoNumber(3) val cid: Long = 0L,
    @ProtoNumber(4) val page: Int = 0,
    @ProtoNumber(5) val part: String = "",
    @ProtoNumber(6) val durationSec: Int = 0
)

/**
 * 单曲 / 专辑 / 歌单 → B 站分 P 绑定在同步报文中的表示。
 * [updatedAt] 用于跨设备后写覆盖，[isDeleted] 表示本机解绑产生的墓碑。
 */
@Serializable
data class SyncBiliCollectionBinding(
    @ProtoNumber(1) val targetKey: String = "",
    @ProtoNumber(2) val targetId: Long = 0L,
    @ProtoNumber(3) val targetName: String = "",
    @ProtoNumber(4) val targetSubtitle: String = "",
    @ProtoNumber(5) val bvid: String = "",
    @ProtoNumber(6) val avid: Long = 0L,
    @ProtoNumber(7) val videoTitle: String = "",
    @ProtoNumber(8) val tracks: List<SyncBiliCollectionBindingTrack> = emptyList(),
    @ProtoNumber(9) val updatedAt: Long = 0L,
    @ProtoNumber(10) val isDeleted: Boolean = false,
    @ProtoNumber(11) val targetType: String = ""
) {
    fun toBiliCollectionBindingOrNull(): BiliCollectionBinding? {
        val key = targetKey.trim()
        if (key.isEmpty()) return null
        if (isDeleted) {
            return BiliCollectionBinding(
                targetKey = key,
                targetType = targetType,
                targetId = targetId,
                targetName = targetName,
                targetSubtitle = targetSubtitle,
                updatedAt = updatedAt,
                isDeleted = true
            )
        }
        if (bvid.isBlank()) return null
        return BiliCollectionBinding(
            targetKey = targetKey,
            targetType = targetType,
            targetId = targetId,
            targetName = targetName,
            targetSubtitle = targetSubtitle,
            bvid = bvid,
            avid = avid,
            videoTitle = videoTitle,
            tracks = tracks.mapNotNull { track ->
                if (track.cid <= 0L) return@mapNotNull null
                BiliCollectionBindingTrack(
                    songId = track.songId,
                    songName = track.songName,
                    cid = track.cid,
                    page = track.page,
                    part = track.part,
                    durationSec = track.durationSec
                )
            },
            updatedAt = updatedAt
        ).takeIf { it.isValid() }
    }
}

fun BiliCollectionBinding.toSyncBiliCollectionBinding(): SyncBiliCollectionBinding {
    return SyncBiliCollectionBinding(
        targetKey = targetKey,
        targetId = targetId,
        targetName = targetName,
        targetSubtitle = targetSubtitle,
        bvid = bvid,
        avid = avid,
        videoTitle = videoTitle,
        tracks = tracks.map {
            SyncBiliCollectionBindingTrack(
                songId = it.songId,
                songName = it.songName,
                cid = it.cid,
                page = it.page,
                part = it.part,
                durationSec = it.durationSec
            )
        },
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        targetType = targetType
    )
}
