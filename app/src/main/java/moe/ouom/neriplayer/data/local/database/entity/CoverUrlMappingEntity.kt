package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cover_url_mapping",
    indices = [
        Index(
            value = ["updated_at"],
            orders = [Index.Order.DESC],
            name = "index_cover_url_mapping_updated_at"
        )
    ]
)
internal data class CoverUrlMappingEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_url")
    val localUrl: String,
    @ColumnInfo(name = "network_url")
    val networkUrl: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
