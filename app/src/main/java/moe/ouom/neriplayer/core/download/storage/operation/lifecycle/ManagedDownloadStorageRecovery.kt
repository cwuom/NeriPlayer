package moe.ouom.neriplayer.core.download.storage.operation.lifecycle

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.storage.operation.content.invalidateSnapshotCache
import moe.ouom.neriplayer.core.download.storage.operation.content.parseDownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.storage.operation.content.preserveAudioPublicationReceipt
import moe.ouom.neriplayer.core.download.storage.operation.content.readAudioPublicationMetadata
import moe.ouom.neriplayer.core.download.storage.operation.content.readTextInternal
import moe.ouom.neriplayer.core.download.storage.operation.content.updateSnapshotCacheAfterMetadataWrite
import moe.ouom.neriplayer.core.download.storage.operation.content.writeRootText
import moe.ouom.neriplayer.core.download.storage.operation.content.writeTextThroughBackend
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StartupRecoveryResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.recovery.PersistentTerminalTemporaryWriteCleanupJournal
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupConsumeResult
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupFinalizationPreparation
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupJournalEntry
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupPreparationSnapshot
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupRoot
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupRootType
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupTarget
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.backend.cleanupTerminalTemporaryWrites
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.displayName
import java.io.File
import java.util.UUID
import org.json.JSONObject
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal suspend fun ManagedDownloadStorage.cleanupPersistedTerminalTemporaryWriteEntry(
    context: Context,
    entry: TerminalTemporaryWriteCleanupJournalEntry,
    root: RootHandle
): StartupRecoveryResult {
    var cleanedCount = 0
    var failedCount = 0
    var externalSignalRequiredCount = 0
    var currentEntry = entry
    var currentRoot = root

    fun aggregate(extraFailedCount: Int = 0): StartupRecoveryResult {
        return StartupRecoveryResult(
            cleanedCount = cleanedCount,
            failedCount = failedCount + extraFailedCount,
            externalSignalRequiredCount = externalSignalRequiredCount
        )
    }

    for (attempt in 0 until TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS) {
        val recovery = try {
            cleanupTerminalTemporaryWriteArtifactsBlocking(
                context = context,
                root = currentRoot,
                targets = currentEntry.targets
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: SecurityException) {
            NPLogger.w(
                TAG,
                "终态临时写入清理缺少权限，保留等待恢复: " +
                    "root=${currentEntry.root.identity}, " +
                    "targets=${currentEntry.targetNames.size}, error=${error.message}"
            )
            StartupRecoveryResult(
                failedCount = currentEntry.targetNames.size,
                externalSignalRequiredCount = currentEntry.targetNames.size
            )
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "终态临时写入清理失败，保留等待恢复: " +
                    "root=${currentEntry.root.identity}, " +
                    "targets=${currentEntry.targetNames.size}, error=${error.message}",
                error
            )
            StartupRecoveryResult(failedCount = currentEntry.targetNames.size)
        }
        cleanedCount += recovery.cleanedCount
        failedCount += recovery.failedCount
        externalSignalRequiredCount += recovery.externalSignalRequiredCount
        if (recovery.failedCount > 0) {
            return aggregate()
        }

        when (
            PersistentTerminalTemporaryWriteCleanupJournal.consumeWithResult(
                context = context,
                entry = currentEntry
            )
        ) {
            TerminalTemporaryWriteCleanupConsumeResult.CONSUMED -> return aggregate()
            TerminalTemporaryWriteCleanupConsumeResult.REFRESHED_TARGETS_RETAINED -> {
                NPLogger.d(
                    TAG,
                    "终态临时写入旧代次已清理，新代次记录留给后续任务: " +
                        "root=${currentEntry.root.identity}, " +
                        "targets=${currentEntry.targetNames.size}"
                )
                return aggregate()
            }
            TerminalTemporaryWriteCleanupConsumeResult.FAILED -> Unit
        }

        // 当前记录缺失通常表示另一个清理 Worker 已经消费它，目标集合变化或日志不可读
        // 仍属于失败，必须留给下一轮恢复
        val rebasedEntry =
            PersistentTerminalTemporaryWriteCleanupJournal.currentEntryIfTargetsMatch(
                context = context,
                entry = currentEntry
            )
        if (rebasedEntry == null) {
            if (PersistentTerminalTemporaryWriteCleanupJournal.consume(context, currentEntry)) {
                return aggregate()
            }
            NPLogger.w(
                TAG,
                "终态临时写入清理已完成但记录未确认消费，保留等待恢复: " +
                    "root=${currentEntry.root.identity}, " +
                    "targets=${currentEntry.targetNames.size}, " +
                    "attempt=${attempt + 1}/" +
                    TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS
            )
            return aggregate(currentEntry.targetNames.size)
        }
        if (rebasedEntry.generationId == currentEntry.generationId) {
            // 目录代次没有变化时再次列举 SAF 不能提高比较安全性，保留记录等待持久重试
            NPLogger.w(
                TAG,
                "终态临时写入清理记录消费写入失败，保留等待恢复: " +
                    "root=${currentEntry.root.identity}, " +
                    "targets=${currentEntry.targetNames.size}"
            )
            return aggregate(currentEntry.targetNames.size)
        }
        if (attempt + 1 >= TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS) {
            NPLogger.w(
                TAG,
                "终态临时写入清理代际持续变化，保留等待恢复: " +
                    "root=${currentEntry.root.identity}, " +
                    "targets=${currentEntry.targetNames.size}, " +
                    "attempts=${attempt + 1}"
            )
            return aggregate(currentEntry.targetNames.size)
        }
        val rebasedRoot = resolveTerminalTemporaryWriteCleanupRoot(context, rebasedEntry)
        if (rebasedRoot == null) {
            NPLogger.w(
                TAG,
                "终态临时写入清理代际已刷新但目录不可恢复，保留等待恢复: " +
                    "root=${rebasedEntry.root.identity}, " +
                    "targets=${rebasedEntry.targetNames.size}"
            )
            return aggregate(rebasedEntry.targetNames.size)
        }
        NPLogger.d(
            TAG,
            "终态临时写入清理检测到同目标代际刷新，重新验证后消费: " +
                "root=${rebasedEntry.root.identity}, " +
                "targets=${rebasedEntry.targetNames.size}, " +
                "attempt=${attempt + 2}/" +
                TERMINAL_TEMPORARY_WRITE_CLEANUP_MAX_REBASE_ATTEMPTS
        )
        currentEntry = rebasedEntry
        currentRoot = rebasedRoot
    }
    return aggregate(entry.targetNames.size)
}

