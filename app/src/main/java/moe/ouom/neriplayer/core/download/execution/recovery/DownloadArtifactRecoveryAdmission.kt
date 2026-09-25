package moe.ouom.neriplayer.core.download.execution.recovery

import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.METADATA_ACTION_REQUIRED_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.state.isRetryDeadlineReady
import android.content.Context
import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState

/** 目录凭据只能补足崩溃窗口，不能绕过用户取消、失败终态或持久退避 */
internal suspend fun DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
    context: Context,
    operationId: String?,
    respectRetryDeadline: Boolean = true,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    val id = operationId?.trim()?.takeIf(String::isNotBlank) ?: return true
    return database.withTransaction {
        val header = database.downloadOperationDao().findHeader(id)
        if (header != null) {
            if (header.stopRequestedByUser || header.state in setOf(
                    "CANCEL_REQUESTED", "CANCELLED", "STOPPED", "INVALID",
                    METADATA_ACTION_REQUIRED_OPERATION_STATE
                )
            ) return@withTransaction false
            if (respectRetryDeadline && !isRetryDeadlineReady(header.nextRetryAtMs, nowMs)) {
                return@withTransaction false
            }
        }
        val batches = database.downloadBatchDao()
        // 兼容旧版清空已经删除 operation 的情况，批次成员仍保留原请求身份
        batches.findMembersByOperation(id).none { member ->
            member.terminalBits == DownloadBatchMemberTerminal.CANCELLED ||
                (header == null && member.terminalBits == DownloadBatchMemberTerminal.FAILED) ||
                batches.findBatchById(member.batchId)?.let { batch ->
                    batch.stateBits and
                        (DownloadBatchState.CLEARING or DownloadBatchState.CANCELLED) != 0
                } == true
        }
    }
}

internal val CLEARED_ARTIFACT_RECOVERY_STOP_STATES = listOf(
    "COMMITTING", "CORE_COMMITTED", "ASSETS_ENRICHING", "DEGRADED_COMPLETE",
    "INVALID", METADATA_ACTION_REQUIRED_OPERATION_STATE
)
