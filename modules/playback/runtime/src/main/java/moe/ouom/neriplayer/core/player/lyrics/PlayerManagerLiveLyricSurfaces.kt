package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.service.lyrics.LiveLyricNotificationBridge
import moe.ouom.neriplayer.core.player.service.lyrics.XiaomiSuperIslandLyricBridge
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayName

/**
 * Owns the Xiaomi Super Island and Android Live Update lyric surfaces for one PlayerManager
 * session. Bridges are created per initialize() so they never capture a cancelled scope, and
 * they are destroyed on release() so no island, foreground service or XMSF block outlives
 * the player.
 */
internal fun PlayerManager.attachLiveLyricSurfaces() {
    xiaomiSuperIslandLyricEnabled = false
    liveUpdateLyricEnabled = false
    val superIsland = XiaomiSuperIslandLyricBridge(application)
    val liveUpdate = LiveLyricNotificationBridge(application)
    xiaomiSuperIslandLyricBridge = superIsland
    liveLyricNotificationBridge = liveUpdate

    ioScope.launch {
        settingsRepo.liveUpdateLyricEnabledFlow.collect { enabled ->
            liveUpdateLyricEnabled = enabled
            liveUpdate.setEnabled(enabled)
            syncExternalBluetoothLyrics(_currentSongFlow.value)
        }
    }
    ioScope.launch {
        settingsRepo.xiaomiSuperIslandLyricEnabledFlow.collect { enabled ->
            xiaomiSuperIslandLyricEnabled = enabled
            superIsland.setEnabled(enabled)
            syncExternalBluetoothLyrics(_currentSongFlow.value)
        }
    }
    ioScope.launch {
        settingsRepo.xiaomiSuperIslandSettingsFlow.collect(superIsland::setSettings)
    }
    mainScope.launch {
        _isPlayingFlow.collect { isPlaying ->
            if (xiaomiSuperIslandLyricEnabled) {
                if (isPlaying) {
                    updateExternalBluetoothLyricLine(_playbackPositionMs.value)
                } else {
                    superIsland.onPlaybackPaused()
                }
            }
            if (liveUpdateLyricEnabled && !isPlaying) {
                liveUpdate.clear()
            }
        }
    }
}

internal fun PlayerManager.releaseLiveLyricSurfaces() {
    xiaomiSuperIslandLyricEnabled = false
    liveUpdateLyricEnabled = false
    xiaomiSuperIslandLyricBridge?.destroy()
    xiaomiSuperIslandLyricBridge = null
    liveLyricNotificationBridge?.setEnabled(false)
    liveLyricNotificationBridge = null
}

internal fun PlayerManager.onLiveLyricSongChanged() {
    if (xiaomiSuperIslandLyricEnabled) {
        xiaomiSuperIslandLyricBridge?.clear(preserveAggressiveIsolation = _isPlayingFlow.value)
    }
}

internal fun PlayerManager.publishLiveLyricSurfaces(
    song: SongItem,
    positionMs: Long,
    lyricOffsetMs: Long
) {
    if (!(xiaomiSuperIslandLyricEnabled || liveUpdateLyricEnabled) || !_isPlayingFlow.value) return
    val lyricPositionMs = positionMs - lyricOffsetMs
    val lyricIndex = externalBluetoothLyrics.indexOfLast { it.startTimeMs <= lyricPositionMs }
    val currentEntry = externalBluetoothLyrics.getOrNull(lyricIndex)
    val superIsland = xiaomiSuperIslandLyricBridge.takeIf { xiaomiSuperIslandLyricEnabled }
    val liveUpdate = liveLyricNotificationBridge.takeIf { liveUpdateLyricEnabled }
    if (currentEntry == null) {
        superIsland?.clear()
        liveUpdate?.clear()
        return
    }
    val translation = floatingTranslationMatchesByIndex[lyricIndex]
    superIsland?.sendLyric(
        song = song,
        line = currentEntry,
        translation = translation,
        positionMs = positionMs,
        durationMs = playbackDurationFlow.value.takeIf { it > 0L } ?: song.durationMs
    )
    liveUpdate?.sendLyric(
        songTitle = song.displayName(),
        line = currentEntry,
        secondaryLyric = translation?.text ?: currentEntry.translation
    )
}

internal fun PlayerManager.clearLiveUpdateLyric() {
    if (liveUpdateLyricEnabled) liveLyricNotificationBridge?.clear()
}
