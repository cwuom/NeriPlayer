package moe.ouom.neriplayer.core.player.report

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliPlayHistoryTarget
import moe.ouom.neriplayer.platform.bilibili.history.resolveBiliPlayHistoryTarget

/**
 * 以观察者身份订阅播放状态，不改动播放核心；两个开关都关闭时不收集进度，也不产生任何请求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun PlayerManager.observePlatformPlayHistorySync() {
    val scope = ioScope
    val flagsFlow = combine(
        settingsRepo.settingFlow(AutoSettingsSchema.playback.neteasePlayHistorySyncEnabled),
        settingsRepo.settingFlow(AutoSettingsSchema.playback.biliPlayHistorySyncEnabled),
        ::PlayHistorySyncFlags
    ).distinctUntilChanged()
    scope.launch {
        var reporter: PlatformPlayHistoryReporter? = null
        flagsFlow.flatMapLatest { flags ->
            if (!flags.any) {
                reporter?.close()
                reporter = null
                return@flatMapLatest emptyFlow()
            }
            val active = reporter ?: PlatformPlayHistoryReporter(
                scope = scope,
                port = AppPlatformPlayHistoryPort,
                songKey = AppQueueSongIdentity::stableKey
            ).also { reporter = it }
            active.updateFlags(flags)
            combine(currentSongFlow, isPlayingFlow, playbackPositionFlow) { song, playing, positionMs ->
                PlayerSample(active, song, playing, positionMs)
            }
        }.collect { sample ->
            sample.reporter.onPlayerState(sample.song, sample.playing, sample.positionMs)
        }
    }
}

private class PlayerSample(
    val reporter: PlatformPlayHistoryReporter,
    val song: SongItem?,
    val playing: Boolean,
    val positionMs: Long
)

private object AppPlatformPlayHistoryPort : PlatformPlayHistoryPort {
    private val repositories get() = PlayerDependencies.repositories

    override fun neteaseSignedIn(): Boolean = repositories.neteaseClient.hasLogin()

    override suspend fun biliSignedIn(): Boolean = repositories.biliClient.hasPlayHistoryLogin()

    override suspend fun scrobbleNetease(songId: Long, listenedSec: Long) {
        repositories.neteaseClient.scrobbleSongCancellable(songId, listenedSec)
    }

    override suspend fun resolveBiliTarget(song: SongItem): BiliPlayHistoryTarget? =
        resolveBiliPlayHistoryTarget(song, repositories.biliClient)

    override suspend fun reportBiliProgress(target: BiliPlayHistoryTarget, playedSec: Long) {
        repositories.biliClient.reportPlayHeartbeat(target, playedSec)
    }
}
