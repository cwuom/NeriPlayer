package moe.ouom.neriplayer.core.download.storage.facade

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.storage.operation.content.buildManagedDeletePolicy
import moe.ouom.neriplayer.core.download.storage.operation.content.buildPendingAudioWriteName
import moe.ouom.neriplayer.core.download.storage.operation.content.deletePendingAudioMetadataBlocking
import moe.ouom.neriplayer.core.download.storage.operation.content.deleteReferencesInternalConcurrently
import moe.ouom.neriplayer.core.download.storage.operation.content.demotePublishedTreeAudioToTemporary
import moe.ouom.neriplayer.core.download.storage.operation.content.invalidateSnapshotCache
import moe.ouom.neriplayer.core.download.storage.operation.content.isDurableCoreMetadata
import moe.ouom.neriplayer.core.download.storage.operation.content.isTreePromotionBackupName
import moe.ouom.neriplayer.core.download.storage.operation.content.metadataEntriesForPendingArtifacts
import moe.ouom.neriplayer.core.download.storage.operation.content.normalizeDirectoryUri
import moe.ouom.neriplayer.core.download.storage.operation.content.parseDownloadedAudioMetadataEntriesBatch
import moe.ouom.neriplayer.core.download.storage.operation.content.promotePendingAudio
import moe.ouom.neriplayer.core.download.storage.operation.content.sealAudioPublicationReceipt
import moe.ouom.neriplayer.core.download.storage.operation.content.readTextInternal
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveSnapshotForIndexedLookup
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveTrustedManagedReferences
import moe.ouom.neriplayer.core.download.storage.operation.content.updateSnapshotCacheAfterStoredEntryWrite
import moe.ouom.neriplayer.core.download.storage.operation.content.writeTextThroughBackend
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.cleanupPendingCoreMetadataAfterAudioPromotion
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.cleanupPersistedTerminalTemporaryWriteEntry
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.completeTerminalTemporaryWriteFinalization
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.ensureTerminalTemporaryWriteFinalizationIdentity
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.findMetadataByDirectLookup
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.findMetadataForAudioBlocking
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.findPendingMetadataForAudioBlocking
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.isPendingAudioPromotionFinalNameCandidate
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.isPendingAudioPromotionNameOccupied
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.matchesPendingPromotionIdentity
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.normalizeTerminalTemporaryWriteCleanupTarget
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.prepareTerminalTemporaryWriteFinalization
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.promotePendingCoreMetadata
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.readTemporaryDirectoryEntries
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.recordTerminalTemporaryWriteCleanup
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.recoverPreparedTerminalTemporaryWriteFinalizations
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolvePendingCorePromotionFinalName
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolveTemporaryRoot
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolveTerminalTemporaryWriteCleanupRoot
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.saveMetadataBlocking
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.temporaryWriteOwnerNameForIdentity
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.terminalTemporaryWriteCleanupTargetsForFinalization
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.terminalTemporaryWriteIdentity
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootForOperation
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StartupRecoveryResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.CancelledPendingDownloadOperation
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.FinalizedPendingAudioPromotion
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.SnapshotEntryBucket
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadPendingArtifactCleanupPlanner
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadManagedAudioPolicy
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.recovery.PersistentTerminalTemporaryWriteCleanupJournal
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupFinalizationPreparation
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupJournalSnapshot
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupTarget
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProbeResult
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupResult
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupSkipReason
import moe.ouom.neriplayer.core.download.storage.backend.StorageConfidence
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle

internal fun ManagedDownloadStorage.shouldTreatAudioAsManagedImpl(
    audioName: String,
    metadataAudioNames: Set<String>,
    coverEntryNames: Set<String>,
    lyricEntryNames: Set<String>,
    allowMetadataLessAudio: Boolean
): Boolean {
    return ManagedDownloadManagedAudioPolicy.shouldTreatAudioAsManaged(
        audioName = audioName,
        metadataAudioNames = metadataAudioNames,
        coverEntryNames = coverEntryNames,
        lyricEntryNames = lyricEntryNames,
        allowMetadataLessAudio = allowMetadataLessAudio
    )
}

internal suspend fun ManagedDownloadStorage.findMetadataForAudioImpl(context: Context, audio: StoredEntry): StoredEntry? =
    withContext(Dispatchers.IO) {
    val snapshot = resolveSnapshotForIndexedLookup(context)
        ?: buildDownloadLibrarySnapshotBlocking(context)
    val canonicalAudioName = ManagedDownloadTreeNaming.canonicalLookupName(audio.name)
    val canonicalLogicalName = ManagedDownloadTreeNaming.canonicalLookupName(audio.logicalName)
    snapshot.metadataEntriesByAudioName[audio.logicalName]
        ?: snapshot.metadataEntriesByCanonicalAudioName[canonicalAudioName]
        ?: snapshot.metadataEntriesByCanonicalAudioName[canonicalLogicalName]
        ?: findMetadataByDirectLookup(context, audio)
}

internal suspend fun ManagedDownloadStorage.readDownloadedMetadataFromRootImpl(
    context: Context,
    audio: StoredEntry,
    directoryUri: String?,
    preferPendingMetadata: Boolean = false,
    useDefaultRootWhenDirectoryUriMissing: Boolean = false
): DownloadedAudioMetadata? = withContext(Dispatchers.IO) {
    val root = resolveRootForOperation(
        context = context,
        directoryUri = directoryUri,
        useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
        unavailableMessage = "迁移前读取源 metadata 的目录不可用"
    ) ?: return@withContext null
    val metadataEntry = if (preferPendingMetadata) {
        val pendingLookup = findPendingMetadataForAudioBlocking(
            context = context,
            root = root,
            audio = audio
        )
        if (pendingLookup.entry != null || !pendingLookup.complete) {
            pendingLookup.entry
        } else {
            findMetadataForAudioBlocking(
                context = context,
                audio = audio,
                rootOverride = root
            )
        }
    } else {
        findMetadataForAudioBlocking(
            context = context,
            audio = audio,
            rootOverride = root
        )
    } ?: return@withContext null
    readTextInternal(context, metadataEntry.reference)
        ?.let(::parseDownloadedAudioMetadataJson)
}

