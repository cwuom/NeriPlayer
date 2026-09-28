package moe.ouom.neriplayer.ui.screen

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.LyricSourcePreference
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsSources

internal open class FakeNowPlayingLyricsSources : NowPlayingLyricsSources {
    override fun hasManagedDownload(song: SongItem) = false
    override fun scheduleManagedRefresh(context: Context) = Unit
    override fun fastDownloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle? = null
    override fun downloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle? = null
    override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean): LocalLyricsScanMetadata? = null
    override suspend fun preferred(song: SongItem, source: LyricSourcePreference): PreferredLyricSourceResult? = null
    override suspend fun neteaseOriginal(songId: Long) = ""
    override suspend fun neteaseRomanized(songId: Long) = ""
    override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> = emptyList()
    override suspend fun onlineTranslated(song: SongItem): List<LyricEntry> = emptyList()
    override suspend fun onlineRomanized(song: SongItem): List<LyricEntry> = emptyList()
}