internal fun ManagedDownloadStorage.recordTerminalTemporaryWriteCleanup(
    context: Context,
    root: RootHandle,
    targets: Collection<TerminalTemporaryWriteCleanupTarget>
): Boolean {
    return PersistentTerminalTemporaryWriteCleanupJournal.enqueueTargets(
        context = context,
        root = terminalTemporaryWriteCleanupJournalRoot(root),
        targets = targets
    )
}

internal fun ManagedDownloadStorage.prepareTerminalTemporaryWriteFinalization(
    context: Context,
    root: RootHandle,
    pendingAudio: StoredEntry,
    metadata: DownloadedAudioMetadata,
    targets: Collection<TerminalTemporaryWriteCleanupTarget>
): TerminalTemporaryWriteCleanupFinalizationPreparation? {
    return PersistentTerminalTemporaryWriteCleanupJournal.prepareFinalizationTargets(
        context = context,
        root = terminalTemporaryWriteCleanupJournalRoot(root),
        pendingAudioName = pendingAudio.name,
        finalAudioName = pendingAudio.logicalName,
        expectedOperationId = metadata.operationId,
        targets = targets,
        expectedFinalizationToken = metadata.terminalTemporaryWriteCleanupToken
    )
}

internal suspend fun ManagedDownloadStorage.ensureTerminalTemporaryWriteFinalizationIdentity(
    context: Context,
    root: RootHandle,
    metadataEntry: StoredEntry,
    metadata: DownloadedAudioMetadata,
    rawMetadata: String
): DownloadedAudioMetadata? {
    if (
        metadata.operationId?.trim()?.isNotEmpty() == true ||
            metadata.terminalTemporaryWriteCleanupToken?.trim()?.isNotEmpty() == true
    ) {
        return metadata
    }
    val token = UUID.randomUUID().toString()
    val json = runCatching {
        JSONObject(rawMetadata)
            .put("terminalTemporaryWriteCleanupToken", token)
            .toString()
    }.getOrNull() ?: return null
    val updatedMetadata = parseDownloadedAudioMetadataJson(json) ?: return null
    val backendResult = writeTextThroughBackend(
        context = context,
        root = root,
        displayName = metadataEntry.name,
        content = json,
        temporaryWriteOwnerName = temporaryWriteOwnerNameForIdentity(
            displayName = metadataEntry.name,
            identity = updatedMetadata.terminalTemporaryWriteCleanupToken
        )
    )
    val writtenMetadata = when (backendResult) {
        is StorageWriteResult.Written -> backendResult.stat.toStoredEntryForBackend(
            fileRoot = (root as? RootHandle.FileRoot)?.dir
        )

        else -> null
    }
    if (writtenMetadata == null) {
        invalidateSnapshotCache(context)
        return null
    }
    val storedMetadata = readTextInternal(context, writtenMetadata.reference)
        ?.let(::parseDownloadedAudioMetadataJson)
    if (!isMetadataWriteVerified(expected = updatedMetadata, actual = storedMetadata)) {
        invalidateSnapshotCache(context)
        NPLogger.w(
            TAG,
            "最终发布身份写入读回校验失败，保留 pending 证据: ${metadataEntry.name}"
        )
        return null
    }
    invalidateSnapshotCache(context)
    return updatedMetadata
}

