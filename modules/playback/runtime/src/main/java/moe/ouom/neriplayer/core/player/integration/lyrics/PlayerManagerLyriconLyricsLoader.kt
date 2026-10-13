package moe.ouom.neriplayer.core.player.integration.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.isServerSong
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.lyrics.output.LyriconLyricsLoader

internal object PlayerManagerLyriconLyricsLoader : LyriconLyricsLoader {
    override suspend fun load(
        song: SongItem,
        preferredSource: LyricSourcePreference,
        publish: (List<LyricEntry>, List<LyricEntry>, LyricSourcePreference?) -> Unit,
    ) {
        val preferred = PlayerManager.getPreferredLyricSourceResult(song, preferredSource)
        val lyrics = try {
            preferred?.lyrics ?: PlayerManager.getLyrics(song, skipPreferredSource = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!song.isServerSong()) throw error
            // The playback page owns retry feedback. A server outage must not crash this output job.
            return
        }
        currentCoroutineContext().ensureActive()
        val translatedLyrics = preferred?.translatedLyrics
            ?: PlayerManager.getTranslatedLyrics(song, skipPreferredSource = true)
        currentCoroutineContext().ensureActive()
        publish(lyrics, translatedLyrics, preferred?.source)
    }
}
