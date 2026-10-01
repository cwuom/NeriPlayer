package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.api.youtube.client.YouTubeMusicClient
import moe.ouom.neriplayer.data.lyrics.repository.LrcLibLyricsRepository

interface DownloadLyricServices {
    val neteaseClient: NeteaseClient
    val youtubeMusicClient: YouTubeMusicClient
    val lrcLibClient: LrcLibLyricsRepository
}