internal fun ManagedDownloadStorage.completeTerminalTemporaryWriteFinalization(
    context: Context,
    preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
): Boolean {
    return PersistentTerminalTemporaryWriteCleanupJournal.completeFinalization(
        context = context,
        preparation = preparation
    )
}

internal fun ManagedDownloadStorage.recoverPreparedTerminalTemporaryWriteFinalizations(
    context: Context
): StartupRecoveryResult {
    return try {
        when (
            val snapshot = PersistentTerminalTemporaryWriteCleanupJournal.preparationSnapshot(
                context
            )
        ) {
            is TerminalTemporaryWriteCleanupPreparationSnapshot.Unavailable -> {
                NPLogger.w(
                    TAG,
                    "最终发布准备记录不可读取，保留等待恢复: ${snapshot.reason}"
                )
                StartupRecoveryResult(failedCount = 1)
            }

            is TerminalTemporaryWriteCleanupPreparationSnapshot.Available -> {
                var failedCount = 0
                var externalSignalRequiredCount = 0
                snapshot.entries.groupBy { preparation -> preparation.root }
                    .forEach rootGroup@{ (_, preparations) ->
                        val root = resolveTerminalTemporaryWriteCleanupRoot(
                            context,
                            preparations.first()
                        )
                        if (root == null) {
                            val blockedTargets = preparations.sumOf { it.targetNames.size }
                            failedCount += blockedTargets
                            externalSignalRequiredCount += blockedTargets
                            NPLogger.w(
                                TAG,
                                "最终发布准备目录不可恢复，保留等待恢复: " +
                                    "root=${preparations.first().root.identity}, " +
                                    "targets=$blockedTargets"
                            )
                            return@rootGroup
                        }
                        val refresh = treeDirectories.refreshRootEntries(context, root)
                        if (!refresh.isComplete) {
                            val blockedTargets = preparations.sumOf { it.targetNames.size }
                            failedCount += blockedTargets
                            NPLogger.w(
                                TAG,
                                "最终发布准备恢复跳过不完整目录枚举: " +
                                    "targets=$blockedTargets"
                            )
                            return@rootGroup
                        }
                        val rootEntries = refresh.entries.filterNot(StoredEntry::isDirectory)
                        preparations.forEach preparation@{ preparation ->
                            if (!isPreparedTerminalTemporaryWriteFinalizationReady(
                                    context = context,
                                    preparation = preparation,
                                    rootEntries = rootEntries
                                )
                            ) {
                                return@preparation
                            }
                            if (!completeTerminalTemporaryWriteFinalization(context, preparation)) {
                                failedCount += preparation.targetNames.size
                                NPLogger.w(
                                    TAG,
                                    "最终发布准备未能转换为终态清理记录，保留等待恢复: " +
                                        "audio=${preparation.finalAudioName}"
                                )
                            }
                        }
                    }
                StartupRecoveryResult(
                    failedCount = failedCount,
                    externalSignalRequiredCount = externalSignalRequiredCount
                )
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: SecurityException) {
        NPLogger.w(TAG, "最终发布准备恢复缺少权限，保留等待恢复: ${error.message}")
        StartupRecoveryResult(failedCount = 1, externalSignalRequiredCount = 1)
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "最终发布准备恢复失败，保留等待恢复: ${error.message}",
            error
        )
        StartupRecoveryResult(failedCount = 1)
    }
}

internal fun ManagedDownloadStorage.isPreparedTerminalTemporaryWriteFinalizationReady(
    context: Context,
    preparation: TerminalTemporaryWriteCleanupFinalizationPreparation,
    rootEntries: List<StoredEntry>
): Boolean {
    val finalAudioExists = rootEntries.any { entry ->
        !entry.isPendingAudioWrite && entry.name == preparation.finalAudioName
    }
    if (!finalAudioExists) {
        return false
    }
    if (rootEntries.any { entry ->
            entry.isPendingAudioWrite && entry.logicalName == preparation.finalAudioName
        }
    ) {
        return false
    }
    return rootEntries.asSequence()
        .filter { entry ->
            ManagedDownloadTreeNaming.metadataAudioName(entry.name) ==
                preparation.finalAudioName
        }
        .mapNotNull { entry -> parseDownloadedAudioMetadata(context, entry) }
        .any { metadata ->
            isFinalizedDownloadedMetadata(metadata) &&
                matchesTerminalTemporaryWriteFinalizationIdentity(
                    metadata = metadata,
                    preparation = preparation
                ) &&
                metadata.audioFileName == preparation.finalAudioName
        }
}

internal fun ManagedDownloadStorage.terminalTemporaryWriteCleanupJournalRoot(
    root: RootHandle
): TerminalTemporaryWriteCleanupRoot {
    return when (root) {
        is RootHandle.FileRoot -> TerminalTemporaryWriteCleanupRoot(
            type = TerminalTemporaryWriteCleanupRootType.FILE,
            identity = root.dir.absolutePath
        )

        is RootHandle.TreeRoot -> TerminalTemporaryWriteCleanupRoot(
            type = TerminalTemporaryWriteCleanupRootType.TREE,
            identity = root.tree.uri.toString()
        )
    }
}

internal fun ManagedDownloadStorage.resolveTerminalTemporaryWriteCleanupRoot(
    context: Context,
    entry: TerminalTemporaryWriteCleanupJournalEntry
): RootHandle? {
    return when (entry.root.type) {
        TerminalTemporaryWriteCleanupRootType.FILE -> {
            File(entry.root.identity)
                .takeIf(File::isAbsolute)
                ?.let(RootHandle::FileRoot)
        }

        TerminalTemporaryWriteCleanupRootType.TREE -> {
            val treeUri = entry.root.identity.toUri()
            if (treeUri.scheme != "content") {
                return null
            }
            DocumentFile.fromTreeUri(context, treeUri)?.let(RootHandle::TreeRoot)
        }
    }
}

internal fun ManagedDownloadStorage.resolveTerminalTemporaryWriteCleanupRoot(
    context: Context,
    preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
): RootHandle? {
    return resolveTerminalTemporaryWriteCleanupRoot(
        context = context,
        root = preparation.root
    )
}

internal fun ManagedDownloadStorage.resolveTerminalTemporaryWriteCleanupRoot(
    context: Context,
    root: TerminalTemporaryWriteCleanupRoot
): RootHandle? {
    return when (root.type) {
        TerminalTemporaryWriteCleanupRootType.FILE -> {
            File(root.identity)
                .takeIf(File::isAbsolute)
                ?.let(RootHandle::FileRoot)
        }

        TerminalTemporaryWriteCleanupRootType.TREE -> {
            val treeUri = root.identity.toUri()
            if (treeUri.scheme != "content") {
                return null
            }
            DocumentFile.fromTreeUri(context, treeUri)?.let(RootHandle::TreeRoot)
        }
    }
}

internal suspend fun ManagedDownloadStorage.cleanupTerminalTemporaryWriteArtifactsBlocking(
    context: Context,
    root: RootHandle,
    targets: Collection<TerminalTemporaryWriteCleanupTarget>
): StartupRecoveryResult {
    val normalizedTargets = targets
        .mapNotNull(::normalizeTerminalTemporaryWriteCleanupTarget)
        .distinct()
    if (normalizedTargets.isEmpty()) {
        return StartupRecoveryResult()
    }
    val backend: StorageBackend
    val targetForCleanupTarget: (TerminalTemporaryWriteCleanupTarget) -> StorageTarget
    when (root) {
        is RootHandle.FileRoot -> {
            backend = FileStorageBackend(root.dir)
            targetForCleanupTarget = { target ->
                StorageTarget.FileTarget(
                    logicalPath = target.displayName,
                    temporaryWriteOwnerName = target.temporaryWriteOwnerName
                )
            }
        }

        is RootHandle.TreeRoot -> {
            backend = SafStorageBackend(context)
            targetForCleanupTarget = { target ->
                StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(root.tree.uri),
                    displayName = target.displayName,
                    mimeType = "application/octet-stream",
                    temporaryWriteOwnerName = target.temporaryWriteOwnerName
                )
            }
        }
    }
    var cleanedCount = 0
    var failedCount = 0
    var externalSignalRequiredCount = 0
    when (
        val result = backend.cleanupTerminalTemporaryWrites(
            normalizedTargets.map(targetForCleanupTarget)
        )
    ) {
        is ManagedTemporaryWriteCleanupResult.Completed -> {
            cleanedCount += result.deletedCount
            failedCount += terminalTemporaryWriteCleanupFailureCount(
                result = result,
                targetCount = normalizedTargets.size
            )
            externalSignalRequiredCount +=
                terminalTemporaryWriteCleanupExternalSignalRequiredCount(
                    result = result,
                    targetCount = normalizedTargets.size
                )
            if (result.retainedActiveCount > 0) {
                NPLogger.d(
                    TAG,
                    "终态临时写入清理跳过活跃写入: targets=${normalizedTargets.size}, " +
                        "count=${result.retainedActiveCount}"
                )
            }
            if (result.failures.isNotEmpty()) {
                NPLogger.w(
                    TAG,
                    "终态临时写入未完全清理: targets=${normalizedTargets.size}, " +
                        "failed=${result.failures.size}"
                )
            }
        }

        is ManagedTemporaryWriteCleanupResult.Skipped -> {
            failedCount += terminalTemporaryWriteCleanupFailureCount(
                result = result,
                targetCount = normalizedTargets.size
            )
            externalSignalRequiredCount +=
                terminalTemporaryWriteCleanupExternalSignalRequiredCount(
                    result = result,
                    targetCount = normalizedTargets.size
                )
            NPLogger.w(
                TAG,
                "终态临时写入清理跳过非完整目录枚举: " +
                    "targets=${normalizedTargets.size}, reason=${result.reason}"
            )
        }
    }
    if (cleanedCount > 0) {
        invalidateSnapshotCache(context)
    }
    return StartupRecoveryResult(
        cleanedCount = cleanedCount,
        failedCount = failedCount,
        externalSignalRequiredCount = externalSignalRequiredCount
    )
}

