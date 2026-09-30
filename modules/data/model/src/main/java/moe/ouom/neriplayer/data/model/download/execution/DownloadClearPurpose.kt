package moe.ouom.neriplayer.data.model.download.execution

/** 清空下载任务与删除整个下载库使用不同的进程死亡恢复策略 */
enum class DownloadClearPurpose {
    TASK_PROGRESS,
    FULL_LIBRARY_DELETE
}
