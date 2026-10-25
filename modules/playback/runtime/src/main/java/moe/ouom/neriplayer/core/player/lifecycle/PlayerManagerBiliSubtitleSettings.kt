package moe.ouom.neriplayer.core.player.lifecycle

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine

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
