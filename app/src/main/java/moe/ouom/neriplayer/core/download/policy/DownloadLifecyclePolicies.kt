package moe.ouom.neriplayer.core.download.policy

import kotlin.math.abs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.SongItem

/** 标签后处理一次尝试后的收尾动作 */
internal enum class TagPostProcessingAction {
    FINALIZE_TAGGED,
    FINALIZE_UNTAGGED,
    RETRY,
    PRESERVE_UNFINALIZED
}

/**
 * 标签后处理结果 -> 收尾动作
 *
 * 标签写入失败时必须保留音频, 但不能把可写标签的容器伪装成已完成
 * - SUCCESS: 标签已写入, 按带标签完成
 * - UNSUPPORTED_CONTAINER: 容器天生写不了 (如 WebM) , 保留音频按无标签完成, 重试无意义
 * - FAILED / 未知: 可能瞬时失败, 仍有重试次数则重试; 重试耗尽后保留未最终确认文件
 *   等待后续元数据收尾重试, 不删除音频也不发布完成状态
 */
internal fun tagPostProcessingAction(
    outcome: DownloadedAudioTagWriteOutcome?,
    hasRemainingAttempts: Boolean
): TagPostProcessingAction = when (outcome) {
    DownloadedAudioTagWriteOutcome.SUCCESS -> TagPostProcessingAction.FINALIZE_TAGGED
    DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER -> TagPostProcessingAction.FINALIZE_UNTAGGED
    DownloadedAudioTagWriteOutcome.FAILED, null ->
        if (hasRemainingAttempts) TagPostProcessingAction.RETRY
        else TagPostProcessingAction.PRESERVE_UNFINALIZED
}

internal fun shouldRunInitialDownloadScan(
    catalogReady: Boolean,
    hasRecoveredEntries: Boolean = false
): Boolean {
    return hasRecoveredEntries || !catalogReady
}

/**
 * 判定一次下载扫描的空结果是否"可疑" (疑似 SAF 列举瞬时失败) , 从而不应用空结果覆盖既有目录
 *
 * 背景 (#168 疑似根因) : SAF DocumentsProvider 可能瞬时返回空/失败游标, 列举失败时兜底为空列表
 * 一次瞬时失败会被当成权威的"空目录"持久化, 下次启动信任空目录不再重扫, 导致已下载歌曲"看不见"
 * (文件本身仍在磁盘)
 *
 * 四条件同时成立才视为可疑, 避免误伤正常清空:
 * 1. 本次扫描结果为空
 * 2. 既有目录 (内存/已持久化) 非空, 说明之前确实扫描/恢复到过下载
 * 3. 存储 root 仍可解析 (SAF 树仍在或使用应用私有目录) , 排除"目录不可用->错误目录为空"的假空结果
 * 4. 本次扫描的存储 root 与既有 catalog 所属 root 一致 (scanMatchesCatalogRoot)
 * 切换/重置下载目录后, 扫描的是新 root, 而既有 catalog 属于旧 root, 此时的空是"换目录后的真空"
 * 应放行以正确清空并让 app 内刷新可自愈, 而不是把旧目录条目误判为瞬时失败保留
 */
internal fun isSuspiciousEmptyDownloadScan(
    scannedSongCount: Int,
    existingSongCount: Int,
    storageRootResolvable: Boolean,
    scanMatchesCatalogRoot: Boolean
): Boolean {
    return scannedSongCount == 0 &&
        existingSongCount > 0 &&
        storageRootResolvable &&
        scanMatchesCatalogRoot
}

internal fun shouldDeferStartupManagedCleanup(
    configuredDirectoryUri: String?,
    treeRootAvailable: Boolean
): Boolean {
    return !configuredDirectoryUri.isNullOrBlank() && treeRootAvailable
}

internal fun shouldDeferPendingDownloadRecoveryForNetwork(
    networkType: TrafficNetworkType,
    mobileDataOverrideAllowed: Boolean
): Boolean {
    return networkType != TrafficNetworkType.WIFI && !mobileDataOverrideAllowed
}

internal fun shouldDeferPreparedDownloadStartForNetwork(
    networkType: TrafficNetworkType,
    mobileDataOverrideAllowed: Boolean,
    deferForNetworkPolicy: Boolean
): Boolean {
    if (!deferForNetworkPolicy) {
        return false
    }
    return shouldDeferPendingDownloadRecoveryForNetwork(
        networkType = networkType,
        mobileDataOverrideAllowed = mobileDataOverrideAllowed
    )
}

