package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.SnapshotEntryBucket
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_MANIFEST_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.ensureManagedLibraryManifestBlocking(
    context: Context,
    root: RootHandle
): String {
    readManagedLibraryIdBlocking(context, root)?.let { existing ->
        return existing
    }
    val libraryId = UUID.randomUUID().toString()
    val payload = JSONObject().apply {
        put("schemaVersion", 1)
        put("libraryId", libraryId)
        put("layoutVersion", 1)
        put("createdAtMs", System.currentTimeMillis())
        put("indexFormatVersion", 1)
    }.toString()
    val written = writeRootText(
        context = context,
        root = root,
        displayName = MANAGED_LIBRARY_MANIFEST_FILE_NAME,
        content = payload
    )
    if (written == null) {
        throw IOException("无法写入 Managed SAF root manifest")
    }
    return libraryId
}

internal fun ManagedDownloadStorage.readManagedLibraryIdBlocking(
    context: Context,
    root: RootHandle
): String? {
    return findManagedLibraryManifestEntry(
        context = context,
        root = root
    )
        ?.let { entry -> readTextInternal(context, entry.reference) }
        ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        ?.optString("libraryId")
        ?.takeIf(String::isNotBlank)
}

internal fun ManagedDownloadStorage.findManagedLibraryManifestEntry(
    context: Context,
    root: RootHandle
): StoredEntry? {
    return when (root) {
        is RootHandle.FileRoot -> {
            File(root.dir, MANAGED_LIBRARY_MANIFEST_FILE_NAME)
                .takeIf { it.isFile }
                ?.toStoredEntry()
        }

        is RootHandle.TreeRoot -> {
            treeChildRegistry.cachedTreeChild(
                context = context,
                parent = root.tree,
                childName = MANAGED_LIBRARY_MANIFEST_FILE_NAME,
                // manifest 是应用自己的稳定文件, 在写入窗口内无需重复查询根目录
                maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
            )?.toStoredEntry()
        }
    }
}

internal fun ManagedDownloadStorage.deletePendingAudioMetadataBlocking(
    context: Context,
    root: RootHandle,
    audioName: String
): Boolean {
    val rootEntries = when (root) {
        is RootHandle.FileRoot -> {
            root.dir.listFiles()?.map(ManagedDownloadStoredEntryMapper::fromFile)
                ?: return false
        }
        is RootHandle.TreeRoot -> {
            val refresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                context = context,
                parent = root.tree
            )
            if (!refresh.isComplete) {
                return false
            }
            refresh.children.map(ManagedDownloadStoredEntryMapper::fromTreeChild)
        }
    }
    val temporary = readTemporaryDirectoryEntries(
        context = context,
        root = root,
        forceRefresh = true,
        rootAlreadyRefreshed = true
    )
    if (!temporary.isComplete) return false
    val entries = rootEntries + temporary.entries
    val pendingNames = pendingMetadataEntryNames(
        audioName = audioName,
        candidateNames = entries.filterNot(StoredEntry::isDirectory).map(StoredEntry::name)
    ).toSet()
    val pendingEntries = entries.filter { entry -> entry.name in pendingNames }
    if (pendingEntries.isEmpty()) {
        return true
    }
    val references = pendingEntries.mapTo(linkedSetOf(), StoredEntry::reference)
    val deletedReferences = deleteReferencesInternal(
        context = context,
        references = references,
        allowedRoot = root,
        trustedReferences = references.toSet(),
        invalidateSnapshot = false
    )
    if (deletedReferences.isNotEmpty()) {
        forgetDeletedReferencesFromCaches(deletedReferences)
        invalidateSnapshotCache(context)
    }
    return deletedReferences.containsAll(references)
}

