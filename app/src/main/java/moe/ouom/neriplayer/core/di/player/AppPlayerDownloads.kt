package moe.ouom.neriplayer.core.di.player

import android.content.Context
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.metadata.RestorableMetadataClearPolicy
import moe.ouom.neriplayer.core.download.policy.toPlaybackSongItem
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.playback.LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.data.model.playback.storage.PlayerDownloadedArtwork
import moe.ouom.neriplayer.data.model.playback.storage.PlayerDownloadedLyrics
import moe.ouom.neriplayer.data.model.playback.storage.PlayerDownloadedMetadataSyncOutcome
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution
import moe.ouom.neriplayer.data.model.playback.storage.PlayerMetadataClearRequest
import moe.ouom.neriplayer.data.model.SongItem

internal object AppPlayerDownloads : PlayerDownloadAccess {
    override val downloadPresenceVersion get() = GlobalDownloadManager.downloadPresenceVersion
    override fun downloadedSongs(): List<SongItem> =
        GlobalDownloadManager.downloadedSongs.value.map { it.toPlaybackSongItem() }

    override fun hasDownloadedSongCached(song: SongItem): Boolean = GlobalDownloadManager.hasDownloadedSongCached(song)
    override fun findDownloadedSongCached(song: SongItem): PlayerDownloadedArtwork? =
        GlobalDownloadManager.findDownloadedSongCached(song)?.let { PlayerDownloadedArtwork(it.coverPath, it.coverUrl) }

    override fun isLikelyManagedDownloadSongFast(context: Context, song: SongItem): Boolean =
        ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song)

    override fun scanLocalFiles(context: Context, forceRefresh: Boolean) {
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh)
    }

    override fun getLocalPlaybackUri(context: Context, song: SongItem): String? =
        AudioDownloadManager.getLocalPlaybackUri(context, song)

    override suspend fun resolvePermittedLocalPlaybackUri(context: Context, song: SongItem, rawLocalReference: String?): String? =
        AudioDownloadManager.resolvePermittedLocalPlaybackUri(context, song, rawLocalReference)

    override suspend fun resolvePermittedLocalPlayback(context: Context, song: SongItem, rawLocalReference: String?): PlayerLocalPlaybackResolution =
        AudioDownloadManager.resolvePermittedLocalPlayback(context, song, rawLocalReference).toPlayerResolution()

    override fun resolveIndexedLocalPlaybackReference(context: Context, song: SongItem): PlayerLocalPlaybackResolution =
        AudioDownloadManager.resolveIndexedLocalPlaybackReference(context, song).toPlayerResolution()

    override fun hasRecentlyCompletedAudio(song: SongItem): Boolean = AudioDownloadManager.peekCompletedAudioReference(song) != null
    override fun invalidateCompletedAudioReference(song: SongItem) {
        AudioDownloadManager.invalidateCompletedAudioReference(song)
    }
    override fun isSongDownloadActive(songKey: String): Boolean = AudioDownloadManager.isSongDownloadActive(songKey)
    override fun isMissingReferenceFailure(error: Throwable): Boolean = ManagedDownloadReferenceLookup.isMissingFailure(error)
    override fun peekLocalCoverUri(song: SongItem): String? = AudioDownloadManager.peekLocalCoverUri(song)

    override suspend fun findCoverReferenceByFileName(context: Context, fileName: String, forceRefresh: Boolean, preferSidecarRefresh: Boolean): String? =
        ManagedDownloadStorage.findCoverReferenceByFileName(context, fileName, forceRefresh, preferSidecarRefresh)

    override fun getLyricsBundleFast(context: Context, song: SongItem, allowColdSafProbe: Boolean): PlayerDownloadedLyrics =
        AudioDownloadManager.getLyricsBundleFast(context, song, allowColdSafProbe).let {
            PlayerDownloadedLyrics(it.lyric, it.translatedLyric, it.romanizedLyric,
                it.hasOriginalSidecar, it.hasTranslatedSidecar, it.hasRomanizedSidecar)
        }

    override fun getLyricContent(context: Context, song: SongItem): String? = AudioDownloadManager.getLyricContent(context, song)
    override fun getTranslatedLyricContent(context: Context, song: SongItem): String? = AudioDownloadManager.getTranslatedLyricContent(context, song)
    override fun getRomanizedLyricContent(context: Context, song: SongItem): String? = AudioDownloadManager.getRomanizedLyricContent(context, song)

    override suspend fun syncDownloadedSongMetadataNow(song: SongItem, clearRestorableOverrides: PlayerMetadataClearRequest): PlayerDownloadedMetadataSyncOutcome =
        GlobalDownloadManager.syncDownloadedSongMetadataNow(song, clearRestorableOverrides.toDownloadPolicy()).toPlayerOutcome()

    override fun syncDownloadedSongMetadata(song: SongItem, clearRestorableOverrides: PlayerMetadataClearRequest) {
        GlobalDownloadManager.syncDownloadedSongMetadata(song, clearRestorableOverrides = clearRestorableOverrides.toDownloadPolicy())
    }
}

internal fun LocalPlaybackReferenceResolution.toPlayerResolution(): PlayerLocalPlaybackResolution = when (this) {
    is LocalPlaybackReferenceResolution.Playable -> PlayerLocalPlaybackResolution.Playable(reference)
    LocalPlaybackReferenceResolution.NotIndexed -> PlayerLocalPlaybackResolution.NotIndexed
    LocalPlaybackReferenceResolution.Missing -> PlayerLocalPlaybackResolution.Missing
    is LocalPlaybackReferenceResolution.TemporarilyUnavailable -> PlayerLocalPlaybackResolution.TemporarilyUnavailable(evidence.toString())
}

internal fun PlayerMetadataClearRequest.toDownloadPolicy() = RestorableMetadataClearPolicy(title, artist, cover, lyrics, userLyricOffset)

internal fun GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.toPlayerOutcome(): PlayerDownloadedMetadataSyncOutcome =
    when (this) {
        GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.SUCCESS -> PlayerDownloadedMetadataSyncOutcome.SUCCESS
        GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED -> PlayerDownloadedMetadataSyncOutcome.NOT_DOWNLOADED
        GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.FAILED -> PlayerDownloadedMetadataSyncOutcome.FAILED
    }