internal fun ManagedDownloadStorage.terminalTemporaryWriteCleanupTargetsForFinalization(
    pendingAudio: StoredEntry,
    metadata: DownloadedAudioMetadata
): List<TerminalTemporaryWriteCleanupTarget> {
    val audioName = pendingAudio.logicalName
    val pendingMetadataName = "$audioName$PENDING_METADATA_SUFFIX"
    return buildList {
        if (pendingAudio.isPendingAudioWrite) {
            add(TerminalTemporaryWriteCleanupTarget(displayName = pendingAudio.name))
        }
        add(TerminalTemporaryWriteCleanupTarget(displayName = audioName))
        add(TerminalTemporaryWriteCleanupTarget(displayName = "$audioName$METADATA_SUFFIX"))
        add(TerminalTemporaryWriteCleanupTarget(displayName = pendingMetadataName))
        temporaryWriteOwnerNameForIdentity(
            displayName = pendingMetadataName,
            identity = terminalTemporaryWriteIdentity(metadata)
        )?.let { temporaryWriteOwnerName ->
            add(
                TerminalTemporaryWriteCleanupTarget(
                    displayName = pendingMetadataName,
                    temporaryWriteOwnerName = temporaryWriteOwnerName
                )
            )
        }
    }.mapNotNull(::normalizeTerminalTemporaryWriteCleanupTarget)
        .distinct()
}

