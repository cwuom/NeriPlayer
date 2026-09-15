package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationProgress
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.commit.sameManagedMigrationStoredEntryIdentity
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCopyResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationNamePlan
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationNamePlanner
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationCopyReceipt
import moe.ouom.neriplayer.core.download.storage.migration.toCopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationSourceEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.canReuseMigrationTargetDigest
import moe.ouom.neriplayer.core.download.storage.migration.committedMigrationReceiptsMeetAudioMinimum
import moe.ouom.neriplayer.core.download.storage.migration.collectReusableMigrationCopyPairs
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationTargetNames
import moe.ouom.neriplayer.core.download.storage.migration.requireSuccessfulMigrationCopies
import moe.ouom.neriplayer.core.download.storage.migration.resolveMinimumMigrationAudioCount
import moe.ouom.neriplayer.core.download.storage.migration.reconcileMigrationSourceManifest
import moe.ouom.neriplayer.core.download.storage.migration.removeDeletedMigrationSources
import moe.ouom.neriplayer.core.download.storage.migration.planDeletedSourceCopyReceiptRecovery
import moe.ouom.neriplayer.core.download.storage.migration.persistedMigrationJournalTargetNames
import moe.ouom.neriplayer.core.download.storage.migration.shouldRetryActiveMigrationJournal
import moe.ouom.neriplayer.core.download.storage.migration.shouldUseDirectMigrationReceiptValidation
import moe.ouom.neriplayer.core.download.storage.migration.hasCompleteMigrationCleanupReceipts
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationCleanupReceipts
import moe.ouom.neriplayer.core.download.storage.migration.mergePersistedMigrationCopyReceipts
import moe.ouom.neriplayer.core.download.storage.migration.migrationSourceEntryCount
import moe.ouom.neriplayer.core.download.storage.migration.upgradeLegacyMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import java.io.File
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle

