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

    fun ensureManagedMediaScanIsolation(subdirectory: String, directory: File) {
        runCatching {
            ManagedDownloadMediaScanIsolation.ensureFileDirectory(
                subdirectory = subdirectory,
                directory = directory,
                ensuredMarkers = ensuredNoMediaMarkers
            )
        }.onFailure {
            NPLogger.w(tag, "创建封面目录 .nomedia 失败: ${it.message}")
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
                    treeChildRegistry.cachedTreeChild(lookupContext, parent, childName) != null
                },
                createMarker = { parent ->
                    parent.createFile("application/octet-stream", NO_MEDIA_FILE_NAME)
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
        }.onFailure {
            NPLogger.w(tag, "创建封面目录 .nomedia 失败: ${it.message}")
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
