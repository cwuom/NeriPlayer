package moe.ouom.neriplayer.core.download.index

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ManagedLibraryFastIndexShardReadResult {
    data object Missing : ManagedLibraryFastIndexShardReadResult

    data class Found(
        val payload: String
    ) : ManagedLibraryFastIndexShardReadResult

    data class Unavailable(
        val error: Throwable
    ) : ManagedLibraryFastIndexShardReadResult
}

internal sealed interface ManagedLibraryFastIndexShardWriteResult {
    data object Written : ManagedLibraryFastIndexShardWriteResult

    data class Unavailable(
        val error: Throwable
    ) : ManagedLibraryFastIndexShardWriteResult
}

internal interface ManagedLibraryFastIndexShardStorage {
    suspend fun readShard(
        rootIdentity: String,
        shard: String
    ): ManagedLibraryFastIndexShardReadResult

    suspend fun writeShard(
        rootIdentity: String,
        shard: String,
        payload: String
    ): ManagedLibraryFastIndexShardWriteResult
}

internal sealed interface ManagedLibraryFastIndexMutationResult {
    val shard: String

    data class Updated(
        override val shard: String,
        val entryCount: Int
    ) : ManagedLibraryFastIndexMutationResult

    data class Unchanged(
        override val shard: String
    ) : ManagedLibraryFastIndexMutationResult

    data class EntryMissing(
        override val shard: String
    ) : ManagedLibraryFastIndexMutationResult

    data class Failed(
        override val shard: String,
        val error: Throwable
    ) : ManagedLibraryFastIndexMutationResult
}

