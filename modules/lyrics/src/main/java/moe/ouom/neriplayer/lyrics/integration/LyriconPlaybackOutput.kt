package moe.ouom.neriplayer.lyrics.integration

import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

class LyriconPlaybackOutput internal constructor(
    loader: LyriconLyricsLoader,
    stableKey: (SongItem) -> String,
    sameIdentity: (SongItem, SongItem?) -> Boolean,
    sink: LyriconOutputSink,
) {
    constructor(
        loader: LyriconLyricsLoader,
        stableKey: (SongItem) -> String,
        sameIdentity: (SongItem, SongItem?) -> Boolean,
    ) : this(loader, stableKey, sameIdentity, LyriconManagerOutputSink())

    private val controller = LyriconOutputController(loader, sink, stableKey, sameIdentity)

    fun publishSong(song: SongItem?) = controller.publishSong(song)

    fun syncSong(scope: CoroutineScope, song: SongItem?, preferences: LyriconPreferences, lyricOffsetOverrideMs: Long? = null) =
        controller.syncSong(scope, song, preferences, lyricOffsetOverrideMs)

    fun updateOffset(song: SongItem?, preferences: LyriconPreferences, positionMs: Long) =
        controller.updateOffset(song, preferences, positionMs)

    fun cancel() = controller.cancel()

    fun hasPendingUpdate(): Boolean = controller.hasPendingUpdate()
}

internal class LyriconManagerOutputSink(private val manager: LyriconManager = LyriconManager) : LyriconOutputSink {
    override fun updateSong(song: SongItem, lyrics: List<LyricEntry>?, translatedLyrics: List<LyricEntry>?, lyricOffsetMs: Long) =
        manager.updateSong(song, lyrics, translatedLyrics, lyricOffsetMs)

    override fun setPlaybackState(playing: Boolean) = manager.setPlaybackState(playing)

    override fun setLyricOffset(offsetMs: Long) = manager.setLyricOffset(offsetMs)

    override fun setPosition(positionMs: Long) = manager.setPosition(positionMs)
}
