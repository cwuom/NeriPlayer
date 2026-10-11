package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockSegment
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockTarget
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.skip.sponsorblock.BiliSponsorBlockRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class BiliSponsorBlockPlaybackControllerFlowTest {
    private val enabledSetting = MutableStateFlow(true)
    private val sponsorBlock = mock(BiliSponsorBlockRepository::class.java)
    private val scope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        val settings = mock(SettingsRepository::class.java)
        `when`(settings.settingFlow(AutoSettingsSchema.playback.biliSponsorBlockEnabled)).thenReturn(enabledSetting)
        val repositories = mock(PlayerRepositoryDependencies::class.java)
        `when`(repositories.settingsRepo).thenReturn(settings)
        `when`(repositories.biliSponsorBlockRepository).thenReturn(sponsorBlock)
        `when`(repositories.biliClient).thenReturn(mock(BiliClient::class.java))
        PlayerTestEnvironment.install(repositories = repositories)
        stubSegments(SELECTED_TARGET, listOf(segment("intro", 0L, 5_000L)))
    }

    @After
    fun tearDown() {
        scope.cancel()
        BiliSponsorBlockPlaybackController.onPlaybackRequestStarted(song(id = 9L), requestToken = -1L)
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `explicit Bili part loads its segments and skips the intro`() {
        BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(SELECTED_SONG, 1L, scope)

        assertEquals(5_000L, BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 1_000L, 0L))
        assertNull(BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 6_000L, 0L))
        assertNull(BiliSponsorBlockPlaybackController.nextSkipPosition(song(id = 2L), 1_000L, 0L))

        BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(SELECTED_SONG, 1L, scope)
        assertEquals(1, segmentLoads())
    }

    @Test
    fun `segments wait for the setting and stop skipping once it is disabled`() {
        enabledSetting.value = false
        BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(SELECTED_SONG, 1L, scope)
        assertNull(BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 1_000L, 0L))
        assertEquals(0, segmentLoads())

        enabledSetting.value = true
        assertEquals(5_000L, BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 1_000L, 60_000L))

        enabledSetting.value = false
        assertNull(BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 1_000L, 60_000L))
        assertEquals(1, segmentLoads())
    }

    @Test
    fun `resolved target replaces an unresolved track and loads its segments`() {
        val unresolved = song(id = 3L)
        BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(unresolved, 3L, scope)
        assertNull(BiliSponsorBlockPlaybackController.nextSkipPosition(unresolved, 1_000L, 0L))

        BiliSponsorBlockPlaybackController.onBiliTrackResolved(unresolved, SELECTED_TARGET, 3L, scope)
        BiliSponsorBlockPlaybackController.onBiliTrackResolved(unresolved, SELECTED_TARGET, 3L, scope)

        assertEquals(5_000L, BiliSponsorBlockPlaybackController.nextSkipPosition(unresolved, 1_000L, 0L))
        assertEquals(1, segmentLoads())
    }

    @Test
    fun `a new playback request clears the previous track`() {
        BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(SELECTED_SONG, 1L, scope)
        BiliSponsorBlockPlaybackController.onPlaybackRequestStarted(SELECTED_SONG, 1L)
        assertEquals(5_000L, BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 1_000L, 0L))

        BiliSponsorBlockPlaybackController.onPlaybackRequestStarted(SELECTED_SONG, 2L)

        assertNull(BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 1_000L, 0L))
    }

    @Test
    fun `segment loading failure leaves the track without skips`() {
        runBlocking {
            `when`(sponsorBlock.loadAutoSkipSegments(SELECTED_TARGET)).thenThrow(IllegalStateException("offline"))
        }

        BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(SELECTED_SONG, 1L, scope)
        BiliSponsorBlockPlaybackController.prepareActiveBiliTrackTarget(SELECTED_SONG, 1L, scope)

        assertNull(BiliSponsorBlockPlaybackController.nextSkipPosition(SELECTED_SONG, 1_000L, 0L))
        assertEquals(1, segmentLoads())
    }

    private fun stubSegments(target: BiliSponsorBlockTarget, segments: List<BiliSponsorBlockSegment>) {
        runBlocking { `when`(sponsorBlock.loadAutoSkipSegments(target)).thenReturn(segments) }
    }

    private fun segmentLoads(): Int =
        mockingDetails(sponsorBlock).invocations.count { it.method.name == "loadAutoSkipSegments" }

    private fun segment(uuid: String, startMs: Long, endMs: Long) =
        BiliSponsorBlockSegment(uuid = uuid, category = "intro", startMs = startMs, endMs = endMs)

    private companion object {
        val SELECTED_TARGET = BiliSponsorBlockTarget(bvid = "BV1Ha1cBJExg", cid = 33_638_122_342L, durationMs = 725_000L)

        val SELECTED_SONG = SongItem(
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

        fun song(id: Long) = SongItem(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null
        )
    }
}
