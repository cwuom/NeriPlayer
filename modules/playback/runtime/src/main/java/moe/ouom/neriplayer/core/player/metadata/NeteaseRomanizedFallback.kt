package moe.ouom.neriplayer.core.player.metadata

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient

internal suspend fun loadNeteaseRomanizedFallback(
    song: SongItem,
    preferWordTimedLyrics: Boolean,
    matcher: EditableLyricsMatcher,
    neteaseClient: NeteaseClient,
    neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
    matchedNeteaseSongId: Long? = null
): List<LyricEntry> {
    if (song.lyricSyncEdited == true && song.matchedRomanizedLyric != null) return emptyList()
    (matchedNeteaseSongId ?: resolveKnownNeteaseLyricSongId(song))?.let { songId ->
        return PlayerLyricsProvider.getNeteaseRomanizedLyrics(songId, neteaseClient, neteaseLyricsCache)
    }

    val request = EditableLyricMatchRequest(
        keyword = listOf(song.name, song.artist).filter(String::isNotBlank).joinToString(" "),
        trackName = song.name,
        artistName = song.artist,
        albumName = song.album,
        durationMs = song.durationMs,
        preferWordTimed = preferWordTimedLyrics,
        sources = setOf(EditableLyricMatchSource.CLOUD_MUSIC)
    )
    val matches = try {
        matcher.matchHighConfidenceLyricsForSource(
            request,
            EditableLyricMatchSource.CLOUD_MUSIC
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NPLogger.d("NERI-PlayerManager", "网易云音译回退匹配失败: ${error.message}")
        return emptyList()
    }
    for (match in matches) {
        val candidate = match.candidate
        if (candidate.source != EditableLyricMatchSource.CLOUD_MUSIC) continue
        val songId = candidate.id.toLongOrNull()?.takeIf { it > 0L } ?: continue
        val reliable = PlayerLyricsProvider.selectFirstUsableAutomaticExternalLyrics(
            expectedDurationMs = song.durationMs,
            expectedTitle = song.name,
            expectedArtist = song.artist,
            matches = listOf(match)
        )
        if (reliable == null) continue
        val romanized = PlayerLyricsProvider.getNeteaseRomanizedLyrics(songId, neteaseClient, neteaseLyricsCache)
        if (romanized.isNotEmpty()) return romanized
    }
    return emptyList()
}

fun resolveKnownNeteaseLyricSongId(song: SongItem): Long? {
    // 只有网易云来源的匹配 ID 才能直接用于取词，其他平台的数字 ID 需要重新匹配
    if (song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC) {
        song.matchedSongId?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
    }
    val directNetease = song.album.startsWith(SongSourceTags.NETEASE) ||
        song.channelId.equals("netease", ignoreCase = true) ||
        song.mediaUri?.startsWith("https://music.163.com/") == true
    return song.id.takeIf { it > 0L && directNetease }
}
