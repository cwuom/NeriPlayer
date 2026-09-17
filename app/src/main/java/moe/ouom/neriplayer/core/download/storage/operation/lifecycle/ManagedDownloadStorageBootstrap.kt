package moe.ouom.neriplayer.core.download.storage.operation.lifecycle

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.shouldDeferStartupManagedCleanup
import moe.ouom.neriplayer.core.download.storage.operation.collectManagedMigrationEntries
import moe.ouom.neriplayer.core.download.storage.operation.content.backendReference
import moe.ouom.neriplayer.core.download.storage.operation.content.buildMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.operation.content.cleanupPendingAudioWrites
import moe.ouom.neriplayer.core.download.storage.operation.content.isMissingReplacementBackup
import moe.ouom.neriplayer.core.download.storage.operation.content.normalizeDirectoryUri
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveTreeRootBlocking
import moe.ouom.neriplayer.core.download.storage.operation.content.trustedManagedRef
import moe.ouom.neriplayer.core.download.storage.operation.content.trustedReferencesFromMigrationRefresh
import moe.ouom.neriplayer.core.download.storage.operation.isMigrationReferenceBoundToRoot
import moe.ouom.neriplayer.core.download.storage.operation.shouldIndexMetadataLessAudio
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StartupRecoveryResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.TemporaryDirectoryEntries
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationProgress
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.OrphanMigrationReplacementRecoveryResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationReplacementBackupCandidate
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationRecoveryTargetResolution
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.ValidatedMigrationReplacementBackup
import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_TEMPORARY_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.commit.sameManagedMigrationStoredEntryIdentity
import moe.ouom.neriplayer.core.download.storage.commit.sameMigrationReplacementBackupIdentity
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.migration.plan.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.storage.migration.migrationProgressCheckpointIds
import moe.ouom.neriplayer.core.download.storage.migration.progress.selectMigrationProgressCheckpoint
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCleanupResult
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCleanupReceipt
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCopyReceipt
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan
import moe.ouom.neriplayer.core.download.storage.migration.plan.toCopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.migration.recovery.canReuseMigrationTargetDigest
import moe.ouom.neriplayer.core.download.storage.migration.recovery.selectOrphanedMigrationReplacementPlans
import moe.ouom.neriplayer.core.download.storage.migration.recovery.persistedMigrationJournalTargetNames
import moe.ouom.neriplayer.core.download.storage.migration.shouldBlockStartupForMigrationRecovery
import moe.ouom.neriplayer.core.download.storage.migration.progress.selectActiveMigrationWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.recovery.hasCompleteMigrationCleanupReceipts
import moe.ouom.neriplayer.core.download.storage.migration.recovery.mergePersistedMigrationCleanupReceipts
import moe.ouom.neriplayer.core.download.storage.migration.recovery.migrationSourceEntryCount
import moe.ouom.neriplayer.core.download.storage.migration.copy.sha256MigrationContent
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.displayName
import java.io.File
import java.io.IOException
import java.util.Locale
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.restorePersistedMigrationProgress(
    context: Context,
    includeJournal: Boolean
): MigrationProgress? {
    val checkpointStore = ManagedDownloadMigrationCheckpointStore(context)
    val request = runCatching { checkpointStore.readRequest() }.getOrNull()
    val journal = if (includeJournal) {
        runCatching { checkpointStore.readReplacementJournal() }.getOrNull()
    } else {
        null
    }
    if (!shouldBlockStartupForMigrationRecovery(request, journal)) {
        return null
    }
    val progress = selectMigrationProgressCheckpoint(
        checkpointIds = migrationProgressCheckpointIds(
            currentWorkId = "",
            inputCheckpointWorkId = null,
            persistedRequest = request,
            persistedJournal = journal
        ),
        readProgress = checkpointStore::readProgress
    ) ?: return null
    migrationProgressSession.restoreIfIdle(progress)
    return migrationProgressFlow.value
}

internal fun ManagedDownloadStorage.hasPendingStartupMigrationRecovery(context: Context): Boolean {
    return try {
        val checkpointStore = ManagedDownloadMigrationCheckpointStore(context)
        val request = checkpointStore.readRequest()
        val journal = checkpointStore.readReplacementJournal()
        val durableRecovery = shouldBlockStartupForMigrationRecovery(request, journal)
        if (durableRecovery) {
            return true
        }
        selectActiveMigrationWorkInfo(
            workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(ManagedDownloadMigrationWorker.WORK_NAME)
                .get(),
            preferredWorkId = request?.workId
        ) != null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "迁移恢复凭据检查失败，延后启动存储清理: ${error.message}",
            error
        )
        true
    }
}

