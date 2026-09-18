package moe.ouom.neriplayer.core.download.manager.runtime

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.buildExpectedDownloadArtists
import moe.ouom.neriplayer.core.download.buildExpectedDownloadTitles
import moe.ouom.neriplayer.core.download.downloadedSongPlaybackReferenceCandidates
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.isUnfinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.shouldRepairDownloadedCover
import moe.ouom.neriplayer.core.download.shouldRepairMetadataLessManagedDownload
import moe.ouom.neriplayer.core.download.shouldTrustFastDownloadedSongCatalogHit
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.catalog.scheduleDownloadedSongReferenceReconcile
import moe.ouom.neriplayer.core.download.manager.catalog.updateDownloadProgress
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.policy.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.policy.shouldTrustDirectPresentDownloadedSongReference
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadedSongReferenceProbe
import android.content.Context
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogIndex
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.forPublication
import moe.ouom.neriplayer.core.player.download.isReadableManagedAudioPlaybackAllowed
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey


internal suspend fun GlobalDownloadManager.findExistingDownloadedAudio(
    context: Context,
    song: SongItem,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
    allowStorageLookup: Boolean = true
): ManagedDownloadStorage.StoredEntry? {
    val songKey = song.stableKey()
    if (isSongCancelled(songKey) || AudioDownloadManager.isSongDownloadActive(songKey)) {
        NPLogger.d(
            TAG,
            "跳过已下载检查: song=${song.name}, cancelled=${isSongCancelled(songKey)}, active=${AudioDownloadManager.isSongDownloadActive(songKey)}"
        )
        return null
    }
    val existingAudio = ManagedDownloadStorage.peekDownloadedAudio(song)
        ?: snapshot?.let {
            ManagedDownloadStorage.findDownloadedAudioIncludingMetadataLess(it, song)
        }
        ?: if (allowStorageLookup) {
            ManagedDownloadStorage.findDownloadedAudio(context, song)
        } else {
            null
        }
        ?: return null
    NPLogger.d(
        TAG,
        "命中已下载候选文件: song=${song.name}, file=${existingAudio.name}, size=${existingAudio.sizeBytes}"
    )
    return validateExistingDownloadedAudio(
        context = context,
        song = song,
        audio = existingAudio,
        snapshotMetadata = ManagedDownloadStorage.metadataForAudioEntry(
            snapshot = snapshot,
            audio = existingAudio
        )
    )
}