internal suspend fun ManagedDownloadStorage.saveMetadataForLegacyUpgradeImpl(
    context: Context,
    audio: StoredEntry,
    json: String,
    expectedAbsent: Boolean,
    knownMetadataEntry: StoredEntry? = null
): Boolean = withContext(Dispatchers.IO) {
    saveMetadataBlocking(
        context = context,
        audio = audio,
        json = json,
        updateSnapshotCache = false,
        expectedAbsent = expectedAbsent,
        knownMetadataEntry = knownMetadataEntry
    )
}

internal suspend fun ManagedDownloadStorage.writePendingAudioMetadataImpl(
    context: Context,
    audioName: String,
    json: String,
    operationId: String? = null
): Boolean = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val root = resolveRootBlocking(appContext)
    val temporaryRoot = resolveTemporaryRoot(
        context = appContext,
        root = root,
        create = true
    ) ?: throw IOException("无法准备下载 .tmp 目录")
    val backendResult = writeTextThroughBackend(
        context = appContext,
        root = temporaryRoot,
        displayName = "$audioName$PENDING_METADATA_SUFFIX",
        content = json,
        temporaryWriteOwnerName = temporaryWriteOwnerNameForOperation(
            displayName = "$audioName$PENDING_METADATA_SUFFIX",
            operationId = operationId
        )
    )
    val written = when (backendResult) {
        is StorageWriteResult.Written -> backendResult.stat.toStoredEntryForBackend(
            fileRoot = (temporaryRoot as? RootHandle.FileRoot)?.dir
        )
        StorageWriteResult.Missing -> {
            NPLogger.w(TAG, "pending metadata target is missing; refusing raw writer fallback")
            null
        }
        else -> {
            NPLogger.w(TAG, "pending metadata typed write failed: $backendResult")
            null
        }
    }
    written != null
}

internal suspend fun ManagedDownloadStorage.promoteFinalizedPendingAudioImpl(
    context: Context,
    audio: StoredEntry
): FinalizedPendingAudioPromotion? = withContext(Dispatchers.IO) {
    val metadataEntry = findMetadataForAudioBlocking(context, audio) ?: return@withContext null
    val rawMetadata = readTextInternal(context, metadataEntry.reference)
        ?: return@withContext null
    val metadata = rawMetadata
        .let(::parseDownloadedAudioMetadataJson)
        ?: return@withContext null
    if (!isFinalizedDownloadedMetadata(metadata)) {
        NPLogger.w(
            TAG,
            "拒绝提升未完成元信息收尾的 pending 音频: ${audio.logicalName}"
        )
        return@withContext null
    }
    val root = resolveRootBlocking(context)
    if (!audio.isPendingAudioWrite) {
        sealAudioPublicationReceipt(context, root, audio)
        val terminalTemporaryWriteTargets =
            terminalTemporaryWriteCleanupTargetsForFinalization(
                pendingAudio = audio,
                metadata = metadata
            )
        val recordedTerminalCleanup = recordTerminalTemporaryWriteCleanup(
            context = context,
            root = root,
            targets = terminalTemporaryWriteTargets
        )
        if (!recordedTerminalCleanup) {
            NPLogger.w(
                TAG,
                "已发布音频未能持久化临时写入清理记录，拒绝重复最终发布: " +
                    "audio=${audio.logicalName}"
            )
            return@withContext null
        }
        return@withContext FinalizedPendingAudioPromotion(
            audio = audio,
            terminalTemporaryWriteCleanupRecorded = true
        )
    }
    val preparedMetadata = ensureTerminalTemporaryWriteFinalizationIdentity(
        context = context,
        root = root,
        metadataEntry = metadataEntry,
        metadata = metadata,
        rawMetadata = rawMetadata
    ) ?: run {
        NPLogger.w(
            TAG,
            "下载最终发布前未能持久化可验证身份，保留 pending 证据: " +
                "audio=${audio.logicalName}"
        )
        return@withContext null
    }
    val terminalTemporaryWriteTargets =
        terminalTemporaryWriteCleanupTargetsForFinalization(
            pendingAudio = audio,
            metadata = preparedMetadata
        )
    val preparation = prepareTerminalTemporaryWriteFinalization(
        context = context,
        root = root,
        pendingAudio = audio,
        metadata = preparedMetadata,
        targets = terminalTemporaryWriteTargets
    ) ?: run {
        NPLogger.w(
            TAG,
            "下载最终发布前未能持久化临时写入准备记录，保留 pending 证据: " +
                "audio=${audio.logicalName}"
        )
        return@withContext null
    }
    val promoted = promotePendingAudio(
        context = context,
        root = root,
        audio = audio
    )
    if (promoted != null && !updateSnapshotCacheAfterStoredEntryWrite(
            context,
            promoted,
            SnapshotEntryBucket.AUDIO
        )
    ) {
        invalidateSnapshotCache(context)
    }
    promoted?.let { finalizedAudio ->
        sealAudioPublicationReceipt(context, root, finalizedAudio)
        val recordedTerminalCleanup = completeTerminalTemporaryWriteFinalization(
            context = context,
            preparation = preparation
        )
        if (!recordedTerminalCleanup) {
            NPLogger.w(
                TAG,
                "下载音频已发布但临时写入清理仍处于准备态，等待恢复重试: " +
                    "audio=${audio.logicalName}"
            )
        }
        FinalizedPendingAudioPromotion(
            audio = finalizedAudio,
            terminalTemporaryWriteCleanupRecorded = recordedTerminalCleanup
        )
    }
}

