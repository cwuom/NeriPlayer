package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.findExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.metadata.findFloatingTranslatedLyricLine
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.resolveLyricDefaultOffsetMs
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger

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
        val translatedLyrics = if (floatingLyricsEnabled && floatingLyricsShowTranslation) {
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
        floatingTranslatedLyrics = if (floatingLyricsEnabled && floatingLyricsShowTranslation) {
            translatedLyrics
        } else {
            emptyList()
        }
        updateExternalBluetoothLyricLine(_playbackPositionMs.value)
    }
}

internal fun PlayerManager.syncFloatingTranslatedLyrics(song: SongItem?) {
    if (!floatingLyricsEnabled || !floatingLyricsShowTranslation) {
        clearFloatingTranslatedLyricLine()
        return
    }
    if (!shouldProvideExternalLyricLine() || song == null) {
        clearFloatingTranslatedLyricLine()
        return
    }

    val songKey = song.stableKey()
    if (externalBluetoothLyricsSongKey != songKey || externalBluetoothLyrics.isEmpty()) {
        syncExternalBluetoothLyrics(song)
        return
    }

    ioScope.launch {
        val translatedLyrics = runCatching { getTranslatedLyrics(song) }
            .onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "floating lyrics translation load failed: song=${song.name}/${song.id}",
                    error
                )
            }
            .getOrDefault(emptyList())

        val currentSong = _currentSongFlow.value
        if (
            !floatingLyricsEnabled ||
            !floatingLyricsShowTranslation ||
            currentSong?.sameIdentityAs(song) != true ||
            externalBluetoothLyricsSongKey != songKey
        ) {
            return@launch
        }

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
    clearFloatingTranslatedLyricLine()
}

private fun PlayerManager.clearFloatingTranslatedLyricLine() {
    floatingTranslatedLyrics = emptyList()
    if (_floatingTranslatedLyricLineFlow.value != null) {
        _floatingTranslatedLyricLineFlow.value = null
    }
}

private fun PlayerManager.shouldProvideExternalLyricLine(): Boolean {
    return externalBluetoothLyricsEnabled || statusBarLyricsEnable || floatingLyricsEnabled
}
