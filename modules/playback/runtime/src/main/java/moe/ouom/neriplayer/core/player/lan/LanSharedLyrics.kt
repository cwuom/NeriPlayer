package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming

/** Uses the normal local/user-edited/cloud lyric resolution; never reparses into plain LRC. */
internal suspend fun resolveLanSharedLyrics(song: SongItem): LanSharedPresentation {
    val lyrics = PlayerManager.getLyrics(song)
    val translations = PlayerManager.getTranslatedLyrics(song)
    val romanized = PlayerManager.getRomanizedLyrics(song)
    return LanSharedPresentation(song.displayName(), song.displayArtist(), song.displayAlbum(PlayerManager.application),
        song.userLyricOffsetMs, lyrics.map(::shareLanLyricLine), translations.map(::shareLanLyricLine),
        romanized.map(::shareLanLyricLine))
}

internal fun shareLanLyricLine(line: LyricEntry): LanSharedLyricLine = LanSharedLyricLine(
    line.text, line.startTimeMs, line.endTimeMs,
    line.words?.map { LanSharedWordTiming(it.startTimeMs, it.endTimeMs, it.charCount) }, line.translation)

internal fun restoreLanLyricLine(line: LanSharedLyricLine): LyricEntry = LyricEntry(
    line.text, line.startTimeMs, line.endTimeMs,
    line.words?.map { WordTiming(it.startTimeMs, it.endTimeMs, it.charCount) }, line.translation)

/** In-memory session data only. The private received file is never a persisted library item. */
object LanSharedPresentationStore {
    private var key: String? = null
    private var presentation: LanSharedPresentation? = null
    fun publish(trackKey: String, value: LanSharedPresentation) { key = trackKey; presentation = value }
    fun forSong(song: SongItem?): LanSharedPresentation? =
        presentation.takeIf { song?.channelId == "local" && "local:${song.audioId}" == key }
    fun clear() { key = null; presentation = null }
}
