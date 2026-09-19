package moe.ouom.neriplayer.core.download.storage.operation.content

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.SnapshotEntryBucket
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.BackendReference
import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeletePolicy
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadReferenceDeleteResult
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.clearTreeDirectoryCache() {
    treeDirectories.clear()
    treeChildRegistry.clear()
    rootResolver.clearCache()
}

internal fun ManagedDownloadStorage.restoreStoredEntryLastModified(entry: StoredEntry, lastModifiedMs: Long) {
    if (lastModifiedMs <= 0L) {
        return
    }
    val localFile = entry.localFilePath
        ?.let(::File)
        ?.takeIf(File::exists)
    if (localFile != null) {
        if (!localFile.setLastModified(lastModifiedMs)) {
            throw IOException("无法保留迁移文件修改时间: ${entry.name}")
        }
        return
    }
    // SAF Provider 决定物理时间，来源时间保存在元数据中
}

internal fun ManagedDownloadStorage.migrationMimeTypeFor(entry: ManagedMigrationEntry): String {
    return ManagedDownloadMigrationPolicy.mimeTypeFor(entry.toRef())
}

internal fun ManagedDownloadStorage.migrationCopyParallelism(sourceRoot: RootHandle, targetRoot: RootHandle): Int {
    return ManagedDownloadMigrationPolicy.copyParallelism(
        usesTreeRoot = sourceRoot is RootHandle.TreeRoot || targetRoot is RootHandle.TreeRoot
    )
}

internal fun ManagedDownloadStorage.migrationRewriteParallelism(targetRoot: RootHandle): Int {
    return ManagedDownloadMigrationPolicy.rewriteParallelism(
        usesTreeRoot = targetRoot is RootHandle.TreeRoot
    )
}

internal fun ManagedDownloadStorage.migrationDeleteParallelism(root: RootHandle): Int {
    return ManagedDownloadMigrationPolicy.deleteParallelism(
        usesTreeRoot = root is RootHandle.TreeRoot
    )
}

internal fun ManagedDownloadStorage.normalizeDirectoryUri(uriString: String?): String? {
    return rootResolver.normalizeDirectoryUri(uriString)
}

internal fun ManagedDownloadStorage.resolveSnapshotCacheKey(context: Context): String {
    val appContext = context.applicationContext
    val configuredUri = normalizeDirectoryUri(settings.configuredDirectoryUri)
    if (configuredUri != null) {
        val identity = ManagedDownloadDirectoryIdentity.directoryIdentity(configuredUri)
            ?: configuredUri
        return "tree:$identity"
    }
    val resolvedRoot = rootResolver.resolveRoot(appContext, settings.configuredDirectoryUri)
        ?: RootHandle.FileRoot(ManagedDownloadRootResolver.defaultRootDirectory(appContext))
    return rootKeyForResolvedRoot(resolvedRoot)
}

internal fun ManagedDownloadStorage.rootKeyForResolvedRoot(root: RootHandle): String {
    return when (root) {
        is RootHandle.TreeRoot -> {
            val treeIdentity = ManagedDownloadDirectoryIdentity.directoryIdentity(
                root.tree.uri.toString()
            ) ?: root.tree.uri.toString()
            "tree:$treeIdentity"
        }
        is RootHandle.FileRoot -> "file:${root.dir.absolutePath}"
    }
}

internal fun ManagedDownloadStorage.resolveTreeRootBlocking(context: Context, directoryUriString: String?): RootHandle.TreeRoot? {
    return rootResolver.resolveTreeRoot(context, directoryUriString)
}

internal fun ManagedDownloadStorage.createDefaultRoot(context: Context): RootHandle.FileRoot {
    return rootResolver.createDefaultRoot(context)
}

internal fun ManagedDownloadStorage.readTextInternal(context: Context, reference: String): String? {
    return runBlocking(Dispatchers.IO) {
        readTextInternalSuspending(context, reference)
    }
}

internal suspend fun ManagedDownloadStorage.readTextInternalSuspending(
    context: Context,
    reference: String
): String? {
    val target = backendReference(context, reference) ?: return null
    return when (val result = target.backend.read(target.reference) { input ->
        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }) {
        is StorageLookupResult.Found -> {
            result.value
        }
        StorageLookupResult.Missing -> null
        StorageLookupResult.PermissionLost -> {
            throw SecurityException("storage permission lost: $reference")
        }
        is StorageLookupResult.ProviderFailure -> {
            // DocumentsProvider 可能把已经删除的 child 包装成
            // IllegalArgumentException。它不是 root 故障，不能让一次陈旧
            // sidecar 读取升级为未捕获异常或阻塞整批扫描
            if (ManagedDownloadReferenceIo.isMissingDocumentFailure(result.error)) {
                NPLogger.d(
                    TAG,
                    "读取托管文本时确认文件已不存在，按缺失处理: reference=$reference"
                )
                null
            } else {
                throw ManagedDownloadRootProviderException(reference, result.error)
            }
        }
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> null
    }
}

