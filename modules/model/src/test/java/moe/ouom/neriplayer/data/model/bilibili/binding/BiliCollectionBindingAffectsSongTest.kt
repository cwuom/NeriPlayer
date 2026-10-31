package moe.ouom.neriplayer.data.model.bilibili.binding

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliCollectionBindingAffectsSongTest {

    private fun song(
        id: Long,
        name: String = "song-$id",
        albumId: Long = 0L,
        album: String = "album-$albumId"
    ) = SongItem(
        id = id,
        name = name,
        artist = "artist",
        album = album,
        albumId = albumId,
        durationMs = 180_000L,
        coverUrl = null
    )

    private fun binding(
        targetKey: String,
        targetType: String,
        tracks: List<BiliCollectionBindingTrack> = emptyList()
    ) = BiliCollectionBinding(
        targetKey = targetKey,
        targetType = targetType,
        bvid = "BV1xx411c7mD",
        tracks = tracks
    )

    private fun track(songId: Long, songName: String = "song-$songId") =
        BiliCollectionBindingTrack(
            songId = songId,
            songName = songName,
            cid = 100L + songId,
            page = 1,
            part = "P1"
        )

    @Test
    fun `single song binding only affects that song`() {
        val binding = binding(biliSongBindingKey(7L), BILI_BINDING_TARGET_SONG, listOf(track(7L)))

        assertTrue(binding.affectsSong(song(7L)))
        assertFalse(binding.affectsSong(song(8L)))
    }

    @Test
    fun `album binding affects every song of the album`() {
        val binding = binding(
            biliAlbumBindingKey(42L, "album"),
            BILI_BINDING_TARGET_ALBUM,
            listOf(track(1L))
        )

        assertTrue(binding.affectsSong(song(1L, albumId = 42L)))
        assertTrue(binding.affectsSong(song(2L, albumId = 42L)))
        assertFalse(binding.affectsSong(song(3L, albumId = 43L)))
    }

    @Test
    fun `playlist binding affects the songs it maps`() {
        val binding = binding(
            biliPlaylistBindingKey(9L, "playlist"),
            BILI_BINDING_TARGET_PLAYLIST,
            listOf(track(5L))
        )

        assertTrue(binding.affectsSong(song(5L)))
        assertFalse(binding.affectsSong(song(6L)))

        val playlistKey = biliPlaylistBindingKey(9L, "playlist")
        assertTrue(binding.affectsSong(song(6L), playlistBindingKey = playlistKey))
    }

    @Test
    fun `binding falls back to song name when ids are unavailable`() {
        val binding = binding(
            biliPlaylistBindingKey(0L, "my playlist"),
            BILI_BINDING_TARGET_PLAYLIST,
            listOf(track(0L, songName = "夜曲"))
        )

        assertTrue(binding.affectsSong(song(0L, name = "夜曲")))
        assertFalse(binding.affectsSong(song(0L, name = "晴天")))
    }

    @Test
    fun `same name with a different id never borrows the binding`() {
        val binding = binding(
            biliPlaylistBindingKey(9L, "playlist"),
            BILI_BINDING_TARGET_PLAYLIST,
            listOf(track(5L, songName = "晴天"))
        )

        assertTrue(binding.affectsSong(song(5L, name = "晴天")))
        assertFalse(binding.affectsSong(song(6L, name = "晴天")))
    }
}