internal suspend fun ManagedDownloadStorage.saveAudioFromTempBlocking(
    context: Context,
    tempFile: File,
    fileName: String,
    mimeType: String?,
    expectedSizeBytes: Long?,
    transferSizeVerified: Boolean,
    seedMetadataJson: String?,
    pendingMetadataJson: String?
): StoredEntry {
    val actualSizeBytes = tempFile.length().coerceAtLeast(0L)
    if (actualSizeBytes <= 0L) {
        throw IOException("下载文件为空: ${tempFile.name}")
    }
    if (shouldRejectTransferSize(
            expectedSizeBytes = expectedSizeBytes,
            actualSizeBytes = actualSizeBytes,
            transferSizeVerified = transferSizeVerified
        )
    ) {
        throw IOException("下载文件大小不匹配: $actualSizeBytes/$expectedSizeBytes")
    }
    if (
        transferSizeVerified &&
        expectedSizeBytes != null &&
        !ManagedDownloadSizePolicy.isTransferSizeComplete(
            expectedSizeBytes = expectedSizeBytes,
            actualSizeBytes = actualSizeBytes
        )
    ) {
        NPLogger.d(
            TAG,
            "提交阶段忽略传输期长度提示: file=${tempFile.name}, " +
                "actual=$actualSizeBytes, expected=$expectedSizeBytes, " +
                "transferAlreadyVerified=$transferSizeVerified"
        )
    }
    val boundedFileName = boundManagedDownloadFileName(fileName)
    val storedEntry = when (val root = resolveRootBlocking(context)) {
        is RootHandle.FileRoot -> {
            val temporaryRoot = resolveTemporaryRoot(
                context = context,
                root = root,
                create = true
            ) as? RootHandle.FileRoot
                ?: throw IOException("无法准备下载 .tmp 目录")
            val existingAudio = findExistingAudioForSeedStableKey(context, seedMetadataJson)
            val reservedFinalName = existingAudio == null
            val finalName = existingAudio?.name
                ?: treeChildRegistry.reserveUniqueFileChildName(root.dir, boundedFileName)
            val pendingName = buildPendingAudioWriteName(finalName)
            val pendingTarget = File(temporaryRoot.dir, pendingName)
            val audioEntry = try {
                writeCollisionPendingMetadata(
                    context = context,
                    root = root,
                    requestedAudioName = boundedFileName,
                    actualAudioName = finalName,
                    pendingMetadataJson = pendingMetadataJson
                )
                val writeResult = FileStorageBackend(temporaryRoot.dir).writeRecoverable(
                    target = StorageTarget.FileTarget(pendingName)
                ) { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                    }
                }
                val stored = when (writeResult) {
                    is StorageWriteResult.Written -> {
                        writeResult.stat.toStoredEntryForBackend(temporaryRoot.dir)
                    }
                    StorageWriteResult.Missing -> throw IOException(
                        "pending 音频写入目标不存在: $pendingName"
                    )
                    StorageWriteResult.OutOfScope -> throw IOException(
                        "pending 音频写入目标越界: $pendingName"
                    )
                    StorageWriteResult.PermissionLost -> throw SecurityException(
                        "pending 音频写入权限丢失: $pendingName"
                    )
                    is StorageWriteResult.ProviderFailure -> throw IOException(
                        "pending 音频写入失败: $pendingName",
                        writeResult.error
                    )
                    is StorageWriteResult.Unsupported -> throw IOException(
                        "pending 音频不支持写入: $pendingName (${writeResult.operation})"
                    )
                }
                verifyFileCommittedLength(
                    target = pendingTarget,
                    expectedSizeBytes = actualSizeBytes,
                    description = pendingTarget.name
                )
                stored.copy(sizeBytes = actualSizeBytes)
            } catch (error: Throwable) {
                deletePendingFileAndConfirm(pendingTarget)?.let { cleanupError ->
                    error.addSuppressed(
                        IOException(
                            "pending 音频写入失败后清理未确认: $pendingName",
                            cleanupError
                        )
                    )
                }
                if (reservedFinalName) {
                    treeChildRegistry.forgetFileChildName(root.dir, finalName)
                }
                throw error
            }
            writeSeedMetadataAfterAudioCommit(
                context = context,
                root = root,
                audioName = finalName,
                seedMetadataJson = seedMetadataJson
            )
            audioEntry
        }

        is RootHandle.TreeRoot -> {
            val temporaryRoot = resolveTemporaryRoot(
                context = context,
                root = root,
                create = true
            ) as? RootHandle.TreeRoot
                ?: throw IOException("无法准备下载 .tmp 目录")
            val existingAudio = findExistingAudioForSeedStableKey(context, seedMetadataJson)
            val reservedFinalName = existingAudio == null
            val finalName = existingAudio?.name
                ?: treeChildRegistry.reserveUniqueTreeChildName(context, root.tree, boundedFileName)
            val createdPendingName = buildPendingAudioWriteName(finalName)
            val audioEntry = try {
                writeCollisionPendingMetadata(
                    context = context,
                    root = root,
                    requestedAudioName = boundedFileName,
                    actualAudioName = finalName,
                    pendingMetadataJson = pendingMetadataJson
                )
                val entry = writeSafFileThroughBackend(
                    context = context,
                    parent = temporaryRoot.tree,
                    displayName = createdPendingName,
                    mimeType = mimeTypeFromName(finalName, mimeType),
                    expectedSizeBytes = actualSizeBytes,
                    sourceFile = tempFile
                )
                treeChildRegistry.rememberTreeChild(temporaryRoot.tree, entry)
                entry
            } catch (error: Throwable) {
                treeChildRegistry.forgetTreeChildName(
                    temporaryRoot.tree,
                    createdPendingName
                )
                if (reservedFinalName) {
                    treeChildRegistry.forgetTreeChildName(root.tree, finalName)
                }
                throw error
            }
            writeSeedMetadataAfterAudioCommit(
                context = context,
                root = root,
                audioName = audioEntry.logicalName,
                seedMetadataJson = seedMetadataJson
            )
            audioEntry
        }
    }
    if (tempFile.exists() && !tempFile.delete()) {
        NPLogger.w(TAG, "删除下载临时文件失败: ${tempFile.name}")
    }
    if (!updateSnapshotCacheAfterStoredEntryWrite(context, storedEntry, SnapshotEntryBucket.AUDIO)) {
        invalidateSnapshotCache(context)
    }
    seedMetadataJson
        ?.let(::parseDownloadedAudioMetadataJson)
        ?.let { metadata ->
            val metadataEntry = findMetadataForAudioBlocking(context, storedEntry)
            if (metadataEntry == null || !updateSnapshotCacheAfterMetadataWrite(context, metadataEntry, metadata)) {
                invalidateSnapshotCache(context)
            }
        }
    return storedEntry
}