internal fun ManagedDownloadStorage.resolveStartupPendingAudioRecovery(context: Context): StartupRecoveryResult {
    val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
    val treeRootAvailable = resolveTreeRootBlocking(context, configuredUri) != null
    return if (shouldDeferStartupManagedCleanup(configuredUri, treeRootAvailable)) {
        schedulePendingAudioWriteCleanup(context)
        StartupRecoveryResult()
    } else {
        cleanupPendingAudioWrites(context)
    }
}

internal fun ManagedDownloadStorage.schedulePendingAudioWriteCleanup(context: Context) {
    val appContext = context.applicationContext
    snapshotScope.launch {
        cleanupPendingAudioWrites(appContext)
    }
}

internal fun ManagedDownloadStorage.resolveStartupMetadataRecovery(context: Context): StartupRecoveryResult {
    val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
    val treeRootAvailable = resolveTreeRootBlocking(context, configuredUri) != null
    if (shouldDeferStartupManagedCleanup(configuredUri, treeRootAvailable)) {
        scheduleUnfinalizedDownloadArtifactCleanup(context)
        return StartupRecoveryResult()
    }
    return cleanupUnfinalizedDownloadArtifacts(context)
}

internal fun ManagedDownloadStorage.scheduleUnfinalizedDownloadArtifactCleanup(context: Context) {
    val appContext = context.applicationContext
    snapshotScope.launch {
        val result = cleanupUnfinalizedDownloadArtifacts(appContext)
        if (result.hasRecoveredEntries) {
            _startupRecoveryResults.tryEmit(result)
        }
    }
}

internal fun ManagedDownloadStorage.resolveTemporaryRoot(
    context: Context,
    root: RootHandle,
    create: Boolean
): RootHandle? {
    return when (root) {
        is RootHandle.FileRoot -> {
            val directory = File(root.dir, DOWNLOAD_TEMPORARY_DIR_NAME)
            if (!create && !directory.isDirectory) {
                null
            } else {
                if (directory.exists() && !directory.isDirectory) {
                    throw IOException("下载临时路径不是目录: ${directory.absolutePath}")
                }
                if (create && !directory.isDirectory &&
                    !directory.mkdirs() && !directory.isDirectory
                ) {
                    throw IOException("无法创建下载临时目录: ${directory.absolutePath}")
                }
                if (directory.isDirectory) {
                    treeDirectories.ensureManagedMediaScanIsolation(
                        DOWNLOAD_TEMPORARY_DIR_NAME,
                        directory
                    )
                    RootHandle.FileRoot(directory)
                } else {
                    null
                }
            }
        }

        is RootHandle.TreeRoot -> {
            val directory = if (create) {
                treeDirectories.findOrCreateDirectory(
                    context = context,
                    parent = root.tree,
                    displayName = DOWNLOAD_TEMPORARY_DIR_NAME
                )
            } else {
                findExistingTemporaryTreeDirectory(
                    context = context,
                    root = root,
                    forceRefresh = false
                ).first
            }
            directory?.also {
                treeDirectories.ensureManagedMediaScanIsolation(
                    context = context,
                    subdirectory = DOWNLOAD_TEMPORARY_DIR_NAME,
                    directory = it
                )
            }?.let(RootHandle::TreeRoot)
        }
    }
}

internal fun ManagedDownloadStorage.findExistingTemporaryTreeDirectory(
    context: Context,
    root: RootHandle.TreeRoot,
    forceRefresh: Boolean
): Pair<DocumentFile?, Boolean> {
    val refresh = if (forceRefresh) {
        treeChildRegistry.refreshTreeChildrenWithStatus(context, root.tree)
    } else {
        val cached = treeChildRegistry.cachedTreeChildrenIfFresh(
            parent = root.tree,
            maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
        )
        cached?.let {
            ManagedDownloadTreeChildRegistry.TreeChildrenRefresh(
                children = it.toList(),
                isComplete = true
            )
        } ?: treeChildRegistry.refreshTreeChildrenWithStatus(context, root.tree)
    }
    val child = refresh.children
        .asSequence()
        .filter(QueriedTreeChild::isDirectory)
        .filter { candidate ->
            ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                candidate.name,
                DOWNLOAD_TEMPORARY_DIR_NAME
            )
        }
        .sortedWith(
            compareBy<QueriedTreeChild>(
                { if (it.name == DOWNLOAD_TEMPORARY_DIR_NAME) 0 else 1 },
                { it.name }
            )
        )
        .firstOrNull()
    val directory = child?.let {
        treeChildRegistry.toDocumentFile(context, root.tree, it)
    }
    return directory to (refresh.isComplete && (child == null || directory != null))
}