internal suspend fun ManagedDownloadStorage.promoteCoreCommittedPendingAudioImpl(
    context: Context,
    audio: StoredEntry,
    directoryUri: String? = null,
    promotePendingMetadata: Boolean = false,
    useDefaultRootWhenDirectoryUriMissing: Boolean = false
): StoredEntry? = withContext(Dispatchers.IO) {
    if (!audio.isPendingAudioWrite) return@withContext audio
    val root = resolveRootForOperation(
        context = context,
        directoryUri = directoryUri,
        useDefaultRootWhenDirectoryUriMissing = useDefaultRootWhenDirectoryUriMissing,
        unavailableMessage = "迁移前提升 pending 音频的目录不可用"
    ) ?: return@withContext null
    val metadataEntry = if (promotePendingMetadata) {
        val pendingLookup = findPendingMetadataForAudioBlocking(
            context = context,
            root = root,
            audio = audio
        )
        if (pendingLookup.entry != null || !pendingLookup.complete) {
            pendingLookup.entry
        } else {
            findMetadataForAudioBlocking(
                context = context,
                audio = audio,
                rootOverride = root
            )
        }
    } else {
        findMetadataForAudioBlocking(
            context = context,
            audio = audio,
            rootOverride = root
        )
    } ?: return@withContext null
    val rawMetadata = readTextInternal(context, metadataEntry.reference)
        ?: return@withContext null
    val metadata = rawMetadata
        .let(::parseDownloadedAudioMetadataJson)
        ?: return@withContext null
    if (!isDurableCoreMetadata(metadata)) {
        return@withContext null
    }
    val finalAudioName = if (promotePendingMetadata) {
        resolvePendingCorePromotionFinalName(
            context = context,
            root = root,
            audio = audio,
            metadataEntry = metadataEntry,
            metadata = metadata
        ) ?: run {
            NPLogger.w(
                TAG,
                "迁移前无法安全解析 pending 音频最终名称，保留凭据: " +
                    "audio=${audio.logicalName}"
            )
            return@withContext null
        }
    } else {
        audio.logicalName
    }
    if (
        promotePendingMetadata &&
            !promotePendingCoreMetadata(
                context = context,
                root = root,
                audio = audio,
                metadataEntry = metadataEntry,
                rawMetadata = rawMetadata,
                metadata = metadata,
                finalAudioName = finalAudioName
            )
    ) {
        NPLogger.w(
            TAG,
            "迁移前 core metadata 未能提升到正式名称，保留 pending 凭据: " +
                "audio=${audio.logicalName}"
        )
        return@withContext null
    }
    val promoted = promotePendingAudio(
        context = context,
        root = root,
        audio = audio,
        finalAudioName = finalAudioName
    ) ?: return@withContext null
    if (promoted.isPendingAudioWrite) {
        return@withContext null
    }
    sealAudioPublicationReceipt(context, root, promoted)
    if (promotePendingMetadata) {
        cleanupPendingCoreMetadataAfterAudioPromotion(
            context = context,
            root = root,
            audio = audio,
            metadataEntry = metadataEntry
        )
    }
    if (!updateSnapshotCacheAfterStoredEntryWrite(
            context = context,
            promoted,
            SnapshotEntryBucket.AUDIO
        )
    ) {
        invalidateSnapshotCache(context)
    }
    promoted
}

internal fun ManagedDownloadStorage.resolveStagedPendingPromotionFinalNameImpl(
    requestedName: String,
    stagedMetadata: DownloadedAudioMetadata,
    expectedStableKey: String?,
    expectedOperationId: String?
): String? {
    if (!matchesPendingPromotionIdentity(
            stagedMetadata = stagedMetadata,
            expectedStableKey = expectedStableKey,
            expectedOperationId = expectedOperationId
        )
    ) {
        return null
    }
    return stagedMetadata.audioFileName?.takeIf { candidate ->
        isPendingAudioPromotionFinalNameCandidate(
            requestedName = requestedName,
            candidateName = candidate
        )
    }
}

internal fun ManagedDownloadStorage.resolvePendingAudioPromotionFinalNameImpl(
    enumerationComplete: Boolean,
    existingNames: Collection<String>,
    requestedName: String
): String? {
    if (
        !enumerationComplete ||
            requestedName.isBlank() ||
            requestedName != requestedName.trim() ||
            requestedName == "." ||
            requestedName == ".." ||
            '/' in requestedName ||
            '\\' in requestedName
    ) {
        return null
    }
    if (existingNames.any { actualName ->
            isTreePromotionBackupName(actualName, requestedName)
        }
    ) {
        return null
    }
    val baseName = requestedName.substringBeforeLast('.', requestedName)
    val extension = requestedName.substringAfterLast('.', "")
    for (index in 0 until 10_000) {
        val candidate = when (index) {
            0 -> requestedName
            else -> if (extension.isBlank()) {
                "$baseName ($index)"
            } else {
                "$baseName ($index).$extension"
            }
        }
        if (existingNames.none { actualName ->
                isPendingAudioPromotionNameOccupied(
                    actualName = actualName,
                    candidateName = candidate
                )
            }
        ) {
            return candidate
        }
    }
    return null
}

internal fun ManagedDownloadStorage.rewritePendingMetadataAudioFileNameImpl(
    rawMetadata: String,
    finalAudioName: String
): String? {
    if (
        finalAudioName.isBlank() ||
            finalAudioName != finalAudioName.trim() ||
            finalAudioName == "." ||
            finalAudioName == ".." ||
            '/' in finalAudioName ||
            '\\' in finalAudioName
    ) {
        return null
    }
    return runCatching {
        JSONObject(rawMetadata)
            .put("audioFileName", finalAudioName)
            .toString()
    }.getOrNull()
}

