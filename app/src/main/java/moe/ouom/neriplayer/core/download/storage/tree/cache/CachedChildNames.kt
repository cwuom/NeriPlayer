package moe.ouom.neriplayer.core.download.storage.tree.cache

import java.util.concurrent.ConcurrentHashMap

internal class CachedChildNames(
    initialNames: Collection<String>,
    initialRefreshedAtMs: Long,
    initialComplete: Boolean
) {
    val names: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(initialNames)
    }

    @Volatile
    var refreshedAtMs: Long = initialRefreshedAtMs

    @Volatile
    var isComplete: Boolean = initialComplete
}
