package moe.ouom.neriplayer.core.player

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.metadata.findExternalBluetoothLyricLine
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.resolveLyricDefaultOffsetMs
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger

internal fun PlayerManager.syncExternalBluetoothLyrics(song: SongItem?) {
    externalBluetoothLyricsLoadJob?.cancel()
    externalBluetoothLyricsLoadJob = null
    externalBluetoothLyrics = emptyList()
    externalBluetoothLyricsSongKey = song?.stableKey()
    clearExternalBluetoothLyricLine()

    if (!shouldProvideExternalLyricLine() || song == null) {
        return
    }

    val songKey = song.stableKey()
    externalBluetoothLyricsLoadJob = ioScope.launch {
        val lyrics = runCatching { getLyrics(song) }
            .onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "external bluetooth lyrics load failed: song=${song.name}/${song.id}",
                    error
                )
            }
            .getOrDefault(emptyList())

        val currentSong = _currentSongFlow.value
        if (!shouldProvideExternalLyricLine() || currentSong?.sameIdentityAs(song) != true) {
            return@launch
        }

        externalBluetoothLyricsSongKey = songKey
        externalBluetoothLyrics = lyrics
        updateExternalBluetoothLyricLine(_playbackPositionMs.value)
    }
}

internal fun PlayerManager.updateExternalBluetoothLyricLine(positionMs: Long) {
    if (!shouldProvideExternalLyricLine()) {
        clearExternalBluetoothLyricLine()
        return
    }

    val song = _currentSongFlow.value
    if (song == null || externalBluetoothLyricsSongKey != song.stableKey()) {
        clearExternalBluetoothLyricLine()
        return
    }

    val lyricOffsetMs = resolveLyricDefaultOffsetMs(
        lyricSource = song.matchedLyricSource,
        cloudMusicDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
        qqMusicDefaultOffsetMs = qqMusicLyricDefaultOffsetMs
    ) + song.userLyricOffsetMs

    val line = findExternalBluetoothLyricLine(
        lyrics = externalBluetoothLyrics,
        positionMs = positionMs,
        lyricOffsetMs = lyricOffsetMs
    )

    if (_externalBluetoothLyricLineFlow.value != line) {
        _externalBluetoothLyricLineFlow.value = line
    }
}

internal fun PlayerManager.clearExternalBluetoothLyricLine() {
    if (_externalBluetoothLyricLineFlow.value != null) {
        _externalBluetoothLyricLineFlow.value = null
    }
}

private fun PlayerManager.shouldProvideExternalLyricLine(): Boolean {
    return externalBluetoothLyricsEnabled || statusBarLyricsEnable
}
