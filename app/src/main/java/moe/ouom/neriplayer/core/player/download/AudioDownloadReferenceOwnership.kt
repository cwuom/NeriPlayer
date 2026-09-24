package moe.ouom.neriplayer.core.player.download

import java.util.concurrent.ConcurrentHashMap

/**
 * 记录歌曲当前允许写入内存引用的下载代次
 *
 * 取消和重下可能让旧协程短暂重叠。清理或登记引用前先核对 operationId，
 * 旧协程就不能把新代次的播放桥和 sidecar 清掉
 */
internal class AudioDownloadReferenceOwnership {
    private data class Owner(
        val operationId: String,
        val attemptId: Long?,
        val leaseCount: Int
    )

    private val ownersBySongKey = ConcurrentHashMap<String, Owner>()

    fun begin(songKey: String, operationId: String, attemptId: Long?) {
        val normalizedSongKey = songKey.trim()
        val normalizedOperationId = operationId.trim()
        if (normalizedSongKey.isBlank() || normalizedOperationId.isBlank()) {
            return
        }
        ownersBySongKey.compute(normalizedSongKey) { _, current ->
            if (current?.operationId == normalizedOperationId) {
                current.copy(
                    attemptId = attemptId ?: current.attemptId,
                    leaseCount = current.leaseCount + 1
                )
            } else {
                Owner(
                    operationId = normalizedOperationId,
                    attemptId = attemptId,
                    leaseCount = 1
                )
            }
        }
    }

    /** core 结束后，增强阶段可以接管空闲歌曲，但不能抢占新代次 */
    fun claimForEnrichment(songKey: String, operationId: String): Boolean {
        val normalizedSongKey = songKey.trim()
        val normalizedOperationId = operationId.trim()
        if (normalizedSongKey.isBlank() || normalizedOperationId.isBlank()) {
            return false
        }
        var claimed = false
        ownersBySongKey.compute(normalizedSongKey) { _, current ->
            when {
                current == null -> {
                    claimed = true
                    Owner(
                        operationId = normalizedOperationId,
                        attemptId = null,
                        leaseCount = 1
                    )
                }

                current.operationId == normalizedOperationId -> {
                    claimed = true
                    current.copy(leaseCount = current.leaseCount + 1)
                }

                else -> current
            }
        }
        return claimed
    }

    /** 无 operation 的调用是用户级清理或启动恢复，保持兼容的无条件语义 */
    fun allows(songKey: String, operationId: String? = null, attemptId: Long? = null): Boolean {
        val normalizedOperationId = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return true
        val owner = ownersBySongKey[songKey.trim()] ?: return false
        return owner.operationId == normalizedOperationId &&
            (attemptId == null || owner.attemptId == attemptId)
    }

    /** 只移除仍属于本 operation 的所有权，不能误删替代代次 */
    fun finish(songKey: String, operationId: String) {
        val normalizedSongKey = songKey.trim()
        val normalizedOperationId = operationId.trim()
        if (normalizedSongKey.isBlank() || normalizedOperationId.isBlank()) {
            return
        }
        ownersBySongKey.computeIfPresent(normalizedSongKey) { _, current ->
            if (current.operationId != normalizedOperationId) {
                current
            } else if (current.leaseCount <= 1) {
                null
            } else {
                current.copy(leaseCount = current.leaseCount - 1)
            }
        }
    }

    /** 取消或宿主停止只撤销指定代次，不能删除替代 operation 的租约 */
    fun revoke(songKey: String, operationIds: Collection<String>) {
        val normalizedSongKey = songKey.trim()
        val normalizedOperationIds = operationIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedSongKey.isBlank() || normalizedOperationIds.isEmpty()) {
            return
        }
        ownersBySongKey.computeIfPresent(normalizedSongKey) { _, current ->
            if (current.operationId in normalizedOperationIds) null else current
        }
    }

    /** 全局清空时撤销所有旧租约，新的 operation 会在下一次 begin 时重新登记 */
    fun revokeAll() {
        ownersBySongKey.clear()
    }
}
