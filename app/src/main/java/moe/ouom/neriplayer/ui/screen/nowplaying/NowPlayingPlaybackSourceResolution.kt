package moe.ouom.neriplayer.ui.screen.nowplaying

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType

/**
 * 播放页来源角标的判定：逻辑音源优先，其次看当前媒体地址与网易云 / B 站来源标记。
 *
 * 绑定只在「还没有实测音源」时兜底显示哔哩哔哩：一旦解析出了具体来源就以它为准。
 * 否则绑定优先级关闭、或分 P 失效回退到网易云时，角标会把实际在放的音源标错。
 */
internal fun resolveNowPlayingPlaybackSourceType(
    isLocalSong: Boolean,
    isYouTubeMusicSong: Boolean,
    isFromNeteaseTag: Boolean,
    isFromBiliTag: Boolean,
    currentMediaUrl: String?,
    playbackAudioSource: PlaybackAudioSource?,
    isNeteaseLocalFallback: Boolean = false,
    hasBiliCollectionBinding: Boolean = false
): PlaybackSourceType? {
    if (isLocalSong || isNeteaseLocalFallback) return PlaybackSourceType.LOCAL

    val resolved = when (playbackAudioSource) {
        PlaybackAudioSource.SUBSONIC -> PlaybackSourceType.SUBSONIC
        PlaybackAudioSource.NETEASE -> PlaybackSourceType.NETEASE
        PlaybackAudioSource.BILIBILI -> PlaybackSourceType.BILIBILI
        PlaybackAudioSource.YOUTUBE_MUSIC -> PlaybackSourceType.YOUTUBE_MUSIC
        PlaybackAudioSource.LOCAL,
        null -> {
            if (isYouTubeMusicSong) {
                PlaybackSourceType.YOUTUBE_MUSIC
            } else {
                val isFromNeteaseUrl =
                    currentMediaUrl?.contains("music.126.net", ignoreCase = true) == true
                val isFromBiliUrl =
                    currentMediaUrl?.contains("bilivideo.", ignoreCase = true) == true
                when {
                    isFromBiliTag || (!isFromNeteaseTag && isFromBiliUrl) ->
                        PlaybackSourceType.BILIBILI
                    isFromNeteaseTag || (!isFromBiliTag && isFromNeteaseUrl) ->
                        PlaybackSourceType.NETEASE
                    else -> null
                }
            }
        }
    }
    if (hasBiliCollectionBinding && playbackAudioSource == null) {
        // 只兜底「猜出来的网易云」：来源标记来自歌曲元信息，不代表现在放的就是网易云
        if (resolved == null || resolved == PlaybackSourceType.NETEASE) {
            return PlaybackSourceType.BILIBILI
        }
    }
    return resolved
}
