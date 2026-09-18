package moe.ouom.neriplayer.core.download.manager.recovery

import android.content.Context
import androidx.room.withTransaction
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.state.DOWNLOAD_RETRY_MAX_COUNT
import moe.ouom.neriplayer.core.download.manager.commit.inspectFinalizedDownloadedAudio
import moe.ouom.neriplayer.core.download.manager.runtime.isRecoveryMetadataOwnedBySong
import moe.ouom.neriplayer.core.download.manager.runtime.loadFinalizationRecoverySnapshot
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.hasDownloadedAudioDurationMismatch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

internal suspend fun GlobalDownloadManager.invalidCoreAudioReason(
    context: Context,
    song: SongItem,
    audio: ManagedDownloadStorage.StoredEntry,
    operationId: String
): String? {
    val metadata = readDownloadedMetadata(context, audio)
    if (metadata != null && !isRecoveryMetadataOwnedBySong(metadata, song, operationId)) {
        return "CORE_AUDIO_IDENTITY_MISMATCH"
    }
    val probe = inspectFinalizedDownloadedAudio(context, audio)
    return "CORE_AUDIO_DURATION_MISMATCH".takeIf {
        probe.readable && hasDownloadedAudioDurationMismatch(song.durationMs, probe.durationMs)
    }
}

internal suspend fun GlobalDownloadManager.requeueInvalidCoreAudio(
    context: Context,
    song: SongItem,
    operationId: String,
    audioReference: String?,
    expectedLeaseId: String?,
    reason: String,
    directoryLeaseOwned: Boolean = false
): Boolean {
    val directoryLease = if (directoryLeaseOwned) null else {
        ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(context, operationId)
            ?: throw DownloadStorageMutationDeferredException(operationId)
    }
    try {
        val request = resetInvalidCoreDownloadForTransfer(
            context, song, operationId, audioReference, expectedLeaseId, reason
        ) ?: return false
        NPLogger.w(TAG, "core 音频凭据无效，保留原文件并按原队号重新传输: operationId=$operationId, reason=$reason")
        request.attemptId?.let { attemptId ->
            updateTaskStatus(song.stableKey(), DownloadStatus.QUEUED, expectedAttemptId = attemptId)
        }
        wakeDownloadExecutionPump(context, reason = reason)
        return true
    } finally {
        directoryLease?.close()
    }
}

internal suspend fun resetInvalidCoreDownloadForTransfer(
    context: Context,
    song: SongItem,
    operationId: String,
    audioReference: String?,
    expectedLeaseId: String?,
    reason: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): DownloadExecutionRequest? {
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(operationId) ?: return@withTransaction null
        if (header.stableKey != song.stableKey() || header.retryCount >= DOWNLOAD_RETRY_MAX_COUNT) {
            return@withTransaction null
        }
        val request = DownloadExecutionRoomStore.read(context, operationId, database)
            ?: return@withTransaction null
        val artifactDao = database.managedDownloadArtifactDao()
        val artifact = artifactDao.findAllByStableKey(song.stableKey()).singleOrNull {
            it.audioReference == audioReference && it.leaseId == expectedLeaseId
        } ?: return@withTransaction null
        if (dao.resetInvalidCoreForTransfer(
                operationId, song.stableKey(), header.updatedAtMs, reason, System.currentTimeMillis()
            ) != 1
        ) return@withTransaction null
        // 只解绑错误引用，保留原文件和 sidecar，不能删除或继续改写另一首歌
        artifactDao.upsert(artifact.copy(
            state = "QUEUED", leaseId = request.artifactLeaseId,
            audioReference = null, audioName = null, fileSize = null, contentHash = null,
            finalizedAtMs = null, needsReconcile = true, lastErrorCode = reason,
            updatedAtMs = System.currentTimeMillis()
        ))
        DownloadExecutionRoomStore.upsert(
            context, request.copy(preserveStaging = false), state = "RETRYABLE", database = database
        )
        request
    }
}

internal suspend fun GlobalDownloadManager.requeueConfirmedMissingCoreAudio(
    context: Context,
    song: SongItem,
    operationId: String,
    audioReference: String?,
    audioName: String?,
    expectedLeaseId: String?
): Boolean {
    val directoryLease = ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(context, operationId)
        ?: throw DownloadStorageMutationDeferredException(operationId)
    try {
        if (downloadedSongDeletionCounts.containsKey(song.stableKey())) return false
        val evidence = ManagedDownloadReferenceLookup.inspect(context, audioReference)
        if (!audioReference.isNullOrBlank() && evidence != ManagedDownloadReferenceLookup.Result.Missing) {
            return false
        }
        val snapshot = loadFinalizationRecoverySnapshot(
            context, forceRefresh = true, allowFreshCacheReuse = false
        ) ?: return false
        if (!snapshot.rootEntriesComplete) return false
        // 引用丢失也可能是 pending 正在提升为正式文件，完整扫描确认前不能重传
        val candidates = snapshot.audioEntries + snapshot.pendingAudioEntries +
            snapshot.audioEntriesWithoutMetadata
        if (candidates.any { audio ->
                val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
                (metadata != null && isRecoveryMetadataOwnedBySong(metadata, song, operationId)) ||
                    (!audioName.isNullOrBlank() &&
                        (audio.name == audioName || audio.logicalName == audioName))
            }
        ) return false
        return requeueInvalidCoreAudio(
            context, song, operationId, audioReference, expectedLeaseId, "CORE_AUDIO_MISSING_CONFIRMED",
            directoryLeaseOwned = true
        )
    } finally {
        directoryLease.close()
    }
}
