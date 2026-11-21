package moe.ouom.neriplayer.core.player.lyrics

import android.graphics.Bitmap
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.service.lyrics.LiveLyricNotificationBridge
import moe.ouom.neriplayer.core.player.service.lyrics.LiveUpdateFrame
import moe.ouom.neriplayer.core.player.service.lyrics.XiaomiSuperIslandLyricBridge
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayArtist
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
            if (!xiaomiSuperIslandLyricEnabled) return@collect
            if (isPlaying) {
                updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            } else {
                superIsland.onPlaybackPaused()
            }
        }
    }
    mainScope.launch {
        // 缓冲与切歌时引擎会短暂停止，按播放意图判断才不会闪出暂停态
        combine(_isPlayingFlow, _playbackControlPlayingFlow) { engine, control -> engine || control }
            .distinctUntilChanged()
            .collect { playing ->
                if (!liveUpdateLyricEnabled) return@collect
                if (playing) {
                    updateExternalBluetoothLyricLine(_playbackPositionMs.value)
                } else {
                    // 暂停态只更新已显示的 Live Update，由桥接层保留一段时间后收起
                    publishLiveUpdateNowPlaying()
                }
            }
    }
}

private fun PlayerManager.isLiveUpdatePlaying(): Boolean =
    _isPlayingFlow.value || _playbackControlPlayingFlow.value

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
    val superIsland = xiaomiSuperIslandLyricBridge.takeIf { xiaomiSuperIslandLyricEnabled && _isPlayingFlow.value }
    val liveUpdateActive = liveUpdateLyricEnabled && isLiveUpdatePlaying()
    // 暂停中拖动进度也要更新还在保留期内的卡片；桥接层不会因暂停帧新建通知
    if (liveUpdateLyricEnabled && !liveUpdateActive) publishLiveUpdateNowPlaying(song, positionMs)
    if (superIsland == null && !liveUpdateActive) return
    // 与歌词页、蓝牙歌词一致：偏移为正表示歌词提前显示
    val lyricPositionMs = positionMs + lyricOffsetMs
    val lyricIndex = externalBluetoothLyrics.indexOfLast { it.startTimeMs <= lyricPositionMs }
    val currentEntry = externalBluetoothLyrics.getOrNull(lyricIndex)
    if (currentEntry == null) {
        superIsland?.clear()
        // 前奏、间奏或无歌词时仍保留正在播放信息，不让 Live Update 反复消失再出现
        if (liveUpdateActive) publishLiveUpdateNowPlaying(song, positionMs)
        return
    }
    val translation = floatingTranslationMatchesByIndex[lyricIndex]
    superIsland?.sendLyric(
        song = song,
        line = currentEntry,
        translation = translation,
        positionMs = positionMs,
        durationMs = liveSurfaceDurationMs(song)
    )
    if (liveUpdateActive) {
        publishLiveUpdateNowPlaying(
            song = song,
            positionMs = positionMs,
            lyric = currentEntry.text,
            translation = translation?.text ?: currentEntry.translation
        )
    }
}

/** 歌词被清空（切歌、加载中、歌词不可用）时退回歌曲信息；没有当前歌曲才真正收起 */
internal fun PlayerManager.clearLiveUpdateLyric() {
    publishLiveUpdateNowPlaying()
}

internal fun PlayerManager.publishLiveUpdateNowPlaying(
    song: SongItem? = _currentSongFlow.value,
    positionMs: Long = _playbackPositionMs.value,
    lyric: String? = null,
    translation: String? = null,
) {
    val bridge = liveLyricNotificationBridge.takeIf { liveUpdateLyricEnabled } ?: return
    if (song == null) {
        bridge.clear()
        return
    }
    bridge.publish(
        LiveUpdateFrame(
            songKey = song.stableKey(),
            songTitle = song.displayName(),
            artist = song.displayArtist(),
            lyric = lyric,
            translation = translation,
            playing = isLiveUpdatePlaying(),
            advancing = _isPlayingFlow.value,
            positionMs = positionMs,
            durationMs = liveSurfaceDurationMs(song),
            playbackSpeed = playbackSoundStateFlow.value.speed,
        )
    )
}

/**
 * 封面由播放服务的封面管线解析后转交，本地、下载和远程歌曲共用同一来源。
 * 关闭期间也转交：封面只在切歌或播放状态变化时送达，开启后才能立即带上当前封面。
 */
internal fun PlayerManager.updateLiveUpdateArtwork(song: SongItem?, artwork: Bitmap?, coverSource: String?) {
    val bridge = liveLyricNotificationBridge ?: return
    song ?: return
    bridge.setArtwork(song.stableKey(), artwork, coverSource)
}

private fun PlayerManager.liveSurfaceDurationMs(song: SongItem): Long =
    playbackDurationFlow.value.takeIf { it > 0L } ?: song.durationMs