internal suspend fun ManagedDownloadStorage.migrateManagedDownloadsImpl(
    context: Context,
    fromDirectoryUri: String?,
    toDirectoryUri: String?,
    minimumSourceEntryCount: Int = 0,
    targetPreviouslyCommitted: Boolean = false,
    persistedTargetNames: Map<String, String> = emptyMap(),
    onSourceAudioCountResolved: suspend (Int) -> Unit = {},
    onTargetNamePlanResolved: suspend (Map<String, String>) -> Unit = {},
    onTargetVerified: suspend () -> Unit = {},
    persistedReplacementJournal: ManagedMigrationReplacementJournal? = null,
    replacementJournalWorkId: String = "",
    onReplacementJournalUpdated: suspend (ManagedMigrationReplacementJournal) -> Unit = {},
    persistedProgress: MigrationProgress? = null,
    progressOwnerWorkId: String? = null,
    persistedCopyReceipts: Map<String, ManagedMigrationCopyReceipt> = emptyMap(),
    onCopyReceipt: suspend (ManagedMigrationCopyReceipt) -> Unit = {},
    onCopyReceiptInvalidated: suspend (String) -> Unit = {},
    onCopyReceiptsFlush: suspend () -> Unit = {},
    pendingArtifactsPreflightVerified: Boolean = false
): MigrationResult = withContext(Dispatchers.IO) {
    val normalizedProgressOwner = progressOwnerWorkId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val ownsProgressSession = if (normalizedProgressOwner != null) {
        migrationProgressSession.ensure(normalizedProgressOwner, persistedProgress)
    } else {
        migrationProgressSession.ensureLegacy(persistedProgress)
    }
    if (!ownsProgressSession) {
        throw ManagedDownloadMigrationException.transient(
            "迁移进度会话已被更新的任务接管，等待重试"
        )
    }
    try {
        if (areEquivalentDirectoryUris(fromDirectoryUri, toDirectoryUri)) {
            return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
        }

        val targetRoot = resolveRoot(context, toDirectoryUri)
            ?: throw ManagedDownloadMigrationException.permanent("目标下载目录不可用")
        val sourceRoot = resolveRoot(context, fromDirectoryUri)
        val persistedPhase = persistedReplacementJournal?.phase
        if (persistedPhase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED) {
            val committedJournal = requireNotNull(persistedReplacementJournal)
            val committedMinimumAudioCount = resolveMinimumMigrationAudioCount(
                requestedMinimum = minimumSourceEntryCount,
                discoveredSourceAudioCount = 0,
                deletedSourceAudioCount = committedJournal.deletedSourceAudioCount
            )
            verifyCommittedMigrationReplacementJournal(
                context = context,
                targetRoot = targetRoot,
                journal = committedJournal
            )
            verifyMigrationCleanupReceipts(
                context = context,
                targetRoot = targetRoot,
                journal = committedJournal
            )
            if (!committedMigrationReceiptsMeetAudioMinimum(
                    committedJournal,
                    committedMinimumAudioCount
                )
            ) {
                verifyPreviouslyCommittedMigrationTarget(
                    context = context,
                    targetRoot = targetRoot,
                    toDirectoryUri = toDirectoryUri,
                    minimumAudioCount = committedMinimumAudioCount
                )
            }
            onTargetVerified()
            return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
        }
        if (shouldRetryActiveMigrationJournal(
                phase = persistedPhase,
                sourceRootAvailable = sourceRoot != null,
                sourceEntriesEmpty = false
            )
        ) {
            throw ManagedDownloadMigrationException.transient(
                "迁移源目录暂时不可用，保留事务等待恢复"
            )
        }
        if (sourceRoot == null) {
            if (!targetPreviouslyCommitted) {
                throw if (persistedPhase == null) {
                    ManagedDownloadMigrationException.permanent("源下载目录不可用")
                } else {
                    ManagedDownloadMigrationException.transient(
                        "迁移源目录暂时不可用，保留事务等待恢复"
                    )
                }
            }
            if (persistedPhase != null) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移源目录暂时不可用，保留事务等待恢复"
                )
            }
            verifyPreviouslyCommittedMigrationTarget(
                context = context,
                targetRoot = targetRoot,
                toDirectoryUri = toDirectoryUri,
                minimumAudioCount = resolveMinimumMigrationAudioCount(
                    requestedMinimum = minimumSourceEntryCount,
                    discoveredSourceAudioCount = 0,
                    deletedSourceAudioCount = persistedReplacementJournal
                        ?.deletedSourceAudioCount
                        ?: 0
                )
            )
            onTargetVerified()
            return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
        }

        // 目标目录也可能留有上次中断的临时目录，迁移前一并整体移除
        discardMigrationTemporaryDirectory(context, targetRoot)
        // pending 音频和 metadata 不是可迁移的正式媒体，迁移前直接移除
        // 应用自己的 .tmp 目录，避免半成品把目录变更卡在重试状态
        if (pendingArtifactsPreflightVerified) {
            NPLogger.d(
                TAG,
                "迁移复用已持有目录租约内的 pending 预检结果，跳过重复枚举"
            )
        } else {
            requireMigrationSourceHasNoPendingArtifacts(
                context = context,
                sourceRoot = sourceRoot
            )
        }

        val journalTargetNames = mergePersistedMigrationTargetNames(
            buildList {
                persistedReplacementJournal?.let { journal ->
                    add(persistedMigrationJournalTargetNames(journal))
                }
            }
        )
        val restoredManifest = persistedReplacementJournal?.let { journal ->
            restoreManagedMigrationEntriesFromJournal(
                root = sourceRoot,
                journal = journal,
                persistedTargetNames = mergePersistedMigrationTargetNames(
                    listOf(persistedTargetNames, journalTargetNames)
                )
            )
        }
        val persistedManifestEntries = restoredManifest?.entries
        val usePersistedManifest = persistedManifestEntries != null
        val entries = persistedManifestEntries ?: collectManagedMigrationEntries(
            context = context,
            root = sourceRoot,
            allowMetadataLessAudio = shouldIndexMetadataLessAudio(fromDirectoryUri)
        )
        val discoveredSourceAudioCount = entries.count { migrationEntry ->
            migrationEntry.subdirectory == null &&
                migrationEntry.entry.extension in audioExtensions
        }
        var minimumAudioCount = resolveMinimumMigrationAudioCount(
            requestedMinimum = minimumSourceEntryCount,
            discoveredSourceAudioCount = discoveredSourceAudioCount,
            deletedSourceAudioCount = persistedReplacementJournal
                ?.deletedSourceAudioCount
                ?: 0
        )
        onSourceAudioCountResolved(minimumAudioCount)
        val upgradedPersistedJournal = persistedReplacementJournal?.let { journal ->
            upgradeLegacyMigrationReplacementJournal(
                journal = journal,
                sourceEntryCount = entries.size
            )
        }
        // 只有完整的 Provider 列举才能确认源文件消失，清单快路径把判断交给
        // 复制 Worker，那里可以区分文件缺失和权限错误
        val deletedSourceRecoveryPlan = if (
            !usePersistedManifest &&
            upgradedPersistedJournal != null &&
            persistedCopyReceipts.isNotEmpty()
        ) {
            planDeletedSourceCopyReceiptRecovery(
                journal = upgradedPersistedJournal,
                currentSourceReferences = entries.map { it.entry.reference },
                copyReceipts = persistedCopyReceipts
            )
        } else {
            null
        }
        var persistedJournalForAttempt = upgradedPersistedJournal?.let { journal ->
            reconcileMigrationSourceManifest(
                journal = deletedSourceRecoveryPlan?.journal ?: journal,
                currentEntries = entries
            )
        }
        if (deletedSourceRecoveryPlan != null && persistedJournalForAttempt != null) {
            persistedJournalForAttempt = applyDeletedSourceCopyReceiptRecoveryPlan(
                context = context,
                targetRoot = targetRoot,
                journal = persistedJournalForAttempt,
                plan = deletedSourceRecoveryPlan
            )
        }
        if (persistedJournalForAttempt != null &&
            persistedJournalForAttempt != persistedReplacementJournal
        ) {
            onReplacementJournalUpdated(persistedJournalForAttempt)
            deletedSourceRecoveryPlan?.let { plan ->
                (plan.promoteCandidates + plan.rollbackCandidates + plan.preserveCandidates)
                    .map(ManagedMigrationCopyReceipt::sourceReference)
                    .distinct()
                    .forEach { sourceReference ->
                        onCopyReceiptInvalidated(sourceReference)
                    }
            }
            minimumAudioCount = resolveMinimumMigrationAudioCount(
                requestedMinimum = minimumSourceEntryCount,
                discoveredSourceAudioCount = discoveredSourceAudioCount,
                deletedSourceAudioCount = persistedJournalForAttempt.deletedSourceAudioCount
            )
            onSourceAudioCountResolved(minimumAudioCount)
        }
        if (!usePersistedManifest && persistedJournalForAttempt != null) {
            val journalForAttempt = persistedJournalForAttempt
            val currentSourceReferences = entries.mapTo(HashSet()) { entry ->
                entry.entry.reference.trim()
            }
            val journalReferences = buildSet {
                journalForAttempt.sourceEntries.forEach { entry ->
                    add(entry.sourceReference.trim())
                }
                journalForAttempt.replacements.forEach { replacement ->
                    add(replacement.sourceReference.trim())
                }
            }
            val missingSourceReferences = journalReferences.filterTo(linkedSetOf()) {
                it.isNotBlank() && it !in currentSourceReferences
            }
            val orphanRecovery = recoverOrphanedMigrationReplacements(
                context = context,
                targetRoot = targetRoot,
                journal = journalForAttempt,
                missingSourceReferences = missingSourceReferences,
                persistedCopyReceiptReferences = persistedCopyReceipts.keys
            )
            if (orphanRecovery.resolvedReferences.isNotEmpty()) {
                persistedJournalForAttempt = removeDeletedMigrationSources(
                    journal = journalForAttempt,
                    deletedReferences = orphanRecovery.resolvedReferences
                )
                onReplacementJournalUpdated(checkNotNull(persistedJournalForAttempt))
            }
            if (orphanRecovery.unresolvedReferences.isNotEmpty()) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移孤儿替换尚未收敛，保留事务等待恢复: " +
                        "count=${orphanRecovery.unresolvedReferences.size}"
                )
            }
        }
        fun verifyCommittedTargetBeforeReturningFailure() {
            if (!targetPreviouslyCommitted) return
            verifyPreviouslyCommittedMigrationTarget(
                context = context,
                targetRoot = targetRoot,
                toDirectoryUri = toDirectoryUri,
                minimumAudioCount = minimumAudioCount
            )
        }
        if (shouldRetryActiveMigrationJournal(
                phase = persistedPhase,
                sourceRootAvailable = true,
                sourceEntriesEmpty = entries.isEmpty(),
                cleanupReceiptComplete = persistedJournalForAttempt?.let {
                    hasCompleteMigrationCleanupReceipts(it)
                } == true,
                sourceEntryCountKnown = persistedJournalForAttempt?.sourceEntryCountKnown
                    ?: true,
                sourceEntriesIncomplete = false
            )
        ) {
            throw ManagedDownloadMigrationException.transient(
                "迁移源目录未返回完整文件列表，保留事务等待恢复"
            )
        }
        if (entries.isEmpty()) {
            val cleanupReceiptsReady = persistedJournalForAttempt?.let {
                hasCompleteMigrationCleanupReceipts(it)
            } == true
            if (cleanupReceiptsReady) {
                val journal = requireNotNull(persistedJournalForAttempt)
                verifyCommittedMigrationReplacementJournal(
                    context = context,
                    targetRoot = targetRoot,
                    journal = journal
                )
                verifyMigrationCleanupReceipts(
                    context = context,
                    targetRoot = targetRoot,
                    journal = journal
                )
                if (!committedMigrationReceiptsMeetAudioMinimum(journal, minimumAudioCount)) {
                    verifyPreviouslyCommittedMigrationTarget(
                        context = context,
                        targetRoot = targetRoot,
                        toDirectoryUri = toDirectoryUri,
                        minimumAudioCount = minimumAudioCount
                    )
                }
                onReplacementJournalUpdated(
                    journal.copy(
                        phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
                        cleanupComplete = true
                    )
                )
            }
            if (minimumAudioCount > 0) {
                if (!targetPreviouslyCommitted) {
                    throw ManagedDownloadMigrationException.permanent(
                        "源下载目录未返回已缓存的下载文件"
                    )
                }
                verifyPreviouslyCommittedMigrationTarget(
                    context = context,
                    targetRoot = targetRoot,
                    toDirectoryUri = toDirectoryUri,
                    minimumAudioCount = minimumAudioCount
                )
            }
            onTargetVerified()
            return@withContext MigrationResult(movedFiles = 0, skippedFiles = 0)
        }

        val receiptValidation = validateMigrationSourceCopyReceipts(
            context = context,
            sourceRoot = checkNotNull(sourceRoot),
            entries = entries,
            persistedCopyReceipts = persistedCopyReceipts,
            preferDirectStats = shouldUseDirectMigrationReceiptValidation(
                usePersistedManifest = usePersistedManifest,
                persistedReceiptCount = persistedCopyReceipts.size
            )
        )
        val validatedCopyReceipts = receiptValidation.receipts
        // 只刷新一次清单指纹，让恢复进度和复制判断使用当前 Provider 事实
        val migrationEntries = entries.map { entry ->
            val current = receiptValidation.sourceEntriesByReference[entry.entry.reference]
            if (current == null) {
                entry
            } else {
                entry.copy(
                    entry = entry.entry.copy(
                        sizeBytes = current.sizeBytes,
                        lastModifiedMs = current.lastModifiedMs
                    )
                )
            }
        }
        val metadataEntriesTotal = migrationEntries.count {
            ManagedDownloadTreeNaming.isMetadataName(it.entry.name)
        }
        val progressTracker = ManagedMigrationProgressReporter(
            totalFiles = migrationEntries.size,
            totalBytes = migrationEntries.sumOf { it.entry.sizeBytes.coerceAtLeast(0L) },
            metadataFilesTotal = metadataEntriesTotal,
            onProgress = { progress ->
                migrationProgressSession.publish(normalizedProgressOwner, progress)
            },
            initialProgress = persistedProgress
        )
        progressTracker.startPreparing(migrationEntries.firstOrNull()?.entry?.name)
        val persistedNames = mergePersistedMigrationTargetNames(
            buildList {
                add(persistedTargetNames)
                persistedJournalForAttempt?.let { journal ->
                    add(persistedMigrationJournalTargetNames(journal))
                }
            }
        )
        val receiptTargetIndex = if (usePersistedManifest) {
            buildMigrationTargetIndexFromReceipts(
                context = context,
                targetRoot = targetRoot,
                entries = migrationEntries,
                persistedCopyReceipts = persistedCopyReceipts,
                persistedTargetNames = persistedNames
            )
        } else {
            null
        }
        val targetIndex = receiptTargetIndex ?: buildMigrationTargetIndex(
            context = context,
            targetRoot = targetRoot,
            skipMetadataParsing = usePersistedManifest
        )
        NPLogger.d(
            TAG,
            "migration_resume target_index=" +
                (if (receiptTargetIndex != null) "receipts" else "provider_scan") +
                " entries=${migrationEntries.size} receipts=${persistedCopyReceipts.size}"
        )
        val sourceMetadataByAudioName = migrationEntries
            .asSequence()
            .filter { entry -> entry.subdirectory == null && entry.metadata != null }
            .associate { entry -> entry.entry.name to requireNotNull(entry.metadata) }
        val generatedNamePlan = if (usePersistedManifest) {
            ManagedMigrationNamePlan(targetNamesByReference = emptyMap())
        } else {
            buildMigrationNamePlan(
                entries = migrationEntries,
                targetIndex = targetIndex,
                sourceMetadataByAudioName = sourceMetadataByAudioName,
                replacementBackupNamespace = persistedJournalForAttempt?.backupNamespace
                    ?: replacementJournalWorkId.takeIf(String::isNotBlank)
                    ?: "migration"
            )
        }
        var namePlan = ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
            entries = migrationEntries.map(ManagedMigrationEntry::toRef),
            targetIndex = targetIndex,
            generatedPlan = generatedNamePlan,
            persistedTargetNames = persistedNames
        ) ?: generatedNamePlan
        namePlan = mergePersistedReplacementPlan(
            fromDirectoryUri = fromDirectoryUri,
            toDirectoryUri = toDirectoryUri,
            generatedPlan = namePlan,
            persistedJournal = persistedJournalForAttempt
        )
        onTargetNamePlanResolved(namePlan.targetNamesByReference)
        val freshSourceManifestByReference = migrationEntries
            .filter { entry ->
                entry.entry.reference in receiptValidation.sourceEntriesByReference
            }
            .associate { entry ->
                entry.entry.reference to ManagedMigrationSourceEntry(
                    sourceReference = entry.entry.reference,
                    sourceName = entry.entry.name,
                    sourceSubdirectory = entry.subdirectory,
                    sizeBytes = entry.entry.sizeBytes.coerceAtLeast(0L),
                    lastModifiedMs = entry.entry.lastModifiedMs.coerceAtLeast(0L),
                    logicalCreatedAtMs = entry.logicalCreatedAtMs(),
                    createdAtSource = entry.logicalCreatedAtSource(),
                    createdAtConfidence = entry.logicalCreatedAtConfidence()
                )
            }
        val sourceManifest = persistedJournalForAttempt?.sourceEntries
            ?.takeIf { it.isNotEmpty() }
            ?.map { persisted ->
                freshSourceManifestByReference[persisted.sourceReference]
                    ?: persisted
            }
            ?: migrationEntries.map { entry ->
                ManagedMigrationSourceEntry(
                    sourceReference = entry.entry.reference,
                    sourceName = entry.entry.name,
                    sourceSubdirectory = entry.subdirectory,
                    sizeBytes = entry.entry.sizeBytes.coerceAtLeast(0L),
                    lastModifiedMs = entry.entry.lastModifiedMs.coerceAtLeast(0L),
                    logicalCreatedAtMs = entry.logicalCreatedAtMs(),
                    createdAtSource = entry.logicalCreatedAtSource(),
                    createdAtConfidence = entry.logicalCreatedAtConfidence()
                )
            }
        var replacementJournal: ManagedMigrationReplacementJournal? =
            ManagedMigrationReplacementJournal(
                workId = replacementJournalWorkId.ifBlank {
                    persistedJournalForAttempt?.workId.orEmpty()
                },
                fromDirectoryUri = fromDirectoryUri,
                toDirectoryUri = toDirectoryUri,
                backupNamespace = persistedJournalForAttempt?.backupNamespace
                    ?: replacementJournalWorkId.ifBlank { "migration" },
                phase = ManagedMigrationReplacementJournalPhase.PLANNED,
                replacements = namePlan.replacementPlansByReference.values.toList(),
                targetNamesByReference = namePlan.targetNamesByReference,
                cleanupReceipts = persistedJournalForAttempt?.cleanupReceipts.orEmpty(),
                sourceEntryCount = migrationSourceEntryCount(
                    sourceEntries = sourceManifest,
                    cleanupReceipts = persistedJournalForAttempt
                        ?.cleanupReceipts
                        .orEmpty()
                ),
                sourceEntries = sourceManifest,
                deletedSourceAudioCount = persistedJournalForAttempt
                    ?.deletedSourceAudioCount
                    ?: 0,
                sourceEntriesComplete = true
            )
        replacementJournal?.let { onReplacementJournalUpdated(it) }

        val reusableCopyPairs = collectReusableMigrationCopyPairs(
            entries = migrationEntries,
            persistedCopyReceipts = validatedCopyReceipts,
            namePlan = namePlan,
            targetIndex = targetIndex
        )
        val reusableCopyEntries = reusableCopyPairs.map { pair -> pair.sourceEntry }
        val reusableCopiesByReference = reusableCopyPairs.associate { pair ->
            pair.sourceEntry.entry.reference to pair.receipt.toCopiedMigrationEntry(
                original = pair.sourceEntry,
                targetEntry = pair.targetEntry,
                reusedFromReceipt = true
            )
        }
        progressTracker.seedCompletedCopies(reusableCopyEntries)
        val entriesToCopy = migrationEntries.filterNot { entry ->
            entry.entry.reference in reusableCopiesByReference
        }

        val copyResults = coroutineScope {
            val entriesChannel = kotlinx.coroutines.channels.Channel<ManagedMigrationEntry>(
                capacity = migrationCopyParallelism(sourceRoot, targetRoot).coerceAtLeast(1)
            )
            val workers = List(
                migrationCopyParallelism(sourceRoot, targetRoot)
                    .coerceAtLeast(1)
                    .coerceAtMost(entriesToCopy.size.coerceAtLeast(1))
            ) {
                async(Dispatchers.IO) {
                    buildList {
                        for (migrationEntry in entriesChannel) {
                            val result = migrationCopyWorker.copyEntry(
                                context = context,
                                targetRoot = targetRoot,
                                migrationEntry = migrationEntry,
                                targetIndex = targetIndex,
                                namePlan = namePlan,
                                progressTracker = progressTracker,
                                resumeReceipt = validatedCopyReceipts[
                                    migrationEntry.entry.reference
                                ]
                            )
                            result.copiedEntry?.toCopyReceipt()?.let { receipt ->
                                onCopyReceipt(receipt)
                            }
                            add(result)
                        }
                    }
                }
            }
            entriesToCopy.forEach { entry ->
                entriesChannel.send(entry)
            }
            entriesChannel.close()
            workers.awaitAll().flatten()
        }
        // 复制结果落盘后才能改写元数据或清理源文件，让目标成为权威副本
        onCopyReceiptsFlush()
        val currentCopyReceipts = copyResults.mapNotNull { result ->
            result.copiedEntry?.toCopyReceipt()
        }
        val copyReceiptsForRecovery = mergeMigrationCopyReceiptsForRecovery(
            persisted = persistedCopyReceipts,
            current = currentCopyReceipts
        )
        val deletedSourceReferences = copyResults
            .asSequence()
            .filter(ManagedMigrationCopyResult::sourceDeleted)
            .mapNotNull { result -> result.sourceReference.trim().takeIf(String::isNotBlank) }
            .toSet()
        if (deletedSourceReferences.isNotEmpty()) {
            val deletedSourceReceiptPlan = replacementJournal?.let { journal ->
                planDeletedSourceCopyReceiptRecovery(
                    journal = journal,
                    currentSourceReferences = migrationEntries.asSequence()
                        .map { entry -> entry.entry.reference }
                        .filterNot(deletedSourceReferences::contains)
                        .toList(),
                    copyReceipts = copyReceiptsForRecovery
                )
            }
            replacementJournal = deletedSourceReceiptPlan?.let { plan ->
                applyDeletedSourceCopyReceiptRecoveryPlan(
                    context = context,
                    targetRoot = targetRoot,
                    journal = plan.journal,
                    plan = plan
                )
            } ?: replacementJournal
            val orphanRecovery = replacementJournal?.let { journal ->
                recoverOrphanedMigrationReplacements(
                    context = context,
                    targetRoot = targetRoot,
                    journal = journal,
                    missingSourceReferences = deletedSourceReferences,
                    persistedCopyReceiptReferences = copyReceiptsForRecovery.keys
                )
            }
            val unresolvedOrphanReferences = orphanRecovery
                ?.unresolvedReferences
                .orEmpty()
            replacementJournal = replacementJournal?.let { journal ->
                removeDeletedMigrationSources(
                    journal = journal,
                    deletedReferences = deletedSourceReferences
                        .filterNot(unresolvedOrphanReferences::contains)
                )
            }
            replacementJournal?.let { onReplacementJournalUpdated(it) }
            deletedSourceReceiptPlan?.let { plan ->
                (plan.promoteCandidates + plan.rollbackCandidates + plan.preserveCandidates)
                    .map(ManagedMigrationCopyReceipt::sourceReference)
                    .distinct()
                    .forEach { sourceReference ->
                        onCopyReceiptInvalidated(sourceReference)
                    }
            }
            if (unresolvedOrphanReferences.isNotEmpty()) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移孤儿替换尚未收敛，保留事务等待恢复: " +
                        "count=${unresolvedOrphanReferences.size}"
                )
            }
            minimumAudioCount = resolveMinimumMigrationAudioCount(
                requestedMinimum = minimumSourceEntryCount,
                discoveredSourceAudioCount = discoveredSourceAudioCount,
                deletedSourceAudioCount = replacementJournal
                    ?.deletedSourceAudioCount
                    ?: 0
            )
            onSourceAudioCountResolved(minimumAudioCount)
        }
        val newlyCopiedEntries = try {
            requireSuccessfulMigrationCopies(copyResults) { completedEntries ->
                rollbackMigratedEntries(context, completedEntries, targetRoot)
            }
        } catch (error: ManagedDownloadMigrationException) {
            copyResults.mapNotNull(ManagedMigrationCopyResult::copiedEntry)
                .map { copied -> copied.original.entry.reference }
                .distinct()
                .forEach { sourceReference -> onCopyReceiptInvalidated(sourceReference) }
            verifyCommittedTargetBeforeReturningFailure()
            throw error
        }
        val newlyCopiedByReference = newlyCopiedEntries.associateBy {
            it.original.entry.reference
        }
        var copiedEntries = migrationEntries.mapNotNull { entry ->
            reusableCopiesByReference[entry.entry.reference]
                ?: newlyCopiedByReference[entry.entry.reference]
        }

        suspend fun invalidateCopyReceipts(entriesToInvalidate: Iterable<CopiedMigrationEntry>) {
            entriesToInvalidate
                .map { copied -> copied.original.entry.reference }
                .distinct()
                .forEach { sourceReference -> onCopyReceiptInvalidated(sourceReference) }
        }

        val rewriteResult = rewriteMigratedMetadataReferences(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
            progressTracker = progressTracker
        )
        copiedEntries = rewriteResult.copiedEntries
        if (rewriteResult.failedFiles > 0) {
            invalidateCopyReceipts(copiedEntries)
            rollbackMigratedEntries(context, copiedEntries, targetRoot)
            verifyCommittedTargetBeforeReturningFailure()
            rewriteResult.error?.let { error -> throw error }
            return@withContext MigrationResult(
                movedFiles = 0,
                skippedFiles = rewriteResult.failedFiles
            )
        }

        val verificationResult = verifyMigratedEntries(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
            progressTracker = progressTracker,
            onEntryVerified = { copied -> onCopyReceipt(copied.toCopyReceipt()) }
        )
        onCopyReceiptsFlush()
        if (verificationResult.failedFiles > 0) {
            invalidateCopyReceipts(copiedEntries)
            rollbackMigratedEntries(context, copiedEntries, targetRoot)
            verifyCommittedTargetBeforeReturningFailure()
            verificationResult.error?.let { error -> throw error }
            return@withContext MigrationResult(
                movedFiles = 0,
                skippedFiles = verificationResult.failedFiles
            )
        }
        copiedEntries = verificationResult.verifiedEntries

        replacementJournal = replacementJournal?.let { journal ->
            try {
                val mergedJournal = journal.copy(
                    cleanupReceipts = mergePersistedMigrationCleanupReceipts(
                        persisted = journal.cleanupReceipts,
                        current = buildMigrationCleanupReceipts(
                            context = context,
                            copiedEntries = copiedEntries
                        )
                    )
                )
                if (!hasCompleteMigrationCleanupReceipts(mergedJournal)) {
                    throw ManagedDownloadMigrationException.transient(
                        "迁移清理凭据未覆盖全部源条目，保留源文件等待恢复"
                    )
                }
                mergedJournal
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                invalidateCopyReceipts(copiedEntries)
                rollbackMigratedEntries(context, copiedEntries, targetRoot)
                verifyCommittedTargetBeforeReturningFailure()
                throw error
            }
        }
        replacementJournal?.let { onReplacementJournalUpdated(it) }

        replacementJournal = replacementJournal?.copy(
            phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED
        )
        replacementJournal?.let { onReplacementJournalUpdated(it) }

        if (targetPreviouslyCommitted) {
            verifyPreviouslyCommittedMigrationTarget(
                context = context,
                targetRoot = targetRoot,
                toDirectoryUri = toDirectoryUri,
                minimumAudioCount = minimumAudioCount
            )
        }

        val replacementBackupCleanup = cleanupMigrationReplacementBackups(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
            progressTracker = progressTracker
        )
        if (replacementBackupCleanup.failedFiles > 0) {
            return@withContext MigrationResult(
                movedFiles = 0,
                skippedFiles = replacementBackupCleanup.failedFiles,
                cleanupFailedFiles = replacementBackupCleanup.failedFiles,
                cleanupRetryableFailedFiles =
                    replacementBackupCleanup.retryableFailedFiles
            )
        }

        val cleanupResult = cleanupMigratedEntriesDetailed(
            context = context,
            copiedEntries = copiedEntries,
            sourceRoot = sourceRoot,
            targetsAlreadyVerified = true,
            progressTracker = progressTracker
        )
        if (cleanupResult.failedFiles == 0) {
            replacementJournal = replacementJournal?.let { journal ->
                if (!hasCompleteMigrationCleanupReceipts(journal)) {
                    throw ManagedDownloadMigrationException.transient(
                        "迁移清理凭据未覆盖全部源条目，保留事务等待恢复"
                    )
                }
                journal.copy(
                    phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
                    cleanupComplete = true
                )
            }
            replacementJournal?.let { onReplacementJournalUpdated(it) }
            try {
                onTargetVerified()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "迁移目录已提交但目录设置回写失败，保留替换日志等待重试: " +
                        error.message,
                    error
                )
                throw ManagedDownloadMigrationException.transient(
                    "迁移目录已提交但目录设置回写失败，保留事务等待重试",
                    error
                )
            }
        }
        progressTracker.finishAll()

        invalidateSnapshotCache(context)

        MigrationResult(
            movedFiles = copiedEntries.size,
            skippedFiles = 0,
            cleanupFailedFiles = cleanupResult.failedFiles,
            cleanupRetryableFailedFiles = cleanupResult.retryableFailedFiles
        )
    } catch (error: ManagedDownloadRootProviderException) {
        throw ManagedDownloadMigrationException.transient(
            "DocumentsProvider 暂时无法访问迁移目录",
            error
        )
    } finally {
        if (normalizedProgressOwner == null && ownsProgressSession) {
            migrationProgressSession.finish(null)
        }
    }
}

