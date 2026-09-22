package moe.ouom.neriplayer.core.download.storage.operation.content

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingAudioWritesFromRoot
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.readTemporaryDirectoryEntries
import moe.ouom.neriplayer.core.download.storage.operation.requireCompleteMigrationDirectoryScan
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StartupRecoveryResult
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.SnapshotEntryBucket
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.SAF_COMMITTED_SIZE_TOLERANCE_BYTES
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationNamePlan
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationTargetIndexBuilder
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationTargetIndex
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.migration.plan.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.migration.recovery.mergePersistedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.displayName
import java.io.File
import java.io.InputStream
import java.io.IOException
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedMetadataReadResult
import moe.ouom.neriplayer.core.download.storage.metadata.classifyManagedMetadataRead
import moe.ouom.neriplayer.core.download.storage.metadata.requireAvailableMetadata
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.parseDownloadedAudioMetadata(
    context: Context,
    entry: StoredEntry
): DownloadedAudioMetadata? {
    val raw = readTextInternal(context, entry.reference) ?: return null
    return parseDownloadedAudioMetadataJson(raw)
}

internal fun ManagedDownloadStorage.metadataEntriesForPendingArtifacts(
    entries: Collection<StoredEntry>
): List<StoredEntry> {
    val pendingAudioNames = entries.asSequence()
        .filterNot(StoredEntry::isDirectory)
        .flatMap { entry ->
            buildList {
                if (entry.isPendingAudioWrite && entry.logicalName.isNotBlank()) {
                    add(entry.logicalName)
                }
                val markerIndex = entry.name.lastIndexOf(PENDING_AUDIO_WRITE_MARKER)
                if (markerIndex > 0) {
                    add(entry.name.substring(0, markerIndex))
                }
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                if (
                    audioName != null &&
                    ManagedDownloadTreeNaming.isPendingMetadataName(
                        actualName = entry.name,
                        audioName = audioName
                    )
                ) {
                    add(audioName)
                }
            }.asSequence()
        }
        .filter(String::isNotBlank)
        .toSet()
    if (pendingAudioNames.isEmpty()) return emptyList()
    return entries.filter { entry ->
        val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            ?: return@filter false
        audioName in pendingAudioNames
    }
}

internal suspend fun ManagedDownloadStorage.parseDownloadedAudioMetadataEntriesBatch(
    context: Context,
    entries: Collection<StoredEntry>
): List<Pair<StoredEntry, DownloadedAudioMetadata?>> {
    val results = readDownloadedAudioMetadataEntriesDetailed(context, entries)
    return entries.map { entry ->
        entry to (results[entry.reference] as? ManagedMetadataReadResult.Found)?.metadata
    }
}

