package moe.ouom.neriplayer.core.player.download

import okhttp3.Call
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 集中维护下载 operation 的生命周期、暂停标记和活动网络调用 */
internal class AudioDownloadOperationRegistry(
    private val referenceOwnership: AudioDownloadReferenceOwnership
) {
    private val mutationLock = Any()
    private val activeCallsBySongKey =
        ConcurrentHashMap<String, MutableSet<Call>>()
    private val activeCallsByOperationId =
        ConcurrentHashMap<String, MutableSet<Call>>()
    private val networkPolicyPausedSongKeys =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val activeSongOperationCounts = ConcurrentHashMap<String, Int>()
    private val activeOperationCounts = ConcurrentHashMap<String, Int>()
    private val activeSongKeyByOperationId = ConcurrentHashMap<String, String>()
    private val executionHostPausedOperationIds = ConcurrentHashMap.newKeySet<String>()
    /** core 提交后仍需允许后台增强，不能被宿主生命周期回调撤销 */
    private val coreCommittedOperationIds = ConcurrentHashMap.newKeySet<String>()

    fun <T> withMutationLock(block: () -> T): T = synchronized(mutationLock) {
        block()
    }

    fun isSongDownloadActive(songKey: String): Boolean = synchronized(mutationLock) {
        (activeSongOperationCounts[songKey] ?: 0) > 0
    }

    fun isOperationDownloadActive(operationId: String): Boolean {
        val normalizedId = operationId.trim()
        return normalizedId.isNotBlank() && synchronized(mutationLock) {
            (activeOperationCounts[normalizedId] ?: 0) > 0
        }
    }

    fun beginSongDownloadOperation(
        songKey: String,
        operationId: String,
        attemptId: Long?
    ) = synchronized(mutationLock) {
        referenceOwnership.begin(
            songKey = songKey,
            operationId = operationId,
            attemptId = attemptId
        )
        activeSongOperationCounts.compute(songKey) { _, current ->
            (current ?: 0) + 1
        }
        activeOperationCounts.compute(operationId) { _, current ->
            (current ?: 0) + 1
        }
        activeSongKeyByOperationId[operationId] = songKey
    }

    fun endSongDownloadOperation(songKey: String, operationId: String) =
        synchronized(mutationLock) {
            activeSongOperationCounts.computeIfPresent(songKey) { _, current ->
                val nextCount = current - 1
                if (nextCount <= 0) null else nextCount
            }
            val remainingOperationCount = activeOperationCounts.computeIfPresent(
                operationId
            ) { _, current ->
                val nextCount = current - 1
                if (nextCount <= 0) null else nextCount
            } ?: 0
            referenceOwnership.finish(songKey, operationId)
            if (remainingOperationCount <= 0) {
                activeSongKeyByOperationId.remove(operationId, songKey)
                executionHostPausedOperationIds.remove(operationId)
            }
        }

    fun claimReferenceOwnershipForEnrichment(
        songKey: String,
        operationId: String
    ): Boolean = synchronized(mutationLock) {
        referenceOwnership.claimForEnrichment(songKey, operationId)
    }

    fun releaseReferenceOwnership(songKey: String, operationId: String) {
        synchronized(mutationLock) {
            referenceOwnership.finish(songKey, operationId)
        }
    }

    fun allowsReference(
        songKey: String,
        operationId: String?,
        attemptId: Long? = null
    ): Boolean = synchronized(mutationLock) {
        referenceOwnership.allows(songKey, operationId, attemptId)
    }

    fun registerActiveCall(songKey: String, call: Call, operationId: String?) {
        synchronized(mutationLock) {
            activeCallsBySongKey.compute(songKey) { _, current ->
                val calls = current ?: newCallSet()
                calls.add(call)
                calls
            }
            normalizeOperationId(operationId)?.let { normalizedId ->
                activeCallsByOperationId.compute(normalizedId) { _, current ->
                    val calls = current ?: newCallSet()
                    calls.add(call)
                    calls
                }
            }
        }
    }

    fun unregisterActiveCall(songKey: String, call: Call, operationId: String?) {
        synchronized(mutationLock) {
            activeCallsBySongKey.computeIfPresent(songKey) { _, current ->
                current.remove(call)
                if (current.isEmpty()) null else current
            }
            normalizeOperationId(operationId)?.let { normalizedId ->
                activeCallsByOperationId.computeIfPresent(normalizedId) { _, current ->
                    current.remove(call)
                    if (current.isEmpty()) null else current
                }
            }
        }
    }

    fun snapshotActiveCalls(songKey: String? = null): List<Call> =
        synchronized(mutationLock) {
            if (songKey == null) {
                activeCallsBySongKey.values.flatMap { calls -> calls.toList() }
            } else {
                activeCallsBySongKey[songKey]?.toList().orEmpty()
            }
        }

    fun snapshotActiveCalls(operationIds: Collection<String>): List<Call> =
        synchronized(mutationLock) {
            operationIds.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .flatMap { operationId ->
                    activeCallsByOperationId[operationId].orEmpty().asSequence()
                }
                .distinct()
                .toList()
        }

    fun activeOperationIdsForSong(songKey: String): Set<String> =
        synchronized(mutationLock) {
            activeSongKeyByOperationId.asSequence()
                .filter { (_, activeSongKey) -> activeSongKey == songKey }
                .map { (operationId, _) -> operationId }
                .toSet()
        }

    fun songKeyForOperation(operationId: String): String? = synchronized(mutationLock) {
        activeSongKeyByOperationId[operationId]
    }

    fun activeOperationCount(operationId: String): Int = synchronized(mutationLock) {
        activeOperationCounts[operationId] ?: 0
    }

    fun activeOperationIds(): Set<String> = synchronized(mutationLock) {
        activeOperationCounts.keys.toSet()
    }

    fun isNetworkPolicyPaused(songKey: String): Boolean = synchronized(mutationLock) {
        networkPolicyPausedSongKeys.contains(songKey)
    }

    fun addNetworkPolicyPaused(songKeys: Collection<String>) {
        synchronized(mutationLock) {
            networkPolicyPausedSongKeys.addAll(songKeys)
        }
    }

    fun removeNetworkPolicyPaused(songKey: String) {
        synchronized(mutationLock) {
            networkPolicyPausedSongKeys.remove(songKey)
        }
    }

    fun removeNetworkPolicyPaused(songKeys: Collection<String>) {
        synchronized(mutationLock) {
            songKeys.forEach(networkPolicyPausedSongKeys::remove)
        }
    }

    fun clearNetworkPolicyPaused() {
        synchronized(mutationLock) {
            networkPolicyPausedSongKeys.clear()
        }
    }

    fun markExecutionHostPaused(operationId: String) {
        synchronized(mutationLock) {
            executionHostPausedOperationIds.add(operationId)
        }
    }

    fun markExecutionHostPaused(operationIds: Collection<String>) {
        synchronized(mutationLock) {
            operationIds.forEach(executionHostPausedOperationIds::add)
        }
    }

    fun clearExecutionHostPaused(operationId: String) {
        synchronized(mutationLock) {
            executionHostPausedOperationIds.remove(operationId)
        }
    }

    fun markCoreCommitted(operationId: String) {
        val normalizedId = operationId.trim()
        if (normalizedId.isBlank()) return
        synchronized(mutationLock) {
            coreCommittedOperationIds.add(normalizedId)
        }
    }

    /** 只有当前代次仍持有引用时才登记 core，避免取消竞态重新打开旧 operation */
    fun markCoreCommittedIfOwned(
        songKey: String,
        operationId: String,
        attemptId: Long?
    ): Boolean {
        val normalizedSongKey = songKey.trim()
        val normalizedId = operationId.trim()
        if (normalizedSongKey.isBlank() || normalizedId.isBlank()) return false
        return synchronized(mutationLock) {
            if (!referenceOwnership.allows(normalizedSongKey, normalizedId, attemptId)) {
                false
            } else {
                coreCommittedOperationIds.add(normalizedId)
                true
            }
        }
    }

    fun clearCoreCommitted(operationId: String) {
        val normalizedId = operationId.trim()
        if (normalizedId.isBlank()) return
        synchronized(mutationLock) {
            coreCommittedOperationIds.remove(normalizedId)
        }
    }

    fun isCoreCommitted(operationId: String): Boolean {
        val normalizedId = operationId.trim()
        return normalizedId.isNotBlank() && synchronized(mutationLock) {
            coreCommittedOperationIds.contains(normalizedId)
        }
    }

    fun clearExecutionHostPausedIfInactive(operationIds: Collection<String>) {
        synchronized(mutationLock) {
            operationIds.forEach { operationId ->
                if ((activeOperationCounts[operationId] ?: 0) <= 0) {
                    executionHostPausedOperationIds.remove(operationId)
                }
            }
        }
    }

    fun clearInactiveExecutionHostPausesExcept(activeOperationIds: Set<String>) {
        synchronized(mutationLock) {
            activeSongKeyByOperationId.keys
                .filter { operationId ->
                    operationId !in activeOperationIds &&
                        (activeOperationCounts[operationId] ?: 0) <= 0
                }
                .forEach(executionHostPausedOperationIds::remove)
        }
    }

    fun isExecutionHostPaused(operationId: String): Boolean = synchronized(mutationLock) {
        executionHostPausedOperationIds.contains(operationId)
    }

    fun revokeReference(songKey: String, operationIds: Collection<String>) {
        synchronized(mutationLock) {
            referenceOwnership.revoke(songKey, operationIds)
        }
    }

    fun revokeAllReferences() {
        synchronized(mutationLock) {
            referenceOwnership.revokeAll()
        }
    }

    private fun normalizeOperationId(operationId: String?): String? = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun newCallSet(): MutableSet<Call> =
        Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
}
