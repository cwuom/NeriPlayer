@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.model.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget

@Serializable
data class SyncBiliVideoSkipInterval(
    @ProtoNumber(1) val startMs: Long = 0L,
    @ProtoNumber(2) val endMs: Long = 0L
)

@Serializable
data class SyncBiliVideoSkipRule(
    @ProtoNumber(1) val bvid: String = "",
    @ProtoNumber(2) val cid: Long = 0L,
    @ProtoNumber(3) val intervals: List<SyncBiliVideoSkipInterval> = emptyList(),
    @ProtoNumber(4) val modifiedAt: Long = 0L,
    @ProtoNumber(5) val isDeleted: Boolean = false
) {
    fun targetOrNull(): BiliVideoSkipTarget? {
        return BiliVideoSkipTarget(bvid = bvid, cid = cid).normalizedOrNull()
    }
}