internal suspend fun ManagedDownloadStorage.demotePublishedAudioForFinalizationImpl(
    context: Context,
    audio: StoredEntry,
    expectedMetadataFinalized: Boolean?
): StoredEntry? = withContext(Dispatchers.IO) {
    if (audio.isPendingAudioWrite) {
        return@withContext audio
    }
    val metadataEntry = findMetadataForAudioBlocking(context, audio) ?: return@withContext null
    val metadata = readTextInternal(context, metadataEntry.reference)
        ?.let(::parseDownloadedAudioMetadataJson)
        ?: return@withContext null
    if (metadata.downloadFinalized != expectedMetadataFinalized) {
        NPLogger.d(
            TAG,
            "跳过已变更状态的已发布音频回退: " +
                "file=${audio.name}, expected=$expectedMetadataFinalized, " +
                "actual=${metadata.downloadFinalized}"
        )
        return@withContext null
    }
    val pendingName = buildPendingAudioWriteName(audio.logicalName)
    val demoted = when (val root = resolveRootBlocking(context)) {
        is RootHandle.FileRoot -> {
            val temporaryRoot = resolveTemporaryRoot(
                context = context,
                root = root,
                create = true
            ) as? RootHandle.FileRoot
                ?: throw IOException("无法准备下载 .tmp 目录")
            demotePublishedFileAudio(
                root = root.dir,
                publishedName = audio.name,
                pendingName = pendingName,
                pendingRoot = temporaryRoot.dir
            )?.toStoredEntry()
        }

        is RootHandle.TreeRoot -> {
            val temporaryRoot = resolveTemporaryRoot(
                context = context,
                root = root,
                create = true
            ) as? RootHandle.TreeRoot
                ?: throw IOException("无法准备下载 .tmp 目录")
            demotePublishedTreeAudioToTemporary(
                context = context,
                root = root,
                audio = audio,
                pendingName = pendingName,
                temporaryRoot = temporaryRoot
            )
        }
    }
    if (demoted != null) {
        // pending 项不能增量写入可见目录快照，强制下次读取重新枚举
        invalidateSnapshotCache(context)
    }
    demoted
}

internal suspend fun ManagedDownloadStorage.deletePendingAudioMetadataImpl(
    context: Context,
    audioName: String
): Boolean = withContext(Dispatchers.IO) {
    val normalizedName = audioName.trim().takeIf(String::isNotBlank)
        ?: return@withContext false
    val root = resolveRootBlocking(context)
    deletePendingAudioMetadataBlocking(context, root, normalizedName)
}