internal fun ManagedDownloadStorage.findExistingAudioForSeedStableKey(
    context: Context,
    seedMetadataJson: String?
): StoredEntry? {
    val stableKey = seedMetadataJson
        ?.let(::parseDownloadedAudioMetadataJson)
        ?.stableKey
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
    val cached = snapshotCacheStore.cachedSnapshot(
        context = context,
        restorePersisted = false
    )
    val snapshot = cached ?: buildDownloadLibrarySnapshotBlocking(context)
    return ManagedDownloadStorageLookup.selectCanonicalAudioEntries(
        audioEntries = snapshot.audioEntriesByStableKey[stableKey].orEmpty(),
        metadataByAudioName = snapshot.metadataByAudioName
    ).maxWithOrNull(
        compareByDescending<StoredEntry> { it.lastModifiedMs }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.name }
    )
}

internal fun ManagedDownloadStorage.promoteFileTargetWithoutReplacement(
    pending: File,
    target: File,
    displayName: String
) {
    try {
        Files.move(
            pending.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (_: AtomicMoveNotSupportedException) {
        try {
            Files.move(pending.toPath(), target.toPath())
        } catch (error: FileAlreadyExistsException) {
            throw IOException("下载目标已存在，保留 pending 文件: $displayName", error)
        } catch (error: Exception) {
            throw IOException("无法提交下载文件: $displayName", error)
        }
    } catch (error: FileAlreadyExistsException) {
        throw IOException("下载目标已存在，保留 pending 文件: $displayName", error)
    } catch (error: Exception) {
        throw IOException("无法提交下载文件: $displayName", error)
    }
}

internal fun ManagedDownloadStorage.deletePendingFileAndConfirm(pending: File): Throwable? {
    return try {
        when {
            !pending.exists() -> null
            !pending.isFile -> IllegalStateException(
                "pending 音频不是普通文件: ${pending.name}"
            )
            !pending.delete() && pending.exists() -> IllegalStateException(
                "pending 音频删除未确认: ${pending.name}"
            )
            pending.exists() -> IllegalStateException(
                "pending 音频删除后仍存在: ${pending.name}"
            )
            else -> null
        }
    } catch (error: Throwable) {
        error
    }
}

internal suspend fun ManagedDownloadStorage.writeSafFileThroughBackend(
    context: Context,
    parent: DocumentFile,
    displayName: String,
    mimeType: String,
    expectedSizeBytes: Long,
    sourceFile: File
): StoredEntry {
    val backend = SafStorageBackend(context)
    val result = backend.writeRecoverable(
        target = StorageTarget.SafTarget(
            parent = StorageReference.SafRef(parent.uri),
            displayName = displayName,
            mimeType = mimeType
        )
    ) { output ->
        sourceFile.inputStream().use { input ->
            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
        }
    }
    val stat = when (result) {
        is StorageWriteResult.Written -> result.stat
        StorageWriteResult.Missing -> throw IOException("SAF 目标不存在: $displayName")
        StorageWriteResult.OutOfScope -> throw IOException("SAF 目标越界: $displayName")
        StorageWriteResult.PermissionLost -> throw SecurityException("SAF 写入权限丢失: $displayName")
        is StorageWriteResult.ProviderFailure -> throw IOException(
            "SAF 写入失败: $displayName",
            result.error
        )
        is StorageWriteResult.Unsupported -> throw IOException(
            "SAF 不支持写入: $displayName (${result.operation})"
        )
    }
    val verifiedSize = stat.sizeBytes ?: when (val measured = backend.read(stat.reference) { input ->
        ManagedDownloadCommitIo.countInputStreamBytes(
            input,
            STREAM_COPY_BUFFER_SIZE_BYTES
        )
    }) {
        is StorageLookupResult.Found -> measured.value
        StorageLookupResult.Missing -> throw IOException(
            "SAF 写入目标在读回时不存在: $displayName"
        )
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF 写入目标读回权限丢失: $displayName"
        )
        is StorageLookupResult.ProviderFailure -> throw IOException(
            "SAF 写入目标读回失败: $displayName",
            measured.error
        )
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> throw IOException(
            "SAF 写入目标不可读: $displayName"
        )
    }
    if (verifiedSize != expectedSizeBytes) {
        throw IOException(
            "SAF 写入大小不匹配: $displayName, expected=$expectedSizeBytes, " +
                "actual=$verifiedSize"
        )
    }
    return stat.toStoredEntryForBackend(fileRoot = null)
        .copy(sizeBytes = verifiedSize)
}

internal suspend fun ManagedDownloadStorage.promotePendingAudio(
    context: Context,
    root: RootHandle,
    audio: StoredEntry,
    finalAudioName: String = audio.logicalName
): StoredEntry? {
    if (!audio.isPendingAudioWrite) return audio
    val finalName = finalAudioName.takeIf(String::isNotBlank)
        ?.takeIf { candidate ->
            isPendingAudioPromotionFinalNameCandidate(
                requestedName = audio.logicalName,
                candidateName = candidate
            )
        }
        ?: return null
    return when (root) {
        is RootHandle.FileRoot -> {
            val pendingFile = File(audio.reference)
            val pendingRoot = pendingFile.parentFile
                ?.takeIf { it.isDirectory }
                ?: root.dir
            promotePendingFileAudio(
                root = root.dir,
                pendingName = audio.name,
                finalName = finalName,
                pendingRoot = pendingRoot
            )?.toStoredEntry()
        }

        is RootHandle.TreeRoot -> {
            val pendingUri = audio.reference.toUri()
            val pendingBackend = SafStorageBackend(context)
            val initialPendingReference = StorageReference.SafRef(pendingUri)
            val pendingStat = pendingBackend.stat(initialPendingReference)
            val rootChildren = treeChildRegistry.cachedTreeChildren(
                context = context,
                parent = root.tree,
                maxCacheAgeMs = 0L
            )
            val pendingIsDirectRootChild = rootChildren.any { child ->
                !child.isDirectory && sameTreeDocument(child.documentUri, pendingUri)
            }
            val exactTargetCandidates = rootChildren.filter { child ->
                !child.isDirectory &&
                    ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
            }
            val hasPromotionBackup = rootChildren.any { child ->
                isTreePromotionBackupName(child.name, finalName)
            }
            if (exactTargetCandidates.size == 1 && !hasPromotionBackup) {
                val expectedRecoverySizeBytes = when (pendingStat) {
                    is StorageLookupResult.Found -> {
                        pendingStat.value
                            .takeUnless(StorageStat::isDirectory)
                            ?.let {
                                resolveCurrentTreePendingAudioSize(
                                    backend = pendingBackend,
                                    reference = initialPendingReference,
                                    reportedSizeBytes = pendingStat.value.sizeBytes,
                                    description = audio.name
                                )
                            }
                    }

                    StorageLookupResult.Missing -> audio.sizeBytes.takeIf { it > 0L }
                    StorageLookupResult.PermissionLost,
                    is StorageLookupResult.ProviderFailure,
                    StorageLookupResult.OutOfScope,
                    is StorageLookupResult.Unsupported -> null
                }
                if (expectedRecoverySizeBytes != null) {
                    val recoveryPendingUri = (pendingStat as? StorageLookupResult.Found)
                        ?.value
                        ?.takeUnless(StorageStat::isDirectory)
                        ?.let { pendingUri }
                    val recoveryPendingParent = if (pendingIsDirectRootChild) {
                        root.tree
                    } else {
                        (resolveTemporaryRoot(context, root, create = false)
                            as? RootHandle.TreeRoot)?.tree
                    }
                    val recovered = ManagedDownloadTreeMutationLocks.withLock(root.tree.uri) {
                        val refreshed = treeChildRegistry.treeChildrenForWrite(
                            context,
                            root.tree
                        )
                        reconcileExistingTreePromotionTargetLocked(
                            context = context,
                            root = root,
                            refresh = refreshed,
                            targetUri = exactTargetCandidates.single().documentUri,
                            pendingUri = recoveryPendingUri,
                            pendingName = audio.name,
                            pendingParent = recoveryPendingParent,
                            finalName = finalName,
                            expectedSizeBytes = expectedRecoverySizeBytes,
                            fallbackLastModifiedMs = System.currentTimeMillis()
                        )
                    }
                    if (recovered != null) {
                        return recovered
                    }
                }
            }
            val pending = when (pendingStat) {
                is StorageLookupResult.Found -> pendingStat.value
                    .takeUnless(StorageStat::isDirectory)
                    ?.let {
                        if (pendingIsDirectRootChild) {
                            resolvePendingTreeDocument(
                                context = context,
                                parent = root.tree,
                                uri = pendingUri
                            )
                        } else {
                            resolvePendingTemporaryTreeDocument(
                                context = context,
                                root = root,
                                pendingUri = pendingUri,
                                pendingName = audio.name
                            )
                        }
                    }
                StorageLookupResult.Missing -> null
                StorageLookupResult.PermissionLost -> throw SecurityException(
                    "SAF pending 音频权限丢失: ${audio.name}"
                )
                is StorageLookupResult.ProviderFailure -> throw pendingStat.error
                StorageLookupResult.OutOfScope,
                is StorageLookupResult.Unsupported -> null
            }
                ?: treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = 0L
                ).firstOrNull { child ->
                    !child.isDirectory && child.name == audio.name
                }?.let { child ->
                    treeChildRegistry.toDocumentFile(context, root.tree, child)
                }
                ?: return null
            val pendingReference = StorageReference.SafRef(pending.uri)
            val pendingSizeStat = if (pending.uri == pendingUri) {
                pendingStat
            } else {
                pendingBackend.stat(pendingReference)
            }
            val pendingReportedSizeBytes = (pendingSizeStat as? StorageLookupResult.Found)
                ?.value
                ?.sizeBytes
            val committedAtMs = System.currentTimeMillis()
            // 新写入的 StoredEntry 已经在写入阶段完成长度读回校验。
            // 优先复用这个尺寸，避免提升前再次完整读取 SAF 音频；复制阶段
            // 仍会统计实际字节数，旧版本或未知尺寸才回退到完整读回
            val expectedSizeBytes = audio.sizeBytes
                .takeIf { audio.sizeKnown && it > 0L }
                ?: pendingReportedSizeBytes?.takeIf { it > 0L }
                ?: resolveCurrentTreePendingAudioSize(
                    backend = pendingBackend,
                    reference = pendingReference,
                    reportedSizeBytes = pendingReportedSizeBytes,
                    description = audio.name
                )
            val treePending = if (pendingIsDirectRootChild) {
                treeChildRegistry.toTreeDocumentFile(
                    context = context,
                    parent = root.tree,
                    child = pending
                )
            } else {
                pending
            }
            val renamedDocument = if (pendingIsDirectRootChild) {
                renameTreeDocumentWithoutReplacing(
                    context = context,
                    parent = root.tree,
                    document = treePending,
                    finalName = finalName
                )
            } else {
                null
            }
            if (renamedDocument != null) {
                val renamedTarget = treeChildRegistry.toTreeDocumentFileOrEnumerated(
                    context = context,
                    parent = root.tree,
                    child = renamedDocument
                ) ?: renamedDocument
                return verifiedTreeStoredEntry(
                    context = context,
                    target = renamedTarget,
                    expectedName = finalName,
                    expectedSizeBytes = expectedSizeBytes,
                    fallbackLastModifiedMs = committedAtMs,
                    description = finalName
                ).also {
                    treeChildRegistry.forgetTreeChildName(root.tree, audio.name)
                    treeChildRegistry.rememberTreeChild(root.tree, it)
                }
            }
            copyPendingTreeAudioWithoutReplacing(
                context = context,
                root = root,
                pending = treePending ?: pending,
                pendingName = audio.name,
                finalName = finalName,
                expectedSizeBytes = expectedSizeBytes,
                fallbackLastModifiedMs = committedAtMs,
                pendingParent = if (pendingIsDirectRootChild) {
                    root.tree
                } else {
                    (resolveTemporaryRoot(context, root, create = false)
                        as? RootHandle.TreeRoot)?.tree
                }
            )
        }
    }
}

