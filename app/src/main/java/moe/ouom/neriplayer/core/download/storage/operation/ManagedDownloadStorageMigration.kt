package moe.ouom.neriplayer.core.download.storage.operation

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.operation.content.backendReference
import moe.ouom.neriplayer.core.download.storage.operation.content.createDefaultRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.deleteTrustedReference
import moe.ouom.neriplayer.core.download.storage.operation.content.forgetDeletedReferencesFromCaches
import moe.ouom.neriplayer.core.download.storage.operation.content.normalizeDirectoryUri
import moe.ouom.neriplayer.core.download.storage.operation.content.parseDownloadedAudioMetadataBatch
import moe.ouom.neriplayer.core.download.storage.operation.content.resolveRoot
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadLibrarySnapshot
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_TEMPORARY_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.commit.sameManagedMigrationStoredEntryIdentity
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationEntryCollector
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCopyReceipt
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationSourceEntry
import moe.ouom.neriplayer.core.download.storage.migration.recovery.isMigrationDocumentIdWithinTree
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import java.io.File
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.resolveRootForOperation(
    context: Context,
    directoryUri: String?,
    useDefaultRootWhenDirectoryUriMissing: Boolean,
    unavailableMessage: String
): RootHandle? {
    val normalizedDirectoryUri = directoryUri
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (normalizedDirectoryUri != null) {
        return resolveRootBlocking(context, normalizedDirectoryUri)
            ?: run {
                NPLogger.w(
                    TAG,
                    "$unavailableMessage: directoryUri=$normalizedDirectoryUri"
                )
                null
            }
    }
    return if (useDefaultRootWhenDirectoryUriMissing) {
        rootResolver.createDefaultRoot(context)
    } else {
        resolveRootBlocking(context)
    }
}

internal fun ManagedDownloadStorage.resolveRootBlocking(context: Context): RootHandle {
    return rootResolver.resolveConfiguredRoot(
        context = context,
        configuredDirectoryUri = settings.configuredDirectoryUri,
        onUnavailableTreeRoot = { configuredUri ->
            NPLogger.w(TAG, "自定义下载目录不可用，停止读写并等待重新授权: $configuredUri")
        }
    )
}

internal fun ManagedDownloadStorage.resolveRootBlocking(context: Context, directoryUriString: String?): RootHandle? {
    return rootResolver.resolveRoot(context, directoryUriString)
}

internal fun ManagedDownloadStorage.findAudioEntry(
    snapshot: DownloadLibrarySnapshot,
    song: SongItem
): StoredEntry? {
    return ManagedDownloadStorageLookup.findAudioEntry(
        snapshot = snapshot,
        song = song,
        fileNameTemplate = settings.fileNameTemplate
    )?.let { result ->
        if (LOG_HOT_AUDIO_HITS) {
            NPLogger.d(TAG, "命中已下载音频(${result.hitType}): song=${song.displayName()}, file=${result.entry.name}")
        }
        result.entry
    }
}

internal fun ManagedDownloadStorage.findAudioEntry(audioEntries: List<StoredEntry>, baseNames: List<String>): StoredEntry? {
    return ManagedDownloadStorageLookup.findAudioEntry(audioEntries, baseNames)
}

internal fun ManagedDownloadStorage.listChildren(context: Context, root: RootHandle): List<StoredEntry> {
    return treeDirectories.listChildren(context, root)
}

internal fun ManagedDownloadStorage.shouldIndexMetadataLessAudio(directoryUri: String?): Boolean {
    return normalizeDirectoryUri(directoryUri) == null
}

internal fun ManagedDownloadStorage.isMigrationSourceEntryBoundToRoot(
    root: RootHandle,
    entry: ManagedMigrationSourceEntry
): Boolean {
    return isMigrationReferenceBoundToRoot(root, entry.sourceReference)
}

internal fun ManagedDownloadStorage.isMigrationReferenceBoundToRoot(
    root: RootHandle,
    rawReference: String
): Boolean {
    val reference = rawReference.trim()
    return when (root) {
        is RootHandle.FileRoot -> {
            val rootPath = root.dir.absolutePath.trimEnd(File.separatorChar)
            reference == rootPath || reference.startsWith(rootPath + File.separator)
        }

        is RootHandle.TreeRoot -> {
            val referenceUri = runCatching { reference.toUri() }.getOrNull()
                ?: return false
            if (
                !referenceUri.scheme.equals("content", ignoreCase = true) ||
                !referenceUri.authority.equals(
                    root.tree.uri.authority,
                    ignoreCase = true
                )
            ) {
                return false
            }
            val treeDocumentId = runCatching {
                DocumentsContract.getTreeDocumentId(root.tree.uri)
            }.getOrNull() ?: return false
            val referenceTreeDocumentId = runCatching {
                DocumentsContract.getTreeDocumentId(referenceUri)
            }.getOrNull()
            if (referenceTreeDocumentId != null) {
                return referenceTreeDocumentId == treeDocumentId
            }
            val documentId = runCatching {
                DocumentsContract.getDocumentId(referenceUri)
            }.getOrNull() ?: return false
            isMigrationDocumentIdWithinTree(
                treeDocumentId = treeDocumentId,
                documentId = documentId
            )
        }
    }
}

