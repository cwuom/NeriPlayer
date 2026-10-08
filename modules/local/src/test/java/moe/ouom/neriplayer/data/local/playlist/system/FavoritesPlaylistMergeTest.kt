package moe.ouom.neriplayer.data.local.playlist.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoritesPlaylistMergeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `duplicate favorites merge into one system playlist with the latest time and last custom cover`() {
        val shared = song(1L)
        val merged = FavoritesPlaylist.merge(
            listOf(
                playlist(-3L, modifiedAt = 30L, customCoverUrl = "content://covers/first.jpg", songs = listOf(shared, song(2L))),
                playlist(-4L, modifiedAt = 50L, customCoverUrl = "content://covers/second.jpg", songs = listOf(shared)),
                playlist(-5L, modifiedAt = 40L, customCoverUrl = " ", songs = listOf(song(3L)))
            ),
            context
        )

        assertEquals(FavoritesPlaylist.SYSTEM_ID, merged.id)
        assertEquals(FavoritesPlaylist.currentName(context), merged.name)
        assertEquals(listOf(1L, 2L, 3L), merged.songs.map(SongItem::id))
        assertEquals(50L, merged.modifiedAt)
        assertEquals("content://covers/second.jpg", merged.customCoverUrl)
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, merged.songOrderVersion)
    }

    @Test
    fun `merging nothing produces an empty playlist stamped with the current time`() {
        val before = System.currentTimeMillis()

        val merged = FavoritesPlaylist.merge(emptyList(), context)

        assertTrue(merged.songs.isEmpty())
        assertTrue(merged.modifiedAt >= before)
        assertNull(merged.customCoverUrl)
    }

    private fun playlist(id: Long, modifiedAt: Long, customCoverUrl: String?, songs: List<SongItem>) = LocalPlaylist(
        id = id,
        name = "我喜欢的音乐",
        songs = songs.toMutableList(),
        modifiedAt = modifiedAt,
        customCoverUrl = customCoverUrl
    )

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null
    )
}
