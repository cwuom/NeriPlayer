package moe.ouom.neriplayer.core.download.storage.operation.lifecycle

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.operation.content.deleteReferencesInternal
import moe.ouom.neriplayer.core.download.storage.operation.content.forgetDeletedReferencesFromCaches
import moe.ouom.neriplayer.core.download.storage.operation.content.inspectStorageReference
import moe.ouom.neriplayer.core.download.storage.operation.content.invalidateSnapshotCache
import moe.ouom.neriplayer.core.download.storage.operation.content.isDurableCoreMetadata
import moe.ouom.neriplayer.core.download.storage.operation.content.isTreePromotionBackupName
import moe.ouom.neriplayer.core.download.storage.operation.content.notifyLyricsRefresh
import moe.ouom.neriplayer.core.download.storage.operation.content.readTextInternal
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveCurrentTreePendingAudioSize
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveSnapshotForIndexedLookup
import moe.ouom.neriplayer.core.download.storage.operation.content.rootKeyForResolvedRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.writeRootText
import moe.ouom.neriplayer.core.download.storage.operation.findAudioEntry
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.storage.operation.shouldIndexMetadataLessAudio
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadLibrarySnapshot
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.ExactRootEntryLookup
import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.migration.plan.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCleanupResult
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationMetadataRewriteResult
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationProgressReporter
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationVerificationResult
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import java.io.File
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.refreshDownloadSidecarSnapshotBlocking(
    context: Context,
    snapshot: DownloadLibrarySnapshot,
    respectThrottle: Boolean,
    refreshCovers: Boolean = true
): DownloadLibrarySnapshot {
    val captured = snapshotCacheStore.captureSnapshot(
        context = context,
        restorePersisted = false
    )
    val activeSnapshot = captured.snapshot
        ?: return snapshotCacheStore.currentSnapshotOrPartial(context)
    val cacheKey = captured.cacheKey
    fun selectCurrent(candidate: DownloadLibrarySnapshot): DownloadLibrarySnapshot {
        return snapshotCacheStore.selectSnapshotIfUnchanged(
            context = context,
            cacheKey = cacheKey,
            snapshot = candidate,
            expectedRevision = captured.revision
        ).snapshot
    }
    if (
        shouldSkipRedundantForcedSidecarRefresh(
            requestedSnapshot = snapshot,
            activeSnapshot = activeSnapshot,
            respectThrottle = respectThrottle
        )
    ) {
        return selectCurrent(activeSnapshot)
    }
    synchronized(sidecarRefreshLock) {
        val nowMs = System.currentTimeMillis()
        if (
            respectThrottle &&
                lastSidecarRefreshKey == cacheKey &&
                nowMs - lastSidecarRefreshAtMs < SIDECAR_REFRESH_THROTTLE_MS
        ) {
            return selectCurrent(activeSnapshot)
        }
        val root = resolveRootBlocking(context)
        if (rootKeyForResolvedRoot(root) != cacheKey) {
            return selectCurrent(activeSnapshot)
        }
        val coverRefresh = if (refreshCovers) {
            val standardCovers = treeDirectories.refreshSubdirectoryEntries(
                context = context,
                root = root,
                subdirectory = COVER_SUBDIRECTORY
            )
            standardCovers
        } else {
            ManagedDownloadTreeDirectories.SubdirectoryEntriesRefresh(
                entries = activeSnapshot.coverEntriesByName.values.toList(),
                isComplete = true
            )
        }
        val lyricRefresh = treeDirectories.refreshSubdirectoryEntries(
            context = context,
            root = root,
            subdirectory = LYRIC_SUBDIRECTORY
        )
        if (!coverRefresh.isComplete || !lyricRefresh.isComplete) {
            NPLogger.w(
                TAG,
                "下载侧载目录刷新不完整，保留旧索引: " +
                    "coversComplete=${coverRefresh.isComplete}, " +
                    "lyricsComplete=${lyricRefresh.isComplete}"
            )
            // buildDownloadLibrarySnapshot 已经把可用的旧侧载合并进 requested
            // snapshot。返回 activeSnapshot 会把同一轮更新的音频核心条目回退掉，
            // 进而让刚提交的歌曲暂时变白
            return selectCurrent(snapshot)
        }
        lastSidecarRefreshKey = cacheKey
        lastSidecarRefreshAtMs = System.currentTimeMillis()
        val updatedSnapshot = ManagedDownloadSnapshotIndex.applySidecarRefresh(
            snapshot = activeSnapshot,
            coverEntries = coverRefresh.entries,
            lyricEntries = lyricRefresh.entries
        )
        if (updatedSnapshot !== activeSnapshot) {
            val publication = snapshotCacheStore.publishSnapshotIfUnchanged(
                context = context,
                cacheKey = cacheKey,
                snapshot = updatedSnapshot,
                expectedRevision = captured.revision
            )
            if (publication.published) {
                notifyLyricsRefresh()
            }
            // 并发 core 提交优先于本轮侧载刷新，调用方必须使用实际生效的快照
            return publication.snapshot
        }
        return selectCurrent(updatedSnapshot)
    }
}