internal fun ManagedDownloadStorage.readTemporaryDirectoryEntries(
    context: Context,
    root: RootHandle,
    forceRefresh: Boolean,
    rootAlreadyRefreshed: Boolean = false
): TemporaryDirectoryEntries {
    return when (root) {
        is RootHandle.FileRoot -> {
            val directory = File(root.dir, DOWNLOAD_TEMPORARY_DIR_NAME)
            if (!directory.exists()) {
                TemporaryDirectoryEntries(emptyList(), isComplete = true, exists = false)
            } else if (!directory.isDirectory) {
                TemporaryDirectoryEntries(emptyList(), isComplete = false, exists = true)
            } else {
                val children = directory.listFiles()
                if (children == null) {
                    TemporaryDirectoryEntries(emptyList(), isComplete = false, exists = true)
                } else {
                    TemporaryDirectoryEntries(
                        entries = children.map(ManagedDownloadStoredEntryMapper::fromFile),
                        isComplete = true,
                        exists = true
                    )
                }
            }
        }

        is RootHandle.TreeRoot -> {
            val (directory, rootComplete) = findExistingTemporaryTreeDirectory(
                context = context,
                root = root,
                // 调用方刚完成根目录列举，要求刷新时只重新读取 .tmp 子目录
                forceRefresh = forceRefresh && !rootAlreadyRefreshed
            )
            if (directory == null) {
                TemporaryDirectoryEntries(
                    entries = emptyList(),
                    isComplete = rootComplete,
                    exists = false
                )
            } else {
                val childRefresh = if (forceRefresh) {
                    treeChildRegistry.refreshTreeChildrenWithStatus(context, directory)
                } else {
                    val cached = treeChildRegistry.cachedTreeChildrenIfFresh(
                        parent = directory,
                        maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                    )
                    cached?.let {
                        ManagedDownloadTreeChildRegistry.TreeChildrenRefresh(
                            children = it.toList(),
                            isComplete = true
                        )
                    } ?: treeChildRegistry.refreshTreeChildrenWithStatus(context, directory)
                }
                TemporaryDirectoryEntries(
                    entries = childRefresh.children
                        .map(ManagedDownloadStoredEntryMapper::fromTreeChild),
                    isComplete = rootComplete && childRefresh.isComplete,
                    exists = true
                )
            }
        }
    }
}

