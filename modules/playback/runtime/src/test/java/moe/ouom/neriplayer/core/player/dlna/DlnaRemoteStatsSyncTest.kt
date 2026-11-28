package moe.ouom.neriplayer.core.player.dlna

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.dlna.session.syncDlnaRemotePlaybackStats
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DlnaRemoteStatsSyncTest {

    @Test
    fun remotePlaybackDrivesListeningStats() {
        val player = PlayerManager
        val previousStats = player.playbackStatsOwner
        val stats = mock(PlaybackStatsOwner::class.java)
        try {
            player.playbackStatsOwner = stats

            // 本地 ExoPlayer 回调在投送期间被屏蔽，必须由远端状态开启统计片段并推进进度
            player.syncDlnaRemotePlaybackStats(isPlaying = true, positionMs = 12_000L)
            verify(stats).onPlayingChanged(eqNonNull(true), eqNonNull("dlna_remote"), anyBoolean())
            verify(stats).onProgress(eqNonNull(12_000L), anyBoolean())

            player.syncDlnaRemotePlaybackStats(isPlaying = false, positionMs = 13_000L)
            verify(stats).onPlayingChanged(eqNonNull(false), eqNonNull("dlna_remote"), anyBoolean())
            verify(stats, never()).onProgress(eqNonNull(13_000L), anyBoolean())
        } finally {
            player.playbackStatsOwner = previousStats
        }
    }

    // Mockito 的 eq() 返回 null，传给 Kotlin 非空参数会 NPE；先注册匹配器再返回原值
    private fun <T> eqNonNull(value: T): T = eq(value) ?: value
}