internal fun ManagedDownloadStorage.findDownloadedAudioBlocking(
    context: Context,
    song: SongItem,
    forceRefresh: Boolean = false
): StoredEntry? {
    val snapshot = buildDownloadLibrarySnapshotBlocking(context, forceRefresh = forceRefresh)
    val entry = findAudioEntry(snapshot, song) ?: return null
    if (
        resolveStoredEntryPlaybackUri(entry)?.let { playbackUri ->
            inspectStorageReference(context, playbackUri)
        } ==
            ManagedDownloadReferenceIo.AccessResult.Accessible
    ) {
        return entry
    }
    if (forceRefresh) {
        return null
    }
    return findDownloadedAudioBlocking(context, song, forceRefresh = true)
}

internal fun ManagedDownloadStorage.composeSnapshot(
    audioEntries: List<StoredEntry>,
    metadataEntries: List<StoredEntry>,
    metadataByAudioName: Map<String, DownloadedAudioMetadata>,
    coverEntries: List<StoredEntry>,
    lyricEntries: List<StoredEntry>,
    rootEntriesComplete: Boolean = true,
    sidecarEntriesComplete: Boolean = true,
    pendingAudioEntries: List<StoredEntry> = emptyList(),
    pendingMetadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
    rootEmptyConfirmationPending: Boolean = false
): DownloadLibrarySnapshot {
    return ManagedDownloadSnapshotIndex.compose(
        audioEntries = audioEntries,
        metadataEntries = metadataEntries,
        metadataByAudioName = metadataByAudioName,
        coverEntries = coverEntries,
        lyricEntries = lyricEntries,
        rootEntriesComplete = rootEntriesComplete,
        rootEmptyConfirmationPending = rootEmptyConfirmationPending,
        sidecarEntriesComplete = sidecarEntriesComplete,
        pendingAudioEntries = pendingAudioEntries,
        pendingMetadataByAudioName = pendingMetadataByAudioName
    )
}

internal suspend fun ManagedDownloadStorage.rewriteMigratedMetadataReferences(
    context: Context,
    targetRoot: RootHandle,
    copiedEntries: List<CopiedMigrationEntry>,
    progressTracker: ManagedMigrationProgressReporter? = null
): ManagedMigrationMetadataRewriteResult {
    return migrationFinalizer.rewriteMigratedMetadataReferences(
        context = context,
        targetRoot = targetRoot,
        copiedEntries = copiedEntries,
        progressTracker = progressTracker
    )
}

internal suspend fun ManagedDownloadStorage.cleanupMigratedEntriesDetailed(
    context: Context,
    copiedEntries: List<CopiedMigrationEntry>,
    sourceRoot: RootHandle,
    targetsAlreadyVerified: Boolean = false,
    progressTracker: ManagedMigrationProgressReporter? = null
): ManagedMigrationCleanupResult {
    return migrationFinalizer.cleanupMigratedEntriesDetailed(
        context = context,
        copiedEntries = copiedEntries,
        sourceRoot = sourceRoot,
        targetsAlreadyVerified = targetsAlreadyVerified,
        progressTracker = progressTracker
    )
}

internal suspend fun ManagedDownloadStorage.verifyMigratedEntries(
    context: Context,
    targetRoot: RootHandle,
    copiedEntries: List<CopiedMigrationEntry>,
    progressTracker: ManagedMigrationProgressReporter? = null,
    onEntryVerified: suspend (CopiedMigrationEntry) -> Unit = {}
): ManagedMigrationVerificationResult {
    return migrationFinalizer.verifyMigratedEntriesDetailed(
        context = context,
        targetRoot = targetRoot,
        copiedEntries = copiedEntries,
        progressTracker = progressTracker,
        onEntryVerified = onEntryVerified
    )
}

