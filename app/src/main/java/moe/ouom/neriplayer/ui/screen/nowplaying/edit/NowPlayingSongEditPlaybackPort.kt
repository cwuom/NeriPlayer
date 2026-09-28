package moe.ouom.neriplayer.ui.screen.nowplaying.edit

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem

internal data class SongEditLyricsWrite(
    val song: SongItem,
    val lyric: String?,
    val translatedLyric: String?,
    val romanizedLyric: String?,
    val writeLocalMetadata: Boolean,
    val persistLocalSidecars: Boolean
)

internal interface NowPlayingSongEditPlaybackPort {
    fun currentSong(): SongItem?
    suspend fun saveLyrics(write: SongEditLyricsWrite): Boolean
}

internal object PlayerManagerNowPlayingSongEditPlaybackPort : NowPlayingSongEditPlaybackPort {
    override fun currentSong(): SongItem? = PlayerManager.currentSongFlow.value

    override suspend fun saveLyrics(write: SongEditLyricsWrite): Boolean =
        PlayerManager.updateSongLyricsAndTranslation(
            songToUpdate = write.song,
            newLyrics = write.lyric,
            newTranslatedLyrics = write.translatedLyric,
            newRomanizedLyrics = write.romanizedLyric,
            writeLocalMetadata = write.writeLocalMetadata,
            persistLocalSidecars = write.persistLocalSidecars,
            syncDownloadedMetadata = false
        )
}
