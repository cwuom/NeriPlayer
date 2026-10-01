package moe.ouom.neriplayer.core.download.storage.tree.cache

import java.util.concurrent.ConcurrentHashMap

internal class CachedTreeChildren(
    initialChildren: Collection<QueriedTreeChild>,
    initialRefreshedAtMs: Long,
    initialComplete: Boolean
) {
    val childrenByName: MutableMap<String, QueriedTreeChild> = ConcurrentHashMap()
    val childrenByReference: MutableMap<String, QueriedTreeChild> = ConcurrentHashMap()

    @Volatile
    var refreshedAtMs: Long = initialRefreshedAtMs

    @Volatile
    var isComplete: Boolean = initialComplete

    init {
        initialChildren.forEach { child ->
            childrenByReference[child.documentUri.toString()]?.let { previous ->
                childrenByName.remove(previous.name)
            }
            childrenByName[child.name]?.let { previous ->
                childrenByReference.remove(previous.documentUri.toString())
            }
            childrenByName[child.name] = child
            childrenByReference[child.documentUri.toString()] = child
        }
    }
}
