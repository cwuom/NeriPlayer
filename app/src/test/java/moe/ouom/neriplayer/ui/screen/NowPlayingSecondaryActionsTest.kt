package moe.ouom.neriplayer.ui.screen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsBiliTargetOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsPage
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsSheetOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.isNeteaseArtistNavigationSource
import moe.ouom.neriplayer.ui.screen.nowplaying.isYouTubeMusicArtistNavigationSource
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveMoreOptionsSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSecondaryActionsTest {
    @Test
    fun `bili target owner refreshes on song content or target generation changes`() {
        val song = SongItem(
            id = 9L, name = "demo", artist = "artist", album = "Bilibili|123", albumId = 1L,
            durationMs = 1000L, coverUrl = null
        )
        val requested = mutableListOf<SongItem>()
        val owner = MoreOptionsBiliTargetOwner { requested += it; null }
        assertNull(owner.resolve(song, 1L))
        assertNull(owner.resolve(song, 1L))
        assertNull(owner.resolve(song, 2L))
        assertNull(owner.resolve(song.copy(name = "updated"), 2L))
        assertEquals(listOf(song, song, song.copy(name = "updated")), requested)
    }

    @Test
    fun `more options owner blocks navigation and dismiss while edit save is active`() = runBlocking {
        val hideGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val owner = MoreOptionsSheetOwner(
            scope = this,
            hide = { hideGate.await(); calls += "hide" },
            onDismiss = { calls += "dismiss" }
        )
        owner.open(MoreOptionsPage.EDIT_INFO)
        assertTrue(owner.sheetGesturesEnabled)
        owner.setEditSaving(true)
        assertFalse(owner.sheetGesturesEnabled)
        owner.back()
        owner.open(MoreOptionsPage.SEARCH)
        owner.dismiss { calls += "after" }
        assertEquals(MoreOptionsPage.EDIT_INFO, owner.page)
        assertFalse(owner.isDismissing)
        assertTrue(calls.isEmpty())

        owner.setEditSaving(false)
        owner.dismiss { calls += "after" }
        yield()
        assertTrue(owner.isDismissing)
        owner.dismiss { calls += "duplicate" }
        hideGate.complete(Unit)
        yield()
        assertEquals(listOf("hide", "after", "dismiss"), calls)
        assertFalse(owner.isDismissing)
        owner.back()
        assertEquals(MoreOptionsPage.MAIN, owner.page)
        owner.open(MoreOptionsPage.LISTEN_TOGETHER)
        assertFalse(owner.sheetGesturesEnabled)
    }

    @Test
    fun `netease artist routing accepts explicit origins and rejects foreign channels`() {
        val song = SongItem(
            id = 9L, name = "demo", artist = "artist", album = "album", albumId = 1L,
            durationMs = 1000L, coverUrl = null
        )
        assertTrue(isNeteaseArtistNavigationSource(song.copy(channelId = "netease")))
        assertTrue(isNeteaseArtistNavigationSource(song.copy(album = "Netease|album")))
        assertTrue(isNeteaseArtistNavigationSource(song.copy(mediaUri = "https://music.163.com/song")))
        assertTrue(isNeteaseArtistNavigationSource(song.copy(localFileName = "Netease - song.mp3")))
        assertTrue(isNeteaseArtistNavigationSource(song.copy(localFilePath = "Netease%20-song.mp3")))
        assertTrue(
            isNeteaseArtistNavigationSource(
                song.copy(
                    neteaseArtists = listOf(NeteaseArtistSummary(8L, "artist"))
                )
            )
        )
        assertFalse(
            isNeteaseArtistNavigationSource(
                song.copy(
                    neteaseArtists = listOf(NeteaseArtistSummary(0L, "artist"))
                )
            )
        )
        assertFalse(
            isNeteaseArtistNavigationSource(
                song.copy(
                    neteaseArtists = listOf(NeteaseArtistSummary(8L, " "))
                )
            )
        )
        assertTrue(
            isNeteaseArtistNavigationSource(
                song.copy(
                    neteaseArtists = listOf(
                        NeteaseArtistSummary(0L, "invalid"),
                        NeteaseArtistSummary(8L, "artist")
                    )
                )
            )
        )
        assertTrue(isNeteaseArtistNavigationSource(song.copy(coverUrl = "https://music.126.net/a.jpg")))
        assertFalse(
            isNeteaseArtistNavigationSource(
                song.copy(
                    channelId = "qq", coverUrl = "https://music.126.net/a.jpg"
                )
            )
        )
        assertFalse(
            isNeteaseArtistNavigationSource(
                song.copy(
                    album = "Bilibili|123", coverUrl = "https://music.126.net/a.jpg"
                )
            )
        )
        assertFalse(
            isNeteaseArtistNavigationSource(
                song.copy(
                    channelId = "youtubeMusic", coverUrl = "https://music.126.net/a.jpg"
                )
            )
        )
        assertFalse(
            isNeteaseArtistNavigationSource(
                song.copy(
                    mediaUri = "file:///music/song.mp3", coverUrl = "https://music.126.net/a.jpg"
                )
            )
        )
        assertFalse(
            isYouTubeMusicArtistNavigationSource(
                song.copy(
                    artist = "",
                    channelId = "youtubeMusic"
                )
            )
        )

        val refreshed = song.copy(name = "refreshed")
        assertEquals(refreshed, resolveMoreOptionsSong(refreshed, song))
        assertEquals(song, resolveMoreOptionsSong(song.copy(id = 10L), song))
        assertEquals(song, resolveMoreOptionsSong(null, song))
    }
}
