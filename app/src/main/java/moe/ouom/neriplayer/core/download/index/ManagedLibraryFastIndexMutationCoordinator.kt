package moe.ouom.neriplayer.core.download.index

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class ManagedLibraryFastIndexRebuildToken(
    val rootIdentity: String,
    val generation: Long
)

internal sealed interface ManagedLibraryFastIndexRebuildResult<out T> {
    data class Applied<T>(val value: T) : ManagedLibraryFastIndexRebuildResult<T>
    data object Stale : ManagedLibraryFastIndexRebuildResult<Nothing>
}

internal class ManagedLibraryFastIndexMutationCoordinator(
    private val rootLocks: ManagedLibraryFastIndexMutationLocks =
        ManagedLibraryFastIndexMutationLocks()
) {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun capture(rootIdentity: String): ManagedLibraryFastIndexRebuildToken {
        require(rootIdentity.isNotBlank()) { "root identity is blank" }
        return ManagedLibraryFastIndexRebuildToken(
            rootIdentity = rootIdentity,
            generation = generationFor(rootIdentity).get()
        )
    }

    suspend fun <T> mutate(
        rootIdentity: String,
        block: suspend () -> T
    ): T {
        require(rootIdentity.isNotBlank()) { "root identity is blank" }
        return rootLocks.withLock(rootIdentity, ROOT_MUTATION_LOCK_SHARD) {
            try {
                block()
            } finally {
                generationFor(rootIdentity).incrementAndGet()
            }
        }
    }

    suspend fun <T> rebuild(
        token: ManagedLibraryFastIndexRebuildToken,
        currentRootIdentity: String,
        block: suspend () -> T
    ): ManagedLibraryFastIndexRebuildResult<T> {
        if (
            currentRootIdentity.isBlank() ||
                currentRootIdentity != token.rootIdentity
        ) {
            return ManagedLibraryFastIndexRebuildResult.Stale
        }
        return rootLocks.withLock(currentRootIdentity, ROOT_MUTATION_LOCK_SHARD) {
            val generation = generationFor(currentRootIdentity)
            if (generation.get() != token.generation) {
                return@withLock ManagedLibraryFastIndexRebuildResult.Stale
            }
            try {
                ManagedLibraryFastIndexRebuildResult.Applied(block())
            } finally {
                generation.incrementAndGet()
            }
        }
    }

    private fun generationFor(rootIdentity: String): AtomicLong {
        return generations.computeIfAbsent(rootIdentity) { AtomicLong(0L) }
    }

    private companion object {
        const val ROOT_MUTATION_LOCK_SHARD = "__root_mutation__"
    }
}
