package moe.ouom.neriplayer.lyrics.integration

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

internal interface LyriconOutputSink {
    fun updateSong(song: SongItem, lyrics: List<LyricEntry>?, translatedLyrics: List<LyricEntry>?, lyricOffsetMs: Long)
    fun setPlaybackState(playing: Boolean)
    fun setLyricOffset(offsetMs: Long)
    fun setPosition(positionMs: Long)
}
