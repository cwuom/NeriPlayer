package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity

internal enum class LegacyJsonCleanupStatus {
    NOT_CONFIRMED,
    BLOCKED,
    COMPLETED,
    PARTIAL_FAILURE
}

internal data class LegacyJsonCleanupTarget(
    val fileName: String,
    val cutoverStateKey: String,
    val exists: Boolean,
    val eligible: Boolean,
    val reason: String?
)

internal data class LegacyJsonCleanupPlan(
    val targets: List<LegacyJsonCleanupTarget>
) {
    val blockedTargets: List<LegacyJsonCleanupTarget>
        get() = targets.filter { it.exists && !it.eligible }

    val existingEligibleTargets: List<LegacyJsonCleanupTarget>
        get() = targets.filter { it.exists && it.eligible }
}

internal data class LegacyJsonCleanupResult(
    val status: LegacyJsonCleanupStatus,
    val deletedFiles: List<String>,
    val failedFiles: List<String>,
    val blockedFiles: List<String>
)

internal class LegacyJsonCleanupCoordinator(
    private val context: Context,
    private val database: NeriUserDataDatabase =
        NeriUserDataDatabase.getInstance(context.applicationContext)
) {
    suspend fun buildPlan(): LegacyJsonCleanupPlan {
        val targets = TARGETS.map { target ->
            val state = database.syncMetadataDao()
                .getMigrationMetadata(target.cutoverStateKey)
                ?.value
            val file = File(context.filesDir, target.fileName)
            val exists = file.exists()
            LegacyJsonCleanupTarget(
                fileName = target.fileName,
                cutoverStateKey = target.cutoverStateKey,
                exists = exists,
                eligible = state == ROOM_PRIMARY_STATE,
                reason = when {
                    !exists -> null
                    state == ROOM_PRIMARY_STATE -> null
                    state == null -> "Room primary marker is missing"
                    else -> "Room primary marker is $state"
                }
            )
        }
        return LegacyJsonCleanupPlan(targets)
    }

    suspend fun execute(
        plan: LegacyJsonCleanupPlan,
        confirmed: Boolean
    ): LegacyJsonCleanupResult {
        if (!confirmed) {
            return LegacyJsonCleanupResult(
                status = LegacyJsonCleanupStatus.NOT_CONFIRMED,
                deletedFiles = emptyList(),
                failedFiles = emptyList(),
                blockedFiles = plan.blockedTargets.map(LegacyJsonCleanupTarget::fileName)
            )
        }

        val freshPlan = buildPlan()
        if (freshPlan.blockedTargets.isNotEmpty()) {
            return LegacyJsonCleanupResult(
                status = LegacyJsonCleanupStatus.BLOCKED,
                deletedFiles = emptyList(),
                failedFiles = emptyList(),
                blockedFiles = freshPlan.blockedTargets
                    .map(LegacyJsonCleanupTarget::fileName)
            )
        }

        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        freshPlan.existingEligibleTargets.forEach { target ->
            val file = File(context.filesDir, target.fileName)
            if (file.delete()) {
                deleted += target.fileName
            } else if (file.exists()) {
                failed += target.fileName
            }
        }
        val status = if (failed.isEmpty()) {
            LegacyJsonCleanupStatus.COMPLETED
        } else {
            LegacyJsonCleanupStatus.PARTIAL_FAILURE
        }
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = CLEANUP_AUDIT_METADATA_KEY,
                value = buildAuditValue(status, deleted, failed),
                updatedAt = System.currentTimeMillis()
            )
        )
        return LegacyJsonCleanupResult(
            status = status,
            deletedFiles = deleted,
            failedFiles = failed,
            blockedFiles = emptyList()
        )
    }

    private fun buildAuditValue(
        status: LegacyJsonCleanupStatus,
        deleted: List<String>,
        failed: List<String>
    ): String {
        return buildString {
            append("status=").append(status.name)
            append(";deleted=").append(deleted.joinToString(","))
            append(";failed=").append(failed.joinToString(","))
        }
    }

    private data class TargetDefinition(
        val fileName: String,
        val cutoverStateKey: String
    )

    companion object {
        const val CLEANUP_AUDIT_METADATA_KEY = "legacy_json_cleanup_last_result"
        const val ROOM_PRIMARY_STATE = "room_primary"

        private val TARGETS = listOf(
            TargetDefinition(
                "local_playlists.json",
                LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "local_playlists.json.bak",
                LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "local_playlists.json.sync-pending.json",
                LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "play_history.json",
                PlayHistoryRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "playlist_usage.json",
                PlaylistUsageRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "local_playlist_playback_stats.json",
                LocalPlaylistPlaybackRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "playback_stats.json",
                PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "playback_stats_daily.json",
                PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "playback_stats_meta.json",
                PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "playback_stats_counters.json",
                PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "favorite_playlists.json",
                FavoritePlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "traffic_stats_daily.json",
                TrafficStatsRoomStore.CUTOVER_STATE_METADATA_KEY
            ),
            TargetDefinition(
                "last_playlist.json",
                "playback_queue_cutover_state"
            ),
            TargetDefinition(
                "last_playback_state.json",
                "playback_queue_cutover_state"
            ),
            TargetDefinition(
                "bili_video_skip_rules.json",
                "bili_video_skip_cutover_state"
            ),
            TargetDefinition(
                "bili_video_skip_drafts.json",
                "bili_video_skip_cutover_state"
            )
        )
    }
}
