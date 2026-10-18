package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMetadataSidecar
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.isMediaStoreCoverReference
import moe.ouom.neriplayer.data.model.SongItem

internal fun SongItem.withLocalMetadataOriginals(
    metadata: LocalMetadataSidecar?,
    lyrics: LocalLyricsScanMetadata?
): SongItem {
    val canCaptureLocalLyrics = lyricSyncEdited != true
    val resolvedLyrics = lyrics?.takeIf { canCaptureLocalLyrics && it.sourceResolved }
    return copy(
        originalName = metadata?.originalName ?: originalName,
        originalArtist = metadata?.originalArtist ?: originalArtist,
        originalCoverUrl = metadata?.originalCoverUrl ?: originalCoverUrl,
        originalLyric = metadata?.originalLyric ?: originalLyric
            ?: resolvedLyrics?.originalLyricBaseline(resolvedLyrics.lyric, resolvedLyrics.hasOriginalSidecar)
            ?: matchedLyric.takeIf { canCaptureLocalLyrics && it == "" },
        originalTranslatedLyric = metadata?.originalTranslatedLyric ?: originalTranslatedLyric
            ?: resolvedLyrics?.originalLyricBaseline(resolvedLyrics.translatedLyric, resolvedLyrics.hasTranslatedSidecar)
            ?: matchedTranslatedLyric.takeIf { canCaptureLocalLyrics && it == "" },
        originalRomanizedLyric = metadata?.originalRomanizedLyric ?: originalRomanizedLyric
            ?: resolvedLyrics?.originalLyricBaseline(resolvedLyrics.romanizedLyric, resolvedLyrics.hasRomanizedSidecar)
            ?: matchedRomanizedLyric.takeIf { canCaptureLocalLyrics && it == "" }
    )
}

private fun LocalLyricsScanMetadata.originalLyricBaseline(
    scannedLyric: String?,
    hasSidecar: Boolean
): String? = scannedLyric ?: "".takeIf { embeddedSourceResolved && !hasSidecar }

internal fun SongItem.withOriginalLyricsFrom(baseline: SongItem): SongItem = copy(
    originalLyric = baseline.originalLyric ?: originalLyric,
    originalTranslatedLyric = baseline.originalTranslatedLyric ?: originalTranslatedLyric,
    originalRomanizedLyric = baseline.originalRomanizedLyric ?: originalRomanizedLyric
)

internal suspend fun PlayerManager.preserveLocalSongMetadataOriginals(
    song: SongItem,
    preserveCover: Boolean = false
): SongItem = withContext(Dispatchers.IO) {
    if (!LocalSongSupport.isLocalSong(song, application)) return@withContext song
    val localSong = resolveLocalSidecarWriteSong(song) ?: song
    val metadata = LocalMediaSupport.readLocalMetadataSidecarFast(application, localSong)
    val lyrics = if (
        song.lyricSyncEdited != true &&
        (song.originalLyric == null || song.originalTranslatedLyric == null ||
            song.originalRomanizedLyric == null)
    ) {
        try {
            LocalMediaSupport.inspectLyricsFast(
                context = application,
                song = localSong,
                includeStoredFallback = false,
                forceRefresh = true
            )
        } catch (error: SecurityException) {
            NPLogger.w("PlayerManager", "本地原始歌词读取权限不可用: ${error.message}")
            null
        }
    } else {
        null
    }
    val prepared = song.withLocalMetadataOriginals(metadata, lyrics)
    if (!preserveCover || prepared.originalCoverUrl == "") return@withContext prepared
    val originalCover = prepared.originalCoverUrl
        ?.takeUnless(::isMediaStoreCoverReference)
        ?: LocalMediaSupport.resolveNearbyCoverUri(application, localSong)
        ?: LocalMediaSupport.peekCachedEmbeddedCoverUri(application, localSong)
        ?: LocalMediaSupport.resolveCoverUri(application, localSong)
        ?: prepared.coverUrl?.takeUnless(::isMediaStoreCoverReference)
    val preservedCover = CustomSongCoverStorage.persistOriginalCover(
        context = application,
        song = song,
        reference = originalCover
    )
    prepared.copy(originalCoverUrl = preservedCover ?: prepared.originalCoverUrl)
}
