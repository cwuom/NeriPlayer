package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_PERSIST_DEBOUNCE_MS
import moe.ouom.neriplayer.core.logging.NPLogger

internal interface ManagedDownloadSnapshotPersistenceStore {
    suspend fun restore(
        expectedKey: String? = null
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>?

    suspend fun persist(
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): Boolean

    suspend fun clear()
}

internal class ManagedDownloadSnapshotCacheStore(
    private val scope: CoroutineScope,
    private val cacheKeyProvider: (Context) -> String,
    private val persistenceStoreProvider: (Context) -> ManagedDownloadSnapshotPersistenceStore =
        { context -> ManagedDownloadSnapshotRoomStore(context) }
) {
    data class SnapshotRead(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        val revision: Long,
        val cacheKey: String
    )

    data class SnapshotPublication(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        val published: Boolean
    )

    data class SnapshotSelection(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        val accepted: Boolean
    )

    private data class SnapshotCache(
        val key: String,
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    )

    @Volatile
    private var snapshotCache: SnapshotCache? = null

    @Volatile
    private var snapshotPersistJob: Job? = null

    @Volatile
    private var snapshotClearJob: Job? = null

    @Volatile
    private var snapshotClearInFlight: Boolean = false

    @Volatile
    private var snapshotGeneration: Long = 0L

    private var snapshotRevision: Long = 0L

    private val snapshotMutationLock = Any()
    private val snapshotPersistenceLock = Any()
    // Room 和磁盘清理必须与延迟写入串行，避免旧快照在 invalidate 后复活
    private val snapshotPersistenceIoMutex = Mutex()

    fun currentKey(context: Context): String {
        return cacheKeyProvider(context.applicationContext)
    }

    fun peekSnapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        return synchronized(snapshotMutationLock) { snapshotCache?.snapshot }
    }

    fun snapshotRevision(): Long {
        return synchronized(snapshotMutationLock) { snapshotRevision }
    }

    fun captureSnapshot(
        context: Context,
        restorePersisted: Boolean = true
    ): SnapshotRead {
        val appContext = context.applicationContext
        cachedSnapshot(appContext, restorePersisted)
        return synchronized(snapshotMutationLock) {
            val cacheKey = currentKey(appContext)
            SnapshotRead(
                snapshot = snapshotCache?.takeIf { it.key == cacheKey }?.snapshot,
                revision = snapshotRevision,
                cacheKey = cacheKey
            )
        }
    }

    fun ensureReady(context: Context): Boolean {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        val currentCache = snapshotCache
        if (currentCache?.key == cacheKey) {
            return true
        }
        return restorePersisted(appContext, expectedKey = cacheKey) != null
    }

    fun cachedSnapshot(
        context: Context,
        restorePersisted: Boolean = true
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        snapshotCache
            ?.takeIf { it.key == cacheKey }
            ?.snapshot
            ?.let { return it }
        if (!restorePersisted) {
            return null
        }
        return restorePersisted(appContext, expectedKey = cacheKey)
    }

    fun putSnapshot(
        context: Context,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        synchronized(snapshotMutationLock) {
            putSnapshotLocked(context.applicationContext, cacheKey, snapshot)
        }
    }

    fun putSnapshotIfUnchanged(
        context: Context,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        expectedRevision: Long
    ): Boolean {
        return publishSnapshotIfUnchanged(
            context = context,
            cacheKey = cacheKey,
            snapshot = snapshot,
            expectedRevision = expectedRevision
        ).published
    }

    fun publishSnapshotIfUnchanged(
        context: Context,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        expectedRevision: Long
    ): SnapshotPublication {
        val appContext = context.applicationContext
        return synchronized(snapshotMutationLock) {
            val activeKey = currentKey(appContext)
            if (snapshotRevision != expectedRevision || activeKey != cacheKey) {
                return@synchronized SnapshotPublication(
                    snapshot = currentSnapshotOrPartialLocked(activeKey),
                    published = false
                )
            }
            putSnapshotLocked(appContext, cacheKey, snapshot)
            SnapshotPublication(snapshot = snapshot, published = true)
        }
    }