internal suspend fun ManagedDownloadStorage.applyDeletedSourceCopyReceiptRecoveryPlan(
    context: Context,
    targetRoot: RootHandle,
    journal: ManagedMigrationReplacementJournal,
    plan: ManagedMigrationDeletedSourceCopyReceiptRecoveryPlan
): ManagedMigrationReplacementJournal {
    if (
        plan.promoteCandidates.isEmpty() &&
        plan.rollbackCandidates.isEmpty() &&
        plan.preserveCandidates.isEmpty()
    ) {
        return journal
    }

    fun sourceEntryFor(receipt: ManagedMigrationCopyReceipt): ManagedMigrationEntry {
        val reference = receipt.sourceReference.trim()
        return ManagedMigrationEntry(
            subdirectory = receipt.sourceSubdirectory,
            entry = StoredEntry(
                name = receipt.sourceName,
                reference = reference,
                mediaUri = reference,
                localFilePath = reference.takeIf { it.startsWith("/") },
                sizeBytes = receipt.sourceSizeBytes.coerceAtLeast(0L),
                lastModifiedMs = receipt.sourceLastModifiedMs.coerceAtLeast(0L),
                isDirectory = false
            ),
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                createdAtMs = receipt.sourceLogicalCreatedAtMs,
                createdAtSource = receipt.sourceCreatedAtSource,
                createdAtConfidence = receipt.sourceCreatedAtConfidence
            )
        )
    }

    fun expectedTargetDigest(receipt: ManagedMigrationCopyReceipt): String? {
        receipt.verifiedTargetDigest
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        // 复制凭据落盘后元数据可能已经改写，改写前的源摘要不能证明目标内容
        return receipt.sourceDigest
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { ManagedDownloadTreeNaming.isMetadataName(receipt.sourceName) }
    }

    val targetCandidatesBySubdirectory = mutableMapOf<
        String?,
        List<StoredEntry>?
    >()

    fun targetCandidates(subdirectory: String?): List<StoredEntry>? {
        if (targetCandidatesBySubdirectory.containsKey(subdirectory)) {
            return targetCandidatesBySubdirectory[subdirectory]
        }
        val refresh = try {
            if (subdirectory == null) {
                treeDirectories.refreshRootEntries(
                    context = context,
                    root = targetRoot
                ).let { result -> result.entries to result.isComplete }
            } else {
                treeDirectories.refreshSubdirectoryEntries(
                    context = context,
                    root = targetRoot,
                    subdirectory = subdirectory
                ).let { result -> result.entries to result.isComplete }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ManagedDownloadMigrationException.transient(
                "迁移恢复目标枚举暂时失败",
                error
            )
        }
        val entries = refresh.first.takeIf { refresh.second }
        targetCandidatesBySubdirectory[subdirectory] = entries
        return entries
    }

    suspend fun resolveTarget(
        receipt: ManagedMigrationCopyReceipt
    ): MigrationRecoveryTargetResolution {
        val target = statMigrationTargetEntry(
            context = context,
            targetRoot = targetRoot,
            expected = receipt.targetEntry
        )
        if (target != null) {
            val expectedDigest = expectedTargetDigest(receipt)
            val actualDigest = expectedDigest?.let { readMigrationTargetDigest(context, target) }
            if (
                sameManagedMigrationStoredEntryIdentity(receipt.targetEntry, target) &&
                (expectedDigest == null || actualDigest.equals(expectedDigest, true))
            ) {
                return MigrationRecoveryTargetResolution(
                    entry = target,
                    alreadyRestored = false
                )
            }
            val backup = receipt.replacementBackup
            if (
                backup != null &&
                isRestoredMigrationReplacementTarget(
                    expectedTarget = receipt.targetEntry,
                    actualTarget = target,
                    replacementBackup = backup,
                    targetDigest = actualDigest,
                    expectedTargetDigest = expectedDigest
                )
            ) {
                return MigrationRecoveryTargetResolution(
                    entry = target,
                    alreadyRestored = true
                )
            }
            throw ManagedDownloadMigrationException.targetChanged(
                if (sameManagedMigrationStoredEntryIdentity(receipt.targetEntry, target)) {
                    "迁移恢复目标内容已发生变化: ${receipt.targetEntry.name}"
                } else {
                    "迁移恢复目标文档已发生变化: ${receipt.targetEntry.name}"
                }
            )
        }

        val backup = receipt.replacementBackup
            ?: return MigrationRecoveryTargetResolution(
                entry = null,
                alreadyRestored = false
            )
        val candidates = targetCandidates(receipt.sourceSubdirectory)
            ?: return MigrationRecoveryTargetResolution(
                entry = null,
                alreadyRestored = false
            )
        val matchingName = candidates.filter { candidate ->
            !candidate.isDirectory && candidate.name == receipt.targetEntry.name
        }
        if (matchingName.size > 1) {
            throw ManagedDownloadMigrationException.targetChanged(
                "迁移恢复目标名称存在多个候选: ${receipt.targetEntry.name}"
            )
        }
        val candidate = matchingName.singleOrNull()
            ?: return MigrationRecoveryTargetResolution(
                entry = null,
                alreadyRestored = false
            )
        val expectedDigest = expectedTargetDigest(receipt)
        val actualDigest = expectedDigest?.let { readMigrationTargetDigest(context, candidate) }
        if (
            isRestoredMigrationReplacementTarget(
                expectedTarget = receipt.targetEntry,
                actualTarget = candidate,
                replacementBackup = backup,
                targetDigest = actualDigest,
                expectedTargetDigest = expectedDigest
            )
        ) {
            return MigrationRecoveryTargetResolution(
                entry = candidate,
                alreadyRestored = true
            )
        }
        throw ManagedDownloadMigrationException.targetChanged(
            "迁移恢复目标文档身份无法确认: ${receipt.targetEntry.name}"
        )
    }

    plan.rollbackCandidates.forEach { receipt ->
        // 目标不存在时已经完成回滚，仍存在时用身份和摘要保护用户文件
        val targetResolution = resolveTarget(receipt)
        if (targetResolution.alreadyRestored) {
            return@forEach
        }
        val target = targetResolution.entry
        val copied = receipt.toCopiedMigrationEntry(
            original = sourceEntryFor(receipt),
            targetEntry = target ?: receipt.targetEntry
        )
        val failed = migrationFinalizer.rollbackMigratedEntries(
            context = context,
            copiedEntries = listOf(copied),
            targetRoot = targetRoot
        )
        if (failed > 0) {
            throw ManagedDownloadMigrationException.transient(
                "迁移缺失源目标回滚暂时失败: ${receipt.targetEntry.name}"
            )
        }
    }

    val cleanupReceipts = buildList {
        (plan.promoteCandidates + plan.preserveCandidates).forEach { receipt ->
            val target = resolveTarget(receipt).entry
                ?: throw ManagedDownloadMigrationException.transient(
                    "迁移缺失源目标暂时不可用: ${receipt.targetEntry.name}"
                )
            val targetDigest = readMigrationTargetDigest(context, target)
            add(
                ManagedMigrationCleanupReceipt(
                    sourceReference = receipt.sourceReference,
                    sourceName = receipt.sourceName,
                    sourceSubdirectory = receipt.sourceSubdirectory,
                    targetEntry = target,
                    targetDigest = targetDigest,
                    sourceLogicalCreatedAtMs = receipt.sourceLogicalCreatedAtMs,
                    sourceCreatedAtSource = receipt.sourceCreatedAtSource,
                    sourceCreatedAtConfidence = receipt.sourceCreatedAtConfidence
                )
            )
        }
    }
    val mergedCleanupReceipts = mergePersistedMigrationCleanupReceipts(
        persisted = journal.cleanupReceipts,
        current = cleanupReceipts
    )
    return journal.copy(
        cleanupReceipts = mergedCleanupReceipts,
        sourceEntryCount = migrationSourceEntryCount(
            sourceEntries = journal.sourceEntries,
            cleanupReceipts = mergedCleanupReceipts
        ),
        sourceEntriesComplete = true,
        deletedSourceAudioCount = maxOf(
            journal.deletedSourceAudioCount,
            plan.deletedSourceAudioCount
        )
    )
}