internal fun ManagedDownloadStorage.resolvePendingTemporaryTreeDocument(
    context: Context,
    root: RootHandle.TreeRoot,
    pendingUri: android.net.Uri,
    pendingName: String
): DocumentFile? {
    val temporaryRoot = resolveTemporaryRoot(
        context = context,
        root = root,
        create = false
    ) as? RootHandle.TreeRoot ?: return null
    val cached = treeChildRegistry.cachedTreeChildren(
        context = context,
        parent = temporaryRoot.tree,
        maxCacheAgeMs = 0L
    )
    val child = cached.firstOrNull { candidate ->
        !candidate.isDirectory &&
            candidate.name == pendingName &&
            sameTreeDocument(candidate.documentUri, pendingUri)
    } ?: cached.firstOrNull { candidate ->
        !candidate.isDirectory && sameTreeDocument(candidate.documentUri, pendingUri)
    } ?: return null
    return treeChildRegistry.toDocumentFile(
        context = context,
        parent = temporaryRoot.tree,
        child = child
    )
}

internal suspend fun ManagedDownloadStorage.resolveCurrentTreePendingAudioSize(
    backend: SafStorageBackend,
    reference: StorageReference.SafRef,
    reportedSizeBytes: Long?,
    description: String
): Long {
    val countedSizeBytes = when (val read = backend.read(reference) { input ->
        ManagedDownloadCommitIo.countInputStreamBytes(
            input,
            STREAM_COPY_BUFFER_SIZE_BYTES
        )
    }) {
        is StorageLookupResult.Found -> read.value
        StorageLookupResult.Missing -> throw IOException(
            "SAF pending 音频在提升前不存在: $description"
        )
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF pending 音频提升前权限丢失: $description"
        )
        is StorageLookupResult.ProviderFailure -> throw IOException(
            "SAF pending 音频提升前读回失败: $description",
            read.error
        )
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> throw IOException(
            "SAF pending 音频提升前不可读: $description"
        )
    }
    val expectedSizeBytes = resolvePendingTreeAudioPromotionExpectedSize(
        reportedSizeBytes = reportedSizeBytes,
        countedSizeBytes = countedSizeBytes
    ) ?: throw IOException("SAF pending 音频提升前为空: $description")
    if (reportedSizeBytes != null && reportedSizeBytes != countedSizeBytes) {
        NPLogger.w(
            TAG,
            "SAF pending 音频报告大小与读回大小不一致，使用读回值: " +
                "$description, reported=$reportedSizeBytes, counted=$countedSizeBytes"
        )
    }
    return expectedSizeBytes
}

