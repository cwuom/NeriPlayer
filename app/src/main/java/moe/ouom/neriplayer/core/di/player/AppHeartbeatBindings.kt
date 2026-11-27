package moe.ouom.neriplayer.core.di.player

import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.heartbeat.OfficialHeartbeatSource
import moe.ouom.neriplayer.core.player.heartbeat.installHeartbeatOfficialRepository
import moe.ouom.neriplayer.core.player.heartbeat.installHeartbeatSelector
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackCoordinator

/**
 * 心动模式依赖接线：`playback/logic` 只认识选歌抽象，网易云官方源在 `playback/runtime`，
 * 由这里在 `app` 侧组装后一次性注入，避免 `logic` 反向依赖 `platform` / `local`。
 */
internal fun installHeartbeatDependencies() {
    val officialRepository = AppContainer.heartbeatOfficialRepository
    installHeartbeatOfficialRepository(officialRepository)
    // 「不喜欢」名单：网易云没有对应接口，只能本地存，用来略过官方推荐里被标记的歌
    val officialSource = OfficialHeartbeatSource(
        repository = officialRepository,
        dislikedSongRepository = AppContainer.dislikedSongRepo,
    )
    installHeartbeatSelector(HeartbeatNextTrackCoordinator(officialSource = officialSource))
}
