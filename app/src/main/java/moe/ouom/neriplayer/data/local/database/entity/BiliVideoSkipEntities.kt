package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bili_video_skip_rule",
    primaryKeys = ["bvid", "cid"],
    indices = [
        Index(
            value = ["modified_at"],
            orders = [Index.Order.DESC],
            name = "index_bili_video_skip_rule_modified_at"
        )
    ]
)
internal data class BiliVideoSkipRuleEntity(
    val bvid: String,
    val cid: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean
)

@Entity(
    tableName = "bili_video_skip_interval",
    primaryKeys = ["bvid", "cid", "position"],
    foreignKeys = [
        ForeignKey(
            entity = BiliVideoSkipRuleEntity::class,
            parentColumns = ["bvid", "cid"],
            childColumns = ["bvid", "cid"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
internal data class BiliVideoSkipIntervalEntity(
    val bvid: String,
    val cid: Long,
    val position: Int,
    @ColumnInfo(name = "start_ms")
    val startMs: Long,
    @ColumnInfo(name = "end_ms")
    val endMs: Long
)

@Entity(tableName = "bili_video_skip_draft")
internal data class BiliVideoSkipDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    val bvid: String,
    val cid: Long,
    @ColumnInfo(name = "start_text")
    val startText: String,
    @ColumnInfo(name = "end_text")
    val endText: String,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long
)
