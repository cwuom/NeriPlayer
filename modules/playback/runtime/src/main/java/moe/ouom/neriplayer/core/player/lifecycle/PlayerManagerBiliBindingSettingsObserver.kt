package moe.ouom.neriplayer.core.player.lifecycle

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

/**
 * 换源与 B 站绑定相关的设置流。
 *
 * 播放热路径只读 [PlayerManager] 上的快照字段，开关变化在这里落地；绑定优先级从关到开时
 * 补一次刷新，让当前这首网易云歌曲立刻换成绑定的分 P。
 */
internal fun PlayerManager.observeBiliBindingPlaybackSettings() {
    ioScope.launch {
        settingsRepo.neteaseAutoSourceQualityProbeFlow.collect { enabled ->
            neteaseAutoSourceQualityProbeEnabled = enabled
        }
    }
    ioScope.launch {
        settingsRepo.biliAlbumBindingPriorityFlow.collect { enabled ->
            val previousEnabled = biliAlbumBindingPriorityEnabled
            biliAlbumBindingPriorityEnabled = enabled
            if (!previousEnabled && enabled) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "bili_collection_binding_priority_enabled"
                )
            }
        }
    }
}