internal fun GlobalDownloadManager.findFastCachedDownloadedSong(
    context: Context,
    song: SongItem,
    catalogIndex: DownloadedSongCatalogIndex = downloadedSongCatalogIndex
): DownloadedSong? {
    val downloadedSong = catalogIndex.find(song) ?: return null
    val references = downloadedSongPlaybackReferenceCandidates(downloadedSong)
    if (references.isEmpty()) return null
    val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
        context = context,
        restorePersisted = false
    )
    var sawMissing = false
    var sawUncertain = false
    var sawIncompleteAudio = false
    var sawIndexMismatch = false
    for (reference in references) {
        if (reference.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)) {
            sawUncertain = true
            continue
        }
        val evidence = ManagedDownloadReferenceLookup.inspect(context, reference)
        when (evidence) {
            ManagedDownloadReferenceLookup.Result.Present -> Unit
            ManagedDownloadReferenceLookup.Result.Missing -> {
                sawMissing = true
                continue
            }
            is ManagedDownloadReferenceLookup.Result.PermissionLost,
            is ManagedDownloadReferenceLookup.Result.ProviderFailure,
            ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                sawUncertain = true
                continue
            }
        }
        val cachedAudio = snapshot?.let { currentSnapshot ->
            currentSnapshot.audioEntriesByLookupKey[reference]
                ?: currentSnapshot.pendingAudioEntries.firstOrNull { entry ->
                    entry.reference == reference ||
                        entry.mediaUri == reference ||
                        entry.localFilePath == reference
                }
                ?: ManagedDownloadStorage.findPendingDownloadedAudio(currentSnapshot, song)
        }
        if (snapshot == null || !snapshot.rootEntriesComplete) {
            if (
                shouldTrustDirectPresentDownloadedSongReference(
                    reference = reference,
                    evidence = evidence,
                    snapshot = snapshot,
                    cachedAudio = cachedAudio
                )
            ) {
                NPLogger.d(
                    TAG,
                    "正式下载音频已确认可读，跳过未完成快照门禁: " +
                        "song=${song.name}, reference=$reference, " +
                        "rootEntriesComplete=${snapshot?.rootEntriesComplete}"
                )
                scheduleCatalogReconcile(context, forceRefresh = snapshot != null)
                return downloadedSong
            }
            sawUncertain = true
            continue
        }
        if (
            cachedAudio != null &&
            !isReadableManagedAudioPlaybackAllowed(
                audioIsPending = cachedAudio.isPendingAudioWrite,
                downloadActive = false,
                downloadCancelled = false,
                metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, cachedAudio),
                // 非 pending 文件已经由 Present 证据确认可读。旧 v15
                // 元数据可能只有 downloadFinalized=false 而没有 artifactState,
                // 不能因为缺少新阶段字段把既有本地歌曲切回远端解析。
                allowLegacyPublishedAudio = !cachedAudio.isPendingAudioWrite
            )
        ) {
            sawIncompleteAudio = true
            sawUncertain = true
            continue
        }
        if (!shouldTrustFastDownloadedSongCatalogHit(reference, snapshot.knownReferences)) {
            sawIndexMismatch = true
            sawUncertain = true
            continue
        }
        return downloadedSong
    }

    when {
        snapshot == null -> {
            NPLogger.d(
                TAG,
                "下载快索引缺少当前目录快照，拒绝把缓存条目视为完成: song=${song.name}"
            )
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
        !snapshot.rootEntriesComplete -> {
            NPLogger.d(
                TAG,
                "下载目录快照不完整，拒绝把缓存条目视为完成: song=${song.name}"
            )
            scheduleCatalogReconcile(context, forceRefresh = !sawUncertain)
        }
        sawIncompleteAudio -> {
            NPLogger.d(
                TAG,
                "下载快索引命中未完成音频，等待 core 或元信息收尾: song=${song.name}"
            )
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
        sawIndexMismatch -> {
            NPLogger.w(
                TAG,
                "下载目录缓存与索引不一致，后台刷新: song=${song.name}"
            )
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
        sawMissing -> scheduleDownloadedSongReferenceReconcile(
            context = context,
            song = downloadedSong,
            probe = DownloadedSongReferenceProbe(
                sawMissing = true,
                sawUncertain = sawUncertain
            )
        )
    }
    return null
}

internal suspend fun GlobalDownloadManager.repairDownloadedCoverIfMissing(
    context: Context,
    song: SongItem,
    downloadedSong: DownloadedSong
): DownloadedSong {
    val coverEvidence = downloadedSong.coverPath?.let { reference ->
        ManagedDownloadReferenceLookup.inspect(context, reference)
    }
    if (
        coverEvidence is ManagedDownloadReferenceLookup.Result.PermissionLost ||
        coverEvidence is ManagedDownloadReferenceLookup.Result.ProviderFailure
    ) {
        NPLogger.w(
            TAG,
            "封面引用暂不可确认，跳过替换: song=${song.name}, evidence=$coverEvidence"
        )
        return downloadedSong
    }
    val existingCoverAccessible =
        coverEvidence is ManagedDownloadReferenceLookup.Result.Present
    val hasNetworkCoverCandidate = AudioDownloadManager
        .buildCoverDownloadCandidateUrls(song)
        .isNotEmpty()
    if (!shouldRepairDownloadedCover(existingCoverAccessible, hasNetworkCoverCandidate)) {
        return downloadedSong
    }

    val storedAudio = resolveStoredAudio(context, song)
        ?: resolveStoredAudio(context, downloadedSong.filePath)
    if (storedAudio == null) {
        NPLogger.w(TAG, "缺少封面但无法定位已下载音频，跳过侧载修复: ${song.name}")
        return downloadedSong
    }

    val sidecarReferences = try {
        AudioDownloadManager.repairCoverForCompletedAudio(
            context = context,
            song = song,
            storedAudio = storedAudio
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "已下载歌曲封面侧载修复失败: ${song.name} - ${error.message}")
        return downloadedSong
    }
    val repairedCover = sidecarReferences.coverReference
        ?.takeIf { reference ->
            ManagedDownloadReferenceLookup.inspect(context, reference) is
                ManagedDownloadReferenceLookup.Result.Present
        }
    if (repairedCover == null) {
        NPLogger.w(TAG, "封面侧载修复未生成可访问文件: ${song.name}")
        return downloadedSong
    }

    val metadataPatched = downloadedAudioMetadataStore.persistCoverReference(
        context = context,
        audio = storedAudio,
        coverReference = repairedCover
    )
    val existingMetadata = readDownloadedMetadata(context, storedAudio)
    val metadataWritten = metadataPatched || persistDownloadedMetadata(
        context = context,
        audio = storedAudio,
        song = song,
        sidecarReferences = sidecarReferences,
        downloadFinalized = isFinalizedDownloadedMetadata(existingMetadata),
        metadataEmbeddingState = existingMetadata?.metadataEmbeddingState,
        resolveExistingSidecars = true
    )
    if (!metadataWritten) {
        NPLogger.w(TAG, "封面侧载已生成但元数据回写失败: ${song.name}")
        scheduleCatalogReconcile(context, forceRefresh = true)
    }
    return downloadedSong.copy(coverPath = repairedCover)
}

internal suspend fun GlobalDownloadManager.validateExistingDownloadedAudio(
    context: Context,
    song: SongItem,
    audio: ManagedDownloadStorage.StoredEntry,
    snapshotMetadata: ManagedDownloadStorage.DownloadedAudioMetadata? = null
): ManagedDownloadStorage.StoredEntry? {
    val metadata = snapshotMetadata ?: run {
        val metadataEntry = ManagedDownloadStorage.findMetadataForAudio(context, audio)
        metadataEntry?.let {
            readDownloadedMetadata(
                context = context,
                audio = audio,
                metadataEntry = it
            )
        }
    }
    if (isUnfinalizedDownloadedMetadata(metadata)) {
        val unfinalizedMetadata = metadata ?: return null
        if (!isMetadataOwnedBySong(unfinalizedMetadata, song)) {
            NPLogger.w(TAG, "未最终确认文件不属于当前歌曲，跳过回滚: song=${song.name}, file=${audio.name}")
            return null
        }
        if (ManagedDownloadStorage.hasReadableContent(context, audio)) {
            NPLogger.w(
                TAG,
                "发现未最终确认但音频已完整，保留文件并进入收尾重试: " +
                    "song=${song.name}, file=${audio.name}"
            )
            return audio
        }
        if (isDurableCoreArtifactState(unfinalizedMetadata.artifactState)) {
            NPLogger.w(
                TAG,
                "core committed 音频暂时不可读，禁止验证路径删除: " +
                    "song=${song.name}, file=${audio.name}"
            )
            return audio
        }
        val evidence = ManagedDownloadReferenceLookup.inspect(
            context = context,
            reference = audio.reference
        )
        if (!ManagedDownloadReferenceLookup.canMarkMissing(evidence)) {
            NPLogger.w(
                TAG,
                "未最终确认音频不可读但缺少 Missing 证据，保留: " +
                    "song=${song.name}, file=${audio.name}, evidence=$evidence"
            )
            return audio
        }
        NPLogger.w(TAG, "发现未最终确认且音频不可读，回滚后重新下载: song=${song.name}, file=${audio.name}")
        rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
        return null
    }

    val hasReadableAudio = audio.sizeBytes > 0L ||
        ManagedDownloadStorage.hasReadableContent(context, audio)
    if (metadata != null && isMetadataOwnedBySong(metadata, song) && hasReadableAudio) {
        NPLogger.d(
            TAG,
            "已下载文件 metadata 快速校验通过: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
        )
        return audio
    }

    val localDetails = inspectDownloadedAudioDetails(context, audio)
    if (metadata != null && localDetails == null) {
        if (hasReadableAudio) {
            NPLogger.w(
                TAG,
                "已下载文件存在 metadata 但音频标签不可读，保留并复用: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
            )
            return audio
        }
        NPLogger.w(
            TAG,
            "已下载文件存在 metadata 但文件为空，回滚后重新下载: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
        )
        if (isMetadataOwnedBySong(metadata, song)) {
            rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
        }
        return null
    }
    if (metadata != null && localDetails != null) {
        NPLogger.d(
            TAG,
            "已下载文件校验通过: song=${song.name}, file=${audio.name}, durationMs=${localDetails.durationMs}, size=${audio.sizeBytes}"
        )
        return audio
    }
    if (localDetails == null) {
        // 无法读取音频标签 (常见于 SAF content:// URI)
        // 通过文件名和文件大小判断是否为有效下载
        if (hasReadableAudio && matchesExpectedDownloadFileName(song, audio)) {
            NPLogger.d(TAG, "无法读取音频标签但文件名匹配，补写元数据: ${audio.name}")
            persistDownloadedMetadata(context, audio, song)
            return audio
        }
        NPLogger.w(TAG, "发现无法验证的候选文件，未确认归属前不回滚: song=${song.name}, file=${audio.name}")
        return null
    }

    val shouldRepair = shouldRepairMetadataLessManagedDownload(
        expectedTitles = buildExpectedDownloadTitles(song),
        expectedArtists = buildExpectedDownloadArtists(song),
        expectedDurationMs = song.durationMs.coerceAtLeast(0L),
        actualTitle = localDetails.originalTitle ?: localDetails.title,
        actualArtist = localDetails.originalArtist ?: localDetails.artist,
        actualDurationMs = localDetails.durationMs
    )
    if (!shouldRepair) {
        persistDownloadedMetadata(context, audio, song)
        return audio
    }

    NPLogger.w(
        TAG,
        "发现残缺下载文件，回滚后重新下载: song=${song.name}, file=${audio.name}"
    )
    if (matchesExpectedDownloadFileName(song, audio)) {
        rollbackCancelledDownload(
            context = context,
            song = song,
            storedAudio = audio
        )
    }
    return null
}

internal suspend fun GlobalDownloadManager.cleanupUnfinalizedDownloadForRetry(
    context: Context,
    song: SongItem,
    forceStorageRefresh: Boolean
) {
    val audio = ManagedDownloadStorage.findDownloadedAudio(
        context = context,
        song = song,
        forceRefresh = forceStorageRefresh
    ) ?: return
    val metadata = readDownloadedMetadata(context, audio)
    if (!isUnfinalizedDownloadedMetadata(metadata)) {
        return
    }
    if (ManagedDownloadStorage.hasReadableContent(context, audio)) {
        NPLogger.d(
            TAG,
            "重试前保留已完整但未最终确认音频，后续只执行收尾: " +
                "song=${song.name}, file=${audio.name}"
        )
        return
    }
    if (isDurableCoreArtifactState(metadata?.artifactState)) {
        NPLogger.w(
            TAG,
            "core committed 音频暂时不可读，重试清理保留: song=${song.name}, file=${audio.name}"
        )
        return
    }
    val evidence = ManagedDownloadReferenceLookup.inspect(
        context = context,
        reference = audio.reference
    )
    if (!ManagedDownloadReferenceLookup.canMarkMissing(evidence)) {
        NPLogger.w(
            TAG,
            "重试清理缺少 Missing 证据，保留音频: song=${song.name}, evidence=$evidence"
        )
        return
    }
    NPLogger.w(TAG, "重试前清理未最终确认下载文件: song=${song.name}, file=${audio.name}")
    rollbackCancelledDownload(
        context = context,
        song = song,
        storedAudio = audio
    )
}

internal fun GlobalDownloadManager.isMetadataOwnedBySong(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata,
    song: SongItem
): Boolean {
    val identity = song.identity()
    val stableKey = identity.stableKey()
    // 已持久化的身份优先，不能被碰巧相同的数字 id 或空 URI 覆盖
    metadata.stableKey?.trim()?.takeIf(String::isNotBlank)?.let { storedKey ->
        return storedKey == stableKey
    }
    val remoteTrackKey = buildDownloadRemoteTrackKey(
        channelId = metadata.channelId,
        audioId = metadata.audioId,
        subAudioId = metadata.subAudioId
    )
    val songRemoteTrackKey = buildDownloadRemoteTrackKey(
        channelId = song.channelId,
        audioId = song.audioId,
        subAudioId = song.subAudioId
    )
    if (remoteTrackKey != null && songRemoteTrackKey != null) {
        return remoteTrackKey == songRemoteTrackKey
    }
    metadata.identityAlbum?.trim()?.takeIf(String::isNotBlank)?.let { source ->
        if ((metadata.songId ?: 0L) > 0L) {
            return metadata.songId == song.id && source == identity.album
        }
    }
    if (metadata.songId != null && metadata.songId > 0L && metadata.songId == song.id &&
        !metadata.album.isNullOrBlank() &&
        (metadata.album == song.album || metadata.album == identity.album)
    ) {
        return true
    }
    val storedUri = metadata.mediaUri?.trim()?.takeIf(String::isNotBlank) ?: return false
    return storedUri == identity.mediaUri?.trim()?.takeIf(String::isNotBlank)
}

internal fun GlobalDownloadManager.isRecoveryMetadataOwnedBySong(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata,
    song: SongItem,
    operationId: String?
): Boolean {
    if (isMetadataOwnedBySong(metadata, song)) return true
    val hasSongIdentity = !metadata.stableKey.isNullOrBlank() ||
        (metadata.songId ?: 0L) > 0L || !metadata.audioId.isNullOrBlank() ||
        !metadata.mediaUri.isNullOrBlank()
    // 无歌曲身份的旧凭据只能用非空 operation 证明归属
    return !hasSongIdentity && !operationId.isNullOrBlank() &&
        metadata.operationId?.trim() == operationId.trim()
}

internal fun GlobalDownloadManager.buildDownloadRemoteTrackKey(
    channelId: String?,
    audioId: String?,
    subAudioId: String?
): String? {
    val normalizedAudioId = audioId?.takeIf(String::isNotBlank) ?: return null
    return listOfNotNull(
        channelId?.takeIf(String::isNotBlank),
        normalizedAudioId,
        subAudioId?.takeIf(String::isNotBlank)
    ).joinToString("|")
}

internal fun GlobalDownloadManager.shouldSkipDownload(context: Context, song: SongItem): Boolean {
    if (!LocalSongSupport.isLocalSong(song, context)) {
        return false
    }
    NPLogger.d(TAG, "跳过本地歌曲下载: ${song.name}")
    return true
}

internal suspend fun GlobalDownloadManager.settleAlreadyDownloadedOperation(
    context: Context,
    song: SongItem,
    operationId: String?,
    expectedAttemptId: Long?,
    reason: String
): Boolean {
    val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
        ?: return true
    val songKey = song.stableKey()
    return runCatching {
        var settled = false
        repeat(2) {
            if (!settled) {
                settled = DownloadExecutionRoomStore.markAlreadyDownloadedCompleted(
                    context = context.applicationContext,
                    operationId = normalizedOperationId,
                    stableKey = songKey,
                    expectedAttemptId = expectedAttemptId,
                    errorCode = reason
                )
            }
        }
        settled
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "已下载 operation 终态收口失败，保留恢复凭据: " +
                "song=${song.name}, operationId=$normalizedOperationId, " +
                "reason=$reason, error=${error.message}",
            error
        )
    }.getOrDefault(false).also { settled ->
        if (settled) {
            NPLogger.d(
                TAG,
                "已下载 operation 已持久完成: " +
                    "song=${song.name}, operationId=$normalizedOperationId, reason=$reason"
            )
        }
    }
}

internal fun GlobalDownloadManager.batchOperationIdForAttempt(
    songKey: String,
    attemptId: Long?
): String? {
    if (attemptId == null || attemptId <= 0L) return null
    val candidates = batchDownloadPresentationsMutable.value.values
        .mapNotNull { presentation ->
            if (presentation.memberAttemptIds[songKey] != attemptId) return@mapNotNull null
            presentation.memberOperationIds[songKey]
                ?.takeIf(String::isNotBlank)
        }
        .distinct()
    return candidates.singleOrNull()
}

internal fun GlobalDownloadManager.publishDownloadStage(
    song: SongItem,
    stage: AudioDownloadManager.DownloadStage,
    operationId: String? = null,
    attemptId: Long? = null,
    bytesRead: Long = 0L,
    totalBytes: Long = 0L
) {
    // 宿主入队尚未进入 AudioDownloadManager 的 operation 生命周期。队列阶段
    // 必须由已持有 durable task 身份的全局投影发布，不能被音频引用租约误判为旧回调
    updateDownloadProgress(
        AudioDownloadManager.DownloadProgress(
            songKey = song.stableKey(),
            songId = song.id,
            fileName = ManagedDownloadStorage.buildDisplayBaseName(song),
            bytesRead = bytesRead.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            speedBytesPerSec = 0L,
            stage = stage,
            attemptId = attemptId,
            operationId = operationId
        ).forPublication()
    )
}