internal suspend fun ManagedDownloadStorage.rollbackMigratedEntries(
    context: Context,
    copiedEntries: List<CopiedMigrationEntry>,
    targetRoot: RootHandle
): Int {
    return migrationFinalizer.rollbackMigratedEntries(
        context = context,
        copiedEntries = copiedEntries,
        targetRoot = targetRoot
    )
}

internal fun ManagedDownloadStorage.shouldIndexMetadataLessAudio(): Boolean {
    return shouldIndexMetadataLessAudio(settings.configuredDirectoryUri)
}

internal fun ManagedDownloadStorage.findMetadataForAudioBlocking(
    context: Context,
    audio: StoredEntry,
    rootOverride: RootHandle? = null
): StoredEntry? {
    val snapshot = rootOverride?.let { null } ?: resolveSnapshotForIndexedLookup(context)
    val canonicalAudioName = ManagedDownloadTreeNaming.canonicalLookupName(audio.name)
    val canonicalLogicalName = ManagedDownloadTreeNaming.canonicalLookupName(audio.logicalName)
    return snapshot?.metadataEntriesByAudioName?.get(audio.logicalName)
        ?: snapshot?.metadataEntriesByCanonicalAudioName?.get(canonicalAudioName)
        ?: snapshot?.metadataEntriesByCanonicalAudioName?.get(canonicalLogicalName)
        ?: findMetadataByDirectLookup(context, audio, rootOverride)
}

internal fun ManagedDownloadStorage.findMetadataByDirectLookup(
    context: Context,
    audio: StoredEntry,
    rootOverride: RootHandle? = null
): StoredEntry? {
    val logicalAudioName = audio.logicalName
    val metadataName = "$logicalAudioName$METADATA_SUFFIX"
    val pendingMetadataName = "$logicalAudioName$PENDING_METADATA_SUFFIX"
    val root = rootOverride ?: resolveRootBlocking(context)

    fun findInRoot(candidateRoot: RootHandle): StoredEntry? {
        return when (candidateRoot) {
            is RootHandle.FileRoot -> {
                val metadataFile = File(candidateRoot.dir, metadataName)
                if (metadataFile.exists() && metadataFile.isFile) {
                    metadataFile.toStoredEntry()
                } else {
                    val pendingFile = File(candidateRoot.dir, pendingMetadataName)
                    if (pendingFile.exists() && pendingFile.isFile) {
                        pendingFile.toStoredEntry()
                    } else {
                        candidateRoot.dir.listFiles()
                            ?.asSequence()
                            ?.filter { file ->
                                ManagedDownloadTreeNaming.metadataNameOrdinal(
                                    file.name,
                                    audio.name
                                ) != null
                            }
                            ?.minWithOrNull(
                                compareBy<File>(
                                    {
                                        ManagedDownloadTreeNaming.metadataNameOrdinal(
                                            it.name,
                                            audio.name
                                        ) ?: Int.MAX_VALUE
                                    },
                                    { it.name }
                                )
                            )
                            ?.takeIf(File::isFile)
                            ?.toStoredEntry()
                    }
                }
            }

            is RootHandle.TreeRoot -> {
                fun selectMetadata(
                    children: Collection<QueriedTreeChild>
                ): StoredEntry? = children.asSequence()
                    .filterNot(QueriedTreeChild::isDirectory)
                    .filter { child -> child.name == metadataName }
                    .firstOrNull()
                    ?.toStoredEntry()
                    ?: children.asSequence()
                        .filterNot(QueriedTreeChild::isDirectory)
                        .filter { child ->
                            ManagedDownloadTreeNaming.metadataNameOrdinal(
                                child.name,
                                audio.name
                            ) != null
                        }
                        .minWithOrNull(
                            compareBy<QueriedTreeChild>(
                                {
                                    ManagedDownloadTreeNaming.metadataNameOrdinal(
                                        it.name,
                                        audio.name
                                    ) ?: Int.MAX_VALUE
                                },
                                { it.name }
                            )
                        )
                        ?.toStoredEntry()
                treeChildRegistry.peekTreeChildren(candidateRoot.tree)
                    ?.let(::selectMetadata)
                    ?: selectMetadata(
                        treeChildRegistry.cachedTreeChildren(
                            context = context,
                            parent = candidateRoot.tree,
                            maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
                        )
                    )
            }
        }
    }

    // 正式 metadata 仍在根目录，先查根以兼容旧版本和已提交音频
    findInRoot(root)?.let { return it }
    if (!audio.isPendingAudioWrite) return null

    // 新版本 pending 音频和 pending metadata 同处 .tmp，避免根目录污染
    val temporaryRoot = resolveTemporaryRoot(context, root, create = false)
    return temporaryRoot?.let(::findInRoot)
}