    fun selectSnapshotIfUnchanged(
        context: Context,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        expectedRevision: Long
    ): SnapshotSelection {
        val appContext = context.applicationContext
        return synchronized(snapshotMutationLock) {
            val activeKey = currentKey(appContext)
            if (snapshotRevision != expectedRevision || activeKey != cacheKey) {
                return@synchronized SnapshotSelection(
                    snapshot = currentSnapshotOrPartialLocked(activeKey),
                    accepted = false
                )
            }
            SnapshotSelection(snapshot = snapshot, accepted = true)
        }
    }

    fun currentSnapshotOrPartial(
        context: Context
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val appContext = context.applicationContext
        return synchronized(snapshotMutationLock) {
            currentSnapshotOrPartialLocked(currentKey(appContext))
        }
    }

    private fun currentSnapshotOrPartialLocked(
        activeKey: String
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return snapshotCache
            ?.takeIf { it.key == activeKey }
            ?.snapshot
            ?: partialSnapshot()
    }

    private fun putSnapshotLocked(
        context: Context,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        snapshotCache = SnapshotCache(key = cacheKey, snapshot = snapshot)
        snapshotRevision += 1L
        schedulePersist(context.applicationContext, cacheKey)
    }

    fun restorePersisted(
        context: Context,
        expectedKey: String? = null
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null && Looper.myLooper() == mainLooper) {
            return null
        }
        val appContext = context.applicationContext
        val generation = synchronized(snapshotPersistenceLock) {
            if (snapshotClearInFlight) {
                return null
            }
            snapshotGeneration
        }
        val roomRestored = runCatching {
            runBlocking {
                persistenceStoreProvider(appContext).restore(expectedKey)
            }
        }.getOrElse { error ->
            NPLogger.w(
                "ManagedDownloadStorage",
                "读取 Room 下载索引失败，回退磁盘快照: ${error.message}"
            )
            null
        }
        val restored = roomRestored
            ?: ManagedDownloadSnapshotDiskCache.restore(appContext, expectedKey)
            ?: return null
        return synchronized(snapshotMutationLock) {
            val restoreStillValid = synchronized(snapshotPersistenceLock) {
                generation == snapshotGeneration && !snapshotClearInFlight
            }
            if (!restoreStillValid) {
                return@synchronized null
            }
            val currentCache = snapshotCache
            if (currentCache?.key == restored.first) {
                currentCache.snapshot
            } else {
                snapshotCache = SnapshotCache(key = restored.first, snapshot = restored.second)
                snapshotRevision += 1L
                restored.second
            }
        }
    }

    fun updateAfterMetadataWrite(
        context: Context,
        metadataEntry: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        if (snapshotCache?.key != cacheKey) {
            restorePersisted(appContext, expectedKey = cacheKey)
        }
        return synchronized(snapshotMutationLock) {
            val currentSnapshot = snapshotCache
                ?.takeIf { it.key == cacheKey }
                ?.snapshot
                ?: partialSnapshot()
            val updatedSnapshot = ManagedDownloadSnapshotIndex.applyMetadataWrite(
                snapshot = currentSnapshot,
                metadataEntry = metadataEntry,
                metadata = metadata
            )
            putSnapshotLocked(appContext, cacheKey, updatedSnapshot)
            true
        }
    }

    fun updateAfterStoredEntryWrite(
        context: Context,
        storedEntry: ManagedDownloadStorage.StoredEntry,
        bucket: ManagedDownloadStorage.SnapshotEntryBucket
    ): Boolean {
        val appContext = context.applicationContext
        val cacheKey = currentKey(appContext)
        if (snapshotCache?.key != cacheKey) {
            restorePersisted(appContext, expectedKey = cacheKey)
        }
        return synchronized(snapshotMutationLock) {
            val currentSnapshot = snapshotCache
                ?.takeIf { it.key == cacheKey }
                ?.snapshot
                ?: partialSnapshot()
            val updatedSnapshot = ManagedDownloadSnapshotIndex.applyStoredEntryWrite(
                snapshot = currentSnapshot,
                storedEntry = storedEntry,
                bucket = bucket
            )
            putSnapshotLocked(appContext, cacheKey, updatedSnapshot)
            true
        }
    }

    /**
     * core 提交发生在首次完整目录扫描之前时，先保留已知条目供播放和收尾使用
     * 目录完整性标记保持为 false，避免把局部快照误当成全量扫描结果
     */
    private fun partialSnapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = emptyList(),
            metadataEntries = emptyList(),
            metadataByAudioName = emptyMap(),
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            rootEntriesComplete = false,
            sidecarEntriesComplete = false
        )
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
        if (snapshotCache?.key != cacheKey) {
            restorePersisted(appContext, expectedKey = cacheKey)
        }
        var clearJobToStart: Job? = null
        val updated = synchronized(snapshotMutationLock) {
            val currentSnapshot = snapshotCache
                ?.takeIf { it.key == cacheKey }
                ?.snapshot
                ?: run {
                    // 没有可更新的内存快照时必须清掉持久化副本，否则后续恢复会复活已删条目
                    clearJobToStart = invalidateLocked(appContext)
                    return@synchronized true
                }
            val updatedSnapshot = ManagedDownloadSnapshotIndex.applyReferenceDeletes(
                snapshot = currentSnapshot,
                references = deletedReferences
            )
            putSnapshotLocked(appContext, cacheKey, updatedSnapshot)
            true
        }
        startSnapshotClear(clearJobToStart, appContext)
        return updated
    }

    fun invalidate(context: Context? = null) {
        val appContext = context?.applicationContext
        val clearJobToStart = synchronized(snapshotMutationLock) {
            invalidateLocked(appContext)
        }
        startSnapshotClear(clearJobToStart, appContext)
    }

    private fun invalidateLocked(appContext: Context?): Job? {
        snapshotCache = null
        snapshotRevision += 1L
        var clearJobToStart: Job? = null
        synchronized(snapshotPersistenceLock) {
            snapshotGeneration += 1L
            snapshotPersistJob?.cancel()
            snapshotPersistJob = null
            if (appContext != null) {
                snapshotClearInFlight = true
                if (snapshotClearJob?.isCompleted != false) {
                    val clearJob = scope.launch(start = CoroutineStart.LAZY) {
                        snapshotPersistenceIoMutex.withLock {
                            persistenceStoreProvider(appContext).clear()
                        }
                    }
                    snapshotClearJob = clearJob
                    clearJobToStart = clearJob
                }
            } else if (snapshotClearJob?.isCompleted != false) {
                snapshotClearJob = null
                snapshotClearInFlight = false
            }
        }
        return clearJobToStart
    }

    private fun startSnapshotClear(clearJob: Job?, appContext: Context?) {
        val job = clearJob ?: return
        job.invokeOnCompletion {
            var shouldReschedulePersist = false
            synchronized(snapshotPersistenceLock) {
                if (snapshotClearJob === job) {
                    snapshotClearJob = null
                    snapshotClearInFlight = false
                    shouldReschedulePersist = true
                }
            }
            if (shouldReschedulePersist && appContext != null) {
                val currentCache = synchronized(snapshotMutationLock) { snapshotCache }
                currentCache?.let { schedulePersist(appContext, it.key) }
            }
        }
        job.start()
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
                val clearJob = synchronized(snapshotPersistenceLock) {
                    snapshotClearJob?.takeUnless(Job::isCompleted)
                }
                clearJob?.join()
                snapshotPersistenceIoMutex.withLock {
                    val currentCache = synchronized(snapshotMutationLock) {
                        synchronized(snapshotPersistenceLock) {
                            snapshotCache
                                ?.takeIf { it.key == expectedKey }
                                ?.takeUnless { snapshotClearInFlight }
                        }
                    } ?: return@withLock
                    persistenceStoreProvider(appContext).persist(
                        cacheKey = currentCache.key,
                        snapshot = currentCache.snapshot
                    )
                }
            }
        }
    }
}