internal suspend fun ManagedDownloadStorage.recoverOrphanedMigrationReplacements(
    context: Context,
    targetRoot: RootHandle,
    journal: ManagedMigrationReplacementJournal,
    missingSourceReferences: Iterable<String>,
    persistedCopyReceiptReferences: Iterable<String>
): OrphanMigrationReplacementRecoveryResult {
    val plans = selectOrphanedMigrationReplacementPlans(
        journal = journal,
        missingSourceReferences = missingSourceReferences,
        persistedCopyReceiptReferences = persistedCopyReceiptReferences
    )
    if (plans.isEmpty()) {
        return OrphanMigrationReplacementRecoveryResult(
            resolvedReferences = emptySet(),
            unresolvedReferences = emptySet()
        )
    }
    val refresh = try {
        treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw ManagedDownloadMigrationException.transient(
            "迁移孤儿替换目标枚举暂时失败",
            error
        )
    }
    if (!refresh.isComplete) {
        throw ManagedDownloadMigrationException.transient(
            "迁移孤儿替换目标枚举不完整，保留替换事务等待恢复"
        )
    }

    fun entriesFor(subdirectory: String?): List<StoredEntry> = when (subdirectory) {
        null -> refresh.rootEntries
        COVER_SUBDIRECTORY -> refresh.coverEntries
        LYRIC_SUBDIRECTORY -> refresh.lyricEntries
        else -> emptyList()
    }

    fun sourceEntryFor(plan: ManagedMigrationReplacementPlan): ManagedMigrationEntry {
        val source = journal.sourceEntries.firstOrNull { entry ->
            entry.sourceReference.trim() == plan.sourceReference.trim()
        }
        val sourceReference = plan.sourceReference.trim()
        return ManagedMigrationEntry(
            subdirectory = plan.subdirectory,
            entry = StoredEntry(
                name = source?.sourceName ?: plan.targetName,
                reference = sourceReference,
                mediaUri = sourceReference,
                localFilePath = sourceReference.takeIf { it.startsWith("/") },
                sizeBytes = source?.sizeBytes?.coerceAtLeast(0L)
                    ?: plan.targetEntry.sizeBytes.coerceAtLeast(0L),
                lastModifiedMs = source?.lastModifiedMs?.coerceAtLeast(0L)
                    ?: plan.targetEntry.lastModifiedMs.coerceAtLeast(0L),
                isDirectory = false
            )
        )
    }

    fun targetIsUnchanged(
        expected: StoredEntry,
        actual: StoredEntry?
    ): Boolean {
        if (actual == null || !sameManagedMigrationStoredEntryIdentity(expected, actual)) {
            return false
        }
        return canReuseMigrationTargetDigest(
            expectedSizeBytes = expected.sizeBytes,
            actualSizeBytes = actual.sizeBytes,
            expectedLastModifiedMs = expected.lastModifiedMs,
            actualLastModifiedMs = actual.lastModifiedMs
        )
    }

    val resolved = linkedSetOf<String>()
    val unresolved = linkedSetOf<String>()
    plans.forEach { plan ->
        val candidates = entriesFor(plan.subdirectory).filter { entry ->
            !entry.isDirectory && entry.name == plan.backupName
        }
        if (candidates.size > 1) {
            throw ManagedDownloadMigrationException.targetChanged(
                "迁移孤儿替换备份存在多个候选: ${plan.backupName}"
            )
        }
        val backup = candidates.singleOrNull()
        val target = entriesFor(plan.subdirectory).firstOrNull { entry ->
            !entry.isDirectory && entry.name == plan.targetName
        }
        when {
            backup != null && !sameMigrationReplacementBackupIdentity(
                plan.targetEntry,
                backup
            ) -> {
                unresolved += plan.sourceReference
                NPLogger.w(
                    TAG,
                    "迁移孤儿替换备份身份变化，保留事务: ${plan.backupName}"
                )
            }

            backup != null -> {
                val copied = CopiedMigrationEntry(
                    original = sourceEntryFor(plan),
                    copiedEntry = plan.targetEntry,
                    createdNew = false,
                    replacementBackup = backup,
                    sourceAuthoritative = true
                )
                if (commitWriter.restoreMigrationReplacement(
                        context = context,
                        root = targetRoot,
                        copied = copied
                    )
                ) {
                    resolved += plan.sourceReference
                } else {
                    unresolved += plan.sourceReference
                    NPLogger.w(
                        TAG,
                        "迁移孤儿替换备份恢复未确认，保留事务: ${plan.backupName}"
                    )
                }
            }

            targetIsUnchanged(plan.targetEntry, target) -> {
                // 目标仍是迁移前的同一文件，说明替换尚未开始
                resolved += plan.sourceReference
            }

            else -> {
                unresolved += plan.sourceReference
                NPLogger.w(
                    TAG,
                    "迁移孤儿替换缺少可恢复目标，保留事务: ${plan.targetName}"
                )
            }
        }
    }
    return OrphanMigrationReplacementRecoveryResult(
        resolvedReferences = resolved,
        unresolvedReferences = unresolved
    )
}

