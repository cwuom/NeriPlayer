package moe.ouom.neriplayer.data.lyrics.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.search.NativeLyricSearchApi
import moe.ouom.neriplayer.api.search.client.QQMusicSearchApi
import moe.ouom.neriplayer.api.search.model.QQMusicSongMetadata
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.model.music.SongDetails
import moe.ouom.neriplayer.core.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.lyrics.matching.chooseQQMusicLyrics

class QQMusicLyricsRepository(
    private val api: QQMusicSearchApi,
    private val amllTtmlClient: AmllLyricsRepository,
    private val amllLyricsEnabledProvider: suspend () -> Boolean
) : NativeLyricSearchApi {
    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> =
        api.search(keyword, page)

    override suspend fun getSongInfo(id: String): SongDetails = withContext(Dispatchers.IO) {
        val metadata = api.getSongMetadata(id)
        coroutineScope {
            val lyricDeferred = async { api.getNativeLyrics(id) }
            val amllLyricDeferred = async { fetchAmllWordLyricIfEnabled(metadata) }
            val (qqLyric, qqTranslatedLyric) = lyricDeferred.await()
            val (lyric, translatedLyric) = chooseQQMusicLyrics(
                qqLyric = qqLyric,
                qqTranslatedLyric = qqTranslatedLyric,
                amllLyric = amllLyricDeferred.await()
            )
            metadata.details.copy(lyric = lyric, translatedLyric = translatedLyric)
        }
    }

    override suspend fun getNativeSongInfo(id: String): SongDetails = api.getNativeSongInfo(id)

    private suspend fun fetchAmllWordLyricIfEnabled(metadata: QQMusicSongMetadata): String? {
        return try {
            if (amllLyricsEnabledProvider()) {
                AmllLyricsResolver.loadRawByMetadata(
                    trackName = metadata.details.songName,
                    artistName = metadata.details.singer,
                    durationMs = metadata.durationMs,
                    amllTtmlClient = amllTtmlClient,
                    requireDurationMatch = metadata.durationMs > 0L
                )?.rawLyrics
            } else {
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "AMLL QQ lyric lookup failed: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "QQMusicLyricsRepository"
    }
}
