package moe.ouom.neriplayer.core.download.storage.tree.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class ManagedDownloadTreeChildCache {
    private val reservationLeaseMs = 10 * 60 * 1000L
    private val namesByParent = ConcurrentHashMap<String, CachedChildNames>()
    private val childrenByParent = ConcurrentHashMap<String, CachedTreeChildren>()
    private val reservationsByParent = mutableMapOf<String, MutableMap<String, Long>>()
    private val oversizedParents = ConcurrentHashMap.newKeySet<String>()
    private val cachedChildrenCount = AtomicInteger(0)
    private val mutationLock = Any()

    fun cachedNames(
        cacheKey: String,
        nowMs: Long,
        maxCacheAgeMs: Long,
        allowReservedNames: Boolean
    ): Set<String>? {
        if (maxCacheAgeMs <= 0L) return null
        if (cacheKey in oversizedParents) return null
        synchronized(mutationLock) {
            pruneExpiredReservationsLocked(cacheKey, nowMs)
        }
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
        if (cacheKey in oversizedParents) return null
        return childrenByParent[cacheKey]
            ?.takeIf { it.isComplete && nowMs - it.refreshedAtMs <= maxCacheAgeMs }
            ?.childrenByName
            ?.values
    }

    fun peekChildren(cacheKey: String): Collection<QueriedTreeChild>? {
        if (cacheKey in oversizedParents) return null
        return childrenByParent[cacheKey]
            ?.takeIf { it.isComplete }
            ?.childrenByName
            ?.values
    }

    fun peekAllChildren(cacheKey: String): Collection<QueriedTreeChild>? {
        if (cacheKey in oversizedParents) return null
        return childrenByParent[cacheKey]?.childrenByName?.values
    }

    fun rememberChildren(
        cacheKey: String,
        children: Collection<QueriedTreeChild>,
        refreshedAtMs: Long,
        isComplete: Boolean
    ): Set<String> {
        synchronized(mutationLock) {
            val refreshedNameSet = children.mapTo(hashSetOf(), QueriedTreeChild::name)
            if (isComplete) {
                discardMaterializedReservationsLocked(cacheKey, refreshedNameSet)
            }
            pruneExpiredReservationsLocked(cacheKey, refreshedAtMs)
            val cachedNames = namesByParent[cacheKey]
            val liveReservations = reservationsByParent[cacheKey].orEmpty().keys
            val cachedNamesForMerge = if (isComplete) {
                liveReservations
            } else {
                cachedNames?.names
            }
            val cachedNamesCompleteForMerge = if (isComplete) {
                liveReservations.isEmpty()
            } else {
                cachedNames?.isComplete
            }
            val refreshedNames = TreeChildNameRefreshMerger.mergeAfterRefresh(
                refreshedNames = children.map(QueriedTreeChild::name),
                cachedNames = cachedNamesForMerge,
                cachedNamesComplete = cachedNamesCompleteForMerge,
                refreshedComplete = isComplete
            )
            val previousChildren = if (cacheKey in oversizedParents) {
                emptyList()
            } else {
                childrenByParent[cacheKey]
                    ?.childrenByName
                    ?.values
                    .orEmpty()
            }
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
            val previousSize = childrenByParent[cacheKey]
                ?.childrenByName
                ?.size
                ?: 0
            val totalAfterRefresh = cachedChildrenCount.get() - previousSize + effectiveChildren.size
            if (!canCache(
                    childCount = effectiveChildren.size,
                    nameCount = effectiveNames.size,
                    totalChildren = totalAfterRefresh
                )
            ) {
                if (!isComplete) {
                    // 不完整查询不能覆盖最后一份可用快照，否则 Provider 短暂
                    // 返回空集时会让后续删除流程误判目录已清空
                    childrenByParent[cacheKey]?.let { cached ->
                        cached.isComplete = false
                        cached.refreshedAtMs = refreshedAtMs
                    }
                    namesByParent[cacheKey]?.let { cached ->
                        cached.isComplete = false
                        cached.refreshedAtMs = refreshedAtMs
                    }
                    return effectiveNames
                }
                disableCacheLocked(cacheKey)
                markOversizedParentLocked(cacheKey)
                return effectiveNames
            }

            oversizedParents.remove(cacheKey)
            ensureParentCapacityLocked(cacheKey)
            replaceChildrenLocked(
                cacheKey = cacheKey,
                children = effectiveChildren,
                refreshedAtMs = refreshedAtMs,
                isComplete = isComplete
            )
            namesByParent[cacheKey] = CachedChildNames(
                initialNames = effectiveNames,
                initialRefreshedAtMs = refreshedAtMs,
                initialComplete = refreshedNames.isComplete
            )
            return namesByParent[cacheKey]?.names ?: effectiveNames
        }
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
        synchronized(mutationLock) {
            if (cacheKey in oversizedParents) return
            namesByParent[cacheKey]?.let { cached ->
                if (childName !in cached.names &&
                    cached.names.size >= MAX_CACHED_CHILDREN_PER_PARENT
                ) {
                    disableCacheLocked(cacheKey)
                    markOversizedParentLocked(cacheKey)
                    return
                }
                cached.names += childName
                cached.refreshedAtMs = refreshedAtMs
                if (isReservation) {
                    reservationsByParent
                        .getOrPut(cacheKey) { linkedMapOf() }[childName] = refreshedAtMs
                    cached.isComplete = false
                } else {
                    reservationsByParent[cacheKey]?.remove(childName)
                }
                return
            }
            ensureParentCapacityLocked(cacheKey)
            if (isReservation) {
                reservationsByParent
                    .getOrPut(cacheKey) { linkedMapOf() }[childName] = refreshedAtMs
            }
            namesByParent[cacheKey] = CachedChildNames(
                initialNames = listOf(childName),
                initialRefreshedAtMs = refreshedAtMs,
                initialComplete = false
            )
        }
    }

    fun rememberChild(
        cacheKey: String,
        child: QueriedTreeChild,
        refreshedAtMs: Long
    ) {
        synchronized(mutationLock) {
            if (cacheKey in oversizedParents) return
            reservationsByParent[cacheKey]?.remove(child.name)
            var cached = childrenByParent[cacheKey]
            if (cached == null) {
                if (!canCache(
                        childCount = 1,
                        nameCount = (namesByParent[cacheKey]?.names?.size ?: 0) + 1,
                        totalChildren = cachedChildrenCount.get() + 1
                    )
                ) {
                    disableCacheLocked(cacheKey)
                    markOversizedParentLocked(cacheKey)
                    return
                }
                ensureParentCapacityLocked(cacheKey)
                val names = namesByParent[cacheKey]
                if (names == null) {
                    namesByParent[cacheKey] = CachedChildNames(
                        initialNames = listOf(child.name),
                        initialRefreshedAtMs = refreshedAtMs,
                        initialComplete = false
                    )
                } else {
                    names.names += child.name
                    names.refreshedAtMs = refreshedAtMs
                }
                cached = CachedTreeChildren(
                    initialChildren = listOf(child),
                    initialRefreshedAtMs = refreshedAtMs,
                    initialComplete = false
                )
                childrenByParent[cacheKey] = cached
                cachedChildrenCount.incrementAndGet()
                return
            }
            val staleNames = cached.childrenByName.values
                .filter { existing ->
                    existing.documentUri.toString() == child.documentUri.toString() &&
                        existing.name != child.name
                }
                .map(QueriedTreeChild::name)
            val currentNamePresent = child.name in cached.childrenByName
            val staleChildCount = staleNames.count { it != child.name }
            val nextChildCount = cached.childrenByName.size - staleChildCount +
                if (currentNamePresent) 0 else 1
            val names = namesByParent[cacheKey]
            val existingNames = names?.names
            val staleNameCount = existingNames?.count { it in staleNames } ?: 0
            val nextNameCount = if (existingNames == null) {
                0
            } else {
                existingNames.size - staleNameCount +
                    if (child.name in existingNames) 0 else 1
            }
            val nextTotalCount = cachedChildrenCount.get() - cached.childrenByName.size +
                nextChildCount
            if (!canCache(
                    childCount = nextChildCount,
                    nameCount = nextNameCount,
                    totalChildren = nextTotalCount
                )
            ) {
                disableCacheLocked(cacheKey)
                markOversizedParentLocked(cacheKey)
                return
            }
            staleNames.forEach(cached.childrenByName::remove)
            names?.let {
                staleNames.forEach(names.names::remove)
                if (child.name !in names.names &&
                    names.names.size >= MAX_CACHED_CHILDREN_PER_PARENT
                ) {
                    disableCacheLocked(cacheKey)
                    markOversizedParentLocked(cacheKey)
                    return
                }
                names.names += child.name
            }
            cached.childrenByName[child.name] = child
            val addedCount = if (currentNamePresent) 0 else 1
            cachedChildrenCount.addAndGet(addedCount - staleNames.size)
            cached.refreshedAtMs = refreshedAtMs
            return
        }
    }

    fun forgetChildName(cacheKey: String, childName: String, refreshedAtMs: Long) {
        synchronized(mutationLock) {
            reservationsByParent[cacheKey]?.remove(childName)
            namesByParent[cacheKey]?.let { cached ->
                cached.names -= childName
                cached.refreshedAtMs = refreshedAtMs
            }
            childrenByParent[cacheKey]?.let { entries ->
                if (entries.childrenByName.remove(childName) != null) {
                    cachedChildrenCount.decrementAndGet()
                }
                entries.refreshedAtMs = refreshedAtMs
            }
        }
    }

    fun forgetChildrenByReference(
        references: Set<String>,
        onForgotChildName: (cacheKey: String, childName: String) -> Unit
    ) {
        if (references.isEmpty()) return
        val forgotten = synchronized(mutationLock) {
            childrenByParent.flatMap { (cacheKey, cachedChildren) ->
                cachedChildren.childrenByName.values
                    .filter { child -> child.documentUri.toString() in references }
                    .map { child -> cacheKey to child.name }
            }
        }
        forgotten.forEach { (cacheKey, childName) ->
            onForgotChildName(cacheKey, childName)
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            namesByParent.clear()
            childrenByParent.clear()
            reservationsByParent.clear()
            oversizedParents.clear()
            cachedChildrenCount.set(0)
        }
    }

    private fun canCache(
        childCount: Int,
        nameCount: Int,
        totalChildren: Int
    ): Boolean {
        return childCount <= MAX_CACHED_CHILDREN_PER_PARENT &&
            nameCount <= MAX_CACHED_CHILDREN_PER_PARENT &&
            totalChildren <= MAX_CACHED_CHILDREN_TOTAL
    }

    private fun replaceChildrenLocked(
        cacheKey: String,
        children: Collection<QueriedTreeChild>,
        refreshedAtMs: Long,
        isComplete: Boolean
    ) {
        val previousSize = childrenByParent[cacheKey]
            ?.childrenByName
            ?.size
            ?: 0
        val replacement = CachedTreeChildren(
            initialChildren = children,
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = isComplete
        )
        childrenByParent[cacheKey] = replacement
        cachedChildrenCount.addAndGet(replacement.childrenByName.size - previousSize)
    }

    private fun disableCacheLocked(cacheKey: String) {
        val removed = childrenByParent.remove(cacheKey)
        if (removed != null) {
            cachedChildrenCount.addAndGet(-removed.childrenByName.size)
        }
        namesByParent.remove(cacheKey)
        reservationsByParent.remove(cacheKey)
    }

    private fun pruneExpiredReservationsLocked(cacheKey: String, nowMs: Long) {
        val reservations = reservationsByParent[cacheKey] ?: return
        val names = namesByParent[cacheKey]
        reservations.entries.removeIf { (name, reservedAtMs) ->
            if (nowMs - reservedAtMs <= reservationLeaseMs) {
                false
            } else {
                if (name !in childrenByParent[cacheKey]?.childrenByName.orEmpty()) {
                    names?.names?.remove(name)
                }
                true
            }
        }
        if (reservations.isEmpty()) {
            reservationsByParent.remove(cacheKey)
        }
    }

    private fun discardMaterializedReservationsLocked(
        cacheKey: String,
        refreshedNames: Set<String>
    ) {
        val reservations = reservationsByParent[cacheKey] ?: return
        reservations.entries.removeIf { (name, _) -> name in refreshedNames }
        if (reservations.isEmpty()) {
            reservationsByParent.remove(cacheKey)
        }
    }

    private fun markOversizedParentLocked(cacheKey: String) {
        oversizedParents.add(cacheKey)
        while (oversizedParents.size > MAX_CACHED_PARENT_COUNT) {
            val victim = oversizedParents.firstOrNull { it != cacheKey } ?: return
            oversizedParents.remove(victim)
        }
    }

    private fun ensureParentCapacityLocked(cacheKey: String) {
        if (namesByParent.containsKey(cacheKey) || childrenByParent.containsKey(cacheKey)) return
        while (true) {
            val keys = HashSet<String>(namesByParent.keys).apply {
                addAll(childrenByParent.keys)
            }
            if (keys.size < MAX_CACHED_PARENT_COUNT) return
            val victim = keys.firstOrNull { it != cacheKey } ?: return
            disableCacheLocked(victim)
            oversizedParents.remove(victim)
        }
    }

    companion object {
        const val MAX_CACHED_PARENT_COUNT = 256
        const val MAX_CACHED_CHILDREN_PER_PARENT = 8_192
        const val MAX_CACHED_CHILDREN_TOTAL = 65_536

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