internal suspend fun ManagedDownloadStorage.verifyCommittedMigrationReplacementJournal(
    context: Context,
    targetRoot: RootHandle,
    journal: ManagedMigrationReplacementJournal
) {
    persistedMigrationJournalTargetNames(journal)
    if (journal.replacements.isEmpty()) {
        if (!hasCompleteMigrationCleanupReceipts(journal)) {
            throw ManagedDownloadMigrationException.transient(
                "迁移事务没有可验证的目标清单"
            )
        }
        return
    }
    if (
        journal.phase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED &&
        journal.cleanupComplete &&
        hasCompleteMigrationCleanupReceipts(journal)
    ) {
        // 所有替换备份都确认删除后才写入 cleanupComplete，重新打开目标无需再列举目录
        return
    }
    val targetIndex = buildMigrationTargetIndex(context, targetRoot)
    journal.replacements.forEach { replacement ->
        val target = targetIndex.entryFor(
            replacement.subdirectory,
            replacement.targetName
        )
        if (target == null || target.isDirectory) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务目标文件暂时不可用: ${replacement.targetName}"
            )
        }
        if (targetIndex.entryFor(replacement.subdirectory, replacement.backupName) != null) {
            throw ManagedDownloadMigrationException.transient(
                "迁移替换事务备份尚未清理: ${replacement.backupName}"
            )
        }
    }
}

internal suspend fun ManagedDownloadStorage.buildMigrationCleanupReceipts(
    context: Context,
    copiedEntries: List<CopiedMigrationEntry>
): List<ManagedMigrationCleanupReceipt> {
    return copiedEntries.map { copied ->
        val targetDigest = if (
            !ManagedDownloadTreeNaming.isMetadataName(copied.original.entry.name)
        ) {
            copied.verifiedTargetDigest?.takeIf(String::isNotBlank)
                ?: copied.sourceDigest?.takeIf(String::isNotBlank)
                ?: readMigrationTargetDigest(context, copied.copiedEntry)
        } else {
        // 元数据引用在计算源摘要后才会改写，因此只有最终目标字节才是权威内容
            readMigrationTargetDigest(context, copied.copiedEntry)
        }
        ManagedMigrationCleanupReceipt(
            sourceReference = copied.original.entry.reference,
            sourceName = copied.original.entry.name,
            sourceSubdirectory = copied.original.subdirectory,
            targetEntry = copied.copiedEntry,
            targetDigest = targetDigest,
            sourceLogicalCreatedAtMs = copied.original.logicalCreatedAtMs(),
            sourceCreatedAtSource = copied.original.logicalCreatedAtSource(),
            sourceCreatedAtConfidence = copied.original.logicalCreatedAtConfidence()
        )
    }
}

