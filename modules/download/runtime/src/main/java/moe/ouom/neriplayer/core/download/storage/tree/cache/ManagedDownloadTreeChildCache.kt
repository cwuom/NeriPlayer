package moe.ouom.neriplayer.core.download.storage.tree.cache

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class ManagedDownloadTreeChildCache(
    private val maxCachedEntries: Int = MAX_CACHED_CHILDREN_TOTAL
) {
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

    fun cachedChild(
        cacheKey: String,
        childName: String,
        nowMs: Long,
        maxCacheAgeMs: Long
    ): QueriedTreeChild? {
        if (maxCacheAgeMs <= 0L || cacheKey in oversizedParents) return null
        val cached = childrenByParent[cacheKey]
            ?.takeIf { it.isComplete && nowMs - it.refreshedAtMs <= maxCacheAgeMs }
            ?: return null
        return cached.childrenByName[childName]
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

    fun peekChild(
        cacheKey: String,
        childName: String,
        includeIncomplete: Boolean
    ): QueriedTreeChild? {
        if (cacheKey in oversizedParents) return null
        val cached = childrenByParent[cacheKey] ?: return null
        if (!includeIncomplete && !cached.isComplete) return null
        return cached.childrenByName[childName]
    }

    fun peekChildByReference(
        cacheKey: String,
        reference: String,
        includeIncomplete: Boolean
    ): QueriedTreeChild? {
        if (cacheKey in oversizedParents) return null
        val cached = childrenByParent[cacheKey] ?: return null
        if (!includeIncomplete && !cached.isComplete) return null
        return cached.childrenByReference[reference]
    }

    fun rememberChildren(
        cacheKey: String,
        children: Collection<QueriedTreeChild>,
        refreshedAtMs: Long,
        isComplete: Boolean
    ): Set<String> {
        synchronized(mutationLock) {
            reservationsByParent.keys.toList().forEach { pruneExpiredReservationsLocked(it, refreshedAtMs) }
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
                    cacheKey = cacheKey,
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
            reservationsByParent.keys.toList().forEach { pruneExpiredReservationsLocked(it, refreshedAtMs) }
            val nextNames = (namesByParent[cacheKey]?.names?.size ?: 0) +
                if (childName in namesByParent[cacheKey]?.names.orEmpty()) 0 else 1
            if (!canCache(
                    cacheKey = cacheKey,
                    childCount = childrenByParent[cacheKey]?.childrenByName?.size ?: 0,
                    nameCount = nextNames,
                    totalChildren = cachedChildrenCount.get()
                )
            ) {
                disableCacheLocked(cacheKey)
                markOversizedParentLocked(cacheKey)
                val reservedNames = namesByParent[cacheKey]?.names.orEmpty()
                if (isReservation && !canCache(
                        cacheKey = cacheKey,
                        childCount = 0,
                        nameCount = reservedNames.size + if (childName in reservedNames) 0 else 1,
                        totalChildren = cachedChildrenCount.get()
                    )
                ) {
                    throw IOException("SAF name reservation capacity exhausted")
                }
            }
            if (!isReservation && cacheKey in oversizedParents) return
            namesByParent[cacheKey]?.let { cached ->
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
            reservationsByParent.keys.toList().forEach { pruneExpiredReservationsLocked(it, refreshedAtMs) }
            reservationsByParent[cacheKey]?.remove(child.name)
            if (cacheKey in oversizedParents) return
            var cached = childrenByParent[cacheKey]
            if (cached == null) {
                if (!canCache(
                        cacheKey = cacheKey,
                        childCount = 1,
                        nameCount = (namesByParent[cacheKey]?.names?.size ?: 0) +
                            if (child.name in namesByParent[cacheKey]?.names.orEmpty()) 0 else 1,
                        totalChildren = cachedChildrenCount.get() + 1
                    )
                ) {
                    disableCacheLocked(cacheKey)
                    markOversizedParentLocked(cacheKey)
                    return
                }
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
            val childReference = child.documentUri.toString()
            val existingByReference = cached.childrenByReference[childReference]
            val existingByName = cached.childrenByName[child.name]
            val replacedReferences = listOfNotNull(existingByReference, existingByName)
                .mapTo(linkedSetOf()) { existing -> existing.documentUri.toString() }
            val staleNames = listOfNotNull(existingByReference)
                .map(QueriedTreeChild::name)
                .filter { staleName -> staleName != child.name }
            val previousChildCount = cached.childrenByName.size
            val nextChildCount = previousChildCount - replacedReferences.size + 1
            val names = namesByParent[cacheKey]
            val existingNames = names?.names
            val staleNameCount = staleNames.count { it in existingNames.orEmpty() }
            val nextNameCount = if (existingNames == null) {
                0
            } else {
                existingNames.size - staleNameCount +
                    if (child.name in existingNames) 0 else 1
            }
            val nextTotalCount = cachedChildrenCount.get() - cached.childrenByName.size +
                nextChildCount
            if (!canCache(
                    cacheKey = cacheKey,
                    childCount = nextChildCount,
                    nameCount = nextNameCount,
                    totalChildren = nextTotalCount
                )
            ) {
                disableCacheLocked(cacheKey)
                markOversizedParentLocked(cacheKey)
                return
            }
            existingByReference?.let { existing ->
                cached.childrenByName.remove(existing.name, existing)
            }
            existingByName?.let { existing ->
                cached.childrenByReference.remove(existing.documentUri.toString(), existing)
            }
            names?.let {
                staleNames.forEach(names.names::remove)
                names.names += child.name
            }
            cached.childrenByName[child.name] = child
            cached.childrenByReference[childReference] = child
            cachedChildrenCount.addAndGet(nextChildCount - previousChildCount)
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
                val removed = entries.childrenByName.remove(childName)
                if (removed != null) {
                    entries.childrenByReference.remove(removed.documentUri.toString(), removed)
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
                references.mapNotNull { reference ->
                    cachedChildren.childrenByReference[reference]
                        ?.let { child -> cacheKey to child.name }
                }
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
        cacheKey: String,
        childCount: Int,
        nameCount: Int,
        totalChildren: Int
    ): Boolean {
        if (childCount > maxCachedEntries || nameCount > maxCachedEntries) return false
        var projectedChildren = totalChildren
        while (true) {
            val keys = namesByParent.keys + childrenByParent.keys
            val projectedNames = namesByParent.entries.sumOf { (key, names) ->
                if (key == cacheKey) 0 else names.names.size
            } + nameCount
            if (projectedChildren <= maxCachedEntries && projectedNames <= maxCachedEntries &&
                (cacheKey in keys || keys.size < MAX_CACHED_PARENT_COUNT)) return true
            // 活跃名称预留不能随可重建快照一起淘汰，否则并发提交可能重用同名
            val victim = keys.filter { it != cacheKey && reservationsByParent[it].isNullOrEmpty() }
                .minByOrNull { childrenByParent[it]?.refreshedAtMs ?: namesByParent[it]?.refreshedAtMs ?: 0L }
                ?: return false
            projectedChildren -= childrenByParent[victim]?.childrenByName?.size ?: 0
            disableCacheLocked(victim)
            oversizedParents.remove(victim)
        }
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
        reservationsByParent[cacheKey]?.takeIf { it.isNotEmpty() }?.let { reservations ->
            namesByParent[cacheKey] = CachedChildNames(
                reservations.keys, reservations.values.maxOrNull() ?: 0L, false
            )
        }
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

    companion object {
        const val MAX_CACHED_PARENT_COUNT = 256
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