internal fun ManagedDownloadStorage.promotePendingCoreMetadata(
    context: Context,
    root: RootHandle,
    audio: StoredEntry,
    metadataEntry: StoredEntry,
    rawMetadata: String,
    metadata: DownloadedAudioMetadata,
    finalAudioName: String
): Boolean {
    val rewrittenMetadata = rewritePendingMetadataAudioFileName(
        rawMetadata = rawMetadata,
        finalAudioName = finalAudioName
    ) ?: return false
    val expectedMetadata = parseDownloadedAudioMetadataJson(rewrittenMetadata)
        ?.takeIf(::isDurableCoreMetadata)
        ?.takeIf { candidate ->
            resolveStagedPendingPromotionFinalName(
                requestedName = audio.logicalName,
                stagedMetadata = candidate,
                expectedStableKey = metadata.stableKey,
                expectedOperationId = metadata.operationId
            ) == finalAudioName
        }
        ?: return false
    val finalMetadataName = "$finalAudioName$METADATA_SUFFIX"
    val sourceIsFinalMetadata =
        !ManagedDownloadTreeNaming.isPendingMetadataName(
            metadataEntry.name,
            audio.logicalName
        ) &&
            ManagedDownloadTreeNaming.isExactTreeStoredName(
                metadataEntry.name,
                finalMetadataName
            )
    val existingLookup = findExactEntryInRoot(
        context = context,
        root = root,
        name = finalMetadataName
    )
    if (!existingLookup.complete) {
        NPLogger.w(
            TAG,
            "迁移前 metadata 目标枚举不完整，保留 pending 凭据: " +
                "name=$finalMetadataName"
        )
        return false
    }
    val existing = if (sourceIsFinalMetadata) metadataEntry else existingLookup.entry
    if (existing != null) {
        val existingMetadata = readTextInternal(context, existing.reference)
            ?.let(::parseDownloadedAudioMetadataJson)
        if (
            existingMetadata == null ||
                !matchesPendingPromotionIdentity(
                    stagedMetadata = existingMetadata,
                    expectedStableKey = metadata.stableKey,
                    expectedOperationId = metadata.operationId
                ) ||
                (
                    !sourceIsFinalMetadata &&
                        resolveStagedPendingPromotionFinalName(
                            requestedName = audio.logicalName,
                            stagedMetadata = existingMetadata,
                            expectedStableKey = metadata.stableKey,
                            expectedOperationId = metadata.operationId
                        ) != finalAudioName
                    )
        ) {
            NPLogger.w(
                TAG,
                "迁移前发现不同歌曲占用 metadata 名称，保留两份凭据: " +
                    "name=$finalMetadataName"
            )
            return false
        }
    }
    val written = writeRootText(
        context = context,
        root = root,
        displayName = finalMetadataName,
        content = rewrittenMetadata,
        expectedAbsent = existing == null,
        knownTargetEntry = existing
    ) ?: return false
    if (!ManagedDownloadTreeNaming.isExactTreeStoredName(written.name, finalMetadataName)) {
        NPLogger.w(
            TAG,
            "迁移前 metadata 写入返回非目标名称，保留 pending 凭据: " +
                "expected=$finalMetadataName, actual=${written.name}"
        )
        invalidateSnapshotCache(context)
        return false
    }
    if (
        readTextInternal(context, written.reference)
            ?.let(::parseDownloadedAudioMetadataJson)
            ?.takeIf(::isDurableCoreMetadata)
            ?.takeIf { candidate ->
                candidate.audioFileName == finalAudioName &&
                    resolveStagedPendingPromotionFinalName(
                        requestedName = audio.logicalName,
                        stagedMetadata = candidate,
                        expectedStableKey = metadata.stableKey,
                        expectedOperationId = metadata.operationId
                    ) == finalAudioName &&
                    isMetadataWriteVerified(expectedMetadata, candidate)
            } == null
    ) {
        return false
    }
    invalidateSnapshotCache(context)
    return true
}