internal suspend fun ManagedDownloadStorage.statMigrationTargetEntry(
    context: Context,
    targetRoot: RootHandle,
    expected: StoredEntry
): StoredEntry? {
    val reference = expected.reference.trim()
    if (!isMigrationReferenceBoundToRoot(targetRoot, reference)) return null
    val backendTarget = backendReference(context, reference) ?: return null
    return when (val result = backendTarget.backend.stat(backendTarget.reference)) {
        is StorageLookupResult.Found -> {
            result.value.toStoredEntryForBackend(
                (targetRoot as? RootHandle.FileRoot)?.dir
            ).takeUnless { entry ->
                entry.isDirectory || entry.name != expected.name
            }
        }
        StorageLookupResult.Missing,
        StorageLookupResult.PermissionLost,
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.ProviderFailure,
        is StorageLookupResult.Unsupported -> null
    }
}

internal suspend fun ManagedDownloadStorage.readMigrationTargetDigest(
    context: Context,
    entry: StoredEntry
): String {
    return when (val result = migrationEntryReader.read(context, entry) { input ->
        sha256MigrationContent(input)
    }) {
        is StorageLookupResult.Found -> result.value.getOrElse { error ->
            throw ManagedDownloadMigrationException.transient(
                "迁移目标校验暂时失败: ${entry.name}",
                error
            )
        }
        StorageLookupResult.Missing,
        StorageLookupResult.PermissionLost,
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported,
        is StorageLookupResult.ProviderFailure -> {
            throw ManagedDownloadMigrationException.transient(
                "迁移目标文件暂时不可用: ${entry.name}"
            )
        }
    }
}

internal fun ManagedDownloadStorage.areEquivalentMigrationTargetIdentity(
    expected: StoredEntry,
    actual: StoredEntry
): Boolean {
    if (
        expected.reference == actual.reference ||
        expected.mediaUri == actual.mediaUri ||
        expected.localFilePath != null &&
            expected.localFilePath == actual.localFilePath
    ) {
        return true
    }
    val expectedSafIdentity = migrationSafDocumentIdentity(expected.reference)
        ?: migrationSafDocumentIdentity(expected.mediaUri)
    val actualSafIdentity = migrationSafDocumentIdentity(actual.reference)
        ?: migrationSafDocumentIdentity(actual.mediaUri)
    return expectedSafIdentity != null && expectedSafIdentity == actualSafIdentity
}

internal fun ManagedDownloadStorage.migrationSafDocumentIdentity(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val uri = runCatching { normalized.toUri() }.getOrNull()
    if (
        uri != null &&
        (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank())
    ) {
        return null
    }
    val authority = uri?.authority ?: rawMigrationSafAuthority(normalized) ?: return null
    val documentId = rawMigrationSafDocumentId(normalized)
        ?: uri?.let { parsed ->
            runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
                ?: parsed.pathSegments.migrationDocumentIdFromSafPath()
                ?: parsed.pathSegments
                    .takeIf { segments -> segments.firstOrNull() == "tree" }
                    ?.let { segments ->
                        runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
                            ?: segments.getOrNull(1)
                    }
        }
        ?: return null
    return "${authority.lowercase(Locale.ROOT)}\u0000$documentId"
}

internal fun ManagedDownloadStorage.rawMigrationSafAuthority(value: String): String? {
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0 || !value.regionMatches(0, "content", 0, schemeEnd, true)) {
        return null
    }
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
    return value.substring(
        authorityStart,
        if (authorityEnd >= 0) authorityEnd else value.length
    ).takeIf(String::isNotBlank)
}

internal fun ManagedDownloadStorage.rawMigrationSafDocumentId(value: String): String? {
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val pathStart = value.indexOf('/', schemeEnd + 3)
    if (pathStart < 0) return null
    val pathEnd = value.indexOfAny(charArrayOf('?', '#'), pathStart)
        .let { end -> if (end >= 0) end else value.length }
    val segments = value.substring(pathStart, pathEnd)
        .split('/')
        .filter(String::isNotEmpty)
    return when {
        segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
        segments.size >= 2 && segments[0] == "document" -> segments[1]
        segments.size >= 2 && segments[0] == "tree" -> segments[1]
        else -> null
    }
}