internal fun shouldKeepCancellationCleanup(
    currentGeneration: Long?,
    cancellationGeneration: Long?,
    cancelled: Boolean
): Boolean {
    if (currentGeneration == cancellationGeneration) {
        return true
    }
    return currentGeneration == null && cancelled
}

internal enum class CompletedDownloadFinalizationAction {
    COMPLETE,
    COMPLETE_WITHOUT_STORED_AUDIO,
    ROLLBACK_CANCELLED
}

internal enum class PreExistingDownloadedAudioAction {
    DIRECT_SETTLE,
    FINALIZE_EXISTING,
    CONTINUE_DOWNLOAD
}

internal fun resolveCompletedDownloadFinalizationAction(
    hasStoredAudio: Boolean,
    cancelled: Boolean
): CompletedDownloadFinalizationAction {
    return when {
        cancelled -> CompletedDownloadFinalizationAction.ROLLBACK_CANCELLED
        !hasStoredAudio -> CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO
        else -> CompletedDownloadFinalizationAction.COMPLETE
    }
}

internal fun resolvePreExistingDownloadedAudioAction(
    hasExistingAudio: Boolean,
    needsFinalization: Boolean = false
): PreExistingDownloadedAudioAction {
    return if (!hasExistingAudio) {
        PreExistingDownloadedAudioAction.CONTINUE_DOWNLOAD
    } else if (needsFinalization) {
        PreExistingDownloadedAudioAction.FINALIZE_EXISTING
    } else {
        PreExistingDownloadedAudioAction.DIRECT_SETTLE
    }
}

internal fun shouldRepairDownloadedCover(
    coverReferenceAccessible: Boolean,
    hasNetworkCoverCandidate: Boolean
): Boolean {
    return !coverReferenceAccessible && hasNetworkCoverCandidate
}

/** 完整音频已经落盘时, 元信息收尾失败也不能删除音频本体 */
internal fun shouldPreserveCompletedAudioAfterFinalizationFailure(
    hasStoredAudio: Boolean,
    cancelled: Boolean
): Boolean {
    return hasStoredAudio && !cancelled
}

internal fun shouldUseImmediateDownloadedPlaybackHydration(
    originalSong: SongItem,
    hydratedSong: SongItem
): Boolean {
    return originalSong.name != hydratedSong.name ||
        originalSong.artist != hydratedSong.artist ||
        originalSong.durationMs != hydratedSong.durationMs ||
        originalSong.coverUrl != hydratedSong.coverUrl ||
        originalSong.customCoverUrl != hydratedSong.customCoverUrl ||
        originalSong.customName != hydratedSong.customName ||
        originalSong.customArtist != hydratedSong.customArtist ||
        originalSong.mediaUri != hydratedSong.mediaUri ||
        originalSong.localFilePath != hydratedSong.localFilePath ||
        originalSong.localFileName != hydratedSong.localFileName
}

internal fun resolveDownloadedPlaybackHydrationDelayMs(
    originalSong: SongItem,
    hydratedSong: SongItem
): Long {
    val lyricsChanged = originalSong.matchedLyric != hydratedSong.matchedLyric ||
        originalSong.matchedTranslatedLyric != hydratedSong.matchedTranslatedLyric ||
        originalSong.matchedRomanizedLyric != hydratedSong.matchedRomanizedLyric ||
        originalSong.originalLyric != hydratedSong.originalLyric ||
        originalSong.originalTranslatedLyric != hydratedSong.originalTranslatedLyric ||
        originalSong.originalRomanizedLyric != hydratedSong.originalRomanizedLyric
    return if (lyricsChanged) {
        0L
    } else if (shouldUseImmediateDownloadedPlaybackHydration(originalSong, hydratedSong)) {
        GlobalDownloadManager.PLAYBACK_METADATA_HYDRATION_DELAY_MS
    } else {
        GlobalDownloadManager.LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS
    }
}

internal suspend fun <T> runNonCancellableDownloadRollback(
    block: suspend () -> T
): T = withContext(NonCancellable) {
    block()
}

internal fun shouldInspectDownloadedAudioDetails(
    allowSlowLocalInspection: Boolean,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    coverReference: String?,
    needsLocalLyricFallback: Boolean
): Boolean {
    if (!allowSlowLocalInspection) return false
    if (needsLocalLyricFallback) return true
    return metadata == null ||
        metadata.name.isNullOrBlank() ||
        metadata.artist.isNullOrBlank() ||
        metadata.originalName.isNullOrBlank() ||
        metadata.originalArtist.isNullOrBlank() ||
        metadata.durationMs <= 0L ||
        coverReference.isNullOrBlank()
}

