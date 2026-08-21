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

    fun peekChildren(cacheKey: String): Collection<QueriedTreeChild>? {
        return childrenByParent[cacheKey]
            ?.takeIf { it.isComplete }
            ?.childrenByName
            ?.values
    }

    fun peekAllChildren(cacheKey: String): Collection<QueriedTreeChild>? {
        return childrenByParent[cacheKey]?.childrenByName?.values
    }

    fun rememberChildren(
        cacheKey: String,
        children: Collection<QueriedTreeChild>,
        refreshedAtMs: Long,
        isComplete: Boolean
    ): Set<String> {
        val cachedNames = namesByParent[cacheKey]
        val refreshedNames = TreeChildNameRefreshMerger.mergeAfterRefresh(
            refreshedNames = children.map(QueriedTreeChild::name),
            cachedNames = cachedNames?.names,
            cachedNamesComplete = cachedNames?.isComplete,
            refreshedComplete = isComplete
        )
        val previousChildren = childrenByParent[cacheKey]
            ?.childrenByName
            ?.values
            .orEmpty()
        val effectiveChildren = if (isComplete) {
            children.toList()
        } else {
            mergeChildren(
                previous = previousChildren,
                refreshed = children
            )
        }
        val effectiveNames = if (isComplete) {
            refreshedNames.names
        } else {
            (refreshedNames.names + effectiveChildren.map(QueriedTreeChild::name))
                .toCollection(linkedSetOf())
        }
        childrenByParent[cacheKey] = CachedTreeChildren(
            initialChildren = effectiveChildren,
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = isComplete
        )
        namesByParent[cacheKey] = CachedChildNames(
            initialNames = effectiveNames,
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = refreshedNames.isComplete
        )
        return namesByParent[cacheKey]?.names ?: effectiveNames
    }

    private fun mergeChildren(
        previous: Collection<QueriedTreeChild>,
        refreshed: Collection<QueriedTreeChild>
    ): List<QueriedTreeChild> {
        val byUri = LinkedHashMap<String, QueriedTreeChild>()
        previous.forEach { child -> byUri[child.documentUri.toString()] = child }
        refreshed.forEach { child -> byUri[child.documentUri.toString()] = child }
        val byName = LinkedHashMap<String, QueriedTreeChild>()
        byUri.values.forEach { child -> byName[child.name] = child }
        return byName.values.toList()
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
            val staleNames = cached.childrenByName.values
                .filter { existing ->
                    existing.documentUri.toString() == child.documentUri.toString() &&
                        existing.name != child.name
                }
                .map(QueriedTreeChild::name)
            staleNames.forEach(cached.childrenByName::remove)
            namesByParent[cacheKey]?.let { names ->
                staleNames.forEach(names.names::remove)
                names.names += child.name
            }
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