internal suspend fun ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifactsImpl(
    context: Context,
    operations: Collection<CancelledPendingDownloadOperation>,
    onProgress: (completedItems: Int, totalItems: Int) -> Unit = { _, _ -> },
    directoryUri: String? = null,
    useDefaultRootWhenDirectoryUriMissing: Boolean = false
): StartupRecoveryResult = withContext(Dispatchers.IO) {
    fun reportProgress(completedItems: Int, totalItems: Int) {
        runCatching {
            onProgress(
                completedItems.coerceAtLeast(0),
                totalItems.coerceAtLeast(0)
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "取消清理进度回调失败，继续执行清理: ${error.message}"
            )
        }
    }
    val normalizedOperations = operations.mapNotNull { operation ->
        val stableKey = operation.stableKey.trim().takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val operationId = operation.operationId.trim().takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        CancelledPendingDownloadOperation(stableKey, operationId)
    }.distinct()
    if (normalizedOperations.isEmpty()) {
        return@withContext StartupRecoveryResult()
    }
    val normalizedDirectoryUri = directoryUri
        ?.trim()
        ?.takeIf(String::isNotBlank)
    try {
        val root = resolveRootForOperation(
            context = context,
            directoryUri = normalizedDirectoryUri,
            useDefaultRootWhenDirectoryUriMissing =
                useDefaultRootWhenDirectoryUriMissing,
            unavailableMessage = "取消清理源目录不可用，保留 pending 凭据"
        ) ?: return@withContext StartupRecoveryResult(
            failedCount = normalizedOperations.size
        )
        // 清空是破坏性操作，不能依赖可能遗漏刚写入 pending 的旧缓存
        val refresh = treeDirectories.refreshRootEntries(context, root)
        if (!refresh.isComplete) {
            NPLogger.w(
                TAG,
                "取消清理跳过不完整下载目录枚举: operations=${normalizedOperations.size}"
            )
            return@withContext StartupRecoveryResult(
                failedCount = normalizedOperations.size
            )
        }
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = true,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete) {
            NPLogger.w(
                TAG,
                "取消清理跳过不完整 .tmp 目录枚举: operations=${normalizedOperations.size}"
            )
            return@withContext StartupRecoveryResult(
                failedCount = normalizedOperations.size
            )
        }
        val rootEntries = (refresh.entries + temporary.entries)
            .filterNot(StoredEntry::isDirectory)
        // 先读取正式和待提交元数据。核心提交后的种子元数据已经在根目录
        // 而待提交音频仍在 .tmp，只读待提交元数据
        // 会把这对可恢复音频误判为普通取消残留
        val metadataEntriesToParse = metadataEntriesForPendingArtifacts(rootEntries)
        val parsedMetadataEntries = parseDownloadedAudioMetadataEntriesBatch(
            context = context,
            entries = metadataEntriesToParse
        ).mapNotNull { (entry, metadata) ->
            metadata?.let { value -> ManagedDownloadParsedMetadataEntry(entry, value) }
        }
        val cleanupPlans = normalizedOperations.map { operation ->
            operation to ManagedDownloadPendingArtifactCleanupPlanner.planCancelledOperation(
                rootEntries = rootEntries,
                parsedMetadataEntries = parsedMetadataEntries,
                stableKey = operation.stableKey,
                operationId = operation.operationId
            )
        }
        val referencesToDelete = cleanupPlans.flatMapTo(linkedSetOf<String>()) { (_, plan) ->
            plan.referencesToDelete
        }
        val protectedPendingReferences = cleanupPlans.flatMapTo(linkedSetOf<String>()) { (_, plan) ->
            plan.protectedReferences
        }
        val deleteReferencesByStableKey = cleanupPlans
            .groupBy({ (operation, _) -> operation.stableKey }, { (_, plan) -> plan })
            .mapValues { (_, plans) ->
                plans.flatMapTo(linkedSetOf()) { plan -> plan.referencesToDelete }
            }
        val isPendingArtifact: (StoredEntry) -> Boolean = { entry ->
            entry.isPendingAudioWrite ||
                entry.name.contains(PENDING_AUDIO_WRITE_MARKER) ||
                entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true) ||
                ManagedDownloadTreeNaming.isPendingMetadataName(
                    entry.name,
                    ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                        ?: ""
                )
        }
        val protectedPendingEntryCount = rootEntries
            .asSequence()
            .filter(isPendingArtifact)
            .count { entry -> entry.reference in protectedPendingReferences }
        if (referencesToDelete.isEmpty()) {
            val unresolvedPendingEntries = rootEntries
                .filter(isPendingArtifact)
                .filterNot { entry -> entry.reference in protectedPendingReferences }
            if (unresolvedPendingEntries.isNotEmpty()) {
                NPLogger.w(
                    TAG,
                    "取消清理发现无法证明归属的 pending，保留证据并等待恢复: " +
                        "operations=${normalizedOperations.size}, " +
                        "entries=${unresolvedPendingEntries.size}"
                )
                reportProgress(0, unresolvedPendingEntries.size)
                return@withContext StartupRecoveryResult(
                    failedCount = unresolvedPendingEntries.size,
                    protectedCount = protectedPendingEntryCount,
                    protectedReferences = protectedPendingReferences
                )
            }
            reportProgress(0, 0)
            return@withContext StartupRecoveryResult(
                protectedCount = protectedPendingEntryCount,
                protectedReferences = protectedPendingReferences
            )
        }
        reportProgress(0, referencesToDelete.size)
        val entriesToDelete = rootEntries.filter { entry ->
            entry.reference in referencesToDelete
        }
        val deletePolicy = buildManagedDeletePolicy(
            context = context,
            allowedRoot = root,
            trustedReferences = referencesToDelete
        )
        val trustedReferences = resolveTrustedManagedReferences(
            references = referencesToDelete,
            deletePolicy = deletePolicy
        )
        val completedReferences = AtomicInteger(0)
        val pendingAudioEntries = entriesToDelete.filter(StoredEntry::isPendingAudioWrite)
        val pendingAudioReferences = pendingAudioEntries
            .mapTo(linkedSetOf(), StoredEntry::reference)
        val pendingMetadataEntries = entriesToDelete.filter { entry ->
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            audioName != null && ManagedDownloadTreeNaming.isPendingMetadataName(
                actualName = entry.name,
                audioName = audioName
            )
        }
        val pendingMetadataReferences = pendingMetadataEntries
            .mapTo(linkedSetOf(), StoredEntry::reference)
        val pendingAudioByLogicalName = rootEntries
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .filter { entry ->
                entry.isPendingAudioWrite ||
                    entry.name.contains(PENDING_AUDIO_WRITE_MARKER)
            }
            .flatMap { entry ->
                linkedSetOf<String>().apply {
                    if (entry.isPendingAudioWrite) {
                        add(entry.logicalName)
                    }
                    val markerIndex = entry.name.lastIndexOf(PENDING_AUDIO_WRITE_MARKER)
                    if (markerIndex > 0) {
                        add(entry.name.substring(0, markerIndex))
                    }
                }.map { logicalName -> logicalName to entry }
            }
            .groupBy(
                keySelector = { (logicalName, _) -> logicalName },
                valueTransform = { (_, entry) -> entry }
            )
        val metadataIdentityByReference = parsedMetadataEntries
            .filter { parsed -> parsed.entry.reference in pendingMetadataReferences }
            .associate { parsed ->
                parsed.entry.reference to terminalTemporaryWriteIdentity(parsed.metadata)
            }

        fun recordTerminalCleanupFor(entries: Collection<StoredEntry>): Boolean {
            val targets = terminalTemporaryWriteCleanupTargets(
                entries = entries,
                temporaryWriteIdentityByMetadataReference = metadataIdentityByReference
            )
            if (targets.isEmpty()) return true
            val recorded = recordTerminalTemporaryWriteCleanup(
                context = context,
                root = root,
                targets = targets
            )
            if (!recorded) {
                NPLogger.w(
                    TAG,
                    "取消下载未能持久化临时写入清理记录，保留 pending 证据: " +
                        "targets=${targets.size}"
                )
            }
            return recorded
        }

        if (!recordTerminalCleanupFor(pendingAudioEntries)) {
            return@withContext StartupRecoveryResult(
                failedCount = pendingAudioReferences.size
            )
        }
        val trustedAudioReferences = trustedReferences.filter { reference ->
            reference.externalReference in pendingAudioReferences
        }
        val deletedAudioReferences = deleteReferencesInternalConcurrently(
            context = context,
            references = trustedAudioReferences,
            deletePolicy = deletePolicy,
            invalidateSnapshot = true,
            onDeleteAttemptFinished = { _, _ ->
                reportProgress(
                    completedItems = completedReferences.incrementAndGet(),
                    totalItems = referencesToDelete.size
                )
            }
        )
        val unresolvedAudioReferences = pendingAudioReferences - deletedAudioReferences
        if (unresolvedAudioReferences.isNotEmpty()) {
            NPLogger.w(
                TAG,
                "取消清理 pending 音频未确认删除，延后 metadata: " +
                    "pending=${pendingAudioReferences.size}, " +
                    "deleted=${deletedAudioReferences.size}, " +
                    "unresolved=${unresolvedAudioReferences.size}"
            )
        }
        val metadataEntriesReadyForDeletion = pendingMetadataEntries.filter { entry ->
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                ?: return@filter false
            pendingAudioByLogicalName[audioName].orEmpty().all { audio ->
                audio.reference in deletedAudioReferences
            }
        }
        val metadataReferencesReadyForDeletion = metadataEntriesReadyForDeletion
            .mapTo(hashSetOf(), StoredEntry::reference)
        val deferredMetadataReferences = pendingMetadataReferences
            .asSequence()
            .filterNot { reference -> reference in metadataReferencesReadyForDeletion }
            .toSet()
        val metadataTargetsRecorded = recordTerminalCleanupFor(
            metadataEntriesReadyForDeletion
        )
        if (!metadataTargetsRecorded) {
            return@withContext StartupRecoveryResult(
                failedCount = deferredMetadataReferences.size +
                    metadataEntriesReadyForDeletion.size
            )
        }
        val trustedMetadataReferences = trustedReferences.filter { reference ->
            reference.externalReference in metadataReferencesReadyForDeletion
        }
        val deletedMetadataReferences = deleteReferencesInternalConcurrently(
            context = context,
            references = trustedMetadataReferences,
            deletePolicy = deletePolicy,
            invalidateSnapshot = true,
            onDeleteAttemptFinished = { _, _ ->
                reportProgress(
                    completedItems = completedReferences.incrementAndGet(),
                    totalItems = referencesToDelete.size
                )
            }
        )
        val unresolvedMetadataReferences = pendingMetadataReferences -
            deletedMetadataReferences
        if (unresolvedMetadataReferences.isNotEmpty()) {
            NPLogger.w(
                TAG,
                "取消清理 pending metadata 未完全删除，保留凭据: " +
                    "pending=${pendingMetadataReferences.size}, " +
                    "deleted=${deletedMetadataReferences.size}, " +
                    "unresolved=${unresolvedMetadataReferences.size}"
            )
        }
        val deletedReferences = deletedAudioReferences + deletedMetadataReferences
        val temporaryCleanup = when {
            pendingAudioEntries.isEmpty() && metadataEntriesReadyForDeletion.isEmpty() ->
                StartupRecoveryResult()
            else -> cleanupPersistedTerminalTemporaryWriteArtifacts(context)
        }
        reportProgress(referencesToDelete.size, referencesToDelete.size)
        val failedReferences = (referencesToDelete - deletedReferences) +
            deferredMetadataReferences
        val failedStableKeys = resolveFailedStableKeys(
            referencesByStableKey = deleteReferencesByStableKey,
            failedReferences = failedReferences
        )
        val failedCount = failedReferences.size + temporaryCleanup.failedCount
        NPLogger.d(
            TAG,
            "取消下载 pending 半成品清理完成: operations=${normalizedOperations.size}, " +
                "cleaned=${deletedReferences.size + temporaryCleanup.cleanedCount}, " +
                "failed=$failedCount, protected=$protectedPendingEntryCount"
        )
        StartupRecoveryResult(
            cleanedCount = deletedReferences.size + temporaryCleanup.cleanedCount,
            failedCount = failedCount,
            externalSignalRequiredCount = temporaryCleanup.externalSignalRequiredCount,
            protectedCount = protectedPendingEntryCount,
            protectedReferences = protectedPendingReferences,
            failedStableKeys = failedStableKeys
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: SecurityException) {
        NPLogger.w(
            TAG,
            "取消下载 pending 半成品清理缺少权限，保留等待恢复: " +
                "operations=${normalizedOperations.size}, error=${error.message}"
        )
        StartupRecoveryResult(failedCount = normalizedOperations.size)
    } catch (error: ManagedDownloadRootUnavailableException) {
        NPLogger.w(
            TAG,
            "取消下载 pending 半成品清理缺少目录，保留等待恢复: " +
                "operations=${normalizedOperations.size}, error=${error.message}"
        )
        StartupRecoveryResult(failedCount = normalizedOperations.size)
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "取消下载 pending 半成品清理失败，保留等待恢复: " +
                "operations=${normalizedOperations.size}, error=${error.message}",
            error
        )
        StartupRecoveryResult(failedCount = normalizedOperations.size)
    }
}

