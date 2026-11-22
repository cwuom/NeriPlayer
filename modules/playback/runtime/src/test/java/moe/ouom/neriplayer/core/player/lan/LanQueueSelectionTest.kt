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
            val requests = mutableListOf<Pair<String?, Boolean>>()
            LanPlaybackBinding.isHost = { true }
            LanPlaybackBinding.command = { intent, _, _ -> fail("unexpected interactive command $intent") }
            LanPlaybackBinding.hostTrackEnded = { next, last -> requests += next?.stableKey to last }

            PlayerManager.publishCurrentQueue(listOf(song(1L)), 0)
            assertTrue(LanPlaybackBinding.ended())
            PlayerManager.publishCurrentQueue(emptyList(), -1)
            assertTrue(LanPlaybackBinding.ended())

            assertEquals(listOf(null to true, null to true), requests)
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
            val offer = requireNotNull(LanPlaybackBinding.queueOffers.forTrack(requireNotNull(LanPlaybackBinding.track(songs[1]))))
            assertEquals(songs, offer.songs)
            assertEquals(1, offer.index)
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

    @Test fun onlyTheAcceptedSelectionReplacesTheQueueOnce() {
        val a = song(1L)
        val b = song(2L)
        val c = song(3L)
        val offers = LanQueueOffers()
        val track = requireNotNull(LanPlaybackBinding.track(b))
        val first = offers.offer(listOf(a, b), 1, track)
        val latest = offers.offer(listOf(b, a, b), 2, track)
        offers.published(first, "old")
        assertNull(offers.consume("old"))
        assertNull(offers.forTrack(requireNotNull(LanPlaybackBinding.track(a))))
        offers.published(latest, "new")
        val committed = requireNotNull(offers.consume("new"))
        assertEquals(2, committed.index)
        val edited = committed.songs + c
        val step = requireNotNull(LanPlaybackBinding.track(a))
        assertNull(offers.forTrack(step))
        val next = currentLanQueueSelection(step, edited, committed.index) { error("track already in the queue") }
        assertEquals(listOf(b, a, b, c), next.songs)
        assertEquals(1, next.index)
        assertNull(offers.consume("new"))
        offers.published(latest, "late")
        assertNull(offers.consume("late"))
    }

    @Test fun failedPublicationOnlyDiscardsItsOwnSelection() {
        val offers = LanQueueOffers()
        val songs = listOf(song(1L), song(2L))
        val first = offers.offer(songs, 0, requireNotNull(LanPlaybackBinding.track(songs.first())))
        val track = requireNotNull(LanPlaybackBinding.track(songs.last()))
        val latest = offers.offer(songs, 1, track)
        offers.discard(first)
        assertSame(latest, offers.forTrack(track))
        offers.discard(latest)
        assertNull(offers.forTrack(track))
    }

    @Test fun anAcceptedQueueStepKeepsConcurrentEditsAndTheSelectedOccurrence() {
        val a = song(1L)
        val duplicate = a.copy(customName = "second occurrence")
        val b = song(2L)
        val queue = listOf(a, duplicate)
        val track = requireNotNull(LanPlaybackBinding.track(a))
        val offers = LanQueueOffers()
        offers.offerNavigation(queue, 1, track)
        val pending = requireNotNull(offers.forTrack(track))
        offers.published(pending, "duplicate")
        val step = requireNotNull(offers.consume("duplicate"))
        val edited = listOf(b, a, duplicate)
        val accepted = requireNotNull(step.resolve(edited))
        assertEquals(edited, accepted.songs)
        assertEquals(2, accepted.index)
        assertNull(step.resolve(listOf(b)))
    }

    @Test fun guestSelectionsNeverStageAQueueEvenWhenTheirRequestIsDenied() {
        try {
            val requests = mutableListOf<String>()
            LanPlaybackBinding.command = { intent, _, _ -> requests += intent }
            val a = song(1L)
            assertTrue(LanPlaybackBinding.playlist(listOf(a), 0, PlaybackCommandSource.LOCAL))
            assertEquals(listOf("NEXT"), requests)
            assertNull(LanPlaybackBinding.queueOffers.forTrack(requireNotNull(LanPlaybackBinding.track(a))))
        } finally {
            LanPlaybackBinding.clear()
        }
    }

    @Test fun guestsWaitForTheHostsPlaylistBoundaryAndTimersStayLocal() {
        val timer = PlayerManager.sleepTimerManager
        assertFalse(timer.timerState.value.isActive)
        val interruptions = mutableListOf<String>()
        try {
            LanPlaybackBinding.externalAudio = true
            LanPlaybackBinding.interruptAudio = { interruptions += it }
            LanPlaybackBinding.command = { intent, _, _ -> fail("unexpected room command $intent") }
            timer.startFinishPlaylist()
            assertTrue(LanPlaybackBinding.ended())
            LanPlaybackBinding.checkEndTimer(lastInPlaylist = false)
            assertTrue(timer.timerState.value.isActive)
            LanPlaybackBinding.checkEndTimer(lastInPlaylist = true)
            assertFalse(timer.timerState.value.isActive)
            timer.startFinishCurrent()
            assertTrue(LanPlaybackBinding.ended())
            assertFalse(timer.timerState.value.isActive)
            PlayerManager.pauseForSleepTimer()
            assertEquals(List(3) { "SLEEP_TIMER" }, interruptions)
        } finally {
            timer.cancel()
            LanPlaybackBinding.clear()
        }
    }

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "Album", 0, 60_000L, null,
        channelId = "netease", audioId = id.toString())
}
