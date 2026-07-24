package moe.ouom.neriplayer.util.media

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongShareHelpTest {

    @Test
    fun `netease album tag builds catalog page`() {
        val song = song(
            id = 123L,
            album = "${PlayerManager.NETEASE_SOURCE_TAG}Album",
            channelId = "netease",
        )
        assertEquals(
            "https://music.163.com/#/song?id=123",
            buildRemoteSongShareUrl(song, emptyList()),
        )
    }

    @Test
    fun `netease channel without album tag still builds catalog page`() {
        val song = song(
            id = 99L,
            album = "Some Album",
            channelId = "netease",
        )
        assertEquals(
            "https://music.163.com/#/song?id=99",
            buildRemoteSongShareUrl(song, emptyList()),
        )
    }

    @Test
    fun `youtube media uri builds music youtube page`() {
        val song = song(
            id = 1L,
            album = "YouTube",
            mediaUri = buildYouTubeMusicMediaUri("dQw4w9WgXcQ"),
            channelId = "youtubeMusic",
        )
        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            buildRemoteSongShareUrl(song, emptyList()),
        )
    }

    @Test
    fun `bilibili single part builds av page`() {
        val song = song(
            id = 555L,
            album = "${PlayerManager.BILI_SOURCE_TAG}:title",
            channelId = "bilibili",
        )
        assertEquals(
            "https://www.bilibili.com/video/av555",
            buildRemoteSongShareUrl(song, emptyList()),
        )
    }

    @Test
    fun `bilibili multi part uses page index from queue order`() {
        val p1 = song(
            id = 777L,
            album = "${PlayerManager.BILI_SOURCE_TAG}:p1",
            channelId = "bilibili",
        )
        val p2 = song(
            id = 777L,
            album = "${PlayerManager.BILI_SOURCE_TAG}:p2",
            channelId = "bilibili",
        )
        val p3 = song(
            id = 777L,
            album = "${PlayerManager.BILI_SOURCE_TAG}:p3",
            channelId = "bilibili",
        )
        val queue = listOf(p1, p2, p3)
        assertEquals(
            "https://www.bilibili.com/video/av777/?p=2",
            buildRemoteSongShareUrl(p2, queue),
        )
    }

    @Test
    fun `bilibili multi part missing from queue falls back to base av page`() {
        val current = song(
            id = 888L,
            album = "${PlayerManager.BILI_SOURCE_TAG}:missing",
            channelId = "bilibili",
        )
        val other = song(
            id = 888L,
            album = "${PlayerManager.BILI_SOURCE_TAG}:other",
            channelId = "bilibili",
        )
        // Only one matching id+tag in queue with different album → size==1 → base page
        assertEquals(
            "https://www.bilibili.com/video/av888",
            buildRemoteSongShareUrl(current, listOf(other)),
        )
    }

    @Test
    fun `local file path song has no remote share url`() {
        val song = song(
            id = 1L,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            mediaUri = "/storage/emulated/0/Music/a.mp3",
            localFilePath = "/storage/emulated/0/Music/a.mp3",
            channelId = "local",
        )
        assertNull(buildRemoteSongShareUrl(song, emptyList()))
    }

    @Test
    fun `local content uri song has no remote share url`() {
        val song = song(
            id = 2L,
            album = "Local Files",
            mediaUri = "content://media/external/audio/media/42",
            channelId = "local",
        )
        assertNull(buildRemoteSongShareUrl(song, emptyList()))
    }

    @Test
    fun `offline netease download keeps catalog page despite local path`() {
        val song = song(
            id = 42L,
            album = "${PlayerManager.NETEASE_SOURCE_TAG}Album",
            mediaUri = "/storage/emulated/0/Music/song.mp3",
            localFilePath = "/storage/emulated/0/Music/song.mp3",
            channelId = "netease",
        )
        assertEquals(
            "https://music.163.com/#/song?id=42",
            buildRemoteSongShareUrl(song, emptyList()),
        )
    }

    @Test
    fun `unknown remote song without catalog mediaUri returns null`() {
        val song = song(
            id = 5L,
            album = "Mystery",
            mediaUri = null,
            channelId = null,
        )
        assertNull(buildRemoteSongShareUrl(song, emptyList()))
    }

    @Test
    fun `stream cdn mediaUri is not treated as share page`() {
        val song = song(
            id = 3L,
            album = "Unknown Source",
            mediaUri = "https://cdn.example.com/stream/audio.m4a?token=secret",
        )
        assertNull(buildRemoteSongShareUrl(song, emptyList()))
    }

    @Test
    fun `trusted catalog mediaUri may be reused as share url`() {
        val song = song(
            id = 4L,
            album = "External",
            mediaUri = "https://music.163.com/#/song?id=42",
        )
        assertEquals(
            "https://music.163.com/#/song?id=42",
            buildRemoteSongShareUrl(song, emptyList()),
        )
    }

    @Test
    fun `invalid platform id does not invent catalog url`() {
        val netease = song(
            id = 0L,
            album = "${PlayerManager.NETEASE_SOURCE_TAG}x",
            channelId = "netease",
        )
        val bili = song(
            id = -1L,
            album = "${PlayerManager.BILI_SOURCE_TAG}:x",
            channelId = "bilibili",
        )
        assertNull(buildRemoteSongShareUrl(netease, emptyList()))
        assertNull(buildRemoteSongShareUrl(bili, emptyList()))
    }

    @Test
    fun `isShareablePublicHttpUrl accepts catalog pages rejects streams and junk`() {
        assertTrue(isShareablePublicHttpUrl("https://music.163.com/#/song?id=1"))
        assertTrue(isShareablePublicHttpUrl("https://www.bilibili.com/video/av1/?p=2"))
        assertTrue(isShareablePublicHttpUrl("https://music.youtube.com/watch?v=abc"))
        assertFalse(isShareablePublicHttpUrl("https://cdn.example.com/a.mp3?token=x"))
        assertFalse(isShareablePublicHttpUrl("content://media/1"))
        assertFalse(isShareablePublicHttpUrl(""))
        assertFalse(isShareablePublicHttpUrl("not a url"))
        assertFalse(isShareablePublicHttpUrl("https://"))
        assertFalse(isShareablePublicHttpUrl("ftp://music.163.com/song"))
    }

    private fun song(
        id: Long,
        album: String,
        mediaUri: String? = null,
        localFilePath: String? = null,
        channelId: String? = null,
        name: String = "name",
        artist: String = "artist",
    ): SongItem = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        channelId = channelId,
    )
}
