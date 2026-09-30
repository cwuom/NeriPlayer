package moe.ouom.neriplayer.data.model.download.execution

/**
 * tracks the point after which a cancellation no longer owns the committed media
 */
enum class DownloadCoreCommitPhase {
    STAGING,
    COMMITTING,
    CORE_COMMITTED
}