internal suspend fun ManagedDownloadStorage.cleanupUnownedPendingDownloadArtifactsForClearImpl(
    context: Context,
    protectedReferences: Set<String> = emptySet(),
    onProgress: (completedItems: Int, totalItems: Int) -> Unit = { _, _ -> }
): StartupRecoveryResult = withContext(Dispatchers.IO) {
    fun reportProgress(completedItems: Int, totalItems: Int) {
        try {
            onProgress(
                completedItems.coerceAtLeast(0),
                totalItems.coerceAtLeast(0)
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(TAG, "孤儿 pending 清理进度回调失败: ${error.message}")
        }
    }

    val root = try {
        resolveRootBlocking(context)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        NPLogger.w(TAG, "清空时无法解析下载根目录，保留临时文件: ${error.message}", error)
        return@withContext StartupRecoveryResult(failedCount = 1)
    }
    val refresh = try {
        treeDirectories.refreshRootEntries(context, root)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        NPLogger.w(TAG, "清空时无法枚举下载根目录，保留临时文件: ${error.message}", error)
        return@withContext StartupRecoveryResult(failedCount = 1)
    }
    if (!refresh.isComplete) {
        NPLogger.w(TAG, "清空时根目录枚举不完整，保留 pending 证据")
        return@withContext StartupRecoveryResult(failedCount = 1)
    }
    val temporary = readTemporaryDirectoryEntries(
        context = context,
        root = root,
        forceRefresh = true,
        rootAlreadyRefreshed = true
    )
    if (!temporary.isComplete) {
        NPLogger.w(TAG, "清空时 .tmp 枚举不完整，保留 pending 证据")
        return@withContext StartupRecoveryResult(failedCount = 1)
    }

    val normalizedProtected = protectedReferences
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    val temporaryReferences = temporary.entries
        .asSequence()
        .filterNot(StoredEntry::isDirectory)
        .mapTo(linkedSetOf(), StoredEntry::reference)
    val allEntries = (refresh.entries + temporary.entries)
        .filterNot(StoredEntry::isDirectory)
        .distinctBy(StoredEntry::reference)
    val isPendingArtifact: (StoredEntry) -> Boolean = { entry ->
        entry.isPendingAudioWrite ||
            entry.name.contains(PENDING_AUDIO_WRITE_MARKER) ||
            entry.name.contains(PENDING_METADATA_SUFFIX, ignoreCase = true) ||
            ManagedDownloadTreeNaming.isPendingMetadataName(
                actualName = entry.name,
                audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name) ?: ""
            )
    }
    val pendingEntries = allEntries.filter(isPendingArtifact)
    if (pendingEntries.isEmpty()) {
        return@withContext StartupRecoveryResult()
    }
    val parsedMetadataByReference = linkedMapOf<String, DownloadedAudioMetadata>()
    val unreadableMetadataReferences = linkedSetOf<String>()
    val metadataEntriesToParse = metadataEntriesForPendingArtifacts(allEntries)
    parseDownloadedAudioMetadataEntriesBatch(
        context = context,
        entries = metadataEntriesToParse
    ).forEach { (entry, metadata) ->
        if (metadata == null) {
            unreadableMetadataReferences += entry.reference
        } else {
            parsedMetadataByReference[entry.reference] = metadata
        }
    }
    val entryByReference = allEntries.associateBy(StoredEntry::reference)
    val orphanPlan = ManagedDownloadPendingArtifactCleanupPlanner
        .planUnownedForExplicitClear(
            entries = allEntries,
            temporaryReferences = temporaryReferences,
            parsedMetadataEntries = parsedMetadataByReference.mapNotNull {
                (reference, metadata) ->
                entryByReference[reference]?.let { entry ->
                    ManagedDownloadParsedMetadataEntry(entry, metadata)
                }
            },
            unreadableMetadataReferences = unreadableMetadataReferences,
            protectedReferences = normalizedProtected
        )
    val protectedPendingReferences = orphanPlan.protectedReferences
    val referencesToDelete = orphanPlan.referencesToDelete

    val unresolvedCount = pendingEntries.count { entry ->
        entry.reference !in protectedPendingReferences &&
            entry.reference !in referencesToDelete
    }
    val deletePolicy = buildManagedDeletePolicy(
        context = context,
        allowedRoot = root,
        trustedReferences = referencesToDelete
    )
    val trustedReferences = resolveTrustedManagedReferences(
        references = referencesToDelete,
        deletePolicy = deletePolicy
    )
    reportProgress(0, trustedReferences.size + unresolvedCount)
    val completed = AtomicInteger(0)
    val deletedReferences = deleteReferencesInternalConcurrently(
        context = context,
        references = trustedReferences,
        deletePolicy = deletePolicy,
        invalidateSnapshot = true,
        onDeleteAttemptFinished = { _, _ ->
            reportProgress(
                completedItems = completed.incrementAndGet(),
                totalItems = trustedReferences.size + unresolvedCount
            )
        }
    )
    val failedCount = (trustedReferences.size - deletedReferences.size) + unresolvedCount
    reportProgress(
        completedItems = trustedReferences.size + unresolvedCount,
        totalItems = trustedReferences.size + unresolvedCount
    )
    NPLogger.d(
        TAG,
        "清空孤儿 pending 临时文件收敛完成: pending=${pendingEntries.size}, " +
            "deleted=${deletedReferences.size}, protected=${protectedPendingReferences.size}, " +
            "blocked=${pendingEntries.count { it.reference in unreadableMetadataReferences }}, " +
            "unresolved=$unresolvedCount, failed=$failedCount"
    )
    StartupRecoveryResult(
        cleanedCount = deletedReferences.size,
        failedCount = failedCount,
        protectedCount = pendingEntries.count { it.reference in protectedPendingReferences },
        protectedReferences = protectedPendingReferences
    )
}