internal fun ManagedDownloadStorage.temporaryWriteOwnerNameForIdentity(
    displayName: String,
    identity: String?
): String? {
    val normalizedDisplayName = normalizeTerminalTemporaryWriteTargetName(displayName)
        ?: return null
    val normalizedIdentity = identity?.trim()?.takeIf(String::isNotBlank) ?: return null
    return "$normalizedDisplayName\u0000$normalizedIdentity"
}

internal fun ManagedDownloadStorage.terminalTemporaryWriteIdentity(metadata: DownloadedAudioMetadata): String? {
    return metadata.operationId?.trim()?.takeIf(String::isNotBlank)
        ?: metadata.terminalTemporaryWriteCleanupToken
            ?.trim()
            ?.takeIf(String::isNotBlank)
}

internal fun ManagedDownloadStorage.normalizeTerminalTemporaryWriteCleanupTarget(
    target: TerminalTemporaryWriteCleanupTarget
): TerminalTemporaryWriteCleanupTarget? {
    val displayName = normalizeTerminalTemporaryWriteTargetName(target.displayName) ?: return null
    return target.copy(
        displayName = displayName,
        temporaryWriteOwnerName = target.temporaryWriteOwnerName
            ?.trim()
            ?.takeIf(String::isNotBlank)
    )
}

