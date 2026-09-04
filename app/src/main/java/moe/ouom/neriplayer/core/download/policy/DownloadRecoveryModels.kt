package moe.ouom.neriplayer.core.download.policy

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem

internal data class PendingDownloadRecoveryCandidate(
    val song: SongItem,
    val workingFile: File?,
    val order: Int,
    val cancelled: Boolean,
    val operationId: String? = null,
    val requiresWifiNetwork: Boolean = true,
    val requiresExplicitResume: Boolean = false
)

/** 只有带 staging 半成品的恢复项需要批量入口保留断点，其余项交给共享泵 */
internal fun shouldRecoverDownloadCandidateWithBatch(
    songKey: String,
    antiJoinedKeys: Set<String>,
    hasWorkingFile: Boolean
): Boolean {
    // pending queue 已经由 Room operation 承载。查询暂时失败时也不能把整批
    // queued operation 误判成旧物理队列，否则启动会串行重建数百个任务并饿死新请求
    return songKey in antiJoinedKeys && hasWorkingFile
}

internal fun shouldRequireExplicitResume(
    userInitiated: Boolean,
    state: String?,
    hasPendingUidtJob: Boolean,
    stopRequestedByUser: Boolean = false,
    cancellationRequestedByUser: Boolean = false,
    resumePending: Boolean = false
): Boolean {
    if (!userInitiated || cancellationRequestedByUser) return false
    return stopRequestedByUser || resumePending
}

internal fun mergePendingDownloadRecoveryCandidates(
    queuedDownloads: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
    resumableDownloads: List<ManagedDownloadStorage.PendingResumableDownload>,
    cancelledKeys: Set<String> = emptySet(),
    cancelledOperationIds: Set<String> = emptySet()
): List<PendingDownloadRecoveryCandidate> {
    val merged = linkedMapOf<String, PendingDownloadRecoveryCandidate>()

    fun cancellationApplies(
        songKey: String,
        operationId: String?,
        hasReplacement: Boolean
    ): Boolean {
        val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
        if (normalizedOperationId != null) {
            return normalizedOperationId in cancelledOperationIds
        }
        // 旧版物理队列没有 operation 身份。只有在没有新的持久 operation
        // 可承接它时，才能把 stableKey 取消标记应用到这类条目
        return !hasReplacement && songKey in cancelledKeys
    }

    queuedDownloads
        .sortedBy(ManagedDownloadStorage.PendingDownloadQueueEntry::order)
        .forEach { entry ->
            val songKey = entry.stableKey.trim()
            if (songKey.isBlank()) return@forEach
            val entryOperationId = entry.operationId
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val existing = merged[songKey]
            merged[songKey] = if (existing == null) {
                PendingDownloadRecoveryCandidate(
                    song = entry.song,
                    workingFile = null,
                    order = entry.order,
                    cancelled = cancellationApplies(
                        songKey = songKey,
                        operationId = entryOperationId,
                        hasReplacement = false
                    ),
                    operationId = entryOperationId,
                    requiresWifiNetwork = entry.requiresWifiNetwork
                )
            } else {
                // 同一 stable key 可能同时出现在物理队列和 Room 队列，
                // 后来的副本只能补充身份，不能覆盖最初的队列顺序
                val operationReplaced = entryOperationId != null &&
                    entryOperationId != existing.operationId
                val effectiveOperationId = entryOperationId ?: existing.operationId
                existing.copy(
                    song = entry.song,
                    operationId = effectiveOperationId,
                    // 替代 operation 不能继承旧 operation 的 stableKey 取消标记
                    cancelled = if (operationReplaced) {
                        cancellationApplies(
                            songKey = songKey,
                            operationId = effectiveOperationId,
                            hasReplacement = true
                        )
                    } else {
                        existing.cancelled || cancellationApplies(
                            songKey = songKey,
                            operationId = effectiveOperationId,
                            hasReplacement = effectiveOperationId != null
                        )
                    }
                )
            }
        }

    resumableDownloads.forEachIndexed { index, pendingDownload ->
        val songKey = pendingDownload.song.stableKey()
        val existing = merged[songKey]
        val pendingOperationId = pendingDownload.operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val operationReplaced = existing?.operationId != null &&
            pendingOperationId != null &&
            existing.operationId != pendingOperationId
        val effectiveOperationId = if (operationReplaced) {
            existing.operationId
        } else {
            pendingOperationId ?: existing?.operationId
        }
        val cancellationAppliesToEffectiveOperation = cancellationApplies(
            songKey = songKey,
            operationId = effectiveOperationId,
            hasReplacement = effectiveOperationId != null
        )
        merged[songKey] = PendingDownloadRecoveryCandidate(
            song = if (operationReplaced) existing.song else pendingDownload.song,
            // 不把旧 operation 的半成品嫁接到新的 operation，避免新任务
            // 在错误的 staging 文件上继续写入
            workingFile = pendingDownload.workingFile.takeUnless { operationReplaced },
            order = existing?.order ?: queuedDownloads.size + index,
            cancelled = if (operationReplaced) {
                // 只继承队列中最终选中的 operation 身份，不继承被替换
                // 的旧 partial 的 stableKey 取消标记
                cancellationAppliesToEffectiveOperation
            } else {
                existing?.cancelled == true || cancellationAppliesToEffectiveOperation
            },
            operationId = effectiveOperationId,
            requiresWifiNetwork = existing?.requiresWifiNetwork ?: true
        )
    }

    return merged.values
        .sortedBy(PendingDownloadRecoveryCandidate::order)
        .mapIndexed { index, candidate -> candidate.copy(order = index) }
}

internal fun recoveryOperationIdsForKeys(
    candidates: Collection<PendingDownloadRecoveryCandidate>,
    songKeys: Collection<String>
): Set<String> {
    val keys = songKeys
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    if (keys.isEmpty()) return emptySet()
    return candidates.asSequence()
        .filter { candidate -> candidate.song.stableKey() in keys }
        .mapNotNull { candidate ->
            candidate.operationId
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        .toSet()
}
