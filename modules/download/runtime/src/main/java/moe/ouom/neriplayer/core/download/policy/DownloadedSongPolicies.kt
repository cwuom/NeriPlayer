package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import moe.ouom.neriplayer.data.model.stableKey

import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteResult

import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadSongDeletePlan
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadDeleteReferenceIndex
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.localFileNameFromFileReference as projectedLocalFileNameFromFileReference
import moe.ouom.neriplayer.data.identity.remoteSourceIdentityOrNull as projectedRemoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.identity.resolvedLocalFileName as projectedLocalFileName
import moe.ouom.neriplayer.data.identity.toPlaybackSongItem as toProjectedPlaybackSongItem

internal fun localFileNameFromFileReference(reference: String?): String? {
    return projectedLocalFileNameFromFileReference(reference)
}

internal fun DownloadedSong.resolvedLocalFileName(): String? {
    return projectedLocalFileName()
}

internal fun isCompleteDownloadedSongSelection(
    selectedSongs: Collection<DownloadedSong>,
    availableSongs: Collection<DownloadedSong>
): Boolean {
    val availableIdentities = availableSongs
        .mapTo(linkedSetOf(), DownloadedSong::deletionIdentity)
    if (availableIdentities.isEmpty()) {
        return false
    }
    val selectedIdentities = selectedSongs
        .mapTo(linkedSetOf(), DownloadedSong::deletionIdentity)
    return selectedIdentities == availableIdentities
}

internal fun DownloadedSong.remoteSourceIdentityOrNull(): SongIdentity? {
    return projectedRemoteSourceIdentityOrNull()
}

internal fun DownloadedSong.remoteSourceStableKeyOrNull(): String? {
    return remoteSourceIdentityOrNull()?.stableKey()
}

internal fun DownloadedSong.withRecoveredRemoteSourceStableKey(): DownloadedSong {
    val recoveredStableKey = remoteSourceStableKeyOrNull() ?: return this
    return if (stableKey == recoveredStableKey) this else copy(stableKey = recoveredStableKey)
}

internal fun DownloadedSong.withCachedDownloadedLyrics(
    metadata: DownloadedAudioMetadata?
): DownloadedSong {
    if (metadata == null) return this
    return copy(
        matchedLyric = metadata.matchedLyric ?: matchedLyric,
        matchedTranslatedLyric = metadata.matchedTranslatedLyric ?: matchedTranslatedLyric,
        matchedRomanizedLyric = metadata.matchedRomanizedLyric ?: matchedRomanizedLyric,
        matchedLyricSource = metadata.matchedLyricSource ?: matchedLyricSource,
        matchedSongId = metadata.matchedSongId ?: matchedSongId,
        userLyricOffsetMs = metadata.userLyricOffsetMs.takeIf { it != 0L }
            ?: userLyricOffsetMs,
        originalLyric = metadata.originalLyric ?: originalLyric,
        originalTranslatedLyric = metadata.originalTranslatedLyric
            ?: originalTranslatedLyric,
        originalRomanizedLyric = metadata.originalRomanizedLyric
            ?: originalRomanizedLyric
    )
}

fun DownloadedSong.toPlaybackSongItem(): SongItem {
    return toProjectedPlaybackSongItem()
}

fun DownloadedSong.toPlaybackSongItem(
    playbackUri: String,
    localFileName: String?,
    localFilePath: String?,
    resolvedDurationMs: Long
): SongItem {
    return toProjectedPlaybackSongItem(
        playbackUri = playbackUri,
        localFileName = localFileName,
        localFilePath = localFilePath,
        resolvedDurationMs = resolvedDurationMs
    )
}

internal fun resolveDownloadedSongDeleteResult(
    deletePlans: List<ManagedDownloadSongDeletePlan>,
    deletedReferences: Set<String>
): DownloadedSongDeleteResult {
    val deletedSongs = deletePlans
        .filter { deletePlan ->
            deletePlan.requiredReferences.all(deletedReferences::contains)
        }
        .map(ManagedDownloadSongDeletePlan::song)
    return DownloadedSongDeleteResult(
        deletedSongs = deletedSongs,
        failedSongs = deletePlans
            .map(ManagedDownloadSongDeletePlan::song)
            .filterNot(deletedSongs::contains)
    )
}

/** 完整目录快照已确认时，以物理引用结果为准，避免旧 catalog 重新复活 */
internal fun resolveConfirmedFullLibraryDeleteResult(
    targetSongs: List<DownloadedSong>,
    snapshotComplete: Boolean,
    requestedReferences: Set<String>,
    deletedReferences: Set<String>,
    remainingReferences: Set<String>? = null,
    fallback: DownloadedSongDeleteResult,
    confirmedMissingAudioReferences: Set<String> = emptySet()
): DownloadedSongDeleteResult {
    val unresolvedReferences = remainingReferences
        ?: requestedReferences.minus(deletedReferences)
    if (!snapshotComplete || unresolvedReferences.isNotEmpty()) {
        // 未决侧载不能让已经确认删除的音频重新出现在 catalog
        val deletedIndex = ManagedDownloadDeleteReferenceIndex(deletedReferences + confirmedMissingAudioReferences)
        val confirmedSongs = targetSongs.filter { song ->
            deletedIndex.resolve(song.deletionIdentity()) != null
        }
        if (confirmedSongs.isEmpty()) return fallback
        val deletedSongs = (fallback.deletedSongs + confirmedSongs)
            .distinctBy(DownloadedSong::deletionIdentity)
        val deletedIdentities = deletedSongs.mapTo(hashSetOf(), DownloadedSong::deletionIdentity)
        return fallback.copy(
            deletedSongs = deletedSongs,
            failedSongs = targetSongs.filterNot { it.deletionIdentity() in deletedIdentities }
        )
    }
    return DownloadedSongDeleteResult(
        deletedSongs = targetSongs.distinctBy(DownloadedSong::deletionIdentity),
        failedSongs = emptyList()
    )
}

/** 复查快照可能带着旧缓存引用，必须扣除两轮已确认删除的对象 */
internal fun resolveFullLibraryRemainingReferences(
    verificationReferences: Set<String>,
    deletedReferences: Set<String>,
    residualDeletedReferences: Set<String>
): Set<String> = verificationReferences - deletedReferences - residualDeletedReferences

internal fun mergeDownloadedSongsAfterDelete(
    currentSongs: List<DownloadedSong>,
    previousSongs: List<DownloadedSong>,
    deletedSongs: List<DownloadedSong>,
    restoredSongs: List<DownloadedSong>
): List<DownloadedSong> {
    val deletedIdentities = deletedSongs.mapTo(mutableSetOf()) {
        it.deletionIdentity()
    }
    val restoredIdentities = restoredSongs
        .mapTo(mutableSetOf()) { it.deletionIdentity() }
        .apply { removeAll(deletedIdentities) }
    val survivingSongs = currentSongs.filterNot { song ->
        song.deletionIdentity() in deletedIdentities
    }
    if (restoredIdentities.isEmpty()) return survivingSongs

    val currentIdentities = survivingSongs.mapTo(mutableSetOf()) {
        it.deletionIdentity()
    }
    val restoredFromPrevious = previousSongs.filter { song ->
        song.deletionIdentity() in restoredIdentities &&
            currentIdentities.add(song.deletionIdentity())
    }
    return if (restoredFromPrevious.isEmpty()) {
        survivingSongs
    } else {
        (survivingSongs + restoredFromPrevious)
            .sortedByDescending(DownloadedSong::downloadTime)
    }
}
