package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.file.cache.ManagedDownloadFileChildNameCache
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.cache.ManagedDownloadTreeChildCache
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.download.storage.tree.cache.TreeChildNameRefreshMerger
import moe.ouom.neriplayer.core.download.storage.tree.query.ManagedDownloadTreeChildQuery

internal class ManagedDownloadTreeChildRegistry(
    writeCacheValidateIntervalMs: Long,
    private val treeCacheValidateIntervalMs: Long,
    private val treeWriteCacheValidateIntervalMs: Long,
    private val onTreeQueryFailed: (Throwable) -> Unit
) {
    private val treeChildCache = ManagedDownloadTreeChildCache()
    private val fileChildNameCache = ManagedDownloadFileChildNameCache(
        writeCacheValidateIntervalMs = writeCacheValidateIntervalMs
    )
    private val childNameReservationLocks = ConcurrentHashMap<String, Any>()

    fun queryTreeChildren(context: Context, parent: DocumentFile): List<QueriedTreeChild> {
        return ManagedDownloadTreeChildQuery.queryChildren(context, parent, onTreeQueryFailed)
    }

    fun rememberFileChildName(dir: File, childName: String) {
        fileChildNameCache.rememberName(dir, childName)
    }

    fun reserveUniqueFileChildName(dir: File, desiredName: String): String {
        return fileChildNameCache.reserveUniqueName(dir, desiredName)
    }

    fun forgetFileChildName(dir: File, childName: String) {
        fileChildNameCache.forgetName(dir, childName)
    }

    fun cachedTreeChildrenNames(context: Context, parent: DocumentFile): Set<String> {
        return cachedTreeChildrenNames(
            context = context,
            parent = parent,
            maxCacheAgeMs = treeCacheValidateIntervalMs
        )
    }

    fun cachedTreeChildrenNamesForWrite(context: Context, parent: DocumentFile): Set<String> {
        return cachedTreeChildrenNames(
            context = context,
            parent = parent,
            maxCacheAgeMs = treeWriteCacheValidateIntervalMs,
            allowReservedNames = true
        )
    }

    fun refreshTreeChildren(context: Context, parent: DocumentFile): Collection<QueriedTreeChild> {
        val refreshedAtMs = System.currentTimeMillis()
        return queryTreeChildren(context, parent).also { children ->
            rememberTreeChildren(parent, children, refreshedAtMs, isComplete = true)
        }
    }

    fun cachedTreeChildren(
        context: Context,
        parent: DocumentFile,
        maxCacheAgeMs: Long
    ): Collection<QueriedTreeChild> {
        val cacheKey = parent.uri.toString()
        val now = System.currentTimeMillis()
        treeChildCache.cachedChildren(
            cacheKey = cacheKey,
            nowMs = now,
            maxCacheAgeMs = maxCacheAgeMs
        )?.let { return it }
        return refreshTreeChildren(context, parent)
    }

    fun cachedTreeChild(
        context: Context,
        parent: DocumentFile,
        childName: String,
        maxCacheAgeMs: Long = treeWriteCacheValidateIntervalMs
    ): QueriedTreeChild? {
        return cachedTreeChildren(context, parent, maxCacheAgeMs)
            .firstOrNull { child -> child.name == childName }
    }

    fun rememberTreeChildren(
        parent: DocumentFile,
        children: Collection<QueriedTreeChild>,
        refreshedAtMs: Long,
        isComplete: Boolean
    ): Set<String> {
        return treeChildCache.rememberChildren(
            cacheKey = parent.uri.toString(),
            children = children,
            refreshedAtMs = refreshedAtMs,
            isComplete = isComplete
        )
    }

    fun rememberTreeChildName(
        parent: DocumentFile,
        childName: String,
        isReservation: Boolean = true
    ) {
        treeChildCache.rememberChildName(
            cacheKey = parent.uri.toString(),
            childName = childName,
            refreshedAtMs = System.currentTimeMillis(),
            isReservation = isReservation
        )
    }

    fun rememberTreeChild(parent: DocumentFile, child: QueriedTreeChild) {
        treeChildCache.rememberChild(
            cacheKey = parent.uri.toString(),
            child = child,
            refreshedAtMs = System.currentTimeMillis()
        )
    }

    fun rememberTreeChild(parent: DocumentFile, entry: ManagedDownloadStorage.StoredEntry) {
        val childUri = runCatching { entry.reference.toUri() }.getOrNull() ?: return
        updateRememberedTreeChild(
            parent = parent,
            childName = entry.name,
            documentUri = childUri,
            sizeBytes = entry.sizeBytes,
            lastModifiedMs = entry.lastModifiedMs,
            isDirectory = entry.isDirectory
        )
    }

    fun updateRememberedTreeChild(
        parent: DocumentFile,
        childName: String,
        documentUri: Uri,
        sizeBytes: Long,
        lastModifiedMs: Long,
        isDirectory: Boolean
    ) {
        rememberTreeChild(
            parent = parent,
            child = QueriedTreeChild(
                name = childName,
                documentUri = documentUri,
                sizeBytes = sizeBytes,
                lastModifiedMs = lastModifiedMs,
                isDirectory = isDirectory
            )
        )
    }

    fun reserveUniqueTreeChildName(
        context: Context,
        parent: DocumentFile,
        desiredName: String
    ): String {
        val cacheKey = parent.uri.toString()
        val lock = childNameReservationLocks.computeIfAbsent("tree:$cacheKey") { Any() }
        return synchronized(lock) {
            ManagedDownloadStorageNaming.createUniqueName(cachedTreeChildrenNamesForWrite(context, parent), desiredName)
                .also { reservedName -> rememberTreeChildName(parent, reservedName) }
        }
    }

    fun forgetTreeChildName(parent: DocumentFile, childName: String) {
        forgetTreeChildName(parent.uri.toString(), childName)
    }

    fun forgetTreeChildName(cacheKey: String, childName: String) {
        treeChildCache.forgetChildName(
            cacheKey = cacheKey,
            childName = childName,
            refreshedAtMs = System.currentTimeMillis()
        )
    }

    fun forgetDeletedReferences(deletedReferences: Set<String>) {
        if (deletedReferences.isEmpty()) return
        deletedReferences
            .filter { reference -> reference.startsWith("/") }
            .forEach { reference ->
                val file = File(reference)
                file.parentFile?.let { parent -> forgetFileChildName(parent, file.name) }
            }

        val deletedContentReferences = deletedReferences
            .filterNot { reference -> reference.startsWith("/") }
            .toSet()
        if (deletedContentReferences.isEmpty()) return

        treeChildCache.forgetChildrenByReference(deletedContentReferences, ::forgetTreeChildName)
    }

    fun clear() {
        treeChildCache.clear()
        fileChildNameCache.clear()
        childNameReservationLocks.clear()
    }

    fun toDocumentFile(context: Context, child: QueriedTreeChild): DocumentFile? {
        return runCatching {
            DocumentFile.fromTreeUri(context, child.documentUri)
                ?: DocumentFile.fromSingleUri(context, child.documentUri)
        }.getOrNull()
    }

    private fun cachedTreeChildrenNames(
        context: Context,
        parent: DocumentFile,
        maxCacheAgeMs: Long,
        allowReservedNames: Boolean = false
    ): Set<String> {
        val cacheKey = parent.uri.toString()
        val now = System.currentTimeMillis()
        treeChildCache.cachedNames(
            cacheKey = cacheKey,
            nowMs = now,
            maxCacheAgeMs = maxCacheAgeMs,
            allowReservedNames = allowReservedNames
        )?.let { return it }
        val refreshedChildren = queryTreeChildren(context, parent)
        return rememberTreeChildren(parent, refreshedChildren, now, isComplete = true)
    }

    companion object {
        fun mergeTreeChildNamesAfterRefresh(
            refreshedNames: Collection<String>,
            cachedNames: Collection<String>?,
            cachedNamesComplete: Boolean?,
            refreshedComplete: Boolean
        ): ManagedDownloadStorage.TreeChildNameRefresh {
            val refresh = TreeChildNameRefreshMerger.mergeAfterRefresh(
                refreshedNames = refreshedNames,
                cachedNames = cachedNames,
                cachedNamesComplete = cachedNamesComplete,
                refreshedComplete = refreshedComplete
            )
            return ManagedDownloadStorage.TreeChildNameRefresh(
                names = refresh.names,
                isComplete = refresh.isComplete
            )
        }
    }
}