internal suspend fun ManagedDownloadStorage.statMigrationReceiptTarget(
    context: Context,
    targetRoot: RootHandle,
    receipt: ManagedMigrationCopyReceipt
): StoredEntry? {
    val reference = receipt.targetEntry.reference.trim()
    if (!isMigrationReferenceBoundToRoot(targetRoot, reference)) return null
    val backendTarget = backendReference(context, reference) ?: return null
    return when (val result = backendTarget.backend.stat(backendTarget.reference)) {
        is StorageLookupResult.Found -> {
            val actual = result.value.toStoredEntryForBackend(
                (targetRoot as? RootHandle.FileRoot)?.dir
            )
            actual.takeUnless { entry ->
                entry.isDirectory ||
                    entry.name != receipt.targetEntry.name ||
                    !sameManagedMigrationStoredEntryIdentity(receipt.targetEntry, entry)
            }
        }
        StorageLookupResult.Missing,
        StorageLookupResult.PermissionLost,
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.ProviderFailure,
        is StorageLookupResult.Unsupported -> null
    }
}

internal suspend fun ManagedDownloadStorage.statMigrationReceiptSource(
    context: Context,
    sourceRoot: RootHandle,
    sourceEntry: StoredEntry
): StorageLookupResult<StorageStat> {
    return try {
        val reference = sourceEntry.reference.trim()
        if (!isMigrationReferenceBoundToRoot(sourceRoot, reference)) {
            return StorageLookupResult.OutOfScope
        }
        val backendSource = backendReference(context, reference)
            ?: return StorageLookupResult.OutOfScope
        backendSource.backend.stat(backendSource.reference)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        StorageLookupResult.ProviderFailure(error)
    }
}

internal fun ManagedDownloadStorage.collectManagedMigrationEntries(
    context: Context,
    root: RootHandle,
    allowMetadataLessAudio: Boolean
): List<ManagedMigrationEntry> {
    val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
    requireCompleteMigrationDirectoryScan(
        root = root,
        isComplete = refresh.isComplete
    )
    val rootEntries = refresh.rootEntries.filterNot(StoredEntry::isDirectory)
    val metadataEntries = rootEntries.filter { ManagedDownloadTreeNaming.isMetadataName(it.name) }
    val coverEntries = refresh.coverEntries
    val lyricEntries = refresh.lyricEntries
    val metadataEntriesByAudioName = metadataEntries
        .mapNotNull { entry ->
            ManagedDownloadTreeNaming.metadataAudioName(entry.name)?.let { audioName ->
                audioName to entry
            }
        }
        .groupBy { it.first }
        .mapValues { (audioName, entries) ->
            entries.minWithOrNull(
                compareBy<Pair<String, StoredEntry>>(
                    { ManagedDownloadTreeNaming.metadataNameOrdinal(it.second.name, audioName) ?: Int.MAX_VALUE },
                    { it.second.name }
                )
            )!!.second
        }
    val audioLastModifiedByName = rootEntries
        .asSequence()
        .filter { entry -> entry.extension in audioExtensions }
        .associate { entry -> entry.name to entry.lastModifiedMs }
    val parsedMetadataByAudioName = parseDownloadedAudioMetadataBatch(
        context = context,
        entries = metadataEntriesByAudioName.map { (audioName, entry) ->
            audioName to entry
        }
    ).mapNotNull { (audioName, metadata) ->
        metadata?.let {
            audioName to enrichMigrationMetadataTemporalFields(
                metadata = it,
                audioLastModifiedMs = audioLastModifiedByName[audioName]
            )
        }
    }.toMap()
    return ManagedDownloadMigrationEntryCollector.collect(
        rootEntries = rootEntries,
        coverEntries = coverEntries,
        lyricEntries = lyricEntries,
        parsedMetadataByAudioName = parsedMetadataByAudioName,
        allowMetadataLessAudio = allowMetadataLessAudio
    )
}

