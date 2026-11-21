package moe.ouom.neriplayer.core.player.lan

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.persistence.prepareStatePersist
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.lanltw.audio.LanAudioState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LanQueueSelectionTest {
    @Test fun guestQueueStaysTemporaryAcrossLocalAndOnlineTracks() {
        val repositories = mock(PlayerRepositoryDependencies::class.java)
        val stats = mock(PlaybackStatsRepository::class.java)
        `when`(stats.statsClearedAtFlow).thenReturn(MutableStateFlow(0L))
        `when`(repositories.playbackStatsRepo).thenReturn(stats)
        PlayerTestEnvironment.install(repositories = repositories)
        val beforeTest = LanGuestPlaybackSnapshot.capture()
        val previous = listOf(song(1L), song(2L))
        try {
            PlayerManager.publishCurrentQueue(previous, 1)
            PlayerManager._currentSongFlow.value = previous[1]
            PlayerManager._playbackPositionMs.value = 23_000L
            val original = LanGuestPlaybackSnapshot.capture()
            LanPlaybackBinding.guestPlayback = original

            val roomLocal = song(3L).copy(sourceStableKey = "lan-session:local:hash")
            val roomOnline = song(4L)
            PlayerManager.publishCurrentQueue(listOf(roomLocal), 0)
            assertNull(PlayerManager.prepareStatePersist())
            PlayerManager.publishCurrentQueue(listOf(roomOnline), 0)
            PlayerManager._currentSongFlow.value = roomOnline
            PlayerManager._playbackPositionMs.value = 55_000L
            assertNull(PlayerManager.prepareStatePersist())

            original.restore()
            assertEquals(previous, PlayerManager.currentQueueFlow.value)
            assertEquals(1, PlayerManager.currentIndex)
            assertEquals(previous[1], PlayerManager.currentSongFlow.value)
            assertEquals(23_000L, PlayerManager.playbackPositionFlow.value)
        } finally {
            LanPlaybackBinding.clear()
            beforeTest.restore()
            PlayerTestEnvironment.reset()
        }
    }

    @Test fun webAudioPositionsAdvanceExternalLyricsWithoutAnExoTick() {
        val current = song(1L)
        val oldSong = PlayerManager.currentSongFlow.value
        val oldPosition = PlayerManager.playbackPositionFlow.value
        val oldDuration = PlayerManager.playbackDurationFlow.value
        val oldState = PlayerManager.playerPlaybackStateFlow.value
        val oldLyrics = PlayerManager.externalBluetoothLyrics
        val oldKey = PlayerManager.externalBluetoothLyricsSongKey
        val oldEnabled = PlayerManager.externalBluetoothLyricsEnabled
        val oldLine = PlayerManager.externalBluetoothLyricLineFlow.value
        val oldPayload = PlayerManager.externalBluetoothLyricPayloadFlow.value
        try {
            LanPlaybackBinding.externalAudio = true
            PlayerManager._currentSongFlow.value = current
            PlayerManager.externalBluetoothLyricsSongKey = current.stableKey()
            PlayerManager.externalBluetoothLyricsEnabled = true
            PlayerManager.externalBluetoothLyrics = listOf(
                LyricEntry("first", 1_000L, 20_000L),
                LyricEntry("second", 30_000L, 59_000L),
            )
            val presentation = LanAudioPresentation()
            presentation.update(LanAudioState(positionSeconds = 10.0, durationSeconds = 60.0))
            assertEquals("first", PlayerManager.externalBluetoothLyricLineFlow.value)
            presentation.update(LanAudioState(positionSeconds = 45.0, durationSeconds = 60.0))
            assertEquals("second", PlayerManager.externalBluetoothLyricLineFlow.value)
        } finally {
            LanPlaybackBinding.clear()
            PlayerManager._currentSongFlow.value = oldSong
            PlayerManager._playbackPositionMs.value = oldPosition
            PlayerManager.playbackProgressOwner.setDuration(oldDuration)
            PlayerManager._playerPlaybackStateFlow.value = oldState
            PlayerManager.externalBluetoothLyrics = oldLyrics
            PlayerManager.externalBluetoothLyricsSongKey = oldKey
            PlayerManager.externalBluetoothLyricsEnabled = oldEnabled
            PlayerManager._externalBluetoothLyricLineFlow.value = oldLine
            PlayerManager._externalBluetoothLyricPayloadFlow.value = oldPayload
        }
    }

    @Test fun endingWithoutAnotherTrackResetsTheRoomPlaybackAnchor() {
        val oldQueue = PlayerManager.currentQueueSnapshot()
        val oldRepeat = PlayerManager.repeatModeSetting
        try {
            PlayerManager.repeatModeSetting = Player.REPEAT_MODE_OFF
            val requests = mutableListOf<Pair<String, Double>>()
            LanPlaybackBinding.isHost = { true }
            LanPlaybackBinding.command = { intent, _, position -> requests += intent to position }

            PlayerManager.publishCurrentQueue(listOf(song(1L)), 0)
            assertTrue(LanPlaybackBinding.ended())
            PlayerManager.publishCurrentQueue(emptyList(), -1)
            assertTrue(LanPlaybackBinding.ended())

            assertEquals(listOf("FINISHED" to 0.0, "FINISHED" to 0.0), requests)
        } finally {
            LanPlaybackBinding.clear()
            PlayerManager.publishCurrentQueue(oldQueue.playlist, oldQueue.currentIndex)
            PlayerManager.repeatModeSetting = oldRepeat
        }
    }

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

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "Album", 0, 60_000L, null)
}
