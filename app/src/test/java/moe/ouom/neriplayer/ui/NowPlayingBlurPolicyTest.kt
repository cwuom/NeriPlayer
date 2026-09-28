package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.currentNowPlayingBlurCoverUrl
import moe.ouom.neriplayer.ui.playback.visual.hasNowPlayingCoverBlur
import moe.ouom.neriplayer.ui.playback.visual.nowPlayingBlurAssetVersion
import moe.ouom.neriplayer.ui.playback.visual.nowPlayingBlurNeighborUrls
import moe.ouom.neriplayer.ui.playback.visual.nowPlayingBlurRequestKey
import moe.ouom.neriplayer.ui.playback.visual.resolveNowPlayingBlurLoadFailure
import moe.ouom.neriplayer.ui.playback.visual.selectNowPlayingAccentCoverUrl
import moe.ouom.neriplayer.ui.playback.visual.shouldDisableNowPlayingBlurNetwork
import moe.ouom.neriplayer.ui.playback.visual.shouldShowStableNowPlayingBlur
import moe.ouom.neriplayer.ui.playback.visual.shouldUseNowPlayingBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBlurPolicyTest {
    private fun song(id: Long) = SongItem(id, "Track $id", "Artist", "Album", 1, 1_000, null)

    @Test
    fun `blur availability requires support preference and a current or retained cover`() {
        assertFalse(hasNowPlayingCoverBlur(false, true, "current", null))
        assertFalse(hasNowPlayingCoverBlur(true, false, "current", null))
        assertFalse(hasNowPlayingCoverBlur(true, true, " ", null))
        assertFalse(hasNowPlayingCoverBlur(true, true, null, " "))
        assertTrue(hasNowPlayingCoverBlur(true, true, "current", null))
        assertTrue(hasNowPlayingCoverBlur(true, true, null, "retained"))
        assertNull(currentNowPlayingBlurCoverUrl(" "))
        assertEquals("current", currentNowPlayingBlurCoverUrl("current"))
    }

    @Test
    fun `neighbor prefetch follows queue identity only for remote current covers`() {
        val queue = listOf(song(1), song(2), song(3))
        val resolve: (SongItem) -> String? = { "cover-${it.id}" }
        assertEquals(
            listOf("cover-1", "cover-3"),
            nowPlayingBlurNeighborUrls(queue, song(2), "https://example.com/cover", resolve)
        )
        assertEquals(
            listOf("cover-2"),
            nowPlayingBlurNeighborUrls(queue, song(1), "https://example.com/cover", resolve)
        )
        assertEquals(
            emptyList<String>(),
            nowPlayingBlurNeighborUrls(queue, song(2), "content://cover", resolve)
        )
        assertEquals(
            emptyList<String>(),
            nowPlayingBlurNeighborUrls(queue, null, "https://example.com/cover", resolve)
        )
        assertEquals(
            emptyList<String>(),
            nowPlayingBlurNeighborUrls(queue, song(4), "https://example.com/cover", resolve)
        )
    }

    @Test
    fun `request identity changes with song cover strength and asset revision`() {
        val cover = NowPlayingOverlayCover("https://example.com/cover", "song-a", null, 7)
        val version = nowPlayingBlurAssetVersion(cover)
        assertEquals("7:song-a:https://example.com/cover", version)
        assertEquals(
            "nowplaying-blur:song-a:https://example.com/cover:4.0:$version",
            nowPlayingBlurRequestKey(cover.url, cover.songKey, 4f, version)
        )
        assertNull(nowPlayingBlurRequestKey(" ", cover.songKey, 4f, version))
        assertFalse(
            nowPlayingBlurRequestKey(cover.url, "song-b", 4f, version) ==
                    nowPlayingBlurRequestKey(cover.url, cover.songKey, 4f, version)
        )
    }

    @Test
    fun `stable image remains while a different cover or blur is loading`() {
        assertTrue(shouldShowStableNowPlayingBlur("old", "new", 4f, 4f))
        assertTrue(shouldShowStableNowPlayingBlur("same", "same", 4f, 8f))
        assertFalse(shouldShowStableNowPlayingBlur("same", "same", 4f, 4f))
        assertFalse(shouldShowStableNowPlayingBlur(null, "new", null, 4f))
    }

    @Test
    fun `stale image errors never replace the active or retained backdrop`() {
        assertFalse(resolveNowPlayingBlurLoadFailure("new", "old", null, false))
        assertTrue(resolveNowPlayingBlurLoadFailure("new", "new", null, false))
        assertFalse(resolveNowPlayingBlurLoadFailure("new", "new", "old-cover", true))
    }

    @Test
    fun `offline mode blocks only remote cover requests`() {
        assertTrue(shouldDisableNowPlayingBlurNetwork(true, "https://example.com/cover"))
        assertFalse(shouldDisableNowPlayingBlurNetwork(false, "https://example.com/cover"))
        assertFalse(shouldDisableNowPlayingBlurNetwork(true, "content://local/cover"))
        assertFalse(shouldDisableNowPlayingBlurNetwork(true, null))
    }

    @Test
    fun `blur fallback retains the stable accent only while blur is usable`() {
        assertTrue(shouldUseNowPlayingBlur(true, false))
        assertFalse(shouldUseNowPlayingBlur(true, true))
        assertFalse(shouldUseNowPlayingBlur(false, false))
        assertEquals("old", selectNowPlayingAccentCoverUrl(true, "old", "new"))
        assertEquals("new", selectNowPlayingAccentCoverUrl(true, null, "new"))
        assertEquals("new", selectNowPlayingAccentCoverUrl(false, "old", "new"))
    }
}
