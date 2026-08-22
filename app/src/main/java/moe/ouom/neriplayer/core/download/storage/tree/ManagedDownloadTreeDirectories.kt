package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.NO_MEDIA_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadTreeDirectories(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val tag: String
) {
    private val subdirectoryCache = ConcurrentHashMap<String, DocumentFile>()
    private val ensuredNoMediaMarkers = ConcurrentHashMap<String, Boolean>()

    fun findOrCreateDirectory(context: Context, parent: DocumentFile, displayName: String): DocumentFile? {
        val cacheKey = "${ManagedDownloadTreeMutationLocks.keyFor(parent.uri)}|$displayName"
        subdirectoryCache[cacheKey]
            ?.takeIf { it.isDirectory }
            ?.let { return it }
        findCanonicalExternalStorageDirectory(context, parent, displayName)
            ?.also { directory ->
                subdirectoryCache[cacheKey] = directory
            }
            ?.let { return it }
        return ManagedDownloadTreeMutationLocks.withLock(parent.uri) {
            subdirectoryCache[cacheKey]
                ?.takeIf { it.isDirectory }
                ?.let { return@withLock it }
            findCanonicalExternalStorageDirectory(context, parent, displayName)
                ?.also { directory -> subdirectoryCache[cacheKey] = directory }
                ?.let { return@withLock it }
            findKnownManagedSubdirectory(context, parent, displayName)
                ?.also { subdirectoryCache[cacheKey] = it }
                ?.let { return@withLock it }
            val refresh = treeChildRegistry.treeChildrenForWrite(context, parent)
            val existingChild = findManagedSubdirectoryChild(refresh.children, displayName)
            if (existingChild != null) {
                return@withLock treeChildRegistry.toDocumentFile(context, parent, existingChild)
                    ?.also { directory -> subdirectoryCache[cacheKey] = directory }
            }
            if (
                !refresh.isComplete &&
                    !ManagedDownloadTreeNaming.canCreateWhenChildrenQueryIsIncomplete(parent.uri)
            ) {
                NPLogger.w(
                    tag,
                    "SAF 子目录枚举不完整，跳过创建 $displayName: parent=${parent.uri}"
                )
                return@withLock null
            }

            val createdDirectory = try {
                parent.createDirectory(displayName)
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                // DocumentsProvider 可能在目录已存在但枚举尚未刷新时拒绝重复创建
                NPLogger.w(
                    tag,
                    "SAF 创建目录失败，准备重新查找已有目录: name=$displayName, " +
                        "parent=${parent.uri}, error=${error.message}"
                )
                subdirectoryCache.remove(cacheKey)
                null
            }
            val resolvedDirectory = createdDirectory?.let { created ->
                treeChildRegistry.toTreeDocumentFile(
                    context = context,
                    parent = parent,
                    child = created
                ) ?: created
            } ?: run {
                val knownDirectory = try {
                    parent.findFile(displayName)
                } catch (error: SecurityException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                knownDirectory
                    ?.takeIf(DocumentFile::isDirectory)
                    ?.also { directory -> subdirectoryCache[cacheKey] = directory }
                    ?: run {
                val retry = treeChildRegistry.refreshTreeChildrenWithStatus(context, parent)
                findManagedSubdirectoryChild(retry.children, displayName)
                    ?.let { child -> treeChildRegistry.toDocumentFile(context, parent, child) }
                    }
            }
            if (resolvedDirectory == null) {
                return@withLock null
            }
            if (
                createdDirectory != null &&
                    !ManagedDownloadTreeNaming.isExactTreeStoredName(
                        resolvedDirectory.name,
                        displayName
                    )
            ) {
                val canonicalChild = treeChildRegistry.refreshTreeChildrenWithStatus(
                    context = context,
                    parent = parent
                ).children.firstOrNull { child ->
                    child.isDirectory &&
                        ManagedDownloadTreeNaming.isExactTreeStoredName(
                            child.name,
                            displayName
                        ) &&
                        child.documentUri != resolvedDirectory.uri
                }
                if (canonicalChild != null) {
                    deleteContentReference(context, createdDirectory.uri)
                    treeChildRegistry.toDocumentFile(context, parent, canonicalChild)
                        ?.also { canonicalDirectory ->
                            subdirectoryCache[cacheKey] = canonicalDirectory
                        }
                        ?.let { return@withLock it }
                }
                findCanonicalExternalStorageDirectory(context, parent, displayName)
                    ?.also { canonicalDirectory ->
                        deleteContentReference(context, createdDirectory.uri)
                        subdirectoryCache[cacheKey] = canonicalDirectory
                    }
                    ?.let { return@withLock it }
                NPLogger.w(
                    tag,
                    "SAF 提供方返回了实际子目录名称: expected=$displayName, " +
                        "actual=${resolvedDirectory.name}"
                )
            }
            subdirectoryCache[cacheKey] = resolvedDirectory
            val createdName = ManagedDownloadTreeNaming.resolveTreeStoredName(
                resolvedDirectory.name,
                displayName
            )
            treeChildRegistry.updateRememberedTreeChild(
                parent = parent,
                childName = createdName,
                documentUri = resolvedDirectory.uri,
                sizeBytes = 0L,
                lastModifiedMs = System.currentTimeMillis(),
                isDirectory = true
            )
            resolvedDirectory
        }
    }

    private fun findCanonicalExternalStorageDirectory(
        context: Context,
        parent: DocumentFile,
        displayName: String
    ): DocumentFile? {
        if (parent.uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
        val parentDocumentId = try {
            try {
                DocumentsContract.getDocumentId(parent.uri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                DocumentsContract.getTreeDocumentId(parent.uri)
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        val childDocumentId = ManagedDownloadTreeNaming.externalStorageChildDocumentId(
            parentDocumentId = parentDocumentId,
            displayName = displayName
        ) ?: return null
        val childDocumentUri = try {
            // 保留原授权树的 tree id, 不能把子目录重新包装成新的 tree 根
            DocumentsContract.buildDocumentUriUsingTree(parent.uri, childDocumentId)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        val directory = try {
            DocumentFile.fromSingleUri(context, childDocumentUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            directory.takeIf { it.exists() && it.isDirectory }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteContentReference(context: Context, uri: android.net.Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    fun findSubdirectories(
        context: Context,
        root: ManagedDownloadRootHandle,
        desiredName: String,
        canonicalLast: Boolean = false
    ): List<ManagedDownloadRootHandle> {
        val comparator = if (canonicalLast) {
            compareBy<NamedDirectoryRoot>(
                { if (it.name == desiredName) 1 else 0 },
                { ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(it.name, desiredName) },
                { it.name }
            )
        } else {
            compareBy<NamedDirectoryRoot>(
                { if (it.name == desiredName) 0 else 1 },
                { ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(it.name, desiredName) },
                { it.name }
            )
        }
        return listDirectoryChildren(context, root)
            .filter { ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(it.name, desiredName) }
            .sortedWith(comparator)
            .map(NamedDirectoryRoot::root)
    }

    fun listChildren(
        context: Context,
        root: ManagedDownloadRootHandle
    ): List<ManagedDownloadStorage.StoredEntry> {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                root.dir.listFiles()
                    ?.map(ManagedDownloadStoredEntryMapper::fromFile)
                    .orEmpty()
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                ).map(ManagedDownloadStoredEntryMapper::fromTreeChild)
            }
        }
    }

    fun listSubdirectoryEntries(
        context: Context,
        root: ManagedDownloadRootHandle,
        subdirectory: String
    ): List<ManagedDownloadStorage.StoredEntry> {
        return findSubdirectories(context, root, subdirectory, canonicalLast = true)
            .flatMap { childRoot -> listChildren(context, childRoot) }
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
    }

    data class SubdirectoryEntriesRefresh(
        val entries: List<ManagedDownloadStorage.StoredEntry>,
        val isComplete: Boolean
    )

    data class RootEntriesRefresh(
        val entries: List<ManagedDownloadStorage.StoredEntry>,
        val isComplete: Boolean
    )

    data class ManagedMigrationEntriesRefresh(
        val rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        val coverEntries: List<ManagedDownloadStorage.StoredEntry>,
        val lyricEntries: List<ManagedDownloadStorage.StoredEntry>,
        val isComplete: Boolean
    )

    fun refreshRootEntries(
        context: Context,
        root: ManagedDownloadRootHandle
    ): RootEntriesRefresh {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val children = root.dir.listFiles()
                    ?: return RootEntriesRefresh(emptyList(), isComplete = false)
                RootEntriesRefresh(
                    entries = children.map(ManagedDownloadStoredEntryMapper::fromFile),
                    isComplete = true
                )
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val refresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                    context = context,
                    parent = root.tree
                )
                RootEntriesRefresh(
                    entries = refresh.children.map(ManagedDownloadStoredEntryMapper::fromTreeChild),
                    isComplete = refresh.isComplete
                )
            }
        }
    }

    fun refreshManagedMigrationEntries(
        context: Context,
        root: ManagedDownloadRootHandle
    ): ManagedMigrationEntriesRefresh {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val rootChildren = root.dir.listFiles()
                    ?: return ManagedMigrationEntriesRefresh(
                        rootEntries = emptyList(),
                        coverEntries = emptyList(),
                        lyricEntries = emptyList(),
                        isComplete = false
                    )
                var isComplete = true
                fun entriesFor(subdirectory: String): List<ManagedDownloadStorage.StoredEntry> {
                    return buildList {
                        rootChildren
                            .asSequence()
                            .filter(File::isDirectory)
                            .filter { directory ->
                                ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                                    directory.name,
                                    subdirectory
                                )
                            }
                            .forEach { directory ->
                                val children = directory.listFiles()
                                if (children == null) {
                                    isComplete = false
                                } else {
                                    children
                                        .asSequence()
                                        .filterNot(File::isDirectory)
                                        .map(ManagedDownloadStoredEntryMapper::fromFile)
                                        .forEach(::add)
                                }
                            }
                    }
                }
                ManagedMigrationEntriesRefresh(
                    rootEntries = rootChildren.map(ManagedDownloadStoredEntryMapper::fromFile),
                    coverEntries = entriesFor(COVER_SUBDIRECTORY),
                    lyricEntries = entriesFor(LYRIC_SUBDIRECTORY),
                    isComplete = isComplete
                )
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val rootRefresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                    context = context,
                    parent = root.tree
                )
                var isComplete = rootRefresh.isComplete
                fun entriesFor(subdirectory: String): List<ManagedDownloadStorage.StoredEntry> {
                    return buildList {
                        rootRefresh.children
                            .asSequence()
                            .filter(QueriedTreeChild::isDirectory)
                            .filter { child ->
                                ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                                    child.name,
                                    subdirectory
                                )
                            }
                            .forEach { child ->
                                val directory = treeChildRegistry.toDocumentFile(
                                    context,
                                    root.tree,
                                    child
                                )
                                if (directory == null) {
                                    isComplete = false
                                } else {
                                    val childRefresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                                        context = context,
                                        parent = directory
                                    )
                                    isComplete = isComplete && childRefresh.isComplete
                                    childRefresh.children
                                        .asSequence()
                                        .filterNot(QueriedTreeChild::isDirectory)
                                        .map(ManagedDownloadStoredEntryMapper::fromTreeChild)
                                        .forEach(::add)
                                }
                            }
                    }
                }
                ManagedMigrationEntriesRefresh(
                    rootEntries = rootRefresh.children.map(ManagedDownloadStoredEntryMapper::fromTreeChild),
                    coverEntries = entriesFor(COVER_SUBDIRECTORY),
                    lyricEntries = entriesFor(LYRIC_SUBDIRECTORY),
                    isComplete = isComplete
                )
            }
        }
    }

    fun refreshSubdirectoryEntries(
        context: Context,
        root: ManagedDownloadRootHandle,
        subdirectory: String
    ): SubdirectoryEntriesRefresh {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val directories = root.dir.listFiles()
                    ?.filter(File::isDirectory)
                    ?.filter { directory ->
                        ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                            directory.name,
                            subdirectory
                        )
                    }
                    ?.sortedWith(compareBy<File>(
                        { if (it.name == subdirectory) 0 else 1 },
                        { ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(it.name, subdirectory) },
                        { it.name }
                    ))
                if (directories == null) {
                    SubdirectoryEntriesRefresh(emptyList(), isComplete = false)
                } else {
                    var isComplete = true
                    val entries = buildList {
                        directories.forEach { directory ->
                            val children = directory.listFiles()
                            if (children == null) {
                                isComplete = false
                                return@forEach
                            }
                            children
                                .filterNot(File::isDirectory)
                                .mapTo(this, ManagedDownloadStoredEntryMapper::fromFile)
                        }
                    }
                    SubdirectoryEntriesRefresh(entries, isComplete)
                }
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val rootRefresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                    context = context,
                    parent = root.tree
                )
                val directoryChildren = rootRefresh.children
                    .filter(QueriedTreeChild::isDirectory)
                    .filter { child ->
                        ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                            child.name,
                            subdirectory
                        )
                    }
                    .sortedWith(compareBy<QueriedTreeChild>(
                        { if (it.name == subdirectory) 0 else 1 },
                        { ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(it.name, subdirectory) },
                        { it.name }
                    ))
                var isComplete = rootRefresh.isComplete
                val entries = buildList {
                    directoryChildren.forEach { child ->
                        val directory = treeChildRegistry.toDocumentFile(context, root.tree, child)
                        if (directory == null) {
                            isComplete = false
                            return@forEach
                        }
                        val childRefresh = treeChildRegistry.refreshTreeChildrenWithStatus(
                            context = context,
                            parent = directory
                        )
                        isComplete = isComplete && childRefresh.isComplete
                        childRefresh.children
                            .filterNot(QueriedTreeChild::isDirectory)
                            .mapTo(this, ManagedDownloadStoredEntryMapper::fromTreeChild)
                    }
                }
                SubdirectoryEntriesRefresh(entries, isComplete)
            }
        }
    }

    fun ensureManagedMediaScanIsolation(subdirectory: String, directory: File) {
        runCatching {
            ManagedDownloadMediaScanIsolation.ensureFileDirectory(
                subdirectory = subdirectory,
                directory = directory,
                ensuredMarkers = ensuredNoMediaMarkers
            )
        }.onFailure {
            NPLogger.w(
                tag,
                "创建 $subdirectory 目录 .nomedia 失败，继续写入侧载文件: " +
                    "${it.javaClass.simpleName}: ${it.message}",
                it
            )
        }
    }

    fun ensureManagedMediaScanIsolation(
        context: Context,
        subdirectory: String,
        directory: DocumentFile
    ) {
        try {
            ManagedDownloadMediaScanIsolation.ensureTreeDirectory(
                context = context,
                subdirectory = subdirectory,
                directory = directory,
                ensuredMarkers = ensuredNoMediaMarkers,
                hasCachedChild = { lookupContext, parent, childName ->
                    hasExistingNoMediaMarker(lookupContext, parent, childName)
                },
                createMarker = { parent ->
                    createNoMediaMarker(context, parent)
                },
                isMarkerAccessible = { lookupContext, marker ->
                    isAccessibleMarker(lookupContext, marker)
                },
                rememberMarker = { marker, storedName ->
                    treeChildRegistry.updateRememberedTreeChild(
                        parent = directory,
                        childName = storedName,
                        documentUri = marker.uri,
                        sizeBytes = 0L,
                        lastModifiedMs = System.currentTimeMillis(),
                        isDirectory = false
                    )
                }
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                tag,
                "创建 $subdirectory 目录 .nomedia 失败，继续写入侧载文件: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error
            )
        }
    }

    fun clear() {
        subdirectoryCache.clear()
        ensuredNoMediaMarkers.clear()
    }

    fun forgetDeletedReferences(deletedReferences: Set<String>) {
        if (deletedReferences.isEmpty()) return
        subdirectoryCache.forEach { (cacheKey, directory) ->
            if (directory.uri.toString() in deletedReferences) {
                subdirectoryCache.remove(cacheKey, directory)
            }
        }
        deletedReferences
            .filterNot { reference -> reference.startsWith("/") }
            .forEach(ensuredNoMediaMarkers::remove)
    }

    private fun findManagedSubdirectoryChild(
        children: Collection<QueriedTreeChild>,
        displayName: String
    ): QueriedTreeChild? {
        return children
            .filter(QueriedTreeChild::isDirectory)
            .filter { child -> ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(child.name, displayName) }
            .sortedWith(
                compareBy<QueriedTreeChild>(
                    { if (it.name == displayName) 0 else 1 },
                    { ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(it.name, displayName) },
                    QueriedTreeChild::name
                )
            )
            .firstOrNull()
    }

    private companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
            "com.android.externalstorage.documents"
    }

    private fun findKnownManagedSubdirectory(
        context: Context,
        parent: DocumentFile,
        displayName: String
    ): DocumentFile? {
        val child = findManagedSubdirectoryChild(
            children = treeChildRegistry.peekTreeChildren(parent).orEmpty(),
            displayName = displayName
        ) ?: return null
        return treeChildRegistry.toDocumentFile(context, parent, child)
    }

    private fun hasExistingNoMediaMarker(
        context: Context,
        directory: DocumentFile,
        childName: String
    ): Boolean {
        if (childName != NO_MEDIA_FILE_NAME) return false
        val cached = treeChildRegistry.cachedTreeChild(context, directory, childName)
        if (cached != null) {
            treeChildRegistry.toDocumentFile(context, directory, cached)
                ?.takeIf { isAccessibleMarker(context, it) }
                ?.let { return true }
        }
        val refreshed = treeChildRegistry.refreshTreeChildren(context, directory)
            .firstOrNull { child ->
                child.name == childName && !child.isDirectory
            }
        val marker = refreshed?.let { child ->
            treeChildRegistry.toDocumentFile(context, directory, child)
        }
        return marker?.let { isAccessibleMarker(context, it) } == true
    }

    private fun createNoMediaMarker(context: Context, parent: DocumentFile): DocumentFile? {
        val mimeTypes = listOf("application/octet-stream", "text/plain")
        mimeTypes.forEach { mimeType ->
            createNoMediaMarkerWithName(
                context = context,
                parent = parent,
                mimeType = mimeType,
                requestedName = NO_MEDIA_FILE_NAME
            )?.let { return it }
        }
        val temporaryName = NO_MEDIA_FILE_NAME.removePrefix(".")
        mimeTypes.forEach { mimeType ->
            createNoMediaMarkerWithName(
                context = context,
                parent = parent,
                mimeType = mimeType,
                requestedName = temporaryName
            )?.let { marker ->
                val renamed = try {
                    marker.renameTo(NO_MEDIA_FILE_NAME)
                } catch (error: SecurityException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                if (renamed) {
                    val renamedMarker = parent.findFile(NO_MEDIA_FILE_NAME) ?: marker
                    val storedName = ManagedDownloadTreeNaming.resolveTreeStoredName(
                        renamedMarker.name,
                        NO_MEDIA_FILE_NAME
                    )
                    if (storedName == NO_MEDIA_FILE_NAME && isAccessibleMarker(context, renamedMarker)) {
                        return renamedMarker
                    }
                }
                deleteDocument(marker)
            }
        }
        return null
    }

    private fun createNoMediaMarkerWithName(
        context: Context,
        parent: DocumentFile,
        mimeType: String,
        requestedName: String
    ): DocumentFile? {
        val createdUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent.uri,
                mimeType,
                requestedName
            )
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        val marker = DocumentFile.fromSingleUri(context, createdUri) ?: return null
        val storedName = ManagedDownloadTreeNaming.resolveTreeStoredName(
            marker.name,
            requestedName
        )
        if (storedName != requestedName) {
            deleteDocument(marker)
            return null
        }
        return marker.takeIf { isAccessibleMarker(context, it) }
            ?: run {
                deleteDocument(marker)
                null
            }
    }

    private fun isAccessibleMarker(context: Context, marker: DocumentFile): Boolean {
        if (marker.isFile && marker.exists()) return true
        return try {
            val resolver = context.contentResolver
            if (resolver.openFileDescriptor(marker.uri, "r")?.use { true } == true) {
                return true
            }
            if (resolver.openInputStream(marker.uri)?.use { true } == true) {
                return true
            }
            resolver.openOutputStream(marker.uri, "w")?.use {
                return true
            }
            false
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteDocument(document: DocumentFile): Boolean {
        return try {
            document.delete()
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private fun listDirectoryChildren(
        context: Context,
        root: ManagedDownloadRootHandle
    ): List<NamedDirectoryRoot> {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> root.dir.listFiles()
                ?.filter(File::isDirectory)
                ?.map { file ->
                    NamedDirectoryRoot(
                        name = file.name,
                        root = ManagedDownloadRootHandle.FileRoot(file)
                    )
                }
                .orEmpty()

            is ManagedDownloadRootHandle.TreeRoot -> {
                treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                )
                    .filter(QueriedTreeChild::isDirectory)
                    .mapNotNull { child ->
                        treeChildRegistry.toDocumentFile(context, root.tree, child)?.let { file ->
                            NamedDirectoryRoot(
                                name = child.name,
                                root = ManagedDownloadRootHandle.TreeRoot(file)
                            )
                        }
                    }
            }
        }
    }

    private data class NamedDirectoryRoot(
        val name: String,
        val root: ManagedDownloadRootHandle
    )
}
