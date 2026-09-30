package moe.ouom.neriplayer.core.integration.download

import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.host.DownloadCredentials
import moe.ouom.neriplayer.core.download.host.DownloadLyricServices
import moe.ouom.neriplayer.core.download.host.DownloadSourceServices

internal object AndroidDownloadServices : DownloadSourceServices, DownloadLyricServices, DownloadCredentials {
    override val neteaseClient get() = AppContainer.neteaseClient
    override val biliClient get() = AppContainer.biliClient
    override val biliPlaybackRepository get() = AppContainer.biliPlaybackRepository
    override val youtubeMusicPlaybackRepository get() = AppContainer.youtubeMusicPlaybackRepository
    override val youtubeMusicDownloadPlaybackRepository get() = AppContainer.youtubeMusicDownloadPlaybackRepository
    override val youtubeMusicClient get() = AppContainer.youtubeMusicClient
    override val lrcLibClient get() = AppContainer.lrcLibClient
    override fun biliCookies() = AppContainer.biliCookieRepo.getCookiesOnce()
    override fun youtubeAuth() = AppContainer.youtubeAuthRepo.getAuthOnce()
}
