package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.screen.nowplaying.hasCachedLocalDownload

internal interface NowPlayingLyricsSources {
    fun cachedPreferred(song: SongItem, source: LyricSourcePreference,
        preferWordTimedLyrics: Boolean): PreferredLyricSourceResult? = null

    fun hasManagedDownload(song: SongItem): Boolean
    fun scheduleManagedRefresh(context: Context)
    fun fastDownloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle?
    fun downloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle?
    fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean): LocalLyricsScanMetadata?
    suspend fun preferred(song: SongItem, source: LyricSourcePreference): PreferredLyricSourceResult?
    suspend fun neteaseOriginal(songId: Long): String
    suspend fun neteaseRomanized(songId: Long): String
    suspend fun onlineOriginal(song: SongItem): List<LyricEntry>
    suspend fun onlineTranslated(song: SongItem): List<LyricEntry>
    suspend fun onlineRomanized(song: SongItem): List<LyricEntry>
}

internal object PlatformNowPlayingLyricsSources : NowPlayingLyricsSources {
    override fun cachedPreferred(song: SongItem, source: LyricSourcePreference,
        preferWordTimedLyrics: Boolean): PreferredLyricSourceResult? =
        PlayerManager.getCachedPreferredLyricSourceResult(song, source, preferWordTimedLyrics)

    override fun hasManagedDownload(song: SongItem): Boolean = hasCachedLocalDownload(song)

    override fun scheduleManagedRefresh(context: Context) {
        ManagedDownloadStorage.scheduleLyricsRefresh(context)
    }

    override fun fastDownloaded(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.DownloadedLyricsBundle? =
        AudioDownloadManager.getLyricsBundleFast(context, song, allowColdSafProbe = false)

    override fun downloaded(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.DownloadedLyricsBundle? = AudioDownloadManager.getLyricsBundle(context, song)

    override fun inspectLocal(
        context: Context,
        song: SongItem,
        includeEmbedded: Boolean
    ): LocalLyricsScanMetadata? = LocalMediaSupport.inspectLyricsFast(
        context = context,
        song = song,
        includeStoredFallback = true,
        includeEmbeddedFallback = includeEmbedded
    )

    override suspend fun preferred(
        song: SongItem,
        source: LyricSourcePreference
    ): PreferredLyricSourceResult? = PlayerManager.getPreferredLyricSourceResult(song, source)

    override suspend fun neteaseOriginal(songId: Long): String =
        PlayerManager.getPreferredNeteaseLyricContent(songId)

    override suspend fun neteaseRomanized(songId: Long): String =
        PlayerManager.getPreferredNeteaseRomanizedLyricContent(songId)

    override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> = PlayerManager.getLyrics(song)

    override suspend fun onlineTranslated(song: SongItem): List<LyricEntry> =
        PlayerManager.getTranslatedLyrics(song)

    override suspend fun onlineRomanized(song: SongItem): List<LyricEntry> =
        PlayerManager.getRomanizedLyrics(song)
}