internal suspend fun ManagedDownloadStorage.verifyMigrationCleanupReceiptsImpl(
    context: Context,
    targetRoot: RootHandle,
    journal: ManagedMigrationReplacementJournal
) = coroutineScope {
    if (journal.cleanupReceipts.isEmpty()) return@coroutineScope
    val verificationLimiter = Semaphore(
        migrationRewriteParallelism(targetRoot).coerceAtLeast(1)
    )
    journal.cleanupReceipts.map { receipt ->
        async(Dispatchers.IO) {
            verificationLimiter.withPermit {
                val target = statMigrationTargetEntry(
                    context = context,
                    targetRoot = targetRoot,
                    expected = receipt.targetEntry
                ) ?: throw ManagedDownloadMigrationException.transient(
                    "迁移清理凭据目标文件暂时不可用: ${receipt.targetEntry.name}"
                )
                if (!areEquivalentMigrationTargetIdentity(receipt.targetEntry, target)) {
                    throw ManagedDownloadMigrationException.targetChanged(
                        "迁移清理凭据目标文档已变化: ${receipt.targetEntry.name}"
                    )
                }
                val digest = if (
                    canReuseMigrationTargetDigest(
                        expectedSizeBytes = receipt.targetEntry.sizeBytes,
                        actualSizeBytes = target.sizeBytes,
                        expectedLastModifiedMs = receipt.targetEntry.lastModifiedMs,
                        actualLastModifiedMs = target.lastModifiedMs
                    )
                ) {
                    receipt.targetDigest
                } else {
                    readMigrationTargetDigest(context, target)
                }
                if (!digest.equals(receipt.targetDigest, ignoreCase = true)) {
                    throw ManagedDownloadMigrationException.targetChanged(
                        "迁移清理凭据目标内容已变化: ${receipt.targetEntry.name}"
                    )
                }
            }
        }
    }.awaitAll()
}