internal fun ManagedDownloadStorage.inspectStorageReference(
    context: Context,
    reference: String?
): ManagedDownloadReferenceIo.AccessResult {
    val target = backendReference(context, reference)
        ?: return ManagedDownloadReferenceIo.AccessResult.Missing
    return try {
        runBlocking(Dispatchers.IO) {
            when (val result = target.backend.stat(target.reference)) {
                is StorageLookupResult.Found -> {
                    ManagedDownloadReferenceIo.AccessResult.Accessible
                }
                StorageLookupResult.Missing -> {
                    ManagedDownloadReferenceIo.AccessResult.Missing
                }
                StorageLookupResult.PermissionLost -> {
                    ManagedDownloadReferenceIo.AccessResult.PermissionLost
                }
                is StorageLookupResult.ProviderFailure -> {
                    ManagedDownloadReferenceIo.AccessResult.ProviderFailure(result.error)
                }
                StorageLookupResult.OutOfScope -> {
                    ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                        IllegalArgumentException("storage reference out of scope")
                    )
                }
                is StorageLookupResult.Unsupported -> {
                    ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                        UnsupportedOperationException(result.operation)
                    )
                }
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: SecurityException) {
        ManagedDownloadReferenceIo.AccessResult.PermissionLost
    } catch (error: Throwable) {
        ManagedDownloadReferenceIo.AccessResult.ProviderFailure(error)
    }
}

internal fun ManagedDownloadStorage.backendReference(
    context: Context,
    rawReference: String?
): BackendReference? {
    val normalized = rawReference?.trim()?.takeIf(String::isNotBlank) ?: return null
    val uri = runCatching { normalized.toUri() }.getOrNull()
    if (uri?.scheme.equals("content", ignoreCase = true) && uri != null) {
        return BackendReference(
            backend = SafStorageBackend(context),
            reference = StorageReference.SafRef(uri)
        )
    }
    val path = when {
        normalized.startsWith("/", ignoreCase = false) -> normalized
        uri?.scheme?.equals("file", ignoreCase = true) == true -> uri.path
        else -> normalized
    }?.takeIf(String::isNotBlank) ?: return null
    val file = File(path)
    val parent = file.parentFile ?: return null
    return BackendReference(
        backend = FileStorageBackend(parent),
        reference = StorageReference.FileRef(file.name)
    )
}

internal fun ManagedDownloadStorage.buildManagedDeletePolicy(
    context: Context,
    allowedRoot: RootHandle? = null,
    trustedReferences: Set<String>? = null
): ManagedDownloadDeletePolicy {
    val roots = listOf(allowedRoot ?: resolveRootBlocking(context))
    val snapshotTrustedReferences = trustedReferences
        ?: if (allowedRoot == null) {
            cachedDownloadLibrarySnapshot(context)?.knownReferences.orEmpty()
        } else {
            emptySet()
        }
    return ManagedDownloadDeletePolicy(
        managedFileRoots = roots.mapNotNull { root ->
            (root as? RootHandle.FileRoot)?.dir?.absolutePath
        },
        managedTreeRoots = roots.mapNotNull { root ->
            (root as? RootHandle.TreeRoot)?.tree?.uri?.toString()
        },
        trustedReferences = snapshotTrustedReferences
            .mapTo(linkedSetOf(), ::trustedManagedRef)
    )
}

internal fun ManagedDownloadStorage.trustedManagedRef(reference: String): TrustedManagedRef {
    val uri = runCatching { reference.toUri() }.getOrNull()
    return if (uri?.scheme.equals("content", ignoreCase = true) && uri != null) {
        TrustedManagedRef(
            reference = StorageReference.SafRef(uri),
            externalReference = reference
        )
    } else {
        val filePath = if (uri?.scheme.equals("file", ignoreCase = true) && uri != null) {
            uri.path ?: reference
        } else {
            reference
        }
        TrustedManagedRef(
            reference = StorageReference.FileRef(filePath),
            externalReference = reference
        )
    }
}

internal fun ManagedDownloadStorage.trustedManagedRefOrNull(reference: String?): TrustedManagedRef? {
    return reference
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(::trustedManagedRef)
}

