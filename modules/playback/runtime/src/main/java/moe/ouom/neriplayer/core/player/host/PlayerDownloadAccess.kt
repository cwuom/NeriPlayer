package moe.ouom.neriplayer.core.player.host

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.SongItem

import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution
import moe.ouom.neriplayer.data.model.playback.storage.PlayerDownloadedLyrics
import moe.ouom.neriplayer.data.model.playback.storage.PlayerDownloadedArtwork
import moe.ouom.neriplayer.data.model.playback.storage.PlayerMetadataClearRequest
import moe.ouom.neriplayer.data.model.playback.storage.PlayerDownloadedMetadataSyncOutcome

interface PlayerDownloadAccess {
    val downloadPresenceVersion: StateFlow<Int>
    fun downloadedSongs(): List<SongItem>
    fun hasDownloadedSongCached(song: SongItem): Boolean
    fun findDownloadedSongCached(song: SongItem): PlayerDownloadedArtwork?
    fun isLikelyManagedDownloadSongFast(context: Context, song: SongItem): Boolean
    fun scanLocalFiles(context: Context, forceRefresh: Boolean)
    fun getLocalPlaybackUri(context: Context, song: SongItem): String?
    suspend fun resolvePermittedLocalPlaybackUri(context: Context, song: SongItem, rawLocalReference: String?): String?
    suspend fun resolvePermittedLocalPlayback(context: Context, song: SongItem, rawLocalReference: String?): PlayerLocalPlaybackResolution
    fun resolveIndexedLocalPlaybackReference(context: Context, song: SongItem): PlayerLocalPlaybackResolution
    fun hasRecentlyCompletedAudio(song: SongItem): Boolean
    fun invalidateCompletedAudioReference(song: SongItem)
    fun isSongDownloadActive(songKey: String): Boolean
    fun isMissingReferenceFailure(error: Throwable): Boolean
    fun peekLocalCoverUri(song: SongItem): String?
    suspend fun findCoverReferenceByFileName(context: Context, fileName: String, forceRefresh: Boolean, preferSidecarRefresh: Boolean): String?
    fun getLyricsBundleFast(context: Context, song: SongItem, allowColdSafProbe: Boolean = true): PlayerDownloadedLyrics
    fun getLyricContent(context: Context, song: SongItem): String?
    fun getTranslatedLyricContent(context: Context, song: SongItem): String?
    fun getRomanizedLyricContent(context: Context, song: SongItem): String?
    suspend fun syncDownloadedSongMetadataNow(song: SongItem, clearRestorableOverrides: PlayerMetadataClearRequest = PlayerMetadataClearRequest()): PlayerDownloadedMetadataSyncOutcome
    fun syncDownloadedSongMetadata(song: SongItem, clearRestorableOverrides: PlayerMetadataClearRequest = PlayerMetadataClearRequest())
}