internal suspend fun ManagedDownloadStorage.cleanupPendingCoreMetadataAfterAudioPromotion(
    context: Context,
    root: RootHandle,
    audio: StoredEntry,
    metadataEntry: StoredEntry
) {
    if (!ManagedDownloadTreeNaming.isPendingMetadataName(metadataEntry.name, audio.logicalName)) {
        return
    }
    val sourceReleased = isPendingAudioPromotionSourceReleased(
        context = context,
        root = root,
        audio = audio
    )
    cleanupPendingCoreMetadataAfterAudioPromotion(
        context = context,
        root = root,
        audio = audio,
        metadataEntry = metadataEntry,
        sourceReleased = sourceReleased
    )
}

internal fun ManagedDownloadStorage.cleanupPendingCoreMetadataAfterAudioPromotion(
    context: Context,
    root: RootHandle,
    audio: StoredEntry,
    metadataEntry: StoredEntry,
    sourceReleased: Boolean
) {
    if (!sourceReleased) {
        NPLogger.w(
            TAG,
            "迁移前音频已提升但 pending 音频清理未确认，保留配对 metadata: " +
                "audio=${audio.name}"
        )
        return
    }
    val deletedReferences = deleteReferencesInternal(
        context = context,
        references = listOf(metadataEntry.reference),
        allowedRoot = root,
        trustedReferences = setOf(metadataEntry.reference),
        invalidateSnapshot = false
    )
    if (metadataEntry.reference !in deletedReferences) {
        NPLogger.w(
            TAG,
            "迁移前音频已提升但 pending metadata 清理未确认，保留后续恢复: " +
                "name=${metadataEntry.name}"
        )
        return
    }
    forgetDeletedReferencesFromCaches(setOf(metadataEntry.reference))
    invalidateSnapshotCache(context)
}

internal suspend fun ManagedDownloadStorage.isPendingAudioPromotionSourceReleased(
    context: Context,
    root: RootHandle,
    audio: StoredEntry
): Boolean {
    return when (root) {
        is RootHandle.FileRoot -> {
            val pending = File(audio.reference)
            !pending.exists()
        }

        is RootHandle.TreeRoot -> {
            val reference = runCatching { StorageReference.SafRef(audio.reference.toUri()) }
                .getOrNull() ?: return false
            when (val stat = SafStorageBackend(context).stat(reference)) {
                StorageLookupResult.Missing -> true
                is StorageLookupResult.Found -> {
                    !stat.value.isDirectory &&
                        !ManagedDownloadPendingAudioWriteNames.isArtifactName(
                            stat.value.displayName
                        )
                }

                StorageLookupResult.PermissionLost,
                is StorageLookupResult.ProviderFailure,
                StorageLookupResult.OutOfScope,
                is StorageLookupResult.Unsupported -> false
            }
        }
    }
}

