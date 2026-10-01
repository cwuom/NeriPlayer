package moe.ouom.neriplayer.data.model.download.execution

/** 最终发布被新代次接管时不能再把旧回调当成可重试故障 */
enum class FinalizedDownloadPublicationResult {
    PUBLISHED,
    STALE,
    RECOVERY_REQUIRED;

    val requiresRecovery: Boolean
        get() = this == RECOVERY_REQUIRED
}