internal suspend fun ManagedDownloadStorage.readDownloadedAudioMetadataEntriesDetailed(
    context: Context,
    entries: Collection<StoredEntry>
): Map<String, ManagedMetadataReadResult> {
    if (entries.isEmpty()) return emptyMap()
    return coroutineScope {
        entries.toList()
            .chunked(METADATA_SCAN_PARALLELISM)
            .flatMap { batch ->
                batch.map { entry ->
                    async(metadataScanDispatcher) {
                        val metadata = try {
                            val target = backendReference(context, entry.reference)
                            if (target == null) {
                                ManagedMetadataReadResult.Unavailable(IOException("unsupported metadata reference"))
                            } else {
                                classifyManagedMetadataRead(target.backend.read(target.reference) { input ->
                                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                })
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            NPLogger.w(
                                TAG,
                                "读取清理 metadata 失败，保留 pending 证据: " +
                                    "name=${entry.name}, error=${error.message}"
                            )
                            ManagedMetadataReadResult.Unavailable(error)
                        }
                        entry.reference to metadata
                    }
                }.awaitAll()
            }.toMap()
    }
}

internal fun ManagedDownloadStorage.parseDownloadedAudioMetadataBatch(
    context: Context,
    entries: Collection<Pair<String, StoredEntry>>,
    requireAvailable: Boolean = false
): Map<String, DownloadedAudioMetadata?> {
    if (entries.isEmpty()) return emptyMap()
    return runBlocking(Dispatchers.IO) {
        val results = readDownloadedAudioMetadataEntriesDetailed(context, entries.map { it.second })
        if (requireAvailable) requireAvailableMetadata(results)
        entries.associate { (audioName, entry) ->
            audioName to (results[entry.reference] as? ManagedMetadataReadResult.Found)?.metadata
        }
    }
}

internal fun ManagedDownloadStorage.invalidateSnapshotCache(context: Context? = null) {
    snapshotCacheStore.invalidate(context)
    notifyLyricsRefresh()
}

internal fun ManagedDownloadStorage.notifyLyricsRefresh() {
    _lyricsRefreshVersion.value = _lyricsRefreshVersion.value + 1L
}

internal fun ManagedDownloadStorage.cleanupPendingAudioWrites(context: Context): StartupRecoveryResult {
    return try {
        val root = resolveRootBlocking(context)
        val refresh = treeDirectories.refreshRootEntries(context, root)
        if (!refresh.isComplete) {
            NPLogger.w(TAG, "下载目录枚举不完整，跳过待提交音频清理")
            return StartupRecoveryResult(failedCount = 1)
        }
        val temporary = readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = true,
            rootAlreadyRefreshed = true
        )
        if (!temporary.isComplete) {
            NPLogger.w(TAG, "下载 .tmp 目录枚举不完整，跳过待提交音频清理")
            return StartupRecoveryResult(failedCount = 1)
        }
        val rootEntries = (refresh.entries + temporary.entries)
            .filterNot(StoredEntry::isDirectory)
        val metadataNames = rootEntries.mapTo(linkedSetOf(), StoredEntry::name)
        val pendingEntries = rootEntries.filter { entry ->
            entry.isPendingAudioWrite ||
                entry.name.contains(PENDING_AUDIO_WRITE_MARKER)
        }
        val unresolvedEntries = pendingEntries.filterNot { entry ->
            val logicalName = pendingAudioWriteNames.logicalAudioName(entry.name)
            metadataNames.any { candidate ->
                candidate == "$logicalName$METADATA_SUFFIX" ||
                    ManagedDownloadTreeNaming.isPendingMetadataName(
                        actualName = candidate,
                        audioName = logicalName
                    )
            }
        }
        if (unresolvedEntries.isNotEmpty()) {
            NPLogger.w(
                TAG,
                "待提交音频缺少可验证 metadata，保留 payload 等待恢复: " +
                    "count=${unresolvedEntries.size}"
            )
        }
        // 没有 owner/终态凭据时不能猜测删除。已完成的 pending 会由
        // recoverPendingAudioWritesFromRoot 先提升，取消项由 operation planner 清理
        if (pendingEntries.isEmpty()) {
            return StartupRecoveryResult()
        }
        StartupRecoveryResult(
            failedCount = unresolvedEntries.size
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: SecurityException) {
        throw error
    } catch (error: ManagedDownloadRootUnavailableException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "下载目录不可用，跳过待提交音频清理: ${error.message}")
        StartupRecoveryResult(failedCount = 1)
    }
}

internal fun ManagedDownloadStorage.buildPendingAudioWriteName(fileName: String): String {
    return pendingAudioWriteNames.buildPendingAudioWriteName(fileName)
}

internal fun ManagedDownloadStorage.renameTreeDocumentWithoutReplacing(
    context: Context,
    parent: DocumentFile,
    document: DocumentFile?,
    finalName: String
): DocumentFile? {
    if (document == null) return null
    val backend = SafStorageBackend(context)
    return ManagedDownloadTreeMutationLocks.withLock(parent.uri) {
        val refresh = treeChildRegistry.treeChildrenForWrite(context, parent)
        if (!canCreateTreePromotionTargetWithoutReplacing(
                enumerationComplete = refresh.isComplete,
                existingNames = refresh.children.map(QueriedTreeChild::name),
                targetName = finalName
            )
        ) {
            NPLogger.w(
                TAG,
                "SAF 重命名目标不可安全创建，保留源文件: $finalName"
            )
            return@withLock null
        }
        when (val result = runBlocking(Dispatchers.IO) {
            backend.rename(
                reference = TrustedManagedRef(
                    reference = StorageReference.SafRef(document.uri),
                    externalReference = document.uri.toString()
                ),
                displayName = finalName
            )
        }) {
            is moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.Renamed -> {
                val renamedUri = (result.stat.reference as? StorageReference.SafRef)?.uri
                    ?: return@withLock null
                DocumentFile.fromSingleUri(context, renamedUri)
            }
            moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.Missing -> null
            moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.PermissionLost -> {
                throw SecurityException("SAF 重命名权限丢失: ${document.uri}")
            }
            is moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.ProviderFailure -> {
                throw result.error
            }
            moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.OutOfScope,
            is moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult.Unsupported -> null
        }
    }
}

internal fun ManagedDownloadStorage.resolvePendingTreeDocument(
    context: Context,
    parent: DocumentFile,
    uri: Uri
): DocumentFile? {
    val direct = DocumentFile.fromSingleUri(context, uri) ?: return null
    return treeChildRegistry.toTreeDocumentFile(
        context = context,
        parent = parent,
        child = direct
    ) ?: direct
}

