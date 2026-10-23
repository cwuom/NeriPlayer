package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.service.lyrics.LiveLyricNotificationBridge
import moe.ouom.neriplayer.core.player.service.lyrics.XiaomiSuperIslandLyricBridge
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayName

/** 厂商歌词输出随播放器会话初始化和释放，避免保留已取消的协程作用域及上首歌词 */
internal fun PlayerManager.attachLiveLyricSurfaces() {
    colorOsLockscreenLyricsEnabled = false
    xiaomiSuperIslandLyricEnabled = false
    liveUpdateLyricEnabled = false
    val superIsland = XiaomiSuperIslandLyricBridge(application)
    val liveUpdate = LiveLyricNotificationBridge(application)
    xiaomiSuperIslandLyricBridge = superIsland
    liveLyricNotificationBridge = liveUpdate

    ioScope.launch {
        settingsRepo.colorOsLockscreenLyricsEnabledFlow.collect { enabled ->
            colorOsLockscreenLyricsEnabled = enabled
            syncExternalBluetoothLyrics(_currentSongFlow.value)
        }
    }
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
    colorOsLyricTimelineOwner.begin(null)
    externalLyricRequest = null
    externalTranslationRequestId++
    colorOsLockscreenLyricsEnabled = false
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
    // 与歌词页、蓝牙歌词一致：偏移为正表示歌词提前显示
    val lyricPositionMs = positionMs + lyricOffsetMs
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
