package moe.ouom.neriplayer.core.player.audio.output

import android.app.Application
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshRequestSemantics
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbSinkRoutePort
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManagerPlaybackQualityPortTest {
    @Test
    fun `local and raw server quality do not invoke any platform settings writer`() = runTest {
        // These sources have no writer in the platform map; reaching it would fail.
        PlayerManagerPlaybackQualityPort.persistPreferredQuality(PlaybackAudioSource.LOCAL, "hires")
        PlayerManagerPlaybackQualityPort.persistPreferredQuality(PlaybackAudioSource.SUBSONIC, "hires")
    }

    @Test
    fun `quality refresh does not resume a stopped USB sink with retained playback intent`() =
        withPlaybackState(playWhenReady = true, isPlaying = true, resumeRequested = true) { player ->
            assertTrue(PlayerManagerUsbSinkRoutePort.stopCurrentSink("usb_permission_granted", true))
            assertTrue(PlayerManager.resumePlaybackRequested)
            assertFalse(player.playWhenReady)
            assertFalse(player.isPlaying)

            val request = refreshRequest()

            assertFalse(request.resumePlaybackAfterRefresh)
            assertEquals(12_345L, request.resumePositionMs)
            assertEquals(12_345L, request.fallbackSeekPositionMs)
            assertTrue(PlayerManager.resumePlaybackRequested)
            verify(player, never()).play()

            assertTrue(PlayerManagerUsbSinkRoutePort.prepareSink(0, 12_345L, true, "usb_permission_granted"))
            assertTrue(player.playWhenReady)
            verify(player).play()
        }

    @Test
    fun `quality refresh preserves active playback`() =
        withPlaybackState(playWhenReady = true, isPlaying = true, resumeRequested = true) {
            assertTrue(refreshRequest().resumePlaybackAfterRefresh)
        }

    @Test
    fun `quality refresh preserves buffering playback requested by the player`() =
        withPlaybackState(playWhenReady = true, isPlaying = false, resumeRequested = true) {
            assertTrue(refreshRequest().resumePlaybackAfterRefresh)
        }

    @Test
    fun `quality refresh keeps user paused playback paused`() =
        withPlaybackState(playWhenReady = false, isPlaying = false, resumeRequested = false) {
            assertFalse(refreshRequest().resumePlaybackAfterRefresh)
        }

    private suspend fun refreshRequest(): RefreshRequestSemantics {
        PlayerManagerPlaybackQualityPort.refreshCurrentSong(PlaybackAudioSource.NETEASE, "quality_changed")
        return requireNotNull(PlayerManager.urlRefreshController.currentSemantics())
    }

    private fun withPlaybackState(
        playWhenReady: Boolean,
        isPlaying: Boolean,
        resumeRequested: Boolean,
        check: suspend (ExoPlayer) -> Unit
    ) = runTest {
        val manager = PlayerManager
        val playerField = PlayerManager::class.java.getDeclaredField("player").apply { isAccessible = true }
        val previousPlayer = playerField.get(null)
        val applicationField = PlayerManager::class.java.getDeclaredField("application").apply { isAccessible = true }
        val previousApplication = applicationField.get(null)
        val previousIoScope = manager.ioScope
        val previousSong = manager._currentSongFlow.value
        val previousAudioInfo = manager._currentPlaybackAudioInfo.value
        val previousResumeRequested = manager.resumePlaybackRequested
        val previousRefreshKey = manager.lastUrlRefreshKey
        val previousRefreshAtMs = manager.lastUrlRefreshAtMs
        val previousMuteVolume = manager.audioRouteMuteRestoreVolume
        val previousExplicitMuteRestore = manager.audioRouteMuteRequiresExplicitRestore
        val previousMuteSuppressed = manager._audioRouteMuteSuppressedFlow.value
        val player = mock(ExoPlayer::class.java)
        var actualPlayWhenReady = playWhenReady
        var actualIsPlaying = isPlaying
        `when`(player.currentPosition).thenReturn(12_345L)
        `when`(player.playWhenReady).thenAnswer { actualPlayWhenReady }
        `when`(player.isPlaying).thenAnswer { actualIsPlaying }
        doAnswer { invocation ->
            actualPlayWhenReady = invocation.getArgument(0)
            null
        }.`when`(player).playWhenReady = anyBoolean()
        doAnswer {
            actualIsPlaying = false
            null
        }.`when`(player).stop()

        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            manager.player = player
            manager.application = mock(Application::class.java)
            // 只检查真实 Port 创建的刷新请求，解析任务留在调度器中并在结束时取消
            manager.ioScope = backgroundScope
            manager._currentSongFlow.value = SongItem(
                id = 450L,
                name = "Quality refresh",
                artist = "Test artist",
                album = "Test album",
                albumId = 0L,
                durationMs = 60_000L,
                coverUrl = null
            )
            manager._currentPlaybackAudioInfo.value = PlaybackAudioInfo(PlaybackAudioSource.NETEASE)
            manager.updateResumePlaybackRequested(resumeRequested)
            manager.audioRouteMuteRestoreVolume = null
            manager.audioRouteMuteRequiresExplicitRestore = false
            check(player)
        } finally {
            manager.urlRefreshController.cancelCurrent()
            manager.ioScope = previousIoScope
            manager._currentSongFlow.value = previousSong
            manager._currentPlaybackAudioInfo.value = previousAudioInfo
            manager.updateResumePlaybackRequested(previousResumeRequested)
            manager.lastUrlRefreshKey = previousRefreshKey
            manager.lastUrlRefreshAtMs = previousRefreshAtMs
            manager.audioRouteMuteRestoreVolume = previousMuteVolume
            manager.audioRouteMuteRequiresExplicitRestore = previousExplicitMuteRestore
            manager._audioRouteMuteSuppressedFlow.value = previousMuteSuppressed
            playerField.set(null, previousPlayer)
            applicationField.set(null, previousApplication)
            Dispatchers.resetMain()
        }
    }
}