internal suspend fun ManagedDownloadStorage.cleanupPersistedTerminalTemporaryWriteArtifactsImpl(
    context: Context
): StartupRecoveryResult = withContext(Dispatchers.IO) {
    val preparationRecovery = recoverPreparedTerminalTemporaryWriteFinalizations(context)
    val terminalRecovery = when (
        val snapshot = PersistentTerminalTemporaryWriteCleanupJournal.snapshot(context)
    ) {
        is TerminalTemporaryWriteCleanupJournalSnapshot.Unavailable -> {
            NPLogger.w(
                TAG,
                "终态临时写入清理记录不可读取，保留等待恢复: ${snapshot.reason}"
            )
            StartupRecoveryResult(failedCount = 1)
        }

        is TerminalTemporaryWriteCleanupJournalSnapshot.Available -> {
            var cleanedCount = 0
            var failedCount = 0
            var externalSignalRequiredCount = 0
            snapshot.entries.forEach { entry ->
                val root = resolveTerminalTemporaryWriteCleanupRoot(context, entry)
                if (root == null) {
                    failedCount += entry.targetNames.size
                    externalSignalRequiredCount += entry.targetNames.size
                    NPLogger.w(
                        TAG,
                        "终态临时写入清理目录不可恢复，保留等待恢复: " +
                            "root=${entry.root.identity}, targets=${entry.targetNames.size}"
                    )
                    return@forEach
                }
                val recovery = cleanupPersistedTerminalTemporaryWriteEntry(
                    context = context,
                    entry = entry,
                    root = root
                )
                cleanedCount += recovery.cleanedCount
                failedCount += recovery.failedCount
                externalSignalRequiredCount += recovery.externalSignalRequiredCount
            }
            StartupRecoveryResult(
                cleanedCount = cleanedCount,
                failedCount = failedCount,
                externalSignalRequiredCount = externalSignalRequiredCount
            )
        }
    }
    StartupRecoveryResult(
        cleanedCount = preparationRecovery.cleanedCount + terminalRecovery.cleanedCount,
        failedCount = preparationRecovery.failedCount + terminalRecovery.failedCount,
        externalSignalRequiredCount =
            preparationRecovery.externalSignalRequiredCount +
                terminalRecovery.externalSignalRequiredCount
    )
}

