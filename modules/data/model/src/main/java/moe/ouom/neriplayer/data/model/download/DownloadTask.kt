package moe.ouom.neriplayer.data.model.download

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadStatus

data class DownloadTask(
    val song: SongItem,
    val progress: DownloadProgress?,
    val status: DownloadStatus,
    val attemptId: Long = 0L
)