internal suspend fun ManagedDownloadStorage.demotePublishedTreeAudioToTemporary(
    context: Context,
    root: RootHandle.TreeRoot,
    audio: StoredEntry,
    pendingName: String,
    temporaryRoot: RootHandle.TreeRoot
): StoredEntry? {
    val sourceUri = runCatching { audio.reference.toUri() }.getOrNull() ?: return null
    val backend = SafStorageBackend(context)
    val copied = backend.read(StorageReference.SafRef(sourceUri)) { input ->
        val result = backend.writeRecoverable(
            target = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(temporaryRoot.tree.uri),
                displayName = pendingName,
                mimeType = mimeTypeFromName(pendingName, null)
            )
        ) { output ->
            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
        }
        when (result) {
            is StorageWriteResult.Written -> result.stat.toStoredEntryForBackend(null)
            StorageWriteResult.Missing -> throw IOException(
                "SAF 已发布音频复制源不存在: ${audio.name}"
            )
            StorageWriteResult.OutOfScope -> throw IOException(
                "SAF 已发布音频复制目标越界: ${audio.name}"
            )
            StorageWriteResult.PermissionLost -> throw SecurityException(
                "SAF 已发布音频复制权限丢失: ${audio.name}"
            )
            is StorageWriteResult.ProviderFailure -> throw IOException(
                "SAF 已发布音频复制失败: ${audio.name}",
                result.error
            )
            is StorageWriteResult.Unsupported -> throw IOException(
                "SAF 已发布音频复制不支持: ${audio.name}"
            )
        }
    }
    val copiedEntry = when (copied) {
        is StorageLookupResult.Found -> copied.value
        StorageLookupResult.Missing -> return null
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF 已发布音频读权限丢失: ${audio.name}"
        )
        is StorageLookupResult.ProviderFailure -> throw IOException(
            "SAF 已发布音频读取失败: ${audio.name}",
            copied.error
        )
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> return null
    }
    val deleted = deleteTrustedReference(
        context,
        TrustedManagedRef(
            reference = StorageReference.SafRef(sourceUri),
            externalReference = sourceUri.toString()
        )
    ).isConfirmedStorageMutation()
    if (!deleted) {
        deleteTrustedReference(
            context,
            TrustedManagedRef(
                reference = StorageReference.SafRef(copiedEntry.reference.toUri()),
                externalReference = copiedEntry.reference
            )
        )
        throw IOException("SAF 已发布音频回退后源清理未确认: ${audio.name}")
    }
    treeChildRegistry.forgetTreeChildName(root.tree, audio.name)
    treeChildRegistry.rememberTreeChild(temporaryRoot.tree, copiedEntry)
    return copiedEntry
}

