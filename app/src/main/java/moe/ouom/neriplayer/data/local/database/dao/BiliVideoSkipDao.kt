package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipDraftEntity
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipIntervalEntity
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipRuleEntity

@Dao
internal interface BiliVideoSkipDao {
    @Query(
        "SELECT * FROM bili_video_skip_rule " +
            "ORDER BY bvid ASC, cid ASC"
    )
    suspend fun getRules(): List<BiliVideoSkipRuleEntity>

    @Query(
        "SELECT * FROM bili_video_skip_interval " +
            "ORDER BY bvid ASC, cid ASC, position ASC"
    )
    suspend fun getIntervals(): List<BiliVideoSkipIntervalEntity>

    @Query(
        "SELECT * FROM bili_video_skip_draft " +
            "ORDER BY bvid ASC, cid ASC"
    )
    suspend fun getDrafts(): List<BiliVideoSkipDraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<BiliVideoSkipRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntervals(intervals: List<BiliVideoSkipIntervalEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDrafts(drafts: List<BiliVideoSkipDraftEntity>)

    @Query("DELETE FROM bili_video_skip_interval")
    suspend fun deleteIntervals()

    @Query("DELETE FROM bili_video_skip_rule")
    suspend fun deleteRules()

    @Query("DELETE FROM bili_video_skip_draft")
    suspend fun deleteDrafts()
}