internal fun ManagedDownloadStorage.resolveTrustedManagedReferences(
    references: Collection<String?>,
    deletePolicy: ManagedDownloadDeletePolicy
): List<TrustedManagedRef> {
    return references.mapNotNull { rawReference ->
        val candidate = trustedManagedRefOrNull(rawReference) ?: return@mapNotNull null
        deletePolicy.trustedReferences.firstOrNull { trusted ->
            trusted.externalReference == candidate.externalReference
        } ?: candidate.takeIf { reference ->
            reference.reference is StorageReference.FileRef &&
                isReferenceAllowedForManagedDelete(
                    reference = reference.externalReference,
                    trustedReferences = emptySet(),
                    managedFileRoots = deletePolicy.managedFileRoots,
                    managedTreeRoots = deletePolicy.managedTreeRoots
                )
        }
    }.distinctBy(TrustedManagedRef::externalReference)
}

internal suspend fun ManagedDownloadStorage.deleteEnumeratedMigrationReference(
    context: Context,
    reference: TrustedManagedRef,
    root: RootHandle
): StorageMutationResult {
    // 在一次短事务内完成列举和删除，避免并发清理让另一个 Worker 使用失效凭据
    return migrationCleanupTrustLock.withLock {
        val trustedReferences = try {
            enumerateCompleteRootReferences(context, root)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withLock error.toMigrationDeletionResult()
        } ?: return@withLock StorageMutationResult.ProviderFailure(
            IllegalStateException("迁移删除前的完整枚举未完成")
        )
        val enumeratedReference = trustedReferences.firstOrNull { trusted ->
            trusted.externalReference == reference.externalReference
        } ?: run {
            NPLogger.w(
                TAG,
                "迁移删除引用未来自当前完整枚举，保留源: ${reference.externalReference}"
            )
            return@withLock StorageMutationResult.OutOfScope
        }
        try {
            val deleted = deleteInternal(
                context = context,
                reference = enumeratedReference.externalReference,
                allowedRoot = root,
                trustedReferences = trustedReferences.mapTo(linkedSetOf()) {
                    it.externalReference
                },
                invalidateSnapshot = false
            )
            if (deleted) {
                StorageMutationResult.Deleted
            } else {
                classifyMigrationDeleteFailure(context, reference)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error.toMigrationDeletionResult()
        }
    }
}

internal fun ManagedDownloadStorage.rootIdentityForLog(root: RootHandle): String {
    return when (root) {
        is RootHandle.FileRoot -> root.dir.absolutePath
        is RootHandle.TreeRoot -> root.tree.uri.toString()
    }
}

internal fun ManagedDownloadStorage.enumerateCompleteRootReferences(
    context: Context,
    root: RootHandle
): Set<TrustedManagedRef>? {
    // 迁移清理必须依赖一次完整的根目录和侧载目录列举
    // 只列根目录会把嵌套的 Covers 和 Lyrics 文件遗留下来
    val refresh = treeDirectories.refreshManagedMigrationEntries(context, root)
    if (!refresh.isComplete) {
        return null
    }
    return trustedReferencesFromMigrationRefresh(refresh)
}

internal fun ManagedDownloadStorage.trustedReferencesFromMigrationRefresh(
    refresh: ManagedDownloadTreeDirectories.ManagedMigrationEntriesRefresh
): Set<TrustedManagedRef> {
    return buildSet {
        (refresh.rootEntries + refresh.coverEntries + refresh.lyricEntries).forEach { entry ->
            add(trustedManagedRef(entry.reference))
            contentReferenceAliasesForTrust(entry.reference).forEach { alias ->
                add(trustedManagedRef(alias))
            }
        }
    }
}

internal fun ManagedDownloadStorage.classifyMigrationDeleteFailure(
    context: Context,
    reference: TrustedManagedRef
): StorageMutationResult {
    return try {
        when (val access = inspectStorageReference(
            context,
            reference.externalReference
        )) {
            ManagedDownloadReferenceIo.AccessResult.Missing -> {
                StorageMutationResult.Missing
            }
            ManagedDownloadReferenceIo.AccessResult.PermissionLost -> {
                StorageMutationResult.PermissionLost
            }
            is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> {
                StorageMutationResult.ProviderFailure(access.error)
            }
            ManagedDownloadReferenceIo.AccessResult.Accessible -> {
                StorageMutationResult.ProviderFailure(
                    IllegalStateException("迁移源文件删除未确认")
                )
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        StorageMutationResult.PermissionLost
    } catch (error: Throwable) {
        StorageMutationResult.ProviderFailure(error)
    }
}

internal fun ManagedDownloadStorage.contentReferenceAliasesForTrust(reference: String): Set<String> {
    val uri = runCatching { reference.toUri() }.getOrNull()
        ?: return emptySet()
    if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
        return emptySet()
    }
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        ?: return emptySet()
    return buildSet {
        add(DocumentsContract.buildDocumentUri(uri.authority, documentId).toString())
        if (uri.pathSegments.any { it == "tree" }) {
            add(
                DocumentsContract.buildDocumentUriUsingTree(uri, documentId).toString()
            )
        }
    }
}

internal fun ManagedDownloadStorage.deleteInternal(
    context: Context,
    reference: String?,
    allowedRoot: RootHandle? = null,
    trustedReferences: Set<String>? = null,
    invalidateSnapshot: Boolean = true
): Boolean {
    return deleteReferencesInternal(
        context = context,
        references = listOf(reference),
        allowedRoot = allowedRoot,
        trustedReferences = trustedReferences,
        invalidateSnapshot = invalidateSnapshot
    ).isNotEmpty()
}

internal fun ManagedDownloadStorage.deleteReferencesInternal(
    context: Context,
    references: Collection<String?>,
    allowedRoot: RootHandle? = null,
    trustedReferences: Set<String>? = null,
    invalidateSnapshot: Boolean,
    onDeleteStarted: (String) -> Unit = {},
    onDeleteAttemptFinished: (String, Boolean) -> Unit = { _, _ -> }
): Set<String> {
    val deletePolicy = buildManagedDeletePolicy(
        context = context,
        allowedRoot = allowedRoot,
        trustedReferences = trustedReferences
    )
    val deleteResult = referenceDeleteExecutor.deleteReferences(
        context = context,
        references = resolveTrustedManagedReferences(references, deletePolicy),
        deletePolicy = deletePolicy,
        onDeleteStarted = { reference ->
            onDeleteStarted(reference.externalReference)
        },
        onDeleteAttemptFinished = { reference, deleted ->
            onDeleteAttemptFinished(reference.externalReference, deleted)
        }
    )
    applyDeleteResultToSnapshot(context, deleteResult, invalidateSnapshot)
    return deleteResult.deletedReferences
}

internal suspend fun ManagedDownloadStorage.deleteReferencesInternalConcurrently(
    context: Context,
    references: Collection<TrustedManagedRef>,
    deletePolicy: ManagedDownloadDeletePolicy,
    invalidateSnapshot: Boolean,
    onDeleteAttemptFinished: (TrustedManagedRef, Boolean) -> Unit = { _, _ -> }
): Set<String> {
    val deleteResult = referenceDeleteExecutor.deleteReferencesConcurrently(
        context = context,
        references = references,
        deletePolicy = deletePolicy,
        onDeleteAttemptFinished = onDeleteAttemptFinished
    )
    applyDeleteResultToSnapshot(context, deleteResult, invalidateSnapshot)
    return deleteResult.deletedReferences
}

internal fun ManagedDownloadStorage.applyDeleteResultToSnapshot(
    context: Context,
    deleteResult: ManagedDownloadReferenceDeleteResult,
    invalidateSnapshot: Boolean
) {
    if (!invalidateSnapshot) {
        return
    }
    val deletedReferences = deleteResult.deletedReferences
    forgetDeletedReferencesFromCaches(deletedReferences)
    if (deleteResult.hasUnconfirmedDeletes) {
        invalidateSnapshotCache(context)
    } else if (deletedReferences.isNotEmpty() && !updateSnapshotCacheAfterDelete(context, deletedReferences)) {
        invalidateSnapshotCache(context)
    }
}

internal fun ManagedDownloadStorage.forgetDeletedReferencesFromCaches(deletedReferences: Set<String>) {
    if (deletedReferences.isEmpty()) return
    treeChildRegistry.forgetDeletedReferences(deletedReferences)
    treeDirectories.forgetDeletedReferences(deletedReferences)
}

internal fun ManagedDownloadStorage.deleteTrustedReference(
    context: Context,
    reference: TrustedManagedRef
): StorageMutationResult {
    return referenceDeleteExecutor.deleteTrustedContentReference(
        context = context,
        reference = reference
    )
}

internal fun ManagedDownloadStorage.updateSnapshotCacheAfterMetadataWrite(
    context: Context,
    metadataEntry: StoredEntry,
    metadata: DownloadedAudioMetadata
): Boolean {
    return snapshotCacheStore.updateAfterMetadataWrite(context, metadataEntry, metadata)
}

internal fun ManagedDownloadStorage.updateSnapshotCacheAfterStoredEntryWrite(
    context: Context,
    storedEntry: StoredEntry,
    bucket: SnapshotEntryBucket
): Boolean {
    return snapshotCacheStore.updateAfterStoredEntryWrite(context, storedEntry, bucket)
        .also {
            if (bucket == SnapshotEntryBucket.LYRIC) {
                notifyLyricsRefresh()
            }
        }
}

internal fun ManagedDownloadStorage.updateSnapshotCacheAfterDelete(
    context: Context,
    deletedReferences: Set<String>
): Boolean {
    return snapshotCacheStore.updateAfterDelete(context, deletedReferences)
        .also { updated ->
            if (updated && deletedReferences.isNotEmpty()) {
                notifyLyricsRefresh()
            }
        }
}
