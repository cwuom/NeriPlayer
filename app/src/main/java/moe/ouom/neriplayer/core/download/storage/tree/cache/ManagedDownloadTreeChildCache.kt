package moe.ouom.neriplayer.core.download.storage.tree.cache

import java.util.concurrent.ConcurrentHashMap

internal class ManagedDownloadTreeChildCache {
    private val namesByParent = ConcurrentHashMap<String, CachedChildNames>()
    private val childrenByParent = ConcurrentHashMap<String, CachedTreeChildren>()

    fun cachedNames(
        cacheKey: String,
        nowMs: Long,
        maxCacheAgeMs: Long,
        allowReservedNames: Boolean
    ): Set<String>? {
        if (maxCacheAgeMs <= 0L) return null
        val cachedNames = namesByParent[cacheKey] ?: return null
        val cachedEntries = childrenByParent[cacheKey]
        val namesFresh = nowMs - cachedNames.refreshedAtMs <= maxCacheAgeMs
        val entriesFresh = cachedEntries != null &&
            cachedEntries.isComplete &&
            nowMs - cachedEntries.refreshedAtMs <= maxCacheAgeMs
        val canUseNames = namesFresh &&
            (
                cachedNames.isComplete ||
                    (allowReservedNames && entriesFresh)
                )
        return cachedNames.names.takeIf { canUseNames }
    }

    fun cachedChildren(
        cacheKey: String,
        nowMs: Long,
        maxCacheAgeMs: Long
    ): Collection<QueriedTreeChild>? {
        if (maxCacheAgeMs <= 0L) return null
        return childrenByParent[cacheKey]
            ?.takeIf { it.isComplete && nowMs - it.refreshedAtMs <= maxCacheAgeMs }
            ?.childrenByName
            ?.values
    }

    fun rememberChildren(
        cacheKey: String,
        children: Collection<QueriedTreeChild>,
        refreshedAtMs: Long,
        isComplete: Boolean
    ): Set<String> {
        val refreshedNames = mergeNamesAfterRefresh(
            refreshedNames = children.map(QueriedTreeChild::name),
            cachedNames = namesByParent[cacheKey]?.names,
            cachedNamesComplete = namesByParent[cacheKey]?.isComplete,
            refreshedComplete = isComplete
        )
        childrenByParent[cacheKey] = CachedTreeChildren(
            initialChildren = children,
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = isComplete
        )
        namesByParent[cacheKey] = CachedChildNames(
            initialNames = refreshedNames.names,
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = refreshedNames.isComplete
        )
        return namesByParent[cacheKey]?.names ?: refreshedNames.names
    }

    fun rememberChildName(
        cacheKey: String,
        childName: String,
        refreshedAtMs: Long,
        isReservation: Boolean
    ) {
        namesByParent[cacheKey]?.let { cached ->
            cached.names += childName
            cached.refreshedAtMs = refreshedAtMs
            if (isReservation) {
                cached.isComplete = false
            }
            return
        }
        namesByParent[cacheKey] = CachedChildNames(
            initialNames = listOf(childName),
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = false
        )
    }

    fun rememberChild(
        cacheKey: String,
        child: QueriedTreeChild,
        refreshedAtMs: Long
    ) {
        rememberChildName(
            cacheKey = cacheKey,
            childName = child.name,
            refreshedAtMs = refreshedAtMs,
            isReservation = false
        )
        childrenByParent[cacheKey]?.let { cached ->
            cached.childrenByName[child.name] = child
            cached.refreshedAtMs = refreshedAtMs
            return
        }
        childrenByParent[cacheKey] = CachedTreeChildren(
            initialChildren = listOf(child),
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = false
        )
    }

    fun forgetChildName(cacheKey: String, childName: String, refreshedAtMs: Long) {
        namesByParent[cacheKey]?.let { cached ->
            cached.names -= childName
            cached.refreshedAtMs = refreshedAtMs
        }
        childrenByParent[cacheKey]?.let { entries ->
            entries.childrenByName -= childName
            entries.refreshedAtMs = refreshedAtMs
        }
    }

    fun forgetChildrenByReference(
        references: Set<String>,
        onForgotChildName: (cacheKey: String, childName: String) -> Unit
    ) {
        if (references.isEmpty()) return
        childrenByParent.forEach { (cacheKey, cachedChildren) ->
            cachedChildren.childrenByName.values
                .filter { child -> child.documentUri.toString() in references }
                .map(QueriedTreeChild::name)
                .forEach { childName -> onForgotChildName(cacheKey, childName) }
        }
    }

    fun clear() {
        namesByParent.clear()
        childrenByParent.clear()
    }

    companion object {
        fun mergeNamesAfterRefresh(
            refreshedNames: Collection<String>,
            cachedNames: Collection<String>?,
            cachedNamesComplete: Boolean?,
            refreshedComplete: Boolean
        ): TreeChildNameRefresh {
            return TreeChildNameRefreshMerger.mergeAfterRefresh(
                refreshedNames = refreshedNames,
                cachedNames = cachedNames,
                cachedNamesComplete = cachedNamesComplete,
                refreshedComplete = refreshedComplete
            )
        }
    }
}
