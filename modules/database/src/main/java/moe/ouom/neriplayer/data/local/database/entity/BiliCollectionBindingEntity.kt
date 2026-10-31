package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单曲 / 专辑 / 歌单 → B 站多 P 视频的绑定。分 P 明细以 JSON 存在 [tracksJson]，
 * 读取方为 BiliCollectionBindingRepository，避免为一层从属数据再开一张表。
 */
@Entity(
    tableName = "bili_collection_binding",
    indices = [
        Index(
            value = ["target_id"],
            name = "index_bili_collection_binding_target_id"
        )
    ]
)
data class BiliCollectionBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_id")
    val targetId: Long,
    @ColumnInfo(name = "target_name")
    val targetName: String,
    @ColumnInfo(name = "target_subtitle")
    val targetSubtitle: String,
    val bvid: String,
    val avid: Long,
    @ColumnInfo(name = "video_title")
    val videoTitle: String,
    @ColumnInfo(name = "tracks_json")
    val tracksJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