internal fun isUnfinalizedDownloadedMetadata(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): Boolean {
    return metadata?.downloadFinalized == false
}

internal fun resolveDownloadedLyricContent(
    fileLyric: String?,
    embeddedMatchedLyric: String?,
    embeddedOriginalLyric: String?,
    localLyricContent: String?,
    indexedLyricContent: String?
): String? {
    return fileLyric?.takeIf(String::isNotBlank)
        ?: embeddedMatchedLyric?.takeIf(String::isNotBlank)
        ?: embeddedOriginalLyric?.takeIf(String::isNotBlank)
        ?: localLyricContent?.takeIf(String::isNotBlank)
        ?: indexedLyricContent?.takeIf(String::isNotBlank)
}

internal fun resolveDownloadedLyricOverride(
    fileLyric: String?,
    embeddedMatchedLyric: String?,
    embeddedOriginalLyric: String?,
    localLyricContent: String?,
    indexedLyricContent: String?
): String? {
    if (fileLyric != null) {
        return fileLyric
    }
    if (embeddedMatchedLyric != null) {
        return embeddedMatchedLyric
    }
    if (embeddedOriginalLyric != null) {
        return embeddedOriginalLyric
    }
    return resolveDownloadedLyricContent(
        fileLyric = null,
        embeddedMatchedLyric = null,
        embeddedOriginalLyric = null,
        localLyricContent = localLyricContent,
        indexedLyricContent = indexedLyricContent
    )
}

internal fun shouldRepairMetadataLessManagedDownload(
    expectedTitles: Collection<String>,
    expectedArtists: Collection<String>,
    expectedDurationMs: Long,
    actualTitle: String?,
    actualArtist: String?,
    actualDurationMs: Long
): Boolean {
    val normalizedExpectedTitles = expectedTitles
        .map(::normalizeManagedDownloadText)
        .filter(String::isNotBlank)
        .toSet()
    val normalizedExpectedArtists = expectedArtists
        .map(::normalizeManagedDownloadText)
        .filter(String::isNotBlank)
        .toSet()
    val normalizedActualTitle = normalizeManagedDownloadText(actualTitle)
    val normalizedActualArtist = normalizeManagedDownloadText(actualArtist)

    if (
        normalizedExpectedTitles.isEmpty() ||
        normalizedExpectedArtists.isEmpty() ||
        normalizedActualTitle.isBlank() ||
        normalizedActualArtist.isBlank()
    ) {
        return true
    }
    if (actualDurationMs <= 0L) {
        return true
    }
    if (
        expectedDurationMs > 0L &&
        abs(expectedDurationMs - actualDurationMs) > 5_000L
    ) {
        return true
    }
    return normalizedActualTitle !in normalizedExpectedTitles ||
        normalizedActualArtist !in normalizedExpectedArtists
}

internal fun shouldTrustFastDownloadedSongCatalogHit(
    reference: String?,
    cachedKnownReferences: Set<String>?
): Boolean {
    val normalizedReference = reference?.takeIf(String::isNotBlank) ?: return false
    return cachedKnownReferences == null || normalizedReference in cachedKnownReferences
}

internal fun shouldProbeCompletedAudioAccessDuringPostProcessing(
    reference: String?,
    fastPathTrusted: Boolean
): Boolean {
    if (reference.isNullOrBlank()) {
        return false
    }
    return !fastPathTrusted
}

internal fun shouldUseIndexedSidecarLookup(
    usesDocumentTree: Boolean,
    allowSlowLookup: Boolean
): Boolean {
    return allowSlowLookup && !usesDocumentTree
}

internal fun shouldSkipCancelledArtifactRecovery(
    downloadActive: Boolean,
    taskStatus: DownloadStatus?
): Boolean {
    if (downloadActive) {
        return true
    }
    return taskStatus == DownloadStatus.QUEUED ||
        taskStatus == DownloadStatus.DOWNLOADING ||
        taskStatus == DownloadStatus.WAITING_NETWORK
}

internal fun buildExpectedDownloadTitles(song: SongItem): Set<String> {
    return linkedSetOf<String>().apply {
        add(song.customName ?: song.name)
        add(song.name)
        song.originalName?.takeIf(String::isNotBlank)?.let(::add)
    }
}

internal fun buildExpectedDownloadArtists(song: SongItem): Set<String> {
    return linkedSetOf<String>().apply {
        add(song.customArtist ?: song.artist)
        add(song.artist)
        song.originalArtist?.takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun normalizeManagedDownloadText(value: String?): String {
    return value
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
}
