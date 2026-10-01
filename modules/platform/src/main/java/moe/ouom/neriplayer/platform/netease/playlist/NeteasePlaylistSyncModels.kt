package moe.ouom.neriplayer.platform.netease.playlist

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncPlan
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncResult

internal data class NeteaseResolvedCandidate(
    val song: SongItem,
    val neteaseId: Long
)

internal data class LocalNeteaseCandidateSummary(
    val supportedSongs: Int,
    val skippedUnsupported: Int,
    val skippedExisting: Int,
    val candidates: List<NeteaseResolvedCandidate>
)

internal data class NeteaseCandidateValidationResult(
    val supportedSongs: Int,
    val skippedUnsupported: Int,
    val skippedExisting: Int,
    val candidates: List<NeteaseResolvedCandidate>
)

internal data class ParsedNeteasePlaylistId(
    val playlistId: Long?,
    val success: Boolean
)

internal data class ParsedNeteasePlaylistTrackIds(
    val trackIds: List<Long>,
    val trackCount: Int,
    val success: Boolean
)

internal data class NeteaseRemotePlaylistSyncPlan(
    val targetPlaylistId: Long,
    val totalSongs: Int,
    val supportedSongs: Int,
    val skippedUnsupported: Int,
    val skippedExisting: Int,
    val candidates: List<NeteaseResolvedCandidate>,
    val compareSucceeded: Boolean,
    val message: String? = null
)

internal data class NeteasePlaylistTrackSnapshot(
    val trackIds: Set<Long>,
    val fingerprints: Set<String>,
    val compareSucceeded: Boolean,
    val message: String? = null
)

internal data class NeteaseSongDetailSummary(
    val ids: Set<Long>,
    val fingerprints: Set<String>
)

internal data class ParsedNeteaseSongDetailSummary(
    val ids: Set<Long>,
    val fingerprints: Set<String>,
    val success: Boolean
)

internal fun NeteaseRemotePlaylistSyncPlan.toLikeSyncPlan(): NeteaseLikeSyncPlan {
    return NeteaseLikeSyncPlan(
        totalSongs = totalSongs,
        supportedSongs = supportedSongs,
        skippedUnsupported = skippedUnsupported,
        skippedExisting = skippedExisting,
        pendingSongs = candidates.map { it.song },
        compareSucceeded = compareSucceeded,
        message = message
    )
}

internal fun NeteaseLikeSyncPlan.toLikeSyncResult(
    targetPlaylistId: Long,
    added: Int = 0,
    failed: Int = 0,
    skippedUnsupported: Int = this.skippedUnsupported
): NeteaseLikeSyncResult {
    return NeteaseLikeSyncResult(
        totalSongs = totalSongs,
        supportedSongs = supportedSongs,
        skippedUnsupported = skippedUnsupported,
        skippedExisting = skippedExisting,
        added = added,
        failed = failed,
        message = message,
        targetPlaylistId = targetPlaylistId.takeIf { it > 0L }
    )
}
