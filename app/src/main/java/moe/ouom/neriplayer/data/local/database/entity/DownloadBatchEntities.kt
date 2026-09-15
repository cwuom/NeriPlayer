package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_batch",
    indices = [
        Index(
            value = ["state_bits", "updated_at_ms"],
            name = "index_download_batch_state_updated"
        ),
        Index(
            value = ["generation"],
            unique = true,
            name = "index_download_batch_generation"
        )
    ]
)
internal data class DownloadBatchEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    val generation: Long,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "state_bits") val stateBits: Int,
    @ColumnInfo(name = "clear_epoch") val clearEpoch: Long,
    @ColumnInfo(name = "network_generation") val networkGeneration: Long?,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long
) {
    init {
        require(batchId.isNotBlank())
        require(generation > 0L)
        require(totalCount > 0)
        require(stateBits >= 0)
        require(
            stateBits and DownloadBatchState.TERMINAL_MASK in
                setOf(0, DownloadBatchState.COMPLETED, DownloadBatchState.CANCELLED)
        )
    }
}

@Entity(
    tableName = "download_batch_member",
    primaryKeys = ["batch_id", "stable_key"],
    indices = [
        Index(
            value = ["batch_id", "ordinal"],
            unique = true,
            name = "index_download_batch_member_order"
        ),
        Index(
            value = ["batch_id", "terminal_bits", "ordinal"],
            name = "index_download_batch_member_terminal"
        ),
        Index(value = ["operation_id"], name = "index_download_batch_member_operation")
    ]
)
internal data class DownloadBatchMemberEntity(
    @ColumnInfo(name = "batch_id") val batchId: String,
    val ordinal: Int,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "terminal_bits") val terminalBits: Int = 0,
    @ColumnInfo(name = "max_fraction_milli") val maxFractionMilli: Int = 0,
    @ColumnInfo(name = "initially_completed") val initiallyCompleted: Boolean = false,
    @ColumnInfo(name = "operation_id") val operationId: String? = null,
    @ColumnInfo(name = "attempt_id") val attemptId: Long? = null,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long
) {
    init {
        require(ordinal >= 0)
        require(stableKey.isNotBlank())
        require(terminalBits in DownloadBatchMemberTerminal.VALID_BITS)
        require(maxFractionMilli in 0..1000)
    }
}

internal object DownloadBatchState {
    const val OPEN = 1 shl 0
    const val NETWORK_WAIT = 1 shl 1
    const val CLEARING = 1 shl 2
    const val COMPLETED = 1 shl 3
    const val CANCELLED = 1 shl 4
    /** 用户只允许当前网络代际在移动网络继续，不能扩散到其它批次 */
    const val USER_MOBILE_ALLOWED = 1 shl 5
    const val TERMINAL_MASK = COMPLETED or CANCELLED
}

internal object DownloadBatchMemberTerminal {
    const val NONE = 0
    const val COMPLETED = 1 shl 0
    const val FAILED = 1 shl 1
    const val CANCELLED = 1 shl 2
    val VALID_BITS: Set<Int> = setOf(NONE, COMPLETED, FAILED, CANCELLED)
}
