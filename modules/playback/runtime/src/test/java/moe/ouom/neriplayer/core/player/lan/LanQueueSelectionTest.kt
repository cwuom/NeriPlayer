package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LanQueueSelectionTest {
    @Test fun selectingLocalQueueItemRoutesThroughLanWithoutChangingIndexBeforeAcceptance() {
        val oldInitialized = PlayerManager.initialized
        val oldQueue = PlayerManager.currentQueueSnapshot()
        val songs = (1L..2L).map { id -> SongItem(id, "Local $id", "Artist", "Album", 0,
            60000, null, mediaUri = "file:///music/$id.mp3", localFilePath = "/music/$id.mp3", channelId = "local", audioId = "$id") }
        PlayerTestEnvironment.install()
        try {
            PlayerManager.initialized = true
            PlayerManager.publishCurrentQueue(songs, 0)
            var requests = 0
            LanPlaybackBinding.isHost = { true }
            LanPlaybackBinding.command = { intent, track, position ->
                requests++
                assertEquals("NEXT", intent)
                assertEquals("local", track?.channelId)
                assertEquals("Local 2", track?.name)
                assertNull(track?.mediaUri)
                assertEquals(0.0, position, 0.0)
            }
            assertFalse(PlayerManager.shouldBlockLocalSongSwitch(songs[1], PlaybackCommandSource.LOCAL))
            PlayerManager.playFromQueue(1)
            assertEquals(1, requests)
            assertEquals(songs, LanPlaybackBinding.offeredQueue)
            assertEquals(0, PlayerManager.currentIndex)
            PlayerManager.playFromQueue(-1)
            assertEquals(1, requests)
        } finally {
            LanPlaybackBinding.clear()
            PlayerManager.publishCurrentQueue(oldQueue.playlist, oldQueue.currentIndex)
            PlayerManager.initialized = oldInitialized
            PlayerTestEnvironment.reset()
        }
    }
}