internal fun ManagedDownloadStorage.reconcileExistingTreePromotionTargetLocked(
    context: Context,
    root: RootHandle.TreeRoot,
    refresh: ManagedDownloadTreeChildRegistry.TreeChildrenRefresh,
    targetUri: Uri,
    pendingUri: Uri?,
    pendingName: String,
    pendingParent: DocumentFile?,
    finalName: String,
    expectedSizeBytes: Long,
    fallbackLastModifiedMs: Long
): StoredEntry? {
    if (!refresh.isComplete) {
        NPLogger.w(
            TAG,
            "SAF 提升恢复跳过不完整目录枚举，保留 pending 音频: $finalName"
        )
        return null
    }
    val exactTargets = refresh.children.filter { child ->
        ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
    }
    if (exactTargets.size != 1) {
        return null
    }
    val exactTarget = exactTargets.single()
    if (
        exactTarget.isDirectory ||
            !sameTreeDocument(exactTarget.documentUri, targetUri) ||
            refresh.children.any { child ->
                isTreePromotionBackupName(child.name, finalName)
            }
    ) {
        return null
    }
    if (
        exactTarget.sizeBytes != null &&
            exactTarget.sizeBytes > 0L &&
            exactTarget.sizeBytes != expectedSizeBytes
    ) {
        NPLogger.w(
            TAG,
            "SAF 提升发现未完成的同名目标，保留目标和 pending: " +
                "name=$finalName, expected=$expectedSizeBytes, " +
                "actual=${exactTarget.sizeBytes}"
        )
        return null
    }
    val target = resolveNewTreePromotionDocument(
        context = context,
        parent = root.tree,
        uri = exactTarget.documentUri
    ) ?: return null
    val entry = try {
        verifiedTreeStoredEntry(
            context = context,
            target = target,
            expectedName = finalName,
            expectedSizeBytes = expectedSizeBytes,
            fallbackLastModifiedMs = fallbackLastModifiedMs,
            description = finalName
        )
    } catch (error: SecurityException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "SAF 提升恢复目标校验失败，保留目标和 pending: " +
                "name=$finalName, error=${error.message}",
            error
        )
        return null
    }
    if (pendingUri != null && !sameTreeDocument(pendingUri, target.uri)) {
        val pendingDeleted = deleteTrustedReference(
            context,
            TrustedManagedRef(
                reference = StorageReference.SafRef(pendingUri),
                externalReference = pendingUri.toString()
            )
        ).isConfirmedStorageMutation()
        if (pendingDeleted) {
            treeChildRegistry.forgetTreeChildName(
                pendingParent ?: root.tree,
                pendingName
            )
        } else {
            NPLogger.w(
                TAG,
                "SAF 提升恢复目标已确认但 pending 清理未确认，保留下次重试: " +
                    "name=$pendingName"
            )
        }
    }
    treeChildRegistry.rememberTreeChild(root.tree, entry)
    NPLogger.d(
        TAG,
        "SAF 提升复用了已提交目标，跳过重复复制: name=$finalName"
    )
    return entry
}

