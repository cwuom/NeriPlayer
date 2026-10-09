package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class BiliVideoSkipPlaybackControllerFlowTest {
    private val skipRepository = mock(BiliVideoSkipRepository::class.java)
    private val biliClient = mock(BiliClient::class.java)
    private val scope = TestScope(StandardTestDispatcher())

    @Before
    fun setUp() {
        `when`(skipRepository.intervalsForPlayback(any(), any(), any()))
            .thenReturn(listOf(BiliVideoSkipInterval(startMs = 0L, endMs = 5_000L)))
        val repositories = mock(PlayerRepositoryDependencies::class.java)
        `when`(repositories.biliVideoSkipRepository).thenReturn(skipRepository)
        `when`(repositories.biliClient).thenReturn(biliClient)
        PlayerTestEnvironment.install(repositories = repositories)
    }

    @After
    fun tearDown() {
        BiliVideoSkipPlaybackController.onPlaybackRequestStarted(otherSong, requestToken = -1L)
        scope.cancel()
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `explicit Bili part skips configured intervals for the active song only`() {
        BiliVideoSkipPlaybackController.onPlaybackRequestStarted(selectedSong, 1L)
        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(selectedSong, 1L, scope)
        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(selectedSong, 1L, scope)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(selectedTarget, BiliVideoSkipPlaybackController.activeTargetFor(selectedSong))
        assertEquals(5_000L, BiliVideoSkipPlaybackController.nextSkipPosition(selectedSong, 1_000L, 60_000L))
        assertNull(BiliVideoSkipPlaybackController.nextSkipPosition(otherSong, 1_000L, 60_000L))
        assertEquals(0, clientCalls())

        BiliVideoSkipPlaybackController.onPlaybackRequestStarted(selectedSong, 2L)
        assertNull(BiliVideoSkipPlaybackController.nextSkipPosition(selectedSong, 1_000L, 60_000L))
    }

    @Test
    fun `failed target resolution retries three times without publishing a target`() {
        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(unresolvedSong, 3L, scope)
        val generationAfterPrepare = BiliVideoSkipPlaybackController.activeTrackGeneration.value
        scope.testScheduler.runCurrent()
        val firstAttemptCalls = clientCalls()
        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(unresolvedSong, 3L, scope)

        scope.testScheduler.advanceUntilIdle()

        assertTrue(firstAttemptCalls > 0)
        assertEquals(3 * firstAttemptCalls, clientCalls())
        assertNull(BiliVideoSkipPlaybackController.activeTargetFor(unresolvedSong))
        assertEquals(generationAfterPrepare, BiliVideoSkipPlaybackController.activeTrackGeneration.value)
    }

    @Test
    fun `a new request cancels pending target resolution`() {
        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(unresolvedSong, 4L, scope)
        scope.testScheduler.runCurrent()
        val firstAttemptCalls = clientCalls()

        BiliVideoSkipPlaybackController.onPlaybackRequestStarted(otherSong, 5L)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(firstAttemptCalls, clientCalls())
        assertNull(BiliVideoSkipPlaybackController.activeTargetFor(unresolvedSong))
    }

    @Test
    fun `late cid for the same request replaces the unresolved track`() {
        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(unresolvedSong, 6L, scope)
        val generationBeforeCid = BiliVideoSkipPlaybackController.activeTrackGeneration.value
        val selectedPart = unresolvedSong.copy(album = "Bilibili|42|BV1skipflow", subAudioId = "42")

        BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(selectedPart, 6L, scope)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            BiliVideoSkipTarget(bvid = "BV1skipflow", cid = 42L),
            BiliVideoSkipPlaybackController.activeTargetFor(selectedPart)
        )
        assertNotEquals(generationBeforeCid, BiliVideoSkipPlaybackController.activeTrackGeneration.value)
        assertEquals(0, clientCalls())
    }

    private fun clientCalls(): Int = mockingDetails(biliClient).invocations.size

    private val selectedTarget = BiliVideoSkipTarget(bvid = "BV1Ha1cBJExg", cid = 33_638_122_342L)

    private val selectedSong = SongItem(
        id = 115_481_721_705_067L,
        name = "RapTure",
        artist = "Kadeza",
        album = "Bilibili|33638122342|BV1Ha1cBJExg",
        albumId = 0L,
        durationMs = 725_000L,
        coverUrl = null,
        channelId = "bilibili",
        audioId = "115481721705067",
        subAudioId = "33638122342"
    )

    private val unresolvedSong = SongItem(
        id = 8_020_001L,
        name = "Unresolved",
        artist = "NeriPlayer",
        album = "Bilibili||BV1skipflow",
        albumId = 0L,
        durationMs = 60_000L,
        coverUrl = null,
        channelId = "bilibili",
        audioId = "8020001"
    )

    private val otherSong = SongItem(
        id = 8_020_009L,
        name = "Other",
        artist = "NeriPlayer",
        album = "Album",
        albumId = 0L,
        durationMs = 60_000L,
        coverUrl = null
    )
}
