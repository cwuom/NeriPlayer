package moe.ouom.neriplayer.data.model.settings.bootstrap

import moe.ouom.neriplayer.data.model.settings.download.DEFAULT_DOWNLOAD_PARALLELISM

data class BootstrapSettingsSnapshot(
    val bypassProxy: Boolean = true,
    val youtubeEnabled: Boolean = true,
    val preferHighRefreshRate: Boolean = false,
    val downloadDirectoryUri: String? = null,
    val downloadDirectoryLabel: String? = null,
    val downloadFileNameTemplate: String? = null,
    val downloadFollowPlaybackAudioQuality: Boolean = true,
    val downloadParallelism: Int = DEFAULT_DOWNLOAD_PARALLELISM
)