internal fun ManagedDownloadStorage.normalizeTerminalTemporaryWriteTargetName(rawName: String): String? {
    val name = rawName.trim().takeIf(String::isNotBlank) ?: return null
    if (name == "." || name == ".." || '/' in name || '\\' in name) {
        return null
    }
    return name
}

internal fun ManagedDownloadStorage.saveMetadataBlocking(
    context: Context,
    audio: StoredEntry,
    json: String,
    updateSnapshotCache: Boolean = true,
    expectedAbsent: Boolean = false,
    knownMetadataEntry: StoredEntry? = null
): Boolean {
    val metadata = parseDownloadedAudioMetadataJson(json)
    if (metadata == null) {
        invalidateSnapshotCache(context)
        return false
    }
    val root = resolveRootBlocking(context)
    val content = preserveAudioPublicationReceipt(
        readAudioPublicationMetadata(context, root, audio.logicalName)?.toString(), json
    )
    val expectedMetadata = if (content == json) metadata else parseDownloadedAudioMetadataJson(content)
    if (expectedMetadata == null) {
        invalidateSnapshotCache(context)
        return false
    }
    val metadataEntry = writeRootText(
        context = context,
        root = root,
        displayName = "${audio.logicalName}$METADATA_SUFFIX",
        content = content,
        expectedAbsent = expectedAbsent,
        knownTargetEntry = knownMetadataEntry
    )
    if (metadataEntry == null) {
        invalidateSnapshotCache(context)
        return false
    }
    val storedContent = readTextInternal(context, metadataEntry.reference)
    val storedMetadata = storedContent?.let(::parseDownloadedAudioMetadataJson)
    if (storedMetadata == null || !isMetadataWriteVerified(expected = expectedMetadata, actual = storedMetadata) ||
        content != json && storedContent != content
    ) {
        invalidateSnapshotCache(context)
        NPLogger.w(TAG, "下载元数据写入读回校验失败: ${audio.name}")
        return false
    }
    if (
        updateSnapshotCache &&
        !updateSnapshotCacheAfterMetadataWrite(context, metadataEntry, storedMetadata)
    ) {
        invalidateSnapshotCache(context)
    }
    return true
}
