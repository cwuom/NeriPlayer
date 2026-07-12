package moe.ouom.neriplayer.core.download.storage.file.cache

import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class ManagedDownloadFileChildNameCache(
    private val writeCacheValidateIntervalMs: Long
) {
    private val namesByDirectory = ConcurrentHashMap<String, CachedChildNames>()
    private val reservationLocks = ConcurrentHashMap<String, Any>()

    fun reserveUniqueName(dir: File, desiredName: String): String {
        val cacheKey = dir.absolutePath
        val lock = reservationLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            ManagedDownloadStorageNaming.createUniqueName(
                existingNames = cachedNames(dir, cacheKey),
                desiredName = desiredName
            ).also { reservedName ->
                rememberName(cacheKey, reservedName, System.currentTimeMillis())
            }
        }
    }

    fun rememberName(dir: File, childName: String) {
        rememberName(
            cacheKey = dir.absolutePath,
            childName = childName,
            refreshedAtMs = System.currentTimeMillis()
        )
    }

    fun forgetName(dir: File, childName: String) {
        val cacheKey = dir.absolutePath
        val cached = namesByDirectory[cacheKey] ?: return
        cached.names -= childName
        cached.refreshedAtMs = System.currentTimeMillis()
    }

    fun clear() {
        namesByDirectory.clear()
        reservationLocks.clear()
    }

    private fun cachedNames(dir: File, cacheKey: String): Set<String> {
        val now = System.currentTimeMillis()
        namesByDirectory[cacheKey]
            ?.takeIf { cached ->
                cached.isComplete &&
                    now - cached.refreshedAtMs <= writeCacheValidateIntervalMs
            }
            ?.let { return it.names }

        val refreshedNames = dir.listFiles()
            ?.mapNotNull(File::getName)
            .orEmpty()
        val refreshed = CachedChildNames(
            initialNames = refreshedNames,
            initialRefreshedAtMs = now,
            initialComplete = true
        )
        namesByDirectory[cacheKey] = refreshed
        return refreshed.names
    }

    private fun rememberName(
        cacheKey: String,
        childName: String,
        refreshedAtMs: Long
    ) {
        namesByDirectory[cacheKey]?.let { cached ->
            cached.names += childName
            cached.refreshedAtMs = refreshedAtMs
            return
        }
        namesByDirectory[cacheKey] = CachedChildNames(
            initialNames = listOf(childName),
            initialRefreshedAtMs = refreshedAtMs,
            initialComplete = false
        )
    }

    private class CachedChildNames(
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
}
