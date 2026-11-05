package moe.ouom.neriplayer.core.player.lifecycle

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider

// 来源偏好变化时同时刷新页面和外部歌词，避免继续沿用旧来源缓存
internal fun PlayerManager.evictLyricCachesForSourcePreferenceChange() {
    PlayerLyricsProvider.clearLyricsCaches(neteaseLyricsCache, ytMusicLyricsCache)
    _lyricsPreferenceRevisionFlow.update { it + 1L }
    syncExternalBluetoothLyrics(_currentSongFlow.value)
}

/**
 * B 站 CC 字幕歌词的开关与轨道偏好。
 *
 * 两者都参与歌词来源决策，变化后清空歌词缓存并让当前歌曲重新取词。
 * 独立成文件是为了让 [PlayerManagerLifecycleExtensions] 保持在源码行数预算内。
 */
internal fun PlayerManager.observeBiliSubtitleLyricsSettings() {
    ioScope.launch {
        settingsRepo.biliCcSubtitleLyricsEnabledFlow.collect { enabled ->
            val changed = biliCcSubtitleLyricsEnabled != enabled
            biliCcSubtitleLyricsEnabled = enabled
            if (changed) {
                evictLyricCachesForSourcePreferenceChange()
                syncLyriconSong(_currentSongFlow.value)
            }
        }
    }
    ioScope.launch {
        settingsRepo.biliCcSubtitleLanguageFlow.collect { language ->
            val changed = biliCcSubtitleLanguage != language
            biliCcSubtitleLanguage = language
            if (changed) {
                evictLyricCachesForSourcePreferenceChange()
                syncLyriconSong(_currentSongFlow.value)
            }
        }
    }
    ioScope.launch {
        settingsRepo.biliCcSubtitleAiOnlyEnabledFlow.collect { enabled ->
            val changed = biliCcSubtitleAiOnlyEnabled != enabled
            biliCcSubtitleAiOnlyEnabled = enabled
            if (changed) {
                evictLyricCachesForSourcePreferenceChange()
                syncLyriconSong(_currentSongFlow.value)
            }
        }
    }
    ioScope.launch {
        settingsRepo.biliCcSubtitleLyricDefaultOffsetMsFlow.collect { offsetMs ->
            biliCcSubtitleLyricDefaultOffsetMs = offsetMs
            updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            updateLyriconLyricOffset()
        }
    }
}