internal fun ManagedDownloadStorage.isRestoredMigrationReplacementTargetImpl(
    expectedTarget: StoredEntry,
    actualTarget: StoredEntry,
    replacementBackup: StoredEntry,
    targetDigest: String? = null,
    expectedTargetDigest: String? = null
): Boolean {
    if (actualTarget.isDirectory || actualTarget.name != expectedTarget.name) {
        return false
    }
    val sameExpectedIdentity = sameManagedMigrationStoredEntryIdentity(
        expectedTarget,
        actualTarget
    )
    val sameBackupIdentity = sameManagedMigrationStoredEntryIdentity(
        replacementBackup,
        actualTarget
    )
    if (sameBackupIdentity && !sameExpectedIdentity) {
        return true
    }
    if (!sameExpectedIdentity) {
        return false
    }
    if (
        replacementBackup.localFilePath.isNullOrBlank() ||
        actualTarget.localFilePath.isNullOrBlank() ||
        replacementBackup.sizeBytes <= 0L ||
        actualTarget.sizeBytes <= 0L ||
        replacementBackup.sizeBytes != actualTarget.sizeBytes ||
        replacementBackup.lastModifiedMs <= 0L ||
        actualTarget.lastModifiedMs <= 0L ||
        replacementBackup.lastModifiedMs != actualTarget.lastModifiedMs
    ) {
        return false
    }
    return !targetDigest.isNullOrBlank() &&
        !expectedTargetDigest.isNullOrBlank() &&
        !targetDigest.equals(expectedTargetDigest, ignoreCase = true)
}

