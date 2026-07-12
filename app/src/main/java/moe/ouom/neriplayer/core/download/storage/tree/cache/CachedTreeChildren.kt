package moe.ouom.neriplayer.core.download.storage.tree.cache

import java.util.concurrent.ConcurrentHashMap

internal class CachedTreeChildren(
    initialChildren: Collection<QueriedTreeChild>,
    initialRefreshedAtMs: Long,
    initialComplete: Boolean
) {
    val childrenByName: MutableMap<String, QueriedTreeChild> = ConcurrentHashMap()

    @Volatile
    var refreshedAtMs: Long = initialRefreshedAtMs

    @Volatile
    var isComplete: Boolean = initialComplete

    init {
        initialChildren.forEach { child ->
            childrenByName[child.name] = child
        }
    }
}
