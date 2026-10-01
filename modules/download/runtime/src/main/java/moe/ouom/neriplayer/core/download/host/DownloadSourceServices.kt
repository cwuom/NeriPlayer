package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.api.bilibili.client.BiliClient
import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.data.platform.bili.playback.BiliPlaybackRepository
import moe.ouom.neriplayer.data.youtube.repository.YouTubeMusicPlaybackRepository

interface DownloadSourceServices {
    val neteaseClient: NeteaseClient
    val biliClient: BiliClient
    val biliPlaybackRepository: BiliPlaybackRepository
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
    val youtubeMusicDownloadPlaybackRepository: YouTubeMusicPlaybackRepository
}
