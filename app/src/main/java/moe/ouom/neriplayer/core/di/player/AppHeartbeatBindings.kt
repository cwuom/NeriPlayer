package moe.ouom.neriplayer.core.di.player

import android.app.Application
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatCandidateCache
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackCoordinator
import moe.ouom.neriplayer.core.player.heartbeat.LocalHeartbeatCandidateProvider
import moe.ouom.neriplayer.core.player.queue.heartbeat.LocalHeartbeatCandidateSource
import moe.ouom.neriplayer.core.player.heartbeat.OfficialHeartbeatSource
import moe.ouom.neriplayer.core.player.heartbeat.installHeartbeatOfficialRepository
import moe.ouom.neriplayer.core.player.heartbeat.installHeartbeatSelector
import moe.ouom.neriplayer.core.player.heartbeat.installHeartbeatSkipRecorder
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
    // 「不喜欢」名单：网易云没有对应接口，只能本地存；官方源与本地源共用这一份。
    val dislikedSongRepository = AppContainer.dislikedSongRepo
    // 跳过次数：任务书 §七 第三阶段「记录用户跳过行为」，只并入重复惩罚，不做硬排除。
    val skipRepository = AppContainer.heartbeatSkipRepo
    installHeartbeatSkipRecorder { identityKey -> skipRepository.recordSkip(identityKey) }

    // 官方源：网易云心动接口。未登录或接口不可用时它自行返回 null，交棒给本地降级。
    val officialRepository = AppContainer.heartbeatOfficialRepository
    installHeartbeatOfficialRepository(officialRepository)
    val officialSource = OfficialHeartbeatSource(
        repository = officialRepository,
        dislikedSongRepository = dislikedSongRepository,
    )

    // 本地降级源：收藏 + 历史 + 统计库（层级 3/4 原料与播放次数）。
    val candidateProvider = LocalHeartbeatCandidateProvider(
        playHistoryRepository = PlayHistoryRepository.getInstance(application),
        localPlaylistRepository = LocalPlaylistRepository.getInstance(application),
        context = application,
        playbackStatsRepository = AppContainer.playbackStatsRepo,
        dislikedSongRepository = dislikedSongRepository,
        heartbeatSkipRepository = skipRepository,
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
