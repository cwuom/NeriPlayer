package moe.ouom.neriplayer.core.player.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.lyrics.parser.toEditableLyricsText

internal data class SelectedSongMetadataLyrics(
    val original: String? = null,
    val translated: String? = null,
    val romanized: String? = null,
    val source: MusicPlatform? = null,
    val songId: String? = null
) {
    val hasUpdates: Boolean get() = original != null || translated != null || romanized != null
}

internal suspend fun fetchSelectedSongMetadataLyrics(
    originalSong: SongItem,
    selectedSong: SongSearchInfo,
    details: SongDetails,
    options: SongMetadataApplyOptions,
    matchLyrics: suspend (EditableLyricMatchRequest) -> List<RankedEditableLyricMatch>,
    loadRomanized: suspend (Long) -> List<LyricEntry>
): SelectedSongMetadataLyrics {
    if (!options.fetchesLyrics) return SelectedSongMetadataLyrics()
    val duration = originalSong.durationMs.takeIf { it > 0L } ?: selectedSong.duration.toDurationMs()
    val request = EditableLyricMatchRequest(
        keyword = "${details.songName} ${details.singer}".trim(),
        trackName = details.songName,
        artistName = details.singer,
        albumName = details.album,
        durationMs = duration,
        preferWordTimed = options.preferWordTimed,
        sources = options.lyricSources.toSet()
    )
    val matches = try {
        matchLyrics(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    currentCoroutineContext().ensureActive()
    var result = SelectedSongMetadataLyrics()
    val romanizedLookups = mutableSetOf<Long>()
    for (match in matches) {
        currentCoroutineContext().ensureActive()
        if (match.candidate.source !in options.lyricSources) continue
        val resolved = PlayerLyricsProvider.selectFirstUsableAutomaticExternalLyrics(
            expectedDurationMs = duration,
            expectedTitle = details.songName,
            expectedArtist = details.singer,
            matches = listOf(match)
        ) ?: continue
        val original = resolved.lyrics.toEditableLyricsText().takeIf(String::isNotBlank)
        val translated = resolved.translatedLyrics.ifEmpty {
            resolved.lyrics.mapNotNull { entry ->
                entry.translation?.takeIf(String::isNotBlank)?.let { translation ->
                    entry.copy(text = translation, words = null, translation = null)
                }
            }
        }.toEditableLyricsText().takeIf(String::isNotBlank)
        val romanized = if (options.romanizedLyrics && result.romanized == null) {
            resolved.romanizedLyrics.ifEmpty {
                resolved.neteaseSongId?.takeIf(romanizedLookups::add)?.let { loadRomanized(it) }.orEmpty()
            }.toEditableLyricsText().takeIf(String::isNotBlank)
        } else null
        val next = result.copy(
            original = result.original ?: original.takeIf { options.originalLyrics },
            translated = result.translated ?: translated.takeIf { options.translatedLyrics },
            romanized = result.romanized ?: romanized
        )
        if (!result.hasUpdates && next.hasUpdates) {
            val platform = when (match.candidate.source) {
                EditableLyricMatchSource.CLOUD_MUSIC -> MusicPlatform.CLOUD_MUSIC
                EditableLyricMatchSource.QQ_MUSIC -> MusicPlatform.QQ_MUSIC
                else -> null
            }
            result = next.copy(source = platform, songId = match.candidate.id.takeIf { platform != null })
        } else {
            result = next
        }
        if ((!options.originalLyrics || result.original != null) &&
            (!options.translatedLyrics || result.translated != null) &&
            (!options.romanizedLyrics || result.romanized != null)
        ) break
    }
    currentCoroutineContext().ensureActive()
    return result
}

private fun String.toDurationMs(): Long {
    val parts = trim().split(':').map { it.toLongOrNull() ?: return 0L }
    if (parts.size !in 2..3 || parts.any { it < 0L }) return 0L
    return parts.fold(0L) { seconds, part -> seconds * 60L + part } * 1_000L
}

internal fun applySelectedSongMetadata(
    originalSong: SongItem,
    selectedSong: SongSearchInfo,
    details: SongDetails,
    coverReference: String?,
    lyrics: SelectedSongMetadataLyrics,
    useCustomOverride: Boolean,
    lyricRevision: Long,
    captureMatchedOriginals: Boolean
): SongItem {
    val updated = applyManualSearchMetadata(
        originalSong = originalSong,
        songName = details.songName,
        singer = details.singer,
        coverUrl = coverReference,
        lyric = null,
        translatedLyric = null,
        matchedSource = selectedSong.source,
        matchedSongId = selectedSong.id,
        useCustomOverride = useCustomOverride,
        preserveExistingMatchedLyrics = true,
        lyricRevision = lyricRevision,
        captureMatchedOriginals = captureMatchedOriginals
    )
    return updated.copy(
        matchedLyric = lyrics.original ?: originalSong.matchedLyric,
        matchedTranslatedLyric = lyrics.translated ?: originalSong.matchedTranslatedLyric,
        matchedRomanizedLyric = lyrics.romanized ?: originalSong.matchedRomanizedLyric,
        lyricSyncEdited = if (lyrics.hasUpdates) true else originalSong.lyricSyncEdited,
        lyricSyncRevision = if (lyrics.hasUpdates) lyricRevision else originalSong.lyricSyncRevision,
        matchedLyricSource = if (lyrics.hasUpdates) lyrics.source else originalSong.matchedLyricSource,
        matchedSongId = if (lyrics.hasUpdates) lyrics.songId else originalSong.matchedSongId
    )
}
