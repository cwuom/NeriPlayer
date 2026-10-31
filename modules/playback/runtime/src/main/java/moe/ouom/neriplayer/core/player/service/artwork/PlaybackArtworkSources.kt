package moe.ouom.neriplayer.core.player.service.artwork

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.net.toUri
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.isUsableCoverReference
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayCoverUrl

private const val MEDIA_ARTWORK_SIZE_PX = 512

internal class DownloadedArtworkReference(val coverPath: String?, val coverUrl: String?)

internal interface PlaybackCoverSources {
    fun isLocal(song: SongItem): Boolean
    fun immediate(song: SongItem): String?
    fun peekLocal(song: SongItem): String?
    fun nearby(song: SongItem): String?
    fun resolveLocal(song: SongItem): String?
    fun downloaded(song: SongItem): DownloadedArtworkReference?
    suspend fun rebind(fileName: String, forceRefresh: Boolean): String?
    fun isUsable(reference: String): Boolean
}

internal fun interface PlaybackArtworkBitmapLoader {
    suspend fun load(source: String): Bitmap?
}

internal fun interface PlaybackArtworkClock {
    fun elapsedRealtime(): Long
}

internal class AndroidPlaybackCoverSources(private val context: Context) : PlaybackCoverSources {
    override fun isLocal(song: SongItem): Boolean = LocalSongSupport.isLocalSong(song, context)

    override fun immediate(song: SongItem): String? {
        val local = isLocal(song)
        val source = resolveImmediateArtworkSource(
            isLocal = local,
            customCover = song.customCoverUrl,
            isDirectoryReference = CustomSongCoverStorage::isDirectoryReference,
            localCover = { runCatching { PlayerDependencies.downloads.peekLocalCoverUri(song) }.getOrNull() },
            displayedCover = { song.displayCoverUrl(context) },
        )
        // 已下载音频可能只有内嵌封面，让后台解析完成后再考虑平台图片
        if (
            !local && resolveRemoteMetadataArtworkUri(source) != null &&
            resolveRemoteMetadataArtworkUri(song.customCoverUrl) == null &&
            PlayerDependencies.downloads.hasDownloadedSongCached(song)
        ) return null
        return source
    }

    override fun peekLocal(song: SongItem): String? = PlayerDependencies.downloads.peekLocalCoverUri(song)

    override fun nearby(song: SongItem): String? = LocalMediaSupport.resolveNearbyCoverUri(context, song)

    override fun resolveLocal(song: SongItem): String? {
        LocalMediaSupport.resolveCoverUri(context, song)?.let { return it }
        if (LocalSongSupport.validExplicitLocalSourceReference(song) != null) return null
        val downloadedSource = PlayerDependencies.downloads.getLocalPlaybackUri(context, song)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return LocalMediaSupport.resolveCoverUri(context, downloadedSource.toUri())
    }

    override fun downloaded(song: SongItem): DownloadedArtworkReference? =
        PlayerDependencies.downloads.findDownloadedSongCached(song)?.let {
            DownloadedArtworkReference(it.coverPath, it.coverUrl)
        }

    override suspend fun rebind(fileName: String, forceRefresh: Boolean): String? =
        PlayerDependencies.downloads.findCoverReferenceByFileName(
            context = context,
            fileName = fileName,
            forceRefresh = forceRefresh,
            preferSidecarRefresh = forceRefresh,
        )

    override fun isUsable(reference: String): Boolean = isUsableCoverReference(context, reference)
}

internal class CoilPlaybackArtworkBitmapLoader(private val context: Context) : PlaybackArtworkBitmapLoader {
    override suspend fun load(source: String): Bitmap? =
        PlayerDependencies.presentation.loadArtwork(context, source, MEDIA_ARTWORK_SIZE_PX)
}

internal object AndroidPlaybackArtworkClock : PlaybackArtworkClock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