internal suspend fun ManagedDownloadStorage.resolvePendingCorePromotionFinalName(
    context: Context,
    root: RootHandle,
    audio: StoredEntry,
    metadataEntry: StoredEntry,
    metadata: DownloadedAudioMetadata
): String? {
    val rootRefresh = treeDirectories.refreshRootEntries(context, root)
    if (!rootRefresh.isComplete) {
        return null
    }
    val expectedStableKey = metadata.stableKey?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    val expectedSizeBytes = pendingAudioPromotionExpectedSizeForPlanning(
        context = context,
        root = root,
        audio = audio
    )
    val directTargets = rootRefresh.entries.filter { entry ->
        !entry.isDirectory &&
            ManagedDownloadTreeNaming.isExactTreeStoredName(
                entry.name,
                audio.logicalName
            )
    }
    val directTarget = directTargets.singleOrNull()
    val directTargetConflicts = directTarget == null && directTargets.isNotEmpty() ||
        directTarget?.let { entry ->
            entry.extension !in audioExtensions ||
                expectedSizeBytes == null ||
                entry.sizeBytes <= 0L ||
                entry.sizeBytes != expectedSizeBytes
        } == true
    val stagedNames = rootRefresh.entries
        .asSequence()
        .filterNot(StoredEntry::isDirectory)
        .mapNotNull { entry ->
            val stagedAudioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                ?: return@mapNotNull null
            if (ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, stagedAudioName)) {
                return@mapNotNull null
            }
            val stagedMetadata = readTextInternal(context, entry.reference)
                ?.let(::parseDownloadedAudioMetadataJson)
                ?.takeIf(::isDurableCoreMetadata)
                ?: return@mapNotNull null
            val finalAudioName = resolveStagedPendingPromotionFinalName(
                requestedName = audio.logicalName,
                stagedMetadata = stagedMetadata,
                expectedStableKey = expectedStableKey,
                expectedOperationId = metadata.operationId
            ) ?: return@mapNotNull null
            finalAudioName.takeIf { candidate ->
                ManagedDownloadTreeNaming.isExactTreeStoredName(
                    stagedAudioName,
                    candidate
                )
            }
        }
        .distinct()
        .toList()
    val renamedStagedNames = stagedNames.filterNot { candidate ->
        ManagedDownloadTreeNaming.isExactTreeStoredName(
            candidate,
            audio.logicalName
        )
    }
    if (renamedStagedNames.size > 1) {
        NPLogger.w(
            TAG,
            "迁移前发现同一 pending 凭据对应多个最终名称，保留等待恢复: " +
                "audio=${audio.logicalName}, candidates=$renamedStagedNames"
        )
        return null
    }
    renamedStagedNames.singleOrNull()?.let { return it }
    if (!directTargetConflicts) {
        return audio.logicalName
    }
    val temporary = readTemporaryDirectoryEntries(
        context = context,
        root = root,
        forceRefresh = true,
        rootAlreadyRefreshed = true
    )
    if (!temporary.isComplete) {
        return null
    }
    val sourceReferences = setOf(audio.reference, metadataEntry.reference)
    return resolvePendingAudioPromotionFinalName(
        enumerationComplete = true,
        existingNames = (rootRefresh.entries + temporary.entries)
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .filterNot { entry -> entry.reference in sourceReferences }
            .map(StoredEntry::name)
            .toList(),
        requestedName = audio.logicalName
    )
}

