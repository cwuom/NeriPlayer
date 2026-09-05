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
        val attemptId: Long?
    )

    private val ownersBySongKey = ConcurrentHashMap<String, Owner>()

    fun begin(songKey: String, operationId: String, attemptId: Long?) {
        val normalizedSongKey = songKey.trim()
        val normalizedOperationId = operationId.trim()
        if (normalizedSongKey.isBlank() || normalizedOperationId.isBlank()) {
            return
        }
        ownersBySongKey[normalizedSongKey] = Owner(
            operationId = normalizedOperationId,
            attemptId = attemptId
        )
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
            if (current.operationId == normalizedOperationId) null else current
        }
    }
}
