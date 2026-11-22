package moe.ouom.neriplayer.ui.onboarding

import android.content.Context
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.PlayerManagerNowPlayingPlaybackSource
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class StartupNowPlayingPreviewTest {

    private val lyrics = buildStartupPreviewLyrics(listOf("a", "b", "c"))
    private val song = SongItem(
        id = STARTUP_PREVIEW_SONG_ID,
        name = "song",
        artist = "artist",
        album = "",
        albumId = 0L,
        durationMs = STARTUP_PREVIEW_DURATION_MS,
        coverUrl = null
    )

    @Test
    fun previewSourceNeverOpensActionsThatTouchRealData() {
        // 队列, 定时, 加歌单和更多操作都会改动真实数据, 引导预览里必须关闭
        assertFalse(StartupPreviewPlaybackSource(song, lyrics).secondaryActionsEnabled)
        assertTrue(PlayerManagerNowPlayingPlaybackSource.secondaryActionsEnabled)
    }

    @Test
    fun previewSourcePlaysTheDemoSongOnlyInMemory() {
        val source = StartupPreviewPlaybackSource(song, lyrics)
        assertEquals(song, source.currentSongFlow.value)
        assertEquals(listOf(song), source.currentQueueFlow.value)
        assertEquals(STARTUP_PREVIEW_START_MS, source.playbackPositionFlow.value)
        assertFalse(source.isPlayingFlow.value)

        source.togglePlayPause()
        assertTrue(source.isPlayingFlow.value)
        assertTrue(source.playbackControlPlayingFlow.value)

        source.advance(1_000L)
        assertEquals(STARTUP_PREVIEW_START_MS + 1_000L, source.playbackPositionFlow.value)

        source.seekTo(-5L)
        assertEquals(0L, source.playbackPositionFlow.value)
        source.seekTo(STARTUP_PREVIEW_DURATION_MS * 2)
        assertEquals(STARTUP_PREVIEW_DURATION_MS, source.playbackPositionFlow.value)

        source.next()
        assertEquals(lyrics.first().startTimeMs, source.playbackPositionFlow.value)
        source.seekTo(lyrics.last().startTimeMs)
        source.previous()
        assertEquals(lyrics.first().startTimeMs, source.playbackPositionFlow.value)
    }

    @Test
    fun previewSourceCyclesShuffleAndRepeatLikeThePlayer() {
        val source = StartupPreviewPlaybackSource(song, lyrics)
        source.setShuffle(true)
        assertTrue(source.shuffleModeFlow.value)
        assertEquals(Player.REPEAT_MODE_OFF, source.repeatModeFlow.value)
        source.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ALL, source.repeatModeFlow.value)
        source.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ONE, source.repeatModeFlow.value)
        source.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_OFF, source.repeatModeFlow.value)
    }

    @Test
    fun previewSourceKeepsTheQueueInSyncWhenTheCoverArrives() {
        val source = StartupPreviewPlaybackSource(song, lyrics)
        val withCover = song.copy(coverUrl = "file:///cover.png")
        source.updateSong(withCover)
        assertEquals(withCover, source.currentSongFlow.value)
        assertEquals(listOf(withCover), source.currentQueueFlow.value)
    }

    @Test
    fun previewLyricsFeedTheRealLyricsPipeline() = runBlocking {
        // 示例歌词要走真实播放页的加载阶段, 不能只在预览里单独画
        val stages = StartupPreviewPlaybackSource(song, lyrics).lyricsStages
        val request = NowPlayingLyricsLoadRequest(
            Mockito.mock(Context::class.java), song, null, true, LyricSourcePreference.Automatic, null
        )
        val loaded = stages.readBackground(request, stages.readFast(request))
        assertEquals(lyrics, loaded.lyrics)
    }
}
