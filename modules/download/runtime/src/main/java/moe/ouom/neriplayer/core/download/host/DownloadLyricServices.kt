package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository

interface DownloadLyricServices {
    val neteaseClient: NeteaseClient
    val youtubeMusicClient: YouTubeMusicClient
    val lrcLibClient: LrcLibLyricsRepository
}