internal suspend fun ManagedDownloadStorage.copyPendingTreeAudioWithoutReplacing(
    context: Context,
    root: RootHandle.TreeRoot,
    pending: DocumentFile,
    pendingName: String,
    finalName: String,
    expectedSizeBytes: Long,
    fallbackLastModifiedMs: Long,
    pendingParent: DocumentFile? = null
): StoredEntry? {
    val backend = SafStorageBackend(context)
    val copied = backend.read(StorageReference.SafRef(pending.uri)) { source ->
        ManagedDownloadTreeMutationLocks.withLock(root.tree.uri) {
            val beforeCreate = treeChildRegistry.treeChildrenForWrite(context, root.tree)
            val recovered = beforeCreate.children
                .filter { child ->
                    ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
                }
                .singleOrNull()
                ?.let { existingTarget ->
                    reconcileExistingTreePromotionTargetLocked(
                        context = context,
                        root = root,
                        refresh = beforeCreate,
                        targetUri = existingTarget.documentUri,
                        pendingUri = pending.uri,
                        pendingName = pendingName,
                        pendingParent = pendingParent,
                        finalName = finalName,
                        expectedSizeBytes = expectedSizeBytes,
                        fallbackLastModifiedMs = fallbackLastModifiedMs
                    )
                }
            if (recovered != null) {
                return@withLock recovered
            }
            if (!canCreateTreePromotionTargetWithoutReplacing(
                    enumerationComplete = beforeCreate.isComplete,
                    existingNames = beforeCreate.children.map(QueriedTreeChild::name),
                    targetName = finalName
                )
            ) {
                NPLogger.w(
                    TAG,
                    "SAF 提升目标不可安全创建，保留 pending 音频: $finalName"
                )
                return@withLock null
            }
            val existingDocumentUris = beforeCreate.children.map(QueriedTreeChild::documentUri)
            val createdUri = try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    root.tree.uri,
                    ManagedDownloadTreeNaming.documentCreateMimeType(
                        finalName,
                        mimeTypeFromName(finalName, null)
                    ),
                    finalName
                )
            } catch (error: SecurityException) {
                throw error
            } catch (error: UnsupportedOperationException) {
                NPLogger.w(TAG, "SAF 不支持无覆写提升创建: $finalName", error)
                return@withLock null
            } catch (error: Throwable) {
                throw IOException("SAF 无覆写提升创建失败: $finalName", error)
            } ?: return@withLock null
            val created = resolveNewTreePromotionDocument(
                context = context,
                parent = root.tree,
                uri = createdUri
            )
            if (created == null) {
                if (existingDocumentUris.none { uri -> sameTreeDocument(uri, createdUri) }) {
                    discardNewTreePromotionTarget(context, root.tree, finalName, createdUri)
                }
                return@withLock null
            }
            if (existingDocumentUris.any { uri -> sameTreeDocument(uri, created.uri) }) {
                NPLogger.w(TAG, "SAF 提升创建返回已有文件，保留 pending 音频: $finalName")
                return@withLock null
            }
            if (created.isDirectory) {
                discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
                NPLogger.w(TAG, "SAF 提升创建了目录而非音频文件: $finalName")
                return@withLock null
            }
            if (!ManagedDownloadTreeNaming.isExactTreeStoredName(created.name, finalName)) {
                discardNewTreePromotionTarget(context, root.tree, created.name ?: finalName, created.uri)
                NPLogger.w(
                    TAG,
                    "SAF 提升创建返回非目标名称，保留 pending 音频: " +
                        "expected=$finalName, actual=${created.name}"
                )
                return@withLock null
            }
            val afterCreate = treeChildRegistry.treeChildrenForWrite(context, root.tree)
            val exactTargets = afterCreate.children.filter { child ->
                ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
            }
            if (
                !afterCreate.isComplete ||
                    exactTargets.size != 1 ||
                    exactTargets.none { child -> sameTreeDocument(child.documentUri, created.uri) }
            ) {
                discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
                NPLogger.w(TAG, "SAF 提升创建后目标不唯一，保留 pending 音频: $finalName")
                return@withLock null
            }
            try {
                val output = context.contentResolver.openOutputStream(created.uri, "w")
                    ?: throw IOException("SAF final 音频不可写: $finalName")
                output.use { target ->
                    val copiedBytes = source.copyTo(target, STREAM_COPY_BUFFER_SIZE_BYTES)
                    if (copiedBytes != expectedSizeBytes) {
                        throw IOException(
                            "SAF pending 音频复制长度不匹配: $pendingName, " +
                                "expected=$expectedSizeBytes, actual=$copiedBytes"
                        )
                    }
                }
                val entry = verifiedTreeStoredEntry(
                    context = context,
                    target = created,
                    expectedName = finalName,
                    expectedSizeBytes = expectedSizeBytes,
                    fallbackLastModifiedMs = fallbackLastModifiedMs,
                    description = finalName
                )
                val pendingDeleted = deleteTrustedReference(
                    context,
                    TrustedManagedRef(
                        reference = StorageReference.SafRef(pending.uri),
                        externalReference = pending.uri.toString()
                    )
                ).isConfirmedStorageMutation()
                if (pendingDeleted) {
                    treeChildRegistry.forgetTreeChildName(
                        pendingParent ?: root.tree,
                        pendingName
                    )
                } else {
                    NPLogger.w(TAG, "音频已提升但 pending 文件清理失败: $pendingName")
                }
                treeChildRegistry.rememberTreeChild(root.tree, entry)
                entry
            } catch (error: Throwable) {
                discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
                throw error
            }
        }
    }
    return when (copied) {
        is StorageLookupResult.Found -> copied.value
        StorageLookupResult.Missing -> throw IOException(
            "SAF pending 音频在提升时不存在: $pendingName"
        )
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF pending 音频提升时权限丢失: $pendingName"
        )
        is StorageLookupResult.ProviderFailure -> {
            if (copied.error is SecurityException) {
                throw copied.error
            }
            throw IOException("SAF pending 音频提升时读取失败: $pendingName", copied.error)
        }
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> throw IOException(
            "SAF pending 音频提升时不可读: $pendingName"
        )
    }
}

