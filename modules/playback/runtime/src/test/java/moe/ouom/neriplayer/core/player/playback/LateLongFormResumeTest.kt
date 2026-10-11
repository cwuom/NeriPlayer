package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class LateLongFormResumeTest {

    @Test
    fun `manual seek to the start before history arrives keeps the user position`() = runTest {
        withLateHistoryResume { manager, fake, song ->
            manager.resumeLongFormWhenHistoryLoads(song, true, 20L)
            manager.seekTo(0L)
            assertEquals(11L, manager.playbackPositionGeneration)
            assertEquals(20L, manager.playbackRequestToken)
            assertEquals(song, manager.currentSongFlow.value)

            runCurrent()

            assertEquals(0L, manager.playbackPositionFlow.value)
            assertEquals(0L, fake.positionMs)
        }
    }

    @Test
    fun `late history restores the remembered position when the user has not sought`() = runTest {
        withLateHistoryResume { manager, fake, song ->
            manager.resumeLongFormWhenHistoryLoads(song, true, 20L)

            runCurrent()

            assertEquals(1_250_000L, manager.playbackPositionFlow.value)
            assertEquals(1_250_000L, fake.positionMs)
        }
    }

    private suspend fun TestScope.withLateHistoryResume(
        block: (PlayerManager, FakeExoPlayer, SongItem) -> Unit
    ) {
        val manager = PlayerManager
        val history = mock(PlayHistoryRepository::class.java)
        val repositories = mock(PlayerRepositoryDependencies::class.java)
        val song = SongItem(42L, "Long audio", "Artist", "Album", 1L, 1_800_000L, null)
        `when`(repositories.playHistoryRepo).thenReturn(history)
        `when`(history.isHistoryLoaded).thenReturn(false)
        `when`(history.awaitHistoryLoaded()).thenReturn(true)
        `when`(history.rememberedPlaybackPosition(song)).thenReturn(1_250_000L)

        val previousMainScope = manager.mainScope
        val previousIoScope = manager.ioScope
        val previousInitialized = manager.initialized
        val previousSong = manager._currentSongFlow.value
        val previousPositionMs = manager._playbackPositionMs.value
        val previousPositionGeneration = manager.playbackPositionGeneration
        val previousRequestToken = manager.playbackRequestToken
        val previousRememberProgress = manager.rememberLongFormPlaybackProgressEnabled
        val previousPendingLoad = manager.pendingMediaLoadActive
        val previousPendingLoadPositionMs = manager.pendingMediaLoadPositionMs
        val previousPendingSeekPosition = manager.pendingSeekPositionOrNull()
        try {
            PlayerTestEnvironment.install(repositories = repositories)
            manager.mainScope = backgroundScope
            manager.ioScope = backgroundScope
            manager.initialized = true
            manager._currentSongFlow.value = song
            manager._playbackPositionMs.value = 800L
            manager.playbackPositionGeneration = 10L
            manager.playbackRequestToken = 20L
            manager.rememberLongFormPlaybackProgressEnabled = true
            manager.pendingMediaLoadActive = false
            val fake = FakeExoPlayer().apply { positionMs = 800L }
            fake.installInto(manager) {
                block(manager, fake, song)
            }
        } finally {
            manager.mainScope = previousMainScope
            manager.ioScope = previousIoScope
            manager.initialized = previousInitialized
            manager._currentSongFlow.value = previousSong
            manager._playbackPositionMs.value = previousPositionMs
            manager.playbackPositionGeneration = previousPositionGeneration
            manager.playbackRequestToken = previousRequestToken
            manager.rememberLongFormPlaybackProgressEnabled = previousRememberProgress
            manager.pendingMediaLoadActive = previousPendingLoad
            manager.pendingMediaLoadPositionMs = previousPendingLoadPositionMs
            manager.clearPendingSeekPosition()
            previousPendingSeekPosition?.let(manager::rememberPendingSeekPosition)
            PlayerTestEnvironment.reset()
        }
    }

    @Test
    fun `history that arrives right after the start moves playback to the remembered position`() {
        assertTrue(
            shouldApplyLateLongFormResume(
                sameRequest = true,
                sameSong = true,
                currentPositionMs = 800L,
                rememberedPositionMs = 1_250_000L
            )
        )
    }

    @Test
    fun `a newer request, another song or progress the user already made are left alone`() {
        assertFalse(shouldApplyLateLongFormResume(false, true, 0L, 1_250_000L))
        assertFalse(shouldApplyLateLongFormResume(true, false, 0L, 1_250_000L))
        assertFalse(
            shouldApplyLateLongFormResume(true, true, LATE_LONG_FORM_RESUME_MAX_ELAPSED_MS + 1L, 1_250_000L)
        )
    }

    @Test
    fun `no remembered position or one behind the playhead does not seek`() {
        assertFalse(shouldApplyLateLongFormResume(true, true, 1_000L, 0L))
        assertFalse(shouldApplyLateLongFormResume(true, true, 2_000L, 1_500L))
    }
}
