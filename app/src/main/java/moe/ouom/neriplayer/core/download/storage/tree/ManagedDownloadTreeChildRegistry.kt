package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
    private val consecutiveEmptyRefreshes = ConcurrentHashMap<String, Int>()

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
        return refreshTreeChildrenWithStatus(context, parent).children
    }

    fun refreshTreeChildrenWithStatus(
        context: Context,
        parent: DocumentFile
    ): TreeChildrenRefresh {
        val refreshedAtMs = System.currentTimeMillis()
        val result = stabilizeEmptyRefresh(
            parent = parent,
            queried = ManagedDownloadTreeChildQuery.queryChildrenWithStatus(
                context = context,
                parent = parent,
                onQueryFailure = onTreeQueryFailed
            )
        )
        rememberTreeChildren(
            parent = parent,
            children = result.children,
            refreshedAtMs = refreshedAtMs,
            isComplete = result.isComplete
        )
        return TreeChildrenRefresh(
            children = result.children,
            isComplete = result.isComplete
        )
    }

    fun treeChildrenForWrite(
        context: Context,
        parent: DocumentFile
    ): TreeChildrenRefresh {
        return refreshTreeChildrenWithStatus(context, parent)
    }

    data class TreeChildrenRefresh(
        val children: List<QueriedTreeChild>,
        val isComplete: Boolean
    )

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

    fun cachedTreeChildForWrite(
        context: Context,
        parent: DocumentFile,
        childName: String
    ): QueriedTreeChild? {
        // a failed provider query is incomplete, but its fallback entries are still useful
        peekTreeChildrenIncludingIncomplete(parent)
            ?.firstOrNull { child -> child.name == childName }
            ?.let { return it }
        return cachedTreeChild(context, parent, childName)
    }

    fun peekTreeChildren(parent: DocumentFile): Collection<QueriedTreeChild>? {
        return treeChildCache.peekChildren(parent.uri.toString())
    }

    fun peekTreeChildrenIncludingIncomplete(parent: DocumentFile): Collection<QueriedTreeChild>? {
        return treeChildCache.peekAllChildren(parent.uri.toString())
    }

    fun peekTreeChild(parent: DocumentFile, childName: String): QueriedTreeChild? {
        return peekTreeChildren(parent)?.firstOrNull { child -> child.name == childName }
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
        consecutiveEmptyRefreshes.clear()
    }

    fun toDocumentFile(
        context: Context,
        parent: DocumentFile,
        child: QueriedTreeChild
    ): DocumentFile? {
        val singleDocument = DocumentFile.fromSingleUri(context, child.documentUri) ?: return null
        return try {
            toTreeDocumentFile(
                context = context,
                parent = parent,
                child = singleDocument
            ) ?: resolveTreeChildDocumentFile(
                child = child,
                treeDocumentFile = {
                    parent.findFile(child.name)
                },
                singleDocumentFile = { DocumentFile.fromSingleUri(context, child.documentUri) }
            )
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun toTreeDocumentFile(
        context: Context,
        parent: DocumentFile,
        child: DocumentFile
    ): DocumentFile? {
        return try {
            val childDocumentId = try {
                DocumentsContract.getDocumentId(child.uri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return null
            val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                parent.uri,
                childDocumentId
            )
            val directTree = DocumentFile.fromTreeUri(context, treeDocumentUri)
                ?.takeIf { wrapper ->
                    documentIdOrNull(wrapper.uri) == childDocumentId
                }
            directTree
                ?: child.name
                    ?.takeIf(String::isNotBlank)
                    ?.let(parent::findFile)
                    ?.takeIf { wrapper -> documentIdOrNull(wrapper.uri) == childDocumentId }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun documentIdOrNull(uri: Uri): String? {
        return try {
            DocumentsContract.getDocumentId(uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun findCanonicalExternalStorageChild(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        isDirectory: Boolean
    ): DocumentFile? {
        if (parent.uri.authority != "com.android.externalstorage.documents") return null
        val parentDocumentId = try {
            DocumentsContract.getDocumentId(parent.uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(parent.uri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val childDocumentId = ManagedDownloadTreeNaming.externalStorageChildDocumentId(
            parentDocumentId = parentDocumentId,
            displayName = displayName
        ) ?: return null
        val childUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, childDocumentId)
        val child = DocumentFile.fromSingleUri(context, childUri) ?: return null
        return try {
            child.takeIf { it.exists() && it.isDirectory == isDirectory }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
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
        val refreshed = stabilizeEmptyRefresh(
            parent = parent,
            queried = ManagedDownloadTreeChildQuery.queryChildrenWithStatus(
                context = context,
                parent = parent,
                onQueryFailure = onTreeQueryFailed
            )
        )
        return rememberTreeChildren(
            parent = parent,
            children = refreshed.children,
            refreshedAtMs = now,
            isComplete = refreshed.isComplete
        )
    }

    private fun stabilizeEmptyRefresh(
        parent: DocumentFile,
        queried: ManagedDownloadTreeChildQuery.QueryResult
    ): ManagedDownloadTreeChildQuery.QueryResult {
        val cacheKey = parent.uri.toString()
        val previous = treeChildCache.peekAllChildren(cacheKey).orEmpty().toList()
        if (!queried.isComplete || queried.children.isNotEmpty() || previous.isEmpty()) {
            consecutiveEmptyRefreshes.remove(cacheKey)
            return queried
        }
        val count = consecutiveEmptyRefreshes.merge(cacheKey, 1) { current, _ -> current + 1 }
            ?: 1
        if (count < EMPTY_REFRESH_CONFIRMATION_COUNT) {
            return ManagedDownloadTreeChildQuery.QueryResult(
                children = previous,
                isComplete = false
            )
        }
        consecutiveEmptyRefreshes.remove(cacheKey)
        return queried
    }

    companion object {
        private const val EMPTY_REFRESH_CONFIRMATION_COUNT = 2

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

internal fun resolveTreeChildDocumentFile(
    child: QueriedTreeChild,
    treeDocumentFile: () -> DocumentFile?,
    singleDocumentFile: () -> DocumentFile?
): DocumentFile? {
    return if (child.isDirectory) {
        // directory wrappers need tree permissions because sidecar writes create children
        treeDocumentFile()
    } else {
        // file reads work with single-document wrappers, including opaque document IDs
        singleDocumentFile() ?: treeDocumentFile()
    }
}