internal suspend fun ManagedDownloadStorage.pendingAudioPromotionExpectedSizeForPlanning(
    context: Context,
    root: RootHandle,
    audio: StoredEntry
): Long? {
    return when (root) {
        is RootHandle.FileRoot -> File(audio.reference)
            .takeIf(File::isFile)
            ?.length()
            ?.takeIf { size -> size > 0L }

        is RootHandle.TreeRoot -> {
            val reference = runCatching { StorageReference.SafRef(audio.reference.toUri()) }
                .getOrNull() ?: return null
            val backend = SafStorageBackend(context)
            when (val stat = backend.stat(reference)) {
                is StorageLookupResult.Found -> stat.value
                    .takeUnless(StorageStat::isDirectory)
                    ?.let { current ->
                        try {
                            resolveCurrentTreePendingAudioSize(
                                backend = backend,
                                reference = reference,
                                reportedSizeBytes = current.sizeBytes,
                                description = audio.name
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            NPLogger.w(
                                TAG,
                                "迁移前 pending 音频大小读回失败，保留凭据: " +
                                    "audio=${audio.logicalName}, error=${error.message}",
                                error
                            )
                            null
                        }
                    }

                StorageLookupResult.Missing,
                StorageLookupResult.PermissionLost,
                is StorageLookupResult.ProviderFailure,
                StorageLookupResult.OutOfScope,
                is StorageLookupResult.Unsupported -> null
            }
        }
    }
}

internal fun ManagedDownloadStorage.matchesPendingPromotionIdentity(
    stagedMetadata: DownloadedAudioMetadata,
    expectedStableKey: String?,
    expectedOperationId: String?
): Boolean {
    val normalizedStableKey = expectedStableKey?.trim()?.takeIf(String::isNotBlank)
        ?: return false
    if (stagedMetadata.stableKey?.trim() != normalizedStableKey) {
        return false
    }
    val normalizedOperationId = expectedOperationId?.trim()?.takeIf(String::isNotBlank)
        ?: return true
    return stagedMetadata.operationId?.trim() == normalizedOperationId
}

internal fun ManagedDownloadStorage.isPendingAudioPromotionFinalNameCandidate(
    requestedName: String,
    candidateName: String
): Boolean {
    if (
        candidateName.isBlank() ||
            candidateName != candidateName.trim() ||
            candidateName == "." ||
            candidateName == ".." ||
            '/' in candidateName ||
            '\\' in candidateName
    ) {
        return false
    }
    return ManagedDownloadTreeNaming.isExactTreeStoredName(candidateName, requestedName) ||
        ManagedDownloadTreeNaming.matchesProviderNumberedName(candidateName, requestedName)
}

internal fun ManagedDownloadStorage.isPendingAudioPromotionNameOccupied(
    actualName: String,
    candidateName: String
): Boolean {
    if (isTreePromotionBackupName(actualName, candidateName)) {
        return true
    }
    val pendingAudioName = actualName
        .takeIf(ManagedDownloadPendingAudioWriteNames::isArtifactName)
        ?.let(pendingAudioWriteNames::logicalAudioName)
    val metadataAudioName = ManagedDownloadTreeNaming.metadataAudioName(actualName)
    return sequenceOf(actualName, pendingAudioName, metadataAudioName)
        .filterNotNull()
        .any { name ->
            ManagedDownloadTreeNaming.isExactTreeStoredName(name, candidateName) ||
                ManagedDownloadTreeNaming.matchesProviderNumberedName(name, candidateName)
        }
}

internal fun ManagedDownloadStorage.findPendingMetadataForAudioBlocking(
    context: Context,
    root: RootHandle,
    audio: StoredEntry
): ExactRootEntryLookup {
    val pendingName = "${audio.logicalName}$PENDING_METADATA_SUFFIX"

    fun findInSingleRoot(candidateRoot: RootHandle): ExactRootEntryLookup {
        return when (candidateRoot) {
            is RootHandle.FileRoot -> {
                val entries = candidateRoot.dir.listFiles()
                    ?: return ExactRootEntryLookup(null, false)
                ExactRootEntryLookup(
                    entry = entries.asSequence()
                        .filter(File::isFile)
                        .filter { file ->
                            ManagedDownloadTreeNaming.isPendingMetadataName(
                                actualName = file.name,
                                audioName = audio.logicalName
                            )
                        }
                        .map(ManagedDownloadStoredEntryMapper::fromFile)
                        .minWithOrNull(
                            compareBy<StoredEntry>(
                                { entry ->
                                    ManagedDownloadTreeNaming.metadataNameOrdinal(
                                        entry.name,
                                        audio.logicalName
                                    ) ?: Int.MAX_VALUE
                                },
                                StoredEntry::name
                            )
                        ),
                    complete = true
                )
            }

            is RootHandle.TreeRoot -> {
                val refresh = treeChildRegistry.treeChildrenForWrite(
                    context,
                    candidateRoot.tree
                )
                ExactRootEntryLookup(
                    entry = refresh.children.asSequence()
                        .filterNot(QueriedTreeChild::isDirectory)
                        .filter { child ->
                            child.name == pendingName ||
                                ManagedDownloadTreeNaming.isPendingMetadataName(
                                    actualName = child.name,
                                    audioName = audio.logicalName
                                )
                        }
                        .map(ManagedDownloadStoredEntryMapper::fromTreeChild)
                        .minWithOrNull(
                            compareBy<StoredEntry>(
                                { entry ->
                                    ManagedDownloadTreeNaming.metadataNameOrdinal(
                                        entry.name,
                                        audio.logicalName
                                    ) ?: Int.MAX_VALUE
                                },
                                StoredEntry::name
                            )
                        ),
                    complete = refresh.isComplete
                )
            }
        }
    }

    val rootLookup = findInSingleRoot(root)
    if (!rootLookup.complete || rootLookup.entry != null) return rootLookup
    val temporaryRoot = resolveTemporaryRoot(
        context = context,
        root = root,
        create = false
    ) ?: return ExactRootEntryLookup(null, true)
    return findInSingleRoot(temporaryRoot)
}

internal fun ManagedDownloadStorage.findExactEntryInRoot(
    context: Context,
    root: RootHandle,
    name: String
): ExactRootEntryLookup {
    return when (root) {
        is RootHandle.FileRoot -> {
            ExactRootEntryLookup(
                entry = File(root.dir, name)
                    .takeIf { file -> file.isFile }
                    ?.toStoredEntry(),
                complete = root.dir.isDirectory
            )
        }

        is RootHandle.TreeRoot -> {
            val refresh = treeChildRegistry.treeChildrenForWrite(context, root.tree)
            ExactRootEntryLookup(
                entry = refresh.children
                    .firstOrNull { child -> !child.isDirectory && child.name == name }
                    ?.toStoredEntry(),
                complete = refresh.isComplete
            )
        }
    }
}
