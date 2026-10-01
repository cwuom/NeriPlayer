package moe.ouom.neriplayer.lyrics.output

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

fun interface LyriconLyricsLoader {
    suspend fun load(
        song: SongItem,
        preferredSource: LyricSourcePreference,
        publish: (List<LyricEntry>, List<LyricEntry>, LyricSourcePreference?) -> Unit,
    )
}