internal fun ManagedDownloadStorage.mergeMigrationCopyReceiptsForRecoveryImpl(
    persisted: Map<String, ManagedMigrationCopyReceipt>,
    current: Iterable<ManagedMigrationCopyReceipt>
): Map<String, ManagedMigrationCopyReceipt> {
    val currentReceipts = current.toList()
    if (currentReceipts.isEmpty()) return persisted
    return mergePersistedMigrationCopyReceipts(
        current = currentReceipts,
        checkpoints = listOf(persisted.values)
    )
}

internal fun ManagedDownloadStorage.releasePersistedDirectoryPermissionImpl(context: Context, uriString: String?) {
    val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }.onFailure {
        NPLogger.w(TAG, "释放下载目录权限失败: ${it.message}")
    }
}

internal fun ManagedDownloadStorage.createWorkingFileImpl(
    context: Context,
    songKey: String,
    fileName: String,
    operationId: String? = null
): File {
    return ManagedDownloadRecoveryFiles.createWorkingFile(
        context,
        songKey,
        fileName,
        operationId
    )
}

internal fun ManagedDownloadStorage.upsertPendingDownloadQueueImpl(
    context: Context,
    songs: List<SongItem>,
    userInitiated: Boolean = false,
    requiresWifiNetwork: Boolean = true,
    downloadAudioQuality: DownloadAudioQualitySelection? = null
): List<String> {
    return ManagedDownloadRecoveryFiles.upsertPendingDownloadQueue(
        context = context,
        songs = songs,
        userInitiated = userInitiated,
        requiresWifiNetwork = requiresWifiNetwork,
        downloadAudioQuality = downloadAudioQuality
    )
}

internal fun ManagedDownloadStorage.removePendingDownloadQueueOperationIdsImpl(
    context: Context,
    operationIds: Collection<String>
) {
    ManagedDownloadRecoveryFiles.removePendingDownloadQueueOperationIds(
        context,
        operationIds
    )
}
