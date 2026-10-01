package moe.ouom.neriplayer.core.player.integration.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.lyrics.integration.LyriconLyricsLoader

internal object PlayerManagerLyriconLyricsLoader : LyriconLyricsLoader {
    override suspend fun load(
        song: SongItem,
        preferredSource: LyricSourcePreference,
        publish: (List<LyricEntry>, List<LyricEntry>, LyricSourcePreference?) -> Unit,
    ) {
        val preferred = PlayerManager.getPreferredLyricSourceResult(song, preferredSource)
        val lyrics = preferred?.lyrics ?: PlayerManager.getLyrics(song, skipPreferredSource = true)
        currentCoroutineContext().ensureActive()
        val translatedLyrics = preferred?.translatedLyrics
            ?: PlayerManager.getTranslatedLyrics(song, skipPreferredSource = true)
        currentCoroutineContext().ensureActive()
        publish(lyrics, translatedLyrics, preferred?.source)
    }
}
