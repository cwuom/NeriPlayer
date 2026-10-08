package moe.ouom.neriplayer.core.player.testing

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import moe.ouom.neriplayer.core.player.PlayerManager
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Mockito-backed ExoPlayer whose observable state is held in plain fields so tests can drive
 * PlayerManager code paths and assert the commands it issued.
 */
internal class FakeExoPlayer {
    var playbackState = Player.STATE_IDLE
    var playWhenReady = false
    var isPlaying = false
    var positionMs = 0L
    var bufferedDurationMs = 0L
    var durationMs = 0L
    var volume = 1f
    var mediaItem: MediaItem? = MediaItem.Builder().setMediaId("fake-media").build()
    var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    val commands = mutableListOf<String>()

    val player: ExoPlayer = mock(ExoPlayer::class.java).also { player ->
        `when`(player.playbackState).thenAnswer { playbackState }
        `when`(player.playWhenReady).thenAnswer { playWhenReady }
        `when`(player.isPlaying).thenAnswer { isPlaying }
        `when`(player.currentPosition).thenAnswer { positionMs }
        `when`(player.totalBufferedDuration).thenAnswer { bufferedDurationMs }
        `when`(player.duration).thenAnswer { durationMs }
        `when`(player.volume).thenAnswer { volume }
        `when`(player.currentMediaItem).thenAnswer { mediaItem }
        `when`(player.mediaItemCount).thenAnswer { if (mediaItem == null) 0 else 1 }
        `when`(player.currentMediaItemIndex).thenReturn(0)
        `when`(player.trackSelectionParameters).thenAnswer { trackSelectionParameters }
        doAnswer { invocation ->
            trackSelectionParameters = invocation.getArgument(0)
            commands += "trackSelectionParameters"
            null
        }.`when`(player).trackSelectionParameters = any()
        doAnswer { invocation ->
            playWhenReady = invocation.getArgument(0)
            commands += "playWhenReady=$playWhenReady"
            null
        }.`when`(player).playWhenReady = anyBoolean()
        doAnswer { invocation ->
            volume = invocation.getArgument(0)
            null
        }.`when`(player).volume = anyFloat()
        doAnswer {
            playbackState = Player.STATE_IDLE
            isPlaying = false
            commands += "stop"
            null
        }.`when`(player).stop()
        doAnswer {
            playbackState = Player.STATE_BUFFERING
            commands += "prepare"
            null
        }.`when`(player).prepare()
        doAnswer {
            playWhenReady = true
            commands += "play"
            null
        }.`when`(player).play()
        doAnswer {
            playWhenReady = false
            isPlaying = false
            commands += "pause"
            null
        }.`when`(player).pause()
        doAnswer { invocation ->
            positionMs = invocation.getArgument(1)
            commands += "seekTo(${invocation.getArgument<Int>(0)},$positionMs)"
            null
        }.`when`(player).seekTo(anyInt(), anyLong())
        doAnswer { invocation ->
            positionMs = invocation.getArgument(0)
            commands += "seekTo($positionMs)"
            null
        }.`when`(player).seekTo(anyLong())
    }

    /**
     * Installs this player and [application] into the PlayerManager singleton for the duration
     * of [block], then restores the previous (possibly uninitialized) values.
     */
    fun <T> installInto(
        manager: PlayerManager,
        application: Application = PlayerTestEnvironment.application,
        block: () -> T
    ): T {
        // lateinit 字段无法通过公开 API 复位, 只在恢复全局单例状态时使用反射
        val playerField = PlayerManager::class.java.getDeclaredField("player").apply { isAccessible = true }
        val applicationField = PlayerManager::class.java.getDeclaredField("application")
            .apply { isAccessible = true }
        val previousPlayer = playerField.get(null)
        val previousApplication = applicationField.get(null)
        manager.player = player
        manager.application = application
        return try {
            block()
        } finally {
            playerField.set(null, previousPlayer)
            applicationField.set(null, previousApplication)
        }
    }
}
