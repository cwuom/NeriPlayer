package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS

internal class ManagedDownloadSnapshotCacheStore(
    private val scope: CoroutineScope,
    private val cacheKeyProvider: (Context) -> String
) {
    private data class SnapshotCache(
        val key: String,
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    )

    @Volatile
    private var snapshotCache: SnapshotCache? = null

    @Volatile
    private var snapshotPersistJob: Job? = null

    private val snapshotPersistenceLock = Any()

    fun currentKey(context: Context): String {
        return cacheKeyProvider(context.applicationContext)
    }

    fun peekSnapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        return snapshotCache?.snapshot
    }

    fun ensureReady(context: Context): Boolean {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        val currentCache = snapshotCache
        if (currentCache?.key == cacheKey) {
            return true
        }
        return restoreFromDisk(appContext, expectedKey = cacheKey) != null
    }

    fun cachedSnapshot(
        context: Context,
        restoreFromDisk: Boolean = true
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?.let { return it }
        if (!restoreFromDisk) {
            return null
        }
        return restoreFromDisk(appContext, expectedKey = cacheKey)
    }

    fun putSnapshot(
        context: Context,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        snapshotCache = SnapshotCache(key = cacheKey, snapshot = snapshot)
        schedulePersist(context.applicationContext, cacheKey)
    }

    fun restoreFromDisk(
        context: Context,
        expectedKey: String? = null
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        val restored = ManagedDownloadSnapshotDiskCache.restore(
            context = context.applicationContext,
            expectedKey = expectedKey
        ) ?: return null
        snapshotCache = SnapshotCache(key = restored.first, snapshot = restored.second)
        return restored.second
    }

    fun updateAfterMetadataWrite(
        context: Context,
        metadataEntry: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        val currentSnapshot = snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?: restoreFromDisk(appContext, expectedKey = cacheKey)
            ?: return true
        val updatedSnapshot = ManagedDownloadSnapshotIndex.applyMetadataWrite(
            snapshot = currentSnapshot,
            metadataEntry = metadataEntry,
            metadata = metadata
        )
        putSnapshot(appContext, cacheKey, updatedSnapshot)
        return true
    }

    fun updateAfterStoredEntryWrite(
        context: Context,
        storedEntry: ManagedDownloadStorage.StoredEntry,
        bucket: ManagedDownloadStorage.SnapshotEntryBucket
    ): Boolean {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        val currentSnapshot = snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?: restoreFromDisk(appContext, expectedKey = cacheKey)
            ?: return false
        val updatedSnapshot = ManagedDownloadSnapshotIndex.applyStoredEntryWrite(
            snapshot = currentSnapshot,
            storedEntry = storedEntry,
            bucket = bucket
        )
        putSnapshot(appContext, cacheKey, updatedSnapshot)
        return true
    }

    fun updateAfterDelete(
        context: Context,
        deletedReferences: Set<String>
    ): Boolean {
        if (deletedReferences.isEmpty()) {
            return true
        }
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        val currentSnapshot = snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?: restoreFromDisk(appContext, expectedKey = cacheKey)
            ?: return true
        val updatedSnapshot = ManagedDownloadSnapshotIndex.applyReferenceDeletes(
            snapshot = currentSnapshot,
            references = deletedReferences
        )
        putSnapshot(appContext, cacheKey, updatedSnapshot)
        return true
    }

    fun invalidate(context: Context? = null) {
        snapshotCache = null
        synchronized(snapshotPersistenceLock) {
            snapshotPersistJob?.cancel()
            snapshotPersistJob = null
        }
        val appContext = context?.applicationContext ?: return
        ManagedDownloadSnapshotDiskCache.delete(appContext)
    }

    private fun schedulePersist(
        context: Context,
        expectedKey: String
    ) {
        val appContext = context.applicationContext
        synchronized(snapshotPersistenceLock) {
            snapshotPersistJob?.cancel()
            snapshotPersistJob = scope.launch {
                delay(SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS)
                val currentCache = snapshotCache
                    ?.takeIf { it.key == expectedKey }
                    ?: return@launch
                ManagedDownloadSnapshotDiskCache.persist(
                    context = appContext,
                    cacheKey = currentCache.key,
                    snapshot = currentCache.snapshot
                )
            }
        }
    }
}