internal fun ManagedDownloadStorage.resolveNewTreePromotionDocument(
    context: Context,
    parent: DocumentFile,
    uri: Uri
): DocumentFile? {
    return DocumentFile.fromSingleUri(context, uri)
        ?.let { document ->
            treeChildRegistry.toTreeDocumentFile(
                context = context,
                parent = parent,
                child = document
            ) ?: document
        }
}

internal fun ManagedDownloadStorage.writeMigrationRootStream(
    context: Context,
    root: RootHandle,
    displayName: String,
    mimeType: String,
    input: InputStream,
    sourceEntry: StoredEntry,
    targetNames: Set<String>,
    targetEntry: StoredEntry? = null,
    onProgress: ((Long) -> Unit)? = null,
    replacementPlan: moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementPlan? = null
): StoredWriteResult {
    return commitWriter.writeMigrationRootStream(
        context = context,
        root = root,
        displayName = displayName,
        mimeType = mimeType,
        input = input,
        sourceEntry = sourceEntry,
        targetNames = targetNames,
        targetEntry = targetEntry,
        onProgress = onProgress,
        replacementPlan = replacementPlan
    )
}

internal fun ManagedDownloadStorage.verifyFileCommittedLength(
    target: File,
    expectedSizeBytes: Long,
    description: String
): Long {
    return ManagedDownloadCommitIo.verifyFileCommittedLength(
        target = target,
        expectedSizeBytes = expectedSizeBytes,
        description = description
    )
}

internal fun ManagedDownloadStorage.verifyDocumentCommittedLength(
    context: Context,
    uri: Uri,
    expectedSizeBytes: Long,
    description: String
): Long {
    return ManagedDownloadCommitIo.verifyDocumentCommittedLength(
        contentResolver = context.contentResolver,
        uri = uri,
        expectedSizeBytes = expectedSizeBytes,
        toleranceBytes = SAF_COMMITTED_SIZE_TOLERANCE_BYTES,
        bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
        description = description,
        onQueryFailure = { error -> NPLogger.w(TAG, "查询 SAF 目标大小失败: $uri, ${error.message}") },
        onCountFailure = { error -> NPLogger.w(TAG, "回读 SAF 目标失败: $uri, ${error.message}") }
    )
}

internal fun ManagedDownloadStorage.verifiedTreeStoredEntry(
    context: Context,
    target: DocumentFile,
    expectedName: String,
    expectedSizeBytes: Long,
    fallbackLastModifiedMs: Long,
    description: String
): StoredEntry {
    return treeFileCommitter.verifiedTreeStoredEntry(
        context = context,
        target = target,
        expectedName = expectedName,
        expectedSizeBytes = expectedSizeBytes,
        fallbackLastModifiedMs = fallbackLastModifiedMs,
        description = description
    )
}

internal fun ManagedDownloadStorage.buildMigrationTargetIndex(
    context: Context,
    targetRoot: RootHandle,
    skipMetadataParsing: Boolean = false
): ManagedMigrationTargetIndex {
    val refresh = treeDirectories.refreshManagedMigrationEntries(context, targetRoot)
    requireCompleteMigrationDirectoryScan(
        root = targetRoot,
        isComplete = refresh.isComplete
    )
    val parsedMetadataByAudioName = if (skipMetadataParsing) {
        null
    } else {
        val metadataEntries = refresh.rootEntries
            .asSequence()
            .filterNot(StoredEntry::isDirectory)
            .filter { entry -> ManagedDownloadTreeNaming.isMetadataName(entry.name) }
            .mapNotNull { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                    audioName to entry
                }
            }
            .groupBy({ (audioName, _) -> audioName }, { (_, entry) -> entry })
            .mapNotNull { (audioName, entries) ->
                entries.minWithOrNull(
                    compareBy<StoredEntry>(
                        { candidate ->
                            ManagedDownloadTreeNaming.metadataNameOrdinal(
                                candidate.name,
                                audioName
                            ) ?: Int.MAX_VALUE
                        },
                        StoredEntry::name
                    )
                )?.let { entry -> audioName to entry }
            }
        parseDownloadedAudioMetadataBatch(
            context = context,
            entries = metadataEntries
        ).mapNotNull { (audioName, metadata) ->
            metadata?.let { audioName to it }
        }.toMap()
    }
    return ManagedDownloadMigrationTargetIndexBuilder.build(
        rootEntries = refresh.rootEntries,
        coverEntries = refresh.coverEntries,
        lyricEntries = refresh.lyricEntries,
        parsedMetadataByAudioName = parsedMetadataByAudioName
    )
}

