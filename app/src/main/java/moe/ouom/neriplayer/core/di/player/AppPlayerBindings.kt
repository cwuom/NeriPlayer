package moe.ouom.neriplayer.core.di.player

import android.app.Application
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies

internal fun installPlayerDependencies(application: Application) {
    PlayerDependencies.install(
        PlayerEnvironment(
            application = application,
            repositories = AppPlayerRepositories,
            downloads = AppPlayerDownloads,
            listenTogether = AppPlayerListenTogether,
            presentation = AppPlayerPresentation,
            isReady = AppContainer::isInitialized,
            launchBackgroundIo = AppContainer::launchBackgroundIo,
        )
    )
}

private object AppPlayerRepositories : PlayerRepositoryDependencies {
    override val subsonicRepository get() = AppContainer.subsonicRepository
    override val settingsRepo get() = AppContainer.settingsRepo
    override val biliCookieRepo get() = AppContainer.biliCookieRepo
    override val neteaseCookieRepo get() = AppContainer.neteaseCookieRepo
    override val youtubeAuthRepo get() = AppContainer.youtubeAuthRepo
    override val biliClient get() = AppContainer.biliClient
    override val neteaseClient get() = AppContainer.neteaseClient
    override val youtubeMusicClient get() = AppContainer.youtubeMusicClient
    override val biliPlaybackRepository get() = AppContainer.biliPlaybackRepository
    override val youtubeMusicPlaybackRepository get() = AppContainer.youtubeMusicPlaybackRepository
    override val biliSponsorBlockRepository get() = AppContainer.biliSponsorBlockRepository
    override val biliVideoSkipRepository get() = AppContainer.biliVideoSkipRepository
    override val cloudMusicSearchApi get() = AppContainer.cloudMusicSearchApi
    override val qqMusicSearchApi get() = AppContainer.qqMusicSearchApi
    override val lrcLibClient get() = AppContainer.lrcLibClient
    override val amllTtmlClient get() = AppContainer.amllTtmlClient
    override val editableLyricsMatcher get() = AppContainer.editableLyricsMatcher
    override val playHistoryRepo get() = AppContainer.playHistoryRepo
    override val playlistUsageRepo get() = AppContainer.playlistUsageRepo
    override val playbackStatsRepo get() = AppContainer.playbackStatsRepo
    override val localPlaylistPlaybackStatsRepo get() = AppContainer.localPlaylistPlaybackStatsRepo
    override val trafficStatsRepo get() = AppContainer.trafficStatsRepo
    override val sharedOkHttpClient get() = AppContainer.sharedOkHttpClient
}

private object AppPlayerListenTogether : PlayerListenTogetherAccess {
    override val roomState get() = AppContainer.listenTogetherSessionManager.roomState
    override val sessionState get() = AppContainer.listenTogetherSessionManager.sessionState

    override fun resumeListenerAfterSafetyPause() {
        AppContainer.listenTogetherSessionManager.resumeListenerAfterSafetyPause()
    }

    override fun isControllerAudioLinkUnavailable(roomId: String, stableKey: String): Boolean =
        AppContainer.listenTogetherSessionManager.isControllerAudioLinkUnavailable(roomId, stableKey)
}