internal fun ManagedDownloadStorage.matchesTerminalTemporaryWriteFinalizationIdentityImpl(
    metadata: DownloadedAudioMetadata,
    preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
): Boolean {
    val expectedOperationId = preparation.expectedOperationId
    val expectedFinalizationToken = preparation.expectedFinalizationToken
    return (
        expectedOperationId != null && metadata.operationId == expectedOperationId
    ) || (
        expectedFinalizationToken != null &&
            metadata.terminalTemporaryWriteCleanupToken == expectedFinalizationToken
    )
}

internal fun ManagedDownloadStorage.terminalTemporaryWriteCleanupTargetsImpl(
    entries: Collection<StoredEntry>,
    temporaryWriteIdentityByMetadataReference: Map<String, String?> = emptyMap()
): List<TerminalTemporaryWriteCleanupTarget> {
    return entries.flatMap { entry ->
        when {
            entry.isPendingAudioWrite -> {
                listOf(
                    TerminalTemporaryWriteCleanupTarget(displayName = entry.name),
                    TerminalTemporaryWriteCleanupTarget(displayName = entry.logicalName)
                )
            }

            else -> {
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                if (
                    audioName != null &&
                    ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, audioName)
                ) {
                    listOfNotNull(
                        TerminalTemporaryWriteCleanupTarget(displayName = audioName),
                        TerminalTemporaryWriteCleanupTarget(displayName = entry.name),
                        temporaryWriteOwnerNameForIdentity(
                            displayName = entry.name,
                            identity = temporaryWriteIdentityByMetadataReference[entry.reference]
                        )?.let { temporaryWriteOwnerName ->
                            TerminalTemporaryWriteCleanupTarget(
                                displayName = entry.name,
                                temporaryWriteOwnerName = temporaryWriteOwnerName
                            )
                        }
                    )
                } else {
                    emptyList()
                }
            }
        }
    }.mapNotNull(::normalizeTerminalTemporaryWriteCleanupTarget)
        .distinct()
}

internal fun ManagedDownloadStorage.terminalTemporaryWriteCleanupFailureCountImpl(
    result: ManagedTemporaryWriteCleanupResult,
    targetCount: Int
): Int {
    val normalizedTargetCount = targetCount.coerceAtLeast(1)
    return when (result) {
        is ManagedTemporaryWriteCleanupResult.Completed -> {
            result.failures.size + result.retainedActiveCount
        }

        is ManagedTemporaryWriteCleanupResult.Skipped -> normalizedTargetCount
    }
}

internal fun ManagedDownloadStorage.terminalTemporaryWriteCleanupExternalSignalRequiredCountImpl(
    result: ManagedTemporaryWriteCleanupResult,
    targetCount: Int
): Int {
    val normalizedTargetCount = targetCount.coerceAtLeast(1)
    return when (result) {
        is ManagedTemporaryWriteCleanupResult.Completed -> {
            result.failures.count { failure ->
                failure == StorageMutationResult.PermissionLost ||
                    failure == StorageMutationResult.OutOfScope
            }
        }

        is ManagedTemporaryWriteCleanupResult.Skipped -> when (val reason = result.reason) {
            is ManagedTemporaryWriteCleanupSkipReason.IncompleteDirectory -> when (
                reason.confidence
            ) {
                StorageConfidence.PermissionLost,
                StorageConfidence.OutOfScope -> normalizedTargetCount

                StorageConfidence.Complete,
                StorageConfidence.Missing,
                is StorageConfidence.ProviderFailure -> 0
            }

            ManagedTemporaryWriteCleanupSkipReason.TargetParentMismatch -> 0
        }
    }
}

internal fun ManagedDownloadStorage.pendingMetadataEntryNamesImpl(
    audioName: String,
    candidateNames: Collection<String>
): List<String> {
    return candidateNames
        .filter { name -> ManagedDownloadTreeNaming.isPendingMetadataName(name, audioName) }
        .distinct()
        .sorted()
}

internal fun ManagedDownloadStorage.resolveUsesDocumentTreeSafelyImpl(
    configuredDirectoryUri: String?,
    resolveRoot: () -> Boolean
): Boolean {
    if (configuredDirectoryUri.isNullOrBlank()) {
        return false
    }
    return try {
        resolveRoot()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ManagedDownloadRootProviderException) {
        NPLogger.w(
            TAG,
            "检查 SAF 下载目录时 provider 暂时不可用，保留 SAF 写入模式: " +
                "${error.message}"
        )
        true
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "检查配置 SAF 下载目录失败，保留 SAF 写入模式: " +
                "${error.javaClass.simpleName}: ${error.message}"
        )
        true
    }
}

internal suspend fun ManagedDownloadStorage.probeStorageRootImpl(
    context: Context
): ManagedDownloadRootProbeResult = withContext(Dispatchers.IO) {
    try {
        val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
        if (configuredUri.isNullOrBlank()) {
            ManagedDownloadRootProbeResult.Accessible
        } else {
            rootResolver.probeTreeRoot(context, configuredUri)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: ManagedDownloadRootProviderException) {
        ManagedDownloadRootProbeResult.ProviderFailure(error)
    } catch (error: Exception) {
        ManagedDownloadRootProbeResult.ProviderFailure(
            ManagedDownloadRootProviderException(
                reference = "configured-root",
                cause = error
            )
        )
    }
}
