package moe.ouom.neriplayer.core.player.host

import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository

import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.search.api.client.CloudMusicSearchApi
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.platform.lyrics.repository.AmllLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.QQMusicLyricsRepository
import moe.ouom.neriplayer.platform.bilibili.playback.BiliPlaybackRepository
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.platform.bilibili.skip.sponsorblock.BiliSponsorBlockRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.traffic.TrafficStatsRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.platform.youtube.repository.YouTubeMusicPlaybackRepository
import okhttp3.OkHttpClient

interface PlayerRepositoryDependencies {
    val subsonicRepository: SubsonicRepository? get() = null
    val settingsRepo: SettingsRepository
    val biliCookieRepo: BiliCookieRepository
    val neteaseCookieRepo: NeteaseCookieRepository
    val youtubeAuthRepo: YouTubeAuthRepository
    val biliClient: BiliClient
    val neteaseClient: NeteaseClient
    val youtubeMusicClient: YouTubeMusicClient
    val biliPlaybackRepository: BiliPlaybackRepository
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
    val biliSponsorBlockRepository: BiliSponsorBlockRepository
    val biliVideoSkipRepository: BiliVideoSkipRepository
    val cloudMusicSearchApi: CloudMusicSearchApi
    val qqMusicSearchApi: QQMusicLyricsRepository
    val lrcLibClient: LrcLibLyricsRepository
    val amllTtmlClient: AmllLyricsRepository
    val editableLyricsMatcher: EditableLyricsMatcher
    val playHistoryRepo: PlayHistoryRepository
    val playlistUsageRepo: PlaylistUsageRepository
    val playbackStatsRepo: PlaybackStatsRepository
    val localPlaylistPlaybackStatsRepo: LocalPlaylistPlaybackStatsRepository
    val trafficStatsRepo: TrafficStatsRepository
    val sharedOkHttpClient: OkHttpClient
}

internal val settingsRepo get() = PlayerDependencies.repositories.settingsRepo
internal val biliCookieRepo get() = PlayerDependencies.repositories.biliCookieRepo
