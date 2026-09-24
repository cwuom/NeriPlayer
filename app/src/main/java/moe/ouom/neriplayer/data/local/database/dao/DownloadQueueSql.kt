package moe.ouom.neriplayer.data.local.database.dao

import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState

// 传输失败和进程中断先恢复，单纯等待宿主名额不算传输失败
internal const val DOWNLOAD_QUEUE_RECOVERY_PRIORITY_SQL =
    "CASE WHEN bytes_written > 0 OR retry_count > 0 OR " +
        "(last_error_code IS NOT NULL AND last_error_code NOT IN " +
        "('HOST_ADMISSION_FULL', 'HOST_SCHEDULE_REJECTED')) " +
        "THEN 0 ELSE 1 END"

internal const val DOWNLOAD_QUEUE_ELIGIBLE_SQL =
    "state IN (:states) AND stop_requested_by_user = 0 " +
        "AND ((batch_id IS NULL AND batch_generation IS NULL) OR EXISTS (" +
        "SELECT 1 FROM download_batch batch WHERE batch.batch_id = download_operation.batch_id " +
        "AND batch.generation = download_operation.batch_generation " +
        "AND (batch.state_bits & 1) != 0 AND (batch.state_bits & 2) = 0 " +
        "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) = 0)) "

internal const val DOWNLOAD_QUEUE_ORDER_SQL =
    "$DOWNLOAD_QUEUE_RECOVERY_PRIORITY_SQL ASC, queue_order ASC, created_at_ms ASC, operation_id ASC"

internal const val DOWNLOAD_QUEUE_CURSOR_SQL =
    "AND (:afterQueueOrder IS NULL OR " +
        "$DOWNLOAD_QUEUE_RECOVERY_PRIORITY_SQL > :afterRecoveryPriority OR " +
        "($DOWNLOAD_QUEUE_RECOVERY_PRIORITY_SQL = :afterRecoveryPriority AND (" +
        "queue_order > :afterQueueOrder OR " +
        "(queue_order = :afterQueueOrder AND created_at_ms > :afterCreatedAtMs) OR " +
        "(queue_order = :afterQueueOrder AND created_at_ms = :afterCreatedAtMs " +
        "AND operation_id > :afterOperationId)))) "
