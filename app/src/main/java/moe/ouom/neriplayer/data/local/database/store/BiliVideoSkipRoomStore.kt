package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipDraftEntity
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipIntervalEntity
import moe.ouom.neriplayer.data.local.database.entity.BiliVideoSkipRuleEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipDraft
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipRule
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget

internal data class BiliVideoSkipRoomSnapshot(
    val rules: List<BiliVideoSkipRule>,
    val drafts: List<BiliVideoSkipDraft>
)

internal enum class BiliVideoSkipRoomImportStatus {
    IMPORTED,
    SKIPPED_ALREADY_PRIMARY
}

internal data class BiliVideoSkipRoomImportResult(
    val status: BiliVideoSkipRoomImportStatus,
    val ruleCount: Int,
    val draftCount: Int
)

internal class BiliVideoSkipRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun isRoomPrimary(): Boolean {
        return database.syncMetadataDao()
            .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
            ?.value == ROOM_PRIMARY_STATE
    }

    suspend fun readIfRoomPrimary(): BiliVideoSkipRoomSnapshot? {
        if (!isRoomPrimary()) {
            return null
        }
        return readSnapshot()
    }

    suspend fun replaceAll(
        rules: List<BiliVideoSkipRule>,
        drafts: List<BiliVideoSkipDraft>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val dao = database.biliVideoSkipDao()
            dao.deleteIntervals()
            dao.deleteRules()
            dao.deleteDrafts()
            dao.insertRules(rules.map(BiliVideoSkipRule::toEntity))
            dao.insertIntervals(
                rules.flatMap { rule ->
                    rule.intervals.mapIndexed { position, interval ->
                        interval.toEntity(rule.target, position)
                    }
                }
            )
            dao.insertDrafts(drafts.map(BiliVideoSkipDraft::toEntity))
            markRoomPrimary(now)
        }
    }

    suspend fun importLegacyAndPromote(
        rules: List<BiliVideoSkipRule>,
        drafts: List<BiliVideoSkipDraft>,
        now: Long = System.currentTimeMillis()
    ): BiliVideoSkipRoomImportResult {
        return database.withTransaction {
            if (isRoomPrimary()) {
                return@withTransaction BiliVideoSkipRoomImportResult(
                    status = BiliVideoSkipRoomImportStatus.SKIPPED_ALREADY_PRIMARY,
                    ruleCount = rules.size,
                    draftCount = drafts.size
                )
            }
            val dao = database.biliVideoSkipDao()
            dao.deleteIntervals()
            dao.deleteRules()
            dao.deleteDrafts()
            dao.insertRules(rules.map(BiliVideoSkipRule::toEntity))
            dao.insertIntervals(
                rules.flatMap { rule ->
                    rule.intervals.mapIndexed { position, interval ->
                        interval.toEntity(rule.target, position)
                    }
                }
            )
            dao.insertDrafts(drafts.map(BiliVideoSkipDraft::toEntity))
            markRoomPrimary(now)
            BiliVideoSkipRoomImportResult(
                status = BiliVideoSkipRoomImportStatus.IMPORTED,
                ruleCount = rules.size,
                draftCount = drafts.size
            )
        }
    }

    suspend fun replaceRules(
        rules: List<BiliVideoSkipRule>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val dao = database.biliVideoSkipDao()
            dao.deleteIntervals()
            dao.deleteRules()
            dao.insertRules(rules.map(BiliVideoSkipRule::toEntity))
            dao.insertIntervals(
                rules.flatMap { rule ->
                    rule.intervals.mapIndexed { position, interval ->
                        interval.toEntity(rule.target, position)
                    }
                }
            )
            markRoomPrimary(now)
        }
    }

    suspend fun replaceDrafts(
        drafts: List<BiliVideoSkipDraft>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val dao = database.biliVideoSkipDao()
            dao.deleteDrafts()
            dao.insertDrafts(drafts.map(BiliVideoSkipDraft::toEntity))
            markRoomPrimary(now)
        }
    }

    private suspend fun readSnapshot(): BiliVideoSkipRoomSnapshot {
        return database.withTransaction {
            val dao = database.biliVideoSkipDao()
            val intervalsByTarget = dao.getIntervals()
                .groupBy { it.bvid to it.cid }
                .mapValues { (_, intervals) ->
                    intervals.sortedBy(BiliVideoSkipIntervalEntity::position)
                        .map { interval ->
                            BiliVideoSkipInterval(
                                startMs = interval.startMs,
                                endMs = interval.endMs
                            )
                        }
                }
            BiliVideoSkipRoomSnapshot(
                rules = dao.getRules().map { rule ->
                    BiliVideoSkipRule(
                        target = BiliVideoSkipTarget(rule.bvid, rule.cid),
                        intervals = intervalsByTarget[rule.bvid to rule.cid].orEmpty(),
                        modifiedAt = rule.modifiedAt,
                        isDeleted = rule.isDeleted
                    )
                },
                drafts = dao.getDrafts().map { draft ->
                    BiliVideoSkipDraft(
                        target = BiliVideoSkipTarget(draft.bvid, draft.cid),
                        startText = draft.startText,
                        endText = draft.endText,
                        modifiedAt = draft.modifiedAt
                    )
                }
            )
        }
    }

    private suspend fun markRoomPrimary(now: Long) {
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = CUTOVER_STATE_METADATA_KEY,
                value = ROOM_PRIMARY_STATE,
                updatedAt = now
            )
        )
    }

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "bili_video_skip_cutover_state"
        const val ROOM_PRIMARY_STATE = "room_primary"
    }
}

private fun BiliVideoSkipRule.toEntity(): BiliVideoSkipRuleEntity {
    return BiliVideoSkipRuleEntity(
        bvid = target.bvid,
        cid = target.cid,
        modifiedAt = modifiedAt,
        isDeleted = isDeleted
    )
}

private fun BiliVideoSkipInterval.toEntity(
    target: BiliVideoSkipTarget,
    position: Int
): BiliVideoSkipIntervalEntity {
    return BiliVideoSkipIntervalEntity(
        bvid = target.bvid,
        cid = target.cid,
        position = position,
        startMs = startMs,
        endMs = endMs
    )
}

private fun BiliVideoSkipDraft.toEntity(): BiliVideoSkipDraftEntity {
    return BiliVideoSkipDraftEntity(
        targetKey = target.stableKey(),
        bvid = target.bvid,
        cid = target.cid,
        startText = startText,
        endText = endText,
        modifiedAt = modifiedAt
    )
}
