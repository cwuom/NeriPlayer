package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.NO_MEDIA_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadTreeDirectories(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val locks: ConcurrentHashMap<String, Any>,
    private val tag: String
) {
    private val subdirectoryCache = ConcurrentHashMap<String, DocumentFile>()
    private val ensuredNoMediaMarkers = ConcurrentHashMap<String, Boolean>()

    fun findOrCreateDirectory(context: Context, parent: DocumentFile, displayName: String): DocumentFile? {
        val cacheKey = "${parent.uri}|$displayName"
        subdirectoryCache[cacheKey]
            ?.takeIf { it.isDirectory }
            ?.let { return it }
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            subdirectoryCache[cacheKey]
                ?.takeIf { it.isDirectory }
                ?.let { return@synchronized it }
            findCachedManagedSubdirectory(
                context = context,
                parent = parent,
                displayName = displayName,
                maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
            )
                ?.also { subdirectoryCache[cacheKey] = it }
                ?.let { return@synchronized it }
            val createdDirectory = parent.createDirectory(displayName)
                ?: findCachedManagedSubdirectory(
                    context = context,
                    parent = parent,
                    displayName = displayName,
                    maxCacheAgeMs = 0L
                )
            createdDirectory?.also {
                subdirectoryCache[cacheKey] = it
                val createdName = ManagedDownloadTreeNaming.resolveTreeStoredName(it.name, displayName)
                treeChildRegistry.updateRememberedTreeChild(
                    parent = parent,
                    childName = createdName,
                    documentUri = it.uri,
                    sizeBytes = 0L,
                    lastModifiedMs = System.currentTimeMillis(),
                    isDirectory = true
                )
            }
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
                        val directory = treeChildRegistry.toDocumentFile(context, child)
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
        }.getOrElse {
            NPLogger.e(tag, "创建 $subdirectory 目录 .nomedia 失败: ${it.message}", it)
            throw it
        }
    }

    fun ensureManagedMediaScanIsolation(
        context: Context,
        subdirectory: String,
        directory: DocumentFile
    ) {
        runCatching {
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
        }.getOrElse {
            NPLogger.e(tag, "创建 $subdirectory 目录 .nomedia 失败: ${it.message}", it)
            throw it
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

    private fun findCachedManagedSubdirectory(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        maxCacheAgeMs: Long
    ): DocumentFile? {
        return treeChildRegistry.cachedTreeChildren(
            context = context,
            parent = parent,
            maxCacheAgeMs = maxCacheAgeMs
        )
            .filter(QueriedTreeChild::isDirectory)
            .filter { child -> ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(child.name, displayName) }
            .sortedWith(
                compareBy<QueriedTreeChild>(
                    { if (it.name == displayName) 0 else 1 },
                    { ManagedDownloadTreeNaming.managedSubdirectoryOrdinal(it.name, displayName) },
                    QueriedTreeChild::name
                )
            )
            .firstNotNullOfOrNull { child -> treeChildRegistry.toDocumentFile(context, child) }
    }

    private fun hasExistingNoMediaMarker(
        context: Context,
        directory: DocumentFile,
        childName: String
    ): Boolean {
        if (childName != NO_MEDIA_FILE_NAME) return false
        val cached = treeChildRegistry.cachedTreeChild(context, directory, childName)
        if (cached != null) {
            treeChildRegistry.toDocumentFile(context, cached)
                ?.takeIf { isAccessibleMarker(context, it) }
                ?.let { return true }
        }
        val refreshed = treeChildRegistry.refreshTreeChildren(context, directory)
            .firstOrNull { child ->
                child.name == childName && !child.isDirectory
            }
        val marker = refreshed?.let { child ->
            treeChildRegistry.toDocumentFile(context, child)
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
                if (runCatching { marker.renameTo(NO_MEDIA_FILE_NAME) }.getOrDefault(false)) {
                    val renamedMarker = parent.findFile(NO_MEDIA_FILE_NAME) ?: marker
                    val storedName = ManagedDownloadTreeNaming.resolveTreeStoredName(
                        renamedMarker.name,
                        NO_MEDIA_FILE_NAME
                    )
                    if (storedName == NO_MEDIA_FILE_NAME && isAccessibleMarker(context, renamedMarker)) {
                        return renamedMarker
                    }
                }
                runCatching { marker.delete() }
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
        val marker = runCatching { parent.createFile(mimeType, requestedName) }.getOrNull()
            ?: return null
        val storedName = ManagedDownloadTreeNaming.resolveTreeStoredName(
            marker.name,
            requestedName
        )
        if (storedName != requestedName) {
            runCatching { marker.delete() }
            return null
        }
        return marker.takeIf { isAccessibleMarker(context, it) }
            ?: run {
                runCatching { marker.delete() }
                null
            }
    }

    private fun isAccessibleMarker(context: Context, marker: DocumentFile): Boolean {
        if (marker.isFile && marker.exists()) return true
        return runCatching {
            val resolver = context.contentResolver
            if (resolver.openFileDescriptor(marker.uri, "r")?.use { true } == true) {
                return@runCatching true
            }
            if (resolver.openInputStream(marker.uri)?.use { true } == true) {
                return@runCatching true
            }
            resolver.openOutputStream(marker.uri, "w")?.use {
                return@runCatching true
            }
            false
        }.getOrDefault(false)
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
                        treeChildRegistry.toDocumentFile(context, child)?.let { file ->
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
