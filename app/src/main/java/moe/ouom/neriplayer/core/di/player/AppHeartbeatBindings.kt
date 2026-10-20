package moe.ouom.neriplayer.core.di.player

import android.app.Application
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatCandidateCache
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackCoordinator
import moe.ouom.neriplayer.core.player.queue.heartbeat.LocalHeartbeatCandidateProvider
import moe.ouom.neriplayer.core.player.queue.heartbeat.LocalHeartbeatCandidateSource
import moe.ouom.neriplayer.core.player.queue.heartbeat.OfficialHeartbeatSource
import moe.ouom.neriplayer.core.player.queue.heartbeat.installHeartbeatOfficialRepository
import moe.ouom.neriplayer.core.player.queue.heartbeat.installHeartbeatSelector
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository

/**
 * 心动模式依赖接线。
 *
 * 分层约束（心动模式任务书 §3.2）：`playback/logic` 只认识
 * [moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackSelector] 抽象，
 * 官方实现与本地降级都在 `playback/runtime`，由本文件在 `app` 侧组装后一次性注入，
 * 从而避免 `logic` 反向依赖 `platform` / `local`。
 */
internal fun installHeartbeatDependencies(application: Application) {
    // 官方源：网易云心动接口。未登录或接口不可用时它自行返回 null，交棒给本地降级。
    val officialRepository = AppContainer.heartbeatOfficialRepository
    installHeartbeatOfficialRepository(officialRepository)
    val officialSource = OfficialHeartbeatSource(officialRepository)

    // 本地降级源：收藏 + 历史 + 统计库（层级 3/4 原料与播放次数）。
    val candidateProvider = LocalHeartbeatCandidateProvider(
        playHistoryRepository = PlayHistoryRepository.getInstance(application),
        localPlaylistRepository = LocalPlaylistRepository.getInstance(application),
        context = application,
        playbackStatsRepository = AppContainer.playbackStatsRepo,
    )
    val localSource = LocalHeartbeatCandidateSource(
        provider = candidateProvider,
        cache = HeartbeatCandidateCache(),
    )

    // 官方优先、本地兜底的组合选择器，同时负责任务书要求的失败熔断。
    installHeartbeatSelector(
        HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
            localSource = localSource,
        )
    )
}