internal fun ManagedDownloadStorage.verifyPreviouslyCommittedMigrationTarget(
    context: Context,
    targetRoot: RootHandle,
    toDirectoryUri: String?,
    minimumAudioCount: Int
) {
    if (minimumAudioCount <= 0) return
    val targetEntries = collectManagedMigrationEntries(
        context = context,
        root = targetRoot,
        allowMetadataLessAudio = shouldIndexMetadataLessAudio(toDirectoryUri)
    )
    val targetAudioCount = targetEntries.count { migrationEntry ->
        migrationEntry.subdirectory == null &&
            migrationEntry.entry.extension in audioExtensions
    }
    if (targetAudioCount < minimumAudioCount) {
        throw ManagedDownloadMigrationException.transient(
            "已提交的目标下载目录文件不足: " +
                "expected=$minimumAudioCount, actual=$targetAudioCount"
        )
    }
}

internal suspend fun ManagedDownloadStorage.cleanupMigrationReplacementBackups(
    context: Context,
    targetRoot: RootHandle,
    copiedEntries: List<CopiedMigrationEntry>,
    progressTracker: ManagedMigrationProgressReporter? = null
): ManagedMigrationCleanupResult {
    val candidates = copiedEntries.mapNotNull { copied ->
        copied.replacementBackup?.let { backup ->
            MigrationReplacementBackupCandidate(
                backup = backup,
                subdirectory = copied.original.subdirectory
            )
        }
    }.distinctBy { candidate -> candidate.backup.reference }
    if (candidates.isEmpty()) {
        return ManagedMigrationCleanupResult(
            failedFiles = 0,
            retryableFailedFiles = 0
        )
    }
    val total = candidates.size
    val refresh = try {
        treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(TAG, "迁移替换备份清理枚举失败: ${error.message}", error)
        null
    }
    if (refresh == null || !refresh.isComplete) {
        candidates.forEach { candidate ->
            progressTracker?.startCleanup(total, candidate.backup.name)
            progressTracker?.finishCleanup(candidate.backup.name)
        }
        return ManagedMigrationCleanupResult(
            failedFiles = total,
            retryableFailedFiles = total
        )
    }
    fun entriesFor(subdirectory: String?): List<StoredEntry> = when (subdirectory) {
        null -> refresh.rootEntries
        COVER_SUBDIRECTORY -> refresh.coverEntries
        LYRIC_SUBDIRECTORY -> refresh.lyricEntries
        else -> emptyList()
    }
    val validated = mutableListOf<ValidatedMigrationReplacementBackup>()
    var failed = 0
    var retryable = 0
    candidates.forEach { candidate ->
        val backup = candidate.backup
        progressTracker?.startCleanup(total, backup.name)
        val actual = entriesFor(candidate.subdirectory).firstOrNull { entry ->
            !entry.isDirectory && entry.name == backup.name
        }
        when {
            actual == null && isMissingReplacementBackup(context, targetRoot, backup) -> {
                progressTracker?.finishCleanup(backup.name)
            }
            actual == null -> {
                failed++
                NPLogger.w(TAG, "迁移替换备份不在当前目标树，保留: ${backup.reference}")
                progressTracker?.finishCleanup(backup.name)
            }
            !sameManagedMigrationStoredEntryIdentity(backup, actual) -> {
                failed++
                NPLogger.w(TAG, "迁移替换备份身份已变化，保留: ${backup.name}")
                progressTracker?.finishCleanup(backup.name)
            }
            else -> {
                validated += ValidatedMigrationReplacementBackup(
                    actual = actual
                )
            }
        }
    }
    if (validated.isNotEmpty()) {
        val references = validated.map { candidate ->
            trustedManagedRef(candidate.actual.reference)
        }
        val results = deleteEnumeratedMigrationReferences(
            context = context,
            references = references,
            root = targetRoot,
            trustedReferencesSnapshot = trustedReferencesFromMigrationRefresh(refresh),
            onDeleteStarted = { reference ->
                progressTracker?.startCleanup(total, reference.externalReference)
            },
            onDeleteFinished = { reference ->
                progressTracker?.finishCleanup(reference.externalReference)
            }
        )
        validated.forEach { candidate ->
            val reference = trustedManagedRef(candidate.actual.reference)
            val result = results[reference] ?: StorageMutationResult.ProviderFailure(
                IOException("replacement backup delete result missing")
            )
            if (!result.isConfirmedStorageMutation()) {
                failed++
                if (
                    result is StorageMutationResult.ProviderFailure ||
                    result is StorageMutationResult.PermissionLost
                ) {
                    retryable++
                }
                NPLogger.w(TAG, "迁移替换备份清理未确认: ${candidate.actual.reference}")
            }
        }
    }
    return ManagedMigrationCleanupResult(
        failedFiles = failed,
        retryableFailedFiles = retryable
    )
}
