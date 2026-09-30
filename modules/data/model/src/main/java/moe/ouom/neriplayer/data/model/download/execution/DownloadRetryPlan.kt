package moe.ouom.neriplayer.data.model.download.execution

data class DownloadRetryPlan(
    val retryCount: Int,
    val nextRetryAtMs: Long?
)