internal fun ManagedDownloadStorage.discardNewTreePromotionTarget(
    context: Context,
    parent: DocumentFile,
    childName: String,
    uri: Uri
) {
    val deleted = deleteTrustedReference(
        context,
        TrustedManagedRef(
            reference = StorageReference.SafRef(uri),
            externalReference = uri.toString()
        )
    ).isConfirmedStorageMutation()
    if (!deleted) {
        NPLogger.w(TAG, "SAF 提升临时目标清理失败: $childName")
    }
    treeChildRegistry.forgetTreeChildName(parent, childName)
    invalidateSnapshotCache(context)
}

internal fun ManagedDownloadStorage.isTreePromotionBackupName(actualName: String, targetName: String): Boolean {
    val directBackupName = ".${targetName}.backup"
    if (ManagedDownloadTreeNaming.isExactTreeStoredName(actualName, directBackupName)) {
        return true
    }
    if (!actualName.startsWith('.') || !actualName.endsWith(".backup", ignoreCase = true)) {
        return false
    }
    val baseWithIdentifier = actualName
        .drop(1)
        .dropLast(".backup".length)
    val separatorIndex = baseWithIdentifier.lastIndexOf('.')
    if (separatorIndex <= 0) {
        return false
    }
    val backedUpName = baseWithIdentifier.substring(0, separatorIndex)
    if (!ManagedDownloadTreeNaming.isExactTreeStoredName(backedUpName, targetName)) {
        return false
    }
    val identifier = baseWithIdentifier.substring(separatorIndex + 1)
    return runCatching { UUID.fromString(identifier) }.isSuccess
}

internal fun ManagedDownloadStorage.sameTreeDocument(first: Uri, second: Uri): Boolean {
    if (first == second) return true
    val firstId = treeDocumentIdOrNull(first)
    val secondId = treeDocumentIdOrNull(second)
    return firstId != null && firstId == secondId
}

internal fun ManagedDownloadStorage.treeDocumentIdOrNull(uri: Uri): String? {
    return try {
        DocumentsContract.getDocumentId(uri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

internal fun ManagedDownloadStorage.writeSeedMetadataAfterAudioCommit(
    context: Context,
    root: RootHandle,
    audioName: String,
    seedMetadataJson: String?
) {
    val content = seedMetadataJson?.takeIf(String::isNotBlank) ?: return
    try {
        writeRootText(
            context = context,
            root = root,
            displayName = "$audioName$METADATA_SUFFIX",
            content = content
        )
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "音频已提交但 seed metadata 写入失败，保留音频等待收尾重试: " +
                "audio=$audioName, error=${error.message}",
            error
        )
    }
}

internal fun ManagedDownloadStorage.writeCollisionPendingMetadata(
    context: Context,
    root: RootHandle,
    requestedAudioName: String,
    actualAudioName: String,
    pendingMetadataJson: String?
) {
    val content = pendingMetadataJson?.takeIf(String::isNotBlank) ?: return
    if (requestedAudioName == actualAudioName) return
    val temporaryRoot = resolveTemporaryRoot(
        context = context,
        root = root,
        create = true
    ) ?: throw IOException("无法准备下载 .tmp 目录")
    val metadataEntry = writeRootText(
        context = context,
        root = temporaryRoot,
        displayName = "$actualAudioName$PENDING_METADATA_SUFFIX",
        content = content
    )
    if (metadataEntry == null) {
        throw IOException(
            "无法为冲突后的下载音频写入 pending metadata: $actualAudioName"
        )
    }
}

internal fun ManagedDownloadStorage.saveLyricTextBlocking(context: Context, displayName: String, content: String): String? {
    return writeSubdirectoryBytesBlocking(
        context = context,
        subdirectory = LYRIC_SUBDIRECTORY,
        displayName = displayName,
        bytes = content.toByteArray(Charsets.UTF_8),
        mimeType = mimeTypeFromName(displayName, null)
    )?.reference
}
