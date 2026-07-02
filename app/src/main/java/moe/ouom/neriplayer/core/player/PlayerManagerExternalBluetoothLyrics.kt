package moe.ouom.neriplayer.core.player

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.metadata.findExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.metadata.findFloatingTranslatedLyricLine
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.resolveLyricDefaultOffsetMs
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger

internal fun PlayerManager.syncExternalBluetoothLyrics(song: SongItem?) {
    externalBluetoothLyricsLoadJob?.cancel()
    externalBluetoothLyricsLoadJob = null
    externalBluetoothLyrics = emptyList()
    floatingTranslatedLyrics = emptyList()
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
        val translatedLyrics = if (floatingLyricsShowTranslation) {
            runCatching { getTranslatedLyrics(song) }
                .onFailure { error ->
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "floating lyrics translation load failed: song=${song.name}/${song.id}",
                        error
                    )
                }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val currentSong = _currentSongFlow.value
        if (!shouldProvideExternalLyricLine() || currentSong?.sameIdentityAs(song) != true) {
            return@launch
        }

        externalBluetoothLyricsSongKey = songKey
        externalBluetoothLyrics = lyrics
        floatingTranslatedLyrics = translatedLyrics
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
    val translatedLine = findFloatingTranslatedLyricLine(
        lyrics = externalBluetoothLyrics,
        translations = floatingTranslatedLyrics,
        positionMs = positionMs,
        lyricOffsetMs = lyricOffsetMs
    )

    if (_externalBluetoothLyricLineFlow.value != line) {
        _externalBluetoothLyricLineFlow.value = line
    }
    if (_floatingTranslatedLyricLineFlow.value != translatedLine) {
        _floatingTranslatedLyricLineFlow.value = translatedLine
    }
}

internal fun PlayerManager.clearExternalBluetoothLyricLine() {
    if (_externalBluetoothLyricLineFlow.value != null) {
        _externalBluetoothLyricLineFlow.value = null
    }
    if (_floatingTranslatedLyricLineFlow.value != null) {
        _floatingTranslatedLyricLineFlow.value = null
    }
}

private fun PlayerManager.shouldProvideExternalLyricLine(): Boolean {
    return externalBluetoothLyricsEnabled || statusBarLyricsEnable || floatingLyricsEnabled
}
