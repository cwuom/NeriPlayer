package moe.ouom.neriplayer.data.model.download.execution

const val METADATA_ACTION_REQUIRED_OPERATION_STATE = "METADATA_ACTION_REQUIRED"

/**
 * 下载 operation 的持久状态
 *
 * wireName 保持数据库和旧版本兼容，业务代码通过这个枚举集中判断转移边界
 */
enum class DownloadOperationState(
    val wireName: String
) {
    PENDING_QUEUE("PENDING_QUEUE"),
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    COMMITTING("COMMITTING"),
    CORE_COMMITTED("CORE_COMMITTED"),
    ASSETS_ENRICHING("ASSETS_ENRICHING"),
    FINALIZED("FINALIZED"),
    DEGRADED_COMPLETE("DEGRADED_COMPLETE"),
    COMPLETED("COMPLETED"),
    CANCEL_REQUESTED("CANCEL_REQUESTED"),
    CANCELLED("CANCELLED"),
    STOPPED("STOPPED"),
    RETRYABLE("RETRYABLE"),
    INVALID("INVALID"),
    WAITING_STORAGE_MUTATION("WAITING_STORAGE_MUTATION"),
    WAITING_HOST("WAITING_HOST"),
    WAITING_DELETE_CLEANUP("WAITING_DELETE_CLEANUP"),
    METADATA_ACTION_REQUIRED(METADATA_ACTION_REQUIRED_OPERATION_STATE),
    UNKNOWN("");

    companion object {
        private val byWireName = entries
            .filterNot { it == UNKNOWN }
            .associateBy(DownloadOperationState::wireName)

        fun parse(value: String?): DownloadOperationState {
            return value?.trim()?.let(byWireName::get) ?: UNKNOWN
        }
    }
}