internal fun ManagedDownloadStorage.mergePersistedReplacementPlan(
    fromDirectoryUri: String?,
    toDirectoryUri: String?,
    generatedPlan: moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationNamePlan,
    persistedJournal: ManagedMigrationReplacementJournal?
): moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationNamePlan {
    if (persistedJournal == null) return generatedPlan
    if (
        !areEquivalentDirectoryUris(fromDirectoryUri, persistedJournal.fromDirectoryUri) ||
        !areEquivalentDirectoryUris(toDirectoryUri, persistedJournal.toDirectoryUri)
    ) {
        throw ManagedDownloadMigrationException.transient(
            "迁移替换事务目录已变化，保留事务等待恢复"
        )
    }
    return mergePersistedMigrationReplacementPlan(
        generatedPlan = generatedPlan,
        persistedJournal = persistedJournal
    )
}

internal fun ManagedDownloadStorage.writeSubdirectoryBytesBlocking(
    context: Context,
    subdirectory: String,
    displayName: String,
    bytes: ByteArray,
    mimeType: String
): StoredEntry? {
    return commitWriter.writeSubdirectoryBytes(
        context = context,
        root = resolveRootBlocking(context),
        subdirectory = subdirectory,
        displayName = displayName,
        bytes = bytes,
        mimeType = mimeType
    ).also { entry ->
        updateSnapshotAfterSubdirectoryWrite(context, subdirectory, entry)
    }
}

internal fun ManagedDownloadStorage.writeSubdirectoryStreamBlocking(
    context: Context,
    subdirectory: String,
    displayName: String,
    input: InputStream,
    mimeType: String,
    expectedSizeBytes: Long?
): StoredEntry? {
    return commitWriter.writeSubdirectoryStream(
        context = context,
        root = resolveRootBlocking(context),
        subdirectory = subdirectory,
        displayName = displayName,
        mimeType = mimeType,
        input = input,
        expectedSizeBytes = expectedSizeBytes
    ).also { entry ->
        updateSnapshotAfterSubdirectoryWrite(context, subdirectory, entry)
    }
}

internal fun ManagedDownloadStorage.updateSnapshotAfterSubdirectoryWrite(
    context: Context,
    subdirectory: String,
    entry: StoredEntry?
) {
    entry ?: return
    val bucket = when (subdirectory) {
        COVER_SUBDIRECTORY -> SnapshotEntryBucket.COVER
        LYRIC_SUBDIRECTORY -> SnapshotEntryBucket.LYRIC
        else -> null
    }
    if (bucket == null || !updateSnapshotCacheAfterStoredEntryWrite(context, entry, bucket)) {
        invalidateSnapshotCache(context)
    }
}

internal fun ManagedDownloadStorage.writeMigrationSubdirectoryStream(
    context: Context,
    root: RootHandle,
    subdirectory: String,
    displayName: String,
    mimeType: String,
    input: InputStream,
    sourceEntry: StoredEntry,
    targetNames: Set<String>,
    targetEntry: StoredEntry? = null,
    onProgress: ((Long) -> Unit)? = null,
    replacementPlan: moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementPlan? = null
): StoredWriteResult {
    return commitWriter.writeMigrationSubdirectoryStream(
        context = context,
        root = root,
        subdirectory = subdirectory,
        displayName = displayName,
        mimeType = mimeType,
        input = input,
        sourceEntry = sourceEntry,
        targetNames = targetNames,
        targetEntry = targetEntry,
        onProgress = onProgress,
        replacementPlan = replacementPlan
    )
}

internal fun ManagedDownloadStorage.writeRootText(
    context: Context,
    root: RootHandle,
    displayName: String,
    content: String,
    expectedAbsent: Boolean = false,
    knownTargetEntry: StoredEntry? = null
): StoredEntry? {
    return commitWriter.writeRootText(
        context = context,
        root = root,
        displayName = displayName,
        content = content,
        expectedAbsent = expectedAbsent,
        knownTargetEntry = knownTargetEntry
    )
}

internal suspend fun ManagedDownloadStorage.writeTextThroughBackend(
    context: Context,
    root: RootHandle,
    displayName: String,
    content: String,
    temporaryWriteOwnerName: String? = null
): StorageWriteResult {
    val backend: StorageBackend
    val target: StorageTarget
    when (root) {
        is RootHandle.FileRoot -> {
            backend = FileStorageBackend(root.dir)
            target = StorageTarget.FileTarget(
                logicalPath = displayName,
                temporaryWriteOwnerName = temporaryWriteOwnerName
            )
        }
        is RootHandle.TreeRoot -> {
            backend = SafStorageBackend(context)
            target = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(root.tree.uri),
                displayName = displayName,
                mimeType = "application/octet-stream",
                temporaryWriteOwnerName = temporaryWriteOwnerName
            )
        }
    }
    return backend.writeRecoverable(target) { output ->
        output.write(content.toByteArray(Charsets.UTF_8))
    }
}
