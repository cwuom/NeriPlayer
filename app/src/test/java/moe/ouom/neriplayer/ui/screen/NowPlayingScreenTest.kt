package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import moe.ouom.neriplayer.data.model.SongItem

class NowPlayingScreenTest {

    @Test
    fun `wide lyrics use synced renderer when advanced lyrics are disabled`() {
        assertEquals(
            NowPlayingWideLyricsMode.SYNCED,
            resolveNowPlayingWideLyricsMode(
                hasLyrics = true,
                advancedLyricsEnabled = false
            )
        )
    }

    @Test
    fun `wide lyrics keep advanced renderer when enabled`() {
        assertEquals(
            NowPlayingWideLyricsMode.ADVANCED,
            resolveNowPlayingWideLyricsMode(
                hasLyrics = true,
                advancedLyricsEnabled = true
            )
        )
    }

    @Test
    fun `wide lyrics show empty state only when lyrics are unavailable`() {
        assertEquals(
            NowPlayingWideLyricsMode.NO_LYRICS,
            resolveNowPlayingWideLyricsMode(
                hasLyrics = false,
                advancedLyricsEnabled = false
            )
        )
    }

    @Test
    fun `compact portrait layout is used when available height is limited`() {
        assertTrue(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 600f,
                uiDensityScale = 1.0f
            )
        )
        assertTrue(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 540f,
                uiDensityScale = 1.0f
            )
        )
    }

    @Test
    fun `compact portrait layout does not affect spacious or landscape screens`() {
        assertFalse(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 601f,
                uiDensityScale = 1.0f
            )
        )
        assertFalse(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = true,
                availableHeightDp = 540f,
                uiDensityScale = 1.2f
            )
        )
    }

    @Test
    fun `high UI density uses compact portrait layout even with ample height`() {
        assertTrue(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 720f,
                uiDensityScale = 1.1f
            )
        )
    }

    @Test
    fun `compact portrait layout hides cover lyrics and dock regardless of preferences`() {
        assertFalse(
            shouldShowNowPlayingCoverLyrics(
                coverLyricsEnabled = true,
                useCompactPortraitLayout = true
            )
        )
        assertFalse(
            shouldUseNowPlayingToolbarDock(
                toolbarDockEnabled = true,
                useCompactPortraitLayout = true
            )
        )
    }

    @Test
    fun `regular portrait layout honors cover lyrics and dock preferences`() {
        assertTrue(
            shouldShowNowPlayingCoverLyrics(
                coverLyricsEnabled = true,
                useCompactPortraitLayout = false
            )
        )
        assertTrue(
            shouldUseNowPlayingToolbarDock(
                toolbarDockEnabled = true,
                useCompactPortraitLayout = false
            )
        )
    }

    @Test
    fun `playback action toolbar keeps normal spacing when five touch targets fit`() {
        val layout = resolvePlaybackActionToolbarLayout(
            availableWidth = 300.dp,
            preferredHorizontalPadding = 16.dp,
            defaultIconSize = 20.dp
        )

        assertEquals(16.dp, layout.horizontalPadding)
        assertEquals(48.dp, layout.minimumInteractiveComponentSize)
        assertEquals(20.dp, layout.iconSize)
        assertFalse(layout.useEqualWidthSlots)
    }

    @Test
    fun `playback action toolbar assigns five equal slots when high density width is narrow`() {
        val layout = resolvePlaybackActionToolbarLayout(
            availableWidth = 220.dp,
            preferredHorizontalPadding = 16.dp,
            defaultIconSize = 20.dp
        )

        assertEquals(0.dp, layout.horizontalPadding)
        assertEquals(44.dp, layout.minimumInteractiveComponentSize)
        assertEquals(20.dp, layout.iconSize)
        assertTrue(layout.useEqualWidthSlots)
    }

    @Test
    fun `playback action toolbar reduces icon size before a very narrow fifth slot overflows`() {
        val layout = resolvePlaybackActionToolbarLayout(
            availableWidth = 190.dp,
            preferredHorizontalPadding = 16.dp,
            defaultIconSize = 20.dp
        )

        assertEquals(38.dp, layout.minimumInteractiveComponentSize)
        assertEquals(18.dp, layout.iconSize)
        assertTrue(layout.useEqualWidthSlots)
    }

    @Test
    fun `download action remains visible when completed task exists but local file is gone`() {
        assertFalse(
            shouldHideDownloadActionForSong(
                hasLocalDownload = false,
                currentTask = null
            )
        )
    }

    @Test
    fun `download action hides only when actual local download exists`() {
        assertTrue(
            shouldHideDownloadActionForSong(
                hasLocalDownload = true,
                currentTask = null
            )
        )
    }

    @Test
    fun `unfinished task keeps download action visible even when local download probe hits`() {
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING
        )

        assertFalse(
            shouldHideDownloadActionForSong(
                hasLocalDownload = true,
                currentTask = task
            )
        )
    }

    @Test
    fun `queue item key keeps duplicate songs visible`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null
        )

        assertNotEquals(
            buildNowPlayingQueueItemKey(index = 0, song = song),
            buildNowPlayingQueueItemKey(index = 1, song = song)
        )
    }

    @Test
    fun `playback source badge uses resolved bili audio source over netease tag`() {
        val sourceType = resolveNowPlayingPlaybackSourceType(
            isLocalSong = false,
            isYouTubeMusicSong = false,
            isFromNeteaseTag = true,
            isFromBiliTag = false,
            currentMediaUrl = "https://m701.music.126.net/demo.mp3",
            playbackAudioSource = PlaybackAudioSource.BILIBILI
        )

        assertTrue(sourceType == PlaybackSourceType.BILIBILI)
    }

    @Test
    fun `remote local cache does not override original platform badge`() {
        val sourceType = resolveNowPlayingPlaybackSourceType(
            isLocalSong = false,
            isYouTubeMusicSong = false,
            isFromNeteaseTag = true,
            isFromBiliTag = false,
            currentMediaUrl = "content://downloads/demo.flac",
            playbackAudioSource = PlaybackAudioSource.LOCAL
        )

        assertTrue(sourceType == PlaybackSourceType.NETEASE)
    }
}
