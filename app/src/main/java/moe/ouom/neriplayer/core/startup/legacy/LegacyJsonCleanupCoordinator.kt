package moe.ouom.neriplayer.core.startup.legacy

import moe.ouom.neriplayer.core.download.integration.legacy.DownloadLegacyStorageAccess
import android.content.Context
import java.io.File
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.store.RepositoryCutoverKeys

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
    val reason: String?,
    val cutoverState: String? = null
)

internal data class LegacyJsonCleanupPlan(
    val targets: List<LegacyJsonCleanupTarget>
) {
    val blockedTargets: List<LegacyJsonCleanupTarget>
        get() = targets.filter { it.exists && !it.eligible }

    val existingEligibleTargets: List<LegacyJsonCleanupTarget>
        get() = targets.filter { it.exists && it.eligible }

    val isBlockedOnlyByUserClearedDownloadQueues: Boolean
        get() = blockedTargets.isNotEmpty() && blockedTargets.all { target ->
            target.cutoverState == DownloadLegacyStorageAccess.USER_CLEARED_STATE &&
                target.cutoverStateKey in USER_CLEAR_SUPPRESSED_QUEUE_STATE_KEYS
        }

    private companion object {
        val USER_CLEAR_SUPPRESSED_QUEUE_STATE_KEYS = setOf(
            DownloadLegacyStorageAccess.PENDING_QUEUE_CUTOVER_STATE_KEY,
            DownloadLegacyStorageAccess.CANCELLED_KEYS_CUTOVER_STATE_KEY
        )
    }
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
                },
                cutoverState = state
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
        val blockedFiles = freshPlan.blockedTargets.map(LegacyJsonCleanupTarget::fileName)
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
        val status = when {
            failed.isNotEmpty() -> LegacyJsonCleanupStatus.PARTIAL_FAILURE
            blockedFiles.isNotEmpty() -> LegacyJsonCleanupStatus.BLOCKED
            else -> LegacyJsonCleanupStatus.COMPLETED
        }
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = CLEANUP_AUDIT_METADATA_KEY,
                value = buildAuditValue(status, deleted, failed, blockedFiles),
                updatedAt = System.currentTimeMillis()
            )
        )
        return LegacyJsonCleanupResult(
            status = status,
            deletedFiles = deleted,
            failedFiles = failed,
            blockedFiles = blockedFiles
        )
    }

    private fun buildAuditValue(
        status: LegacyJsonCleanupStatus,
        deleted: List<String>,
        failed: List<String>,
        blocked: List<String>
    ): String {
        return buildString {
            append("status=").append(status.name)
            append(";deleted=").append(deleted.joinToString(","))
            append(";failed=").append(failed.joinToString(","))
            append(";blocked=").append(blocked.joinToString(","))
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
                RepositoryCutoverKeys.LOCAL_PLAYLIST
            ),
            TargetDefinition(
                "local_playlists.json.bak",
                RepositoryCutoverKeys.LOCAL_PLAYLIST
            ),
            TargetDefinition(
                "local_playlists.json.sync-pending.json",
                RepositoryCutoverKeys.LOCAL_PLAYLIST
            ),
            TargetDefinition(
                "play_history.json",
                RepositoryCutoverKeys.PLAY_HISTORY
            ),
            TargetDefinition(
                "playlist_usage.json",
                RepositoryCutoverKeys.PLAYLIST_USAGE
            ),
            TargetDefinition(
                "local_playlist_playback_stats.json",
                RepositoryCutoverKeys.LOCAL_PLAYLIST_PLAYBACK
            ),
            TargetDefinition(
                "playback_stats.json",
                RepositoryCutoverKeys.PLAYBACK_STATS
            ),
            TargetDefinition(
                "playback_stats_daily.json",
                RepositoryCutoverKeys.PLAYBACK_STATS
            ),
            TargetDefinition(
                "playback_stats_meta.json",
                RepositoryCutoverKeys.PLAYBACK_STATS
            ),
            TargetDefinition(
                "playback_stats_counters.json",
                RepositoryCutoverKeys.PLAYBACK_STATS
            ),
            TargetDefinition(
                "favorite_playlists.json",
                RepositoryCutoverKeys.FAVORITE_PLAYLIST
            ),
            TargetDefinition(
                "traffic_stats_daily.json",
                RepositoryCutoverKeys.TRAFFIC_STATS
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
            ),
            TargetDefinition(
                "cover_url_mapping.json",
                RepositoryCutoverKeys.COVER_URL_MAPPING
            ),
            TargetDefinition(
                "pending_download_queue_v1.json",
                DownloadLegacyStorageAccess.PENDING_QUEUE_CUTOVER_STATE_KEY
            ),
            TargetDefinition(
                "cancelled_download_keys_v1.json",
                DownloadLegacyStorageAccess.CANCELLED_KEYS_CUTOVER_STATE_KEY
            ),
            TargetDefinition(
                "downloaded_song_catalog_v4.json",
                DownloadLegacyStorageAccess.CATALOG_CUTOVER_STATE_KEY
            ),
            TargetDefinition(
                "downloaded_song_catalog_v3.json",
                DownloadLegacyStorageAccess.CATALOG_CUTOVER_STATE_KEY
            ),
            TargetDefinition(
                "managed_download_snapshot_v1.json",
                DownloadLegacyStorageAccess.SNAPSHOT_CUTOVER_STATE_KEY
            )
        )
    }
}