internal class ManagedLibraryFastIndexMutator(
    private val generatedAtMs: () -> Long = System::currentTimeMillis,
    private val mutationLocks: ManagedLibraryFastIndexMutationLocks =
        ManagedLibraryFastIndexMutationLocks()
) {
    /**
     * 同 stableKey 的记录会被完整替换，调用方必须提供不丢字段的完整 entry
     */
    suspend fun upsertCompleteEntry(
        rootIdentity: String,
        libraryId: String,
        entry: ManagedLibraryIndexEntry,
        storage: ManagedLibraryFastIndexShardStorage
    ): ManagedLibraryFastIndexMutationResult {
        if (entry.stableKey.isBlank()) {
            return ManagedLibraryFastIndexMutationResult.Failed(
                shard = "",
                error = IllegalArgumentException("stable key is blank")
            )
        }
        val shard = ManagedLibraryFastIndex.shardFor(entry.stableKey)
        return mutate(
            rootIdentity = rootIdentity,
            libraryId = libraryId,
            shard = shard,
            storage = storage
        ) { existingEntries ->
            val nextEntries = existingEntries
                .filterNot { existing -> existing.stableKey == entry.stableKey } + entry
            MutationPlan.Write(nextEntries)
        }
    }

    suspend fun updateExistingEntry(
        rootIdentity: String,
        libraryId: String,
        stableKey: String,
        storage: ManagedLibraryFastIndexShardStorage,
        transform: (ManagedLibraryIndexEntry) -> ManagedLibraryIndexEntry
    ): ManagedLibraryFastIndexMutationResult {
        if (stableKey.isBlank()) {
            return ManagedLibraryFastIndexMutationResult.Failed(
                shard = "",
                error = IllegalArgumentException("stable key is blank")
            )
        }
        val shard = ManagedLibraryFastIndex.shardFor(stableKey)
        return mutate(
            rootIdentity = rootIdentity,
            libraryId = libraryId,
            shard = shard,
            storage = storage
        ) { existingEntries ->
            val existing = existingEntries.firstOrNull { entry ->
                entry.stableKey == stableKey
            } ?: return@mutate MutationPlan.EntryMissing
            val updated = transform(existing)
            if (
                updated.stableKey != stableKey ||
                    ManagedLibraryFastIndex.shardFor(updated.stableKey) != shard
            ) {
                return@mutate MutationPlan.Failed(
                    IllegalArgumentException("fast index update cannot change stable key")
                )
            }
            MutationPlan.Write(
                existingEntries.map { entry ->
                    if (entry.stableKey == stableKey) updated else entry
                }
            )
        }
    }

    suspend fun remove(
        rootIdentity: String,
        libraryId: String,
        stableKey: String,
        storage: ManagedLibraryFastIndexShardStorage
    ): ManagedLibraryFastIndexMutationResult {
        if (stableKey.isBlank()) {
            return ManagedLibraryFastIndexMutationResult.Failed(
                shard = "",
                error = IllegalArgumentException("stable key is blank")
            )
        }
        val shard = ManagedLibraryFastIndex.shardFor(stableKey)
        return mutate(
            rootIdentity = rootIdentity,
            libraryId = libraryId,
            shard = shard,
            storage = storage
        ) { existingEntries ->
            val nextEntries = existingEntries.filterNot { entry ->
                entry.stableKey == stableKey
            }
            if (nextEntries.size == existingEntries.size) {
                MutationPlan.Unchanged
            } else {
                MutationPlan.Write(nextEntries)
            }
        }
    }

    private sealed interface MutationPlan {
        data class Write(
            val entries: List<ManagedLibraryIndexEntry>
        ) : MutationPlan

        data object Unchanged : MutationPlan
        data object EntryMissing : MutationPlan

        data class Failed(
            val error: Throwable
        ) : MutationPlan
    }

    private suspend fun mutate(
        rootIdentity: String,
        libraryId: String,
        shard: String,
        storage: ManagedLibraryFastIndexShardStorage,
        transform: (List<ManagedLibraryIndexEntry>) -> MutationPlan
    ): ManagedLibraryFastIndexMutationResult {
        validateIdentity(rootIdentity, libraryId, shard)?.let { error ->
            return ManagedLibraryFastIndexMutationResult.Failed(shard, error)
        }
        return mutationLocks.withLock(rootIdentity, shard) {
            val existingEntries = when (
                val readResult = storage.readShard(rootIdentity, shard)
            ) {
                ManagedLibraryFastIndexShardReadResult.Missing -> emptyList()
                is ManagedLibraryFastIndexShardReadResult.Unavailable -> {
                    return@withLock ManagedLibraryFastIndexMutationResult.Failed(
                        shard = shard,
                        error = readResult.error
                    )
                }
                is ManagedLibraryFastIndexShardReadResult.Found -> {
                    val decoded = ManagedLibraryFastIndex.decode(readResult.payload)
                        ?: return@withLock ManagedLibraryFastIndexMutationResult.Failed(
                            shard = shard,
                            error = IllegalStateException("fast index shard is invalid: $shard")
                        )
                    if (decoded.libraryId != libraryId || decoded.shard != shard) {
                        return@withLock ManagedLibraryFastIndexMutationResult.Failed(
                            shard = shard,
                            error = IllegalStateException(
                                "fast index shard identity mismatch: $shard"
                            )
                        )
                    }
                    if (decoded.entries.distinctBy(ManagedLibraryIndexEntry::stableKey).size !=
                        decoded.entries.size
                    ) {
                        return@withLock ManagedLibraryFastIndexMutationResult.Failed(
                            shard = shard,
                            error = IllegalStateException(
                                "fast index shard contains duplicate stable keys: $shard"
                            )
                        )
                    }
                    decoded.entries
                }
            }
            val nextEntries = when (val plan = transform(existingEntries)) {
                is MutationPlan.Write -> {
                    plan.entries.sortedBy(ManagedLibraryIndexEntry::stableKey)
                }
                MutationPlan.Unchanged -> {
                    return@withLock ManagedLibraryFastIndexMutationResult.Unchanged(shard)
                }
                MutationPlan.EntryMissing -> {
                    return@withLock ManagedLibraryFastIndexMutationResult.EntryMissing(shard)
                }
                is MutationPlan.Failed -> {
                    return@withLock ManagedLibraryFastIndexMutationResult.Failed(
                        shard = shard,
                        error = plan.error
                    )
                }
            }
            if (nextEntries == existingEntries.sortedBy(ManagedLibraryIndexEntry::stableKey)) {
                return@withLock ManagedLibraryFastIndexMutationResult.Unchanged(shard)
            }
            val payload = ManagedLibraryFastIndex.encode(
                libraryId = libraryId,
                shard = shard,
                entries = nextEntries,
                generatedAtMs = generatedAtMs()
            )
            when (val writeResult = storage.writeShard(rootIdentity, shard, payload)) {
                ManagedLibraryFastIndexShardWriteResult.Written -> {
                    ManagedLibraryFastIndexMutationResult.Updated(
                        shard = shard,
                        entryCount = nextEntries.size
                    )
                }
                is ManagedLibraryFastIndexShardWriteResult.Unavailable -> {
                    ManagedLibraryFastIndexMutationResult.Failed(
                        shard = shard,
                        error = writeResult.error
                    )
                }
            }
        }
    }

    private fun validateIdentity(
        rootIdentity: String,
        libraryId: String,
        shard: String
    ): Throwable? {
        return when {
            rootIdentity.isBlank() -> IllegalArgumentException("root identity is blank")
            libraryId.isBlank() -> IllegalArgumentException("library id is blank")
            shard.isBlank() -> IllegalArgumentException("shard is blank")
            else -> null
        }
    }
}

internal class ManagedLibraryFastIndexMutationLocks {
    private data class LockKey(
        val rootIdentity: String,
        val shard: String
    )

    private class LockLease {
        val mutex = Mutex()
        var referenceCount: Int = 0
    }

    private val monitor = Any()
    private val leases = mutableMapOf<LockKey, LockLease>()

    suspend fun <T> withLock(
        rootIdentity: String,
        shard: String,
        block: suspend () -> T
    ): T {
        val key = LockKey(rootIdentity, shard)
        val lease = synchronized(monitor) {
            leases.getOrPut(key, ::LockLease).also { current ->
                current.referenceCount += 1
            }
        }
        return try {
            lease.mutex.withLock { block() }
        } finally {
            synchronized(monitor) {
                lease.referenceCount -= 1
                if (lease.referenceCount == 0) {
                    leases.remove(key, lease)
                }
            }
        }
    }

    internal fun activeLockCount(): Int = synchronized(monitor) { leases.size }
}