internal fun ManagedDownloadStorage.enrichMigrationMetadataTemporalFields(
    metadata: DownloadedAudioMetadata,
    audioLastModifiedMs: Long?
): DownloadedAudioMetadata {
    val fallbackTimestamp = audioLastModifiedMs?.takeIf { it > 0L } ?: return metadata
    if (
        metadata.createdAtMs?.let { it > 0L } == true ||
            metadata.sourceCreatedAtMs?.let { it > 0L } == true
    ) {
        return metadata.copy(
            sourceModifiedAtMs = metadata.sourceModifiedAtMs?.takeIf { it > 0L } ?: fallbackTimestamp
        )
    }
    return metadata.copy(
        createdAtMs = fallbackTimestamp,
        createdAtSource = metadata.createdAtSource ?: "MTIME",
        createdAtConfidence = metadata.createdAtConfidence ?: "INFERRED",
        sourceModifiedAtMs = metadata.sourceModifiedAtMs?.takeIf { it > 0L } ?: fallbackTimestamp
    )
}

internal fun ManagedDownloadStorage.requireCompleteMigrationDirectoryScan(
    root: RootHandle,
    isComplete: Boolean
) {
    if (isComplete) {
        return
    }
    throw ManagedDownloadMigrationException.transient(
        "迁移目录枚举不完整: root=${root.javaClass.simpleName}"
    )
}

internal fun ManagedDownloadStorage.requireMigrationSourceHasNoPendingArtifacts(
    context: Context,
    sourceRoot: RootHandle
) {
    discardMigrationTemporaryDirectory(context, sourceRoot)
}

internal fun ManagedDownloadStorage.discardMigrationTemporaryDirectory(
    context: Context,
    root: RootHandle
): Boolean {
    val deletedReferences = linkedSetOf<String>()
    when (root) {
        is RootHandle.FileRoot -> {
            val children = root.dir.listFiles()
                ?: throw ManagedDownloadMigrationException.transient(
                    "迁移前无法检查源目录临时文件"
                )
            children
                .asSequence()
                .filter(File::isDirectory)
                .filter { directory ->
                    ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                        directory.name,
                        DOWNLOAD_TEMPORARY_DIR_NAME
                    )
                }
                .forEach { directory ->
                    if (!directory.deleteRecursively() && directory.exists()) {
                        throw ManagedDownloadMigrationException.transient(
                            "迁移前无法删除源目录 .tmp"
                        )
                    }
                    deletedReferences += directory.absolutePath
                }
        }

        is RootHandle.TreeRoot -> {
            val refresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                context = context,
                parent = root.tree
            )
            if (!refresh.isComplete) {
                throw ManagedDownloadMigrationException.transient(
                    "迁移前源目录枚举不完整，暂缓处理临时文件"
                )
            }
            refresh.children
                .asSequence()
                .filter(QueriedTreeChild::isDirectory)
                .filter { child ->
                    ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                        child.name,
                        DOWNLOAD_TEMPORARY_DIR_NAME
                    )
                }
                .forEach { child ->
                    val directory = treeChildRegistry.toDocumentFile(
                        context,
                        root.tree,
                        child
                    ) ?: throw ManagedDownloadMigrationException.transient(
                        "迁移前无法读取源目录 .tmp"
                    )
                    when (
                        deleteTrustedReference(
                            context,
                            TrustedManagedRef(
                                reference = StorageReference.SafRef(directory.uri),
                                externalReference = directory.uri.toString()
                            )
                        )
                    ) {
                        StorageMutationResult.Deleted,
                        StorageMutationResult.Missing -> {
                            deletedReferences += directory.uri.toString()
                        }
                        StorageMutationResult.OutOfScope,
                        StorageMutationResult.PermissionLost,
                        is StorageMutationResult.ProviderFailure,
                        is StorageMutationResult.Unsupported -> {
                            throw ManagedDownloadMigrationException.transient(
                                "迁移前无法删除源目录 .tmp"
                            )
                        }
                    }
                }
        }
    }
    forgetDeletedReferencesFromCaches(deletedReferences)
    if (deletedReferences.isNotEmpty()) {
        NPLogger.i(
            TAG,
            "迁移前已整体删除 .tmp 目录: count=${deletedReferences.size}"
        )
    }
    return true
}

internal fun ManagedDownloadStorage.listSubdirectoryEntries(context: Context, root: RootHandle, subdirectory: String): List<StoredEntry> {
    return treeDirectories.listSubdirectoryEntries(context, root, subdirectory)
}
