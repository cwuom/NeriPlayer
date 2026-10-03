package moe.ouom.neriplayer.data.playlist.favorite

import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteArtistTest {
    @Test
    fun `artist filtering keeps all creator platforms out of playlist collections`() {
        val sources = listOf("neteaseArtist", "biliArtist", "youtubeMusicArtist", "netease", "bilibili", "youtubeMusic")

        assertEquals(listOf("neteaseArtist", "biliArtist", "youtubeMusicArtist"), sources.filter(::isArtistFavoriteSource))
        assertEquals(listOf("netease", "bilibili", "youtubeMusic"), sources.filterNot(::isArtistFavoriteSource))
    }

    @Test
    fun `remote import preserves local metadata and order while adding unique creators`() {
        val existing = listOf(favorite(1L), favorite(2L, source = "biliArtist"))
        val result = mergeFollowedArtistFavorites(
            existing,
            "neteaseArtist",
            listOf(
                FavoriteArtist(1L, "Remote replacement"),
                FavoriteArtist(3L, "New artist", "https://image.test/3", 20, subtitle = "alias"),
                FavoriteArtist(3L, "Duplicate"),
                FavoriteArtist(0L, "Invalid"),
                FavoriteArtist(4L, " ")
            ),
            importStartedAt = 100L,
            now = 200L
        )

        assertEquals(1, result.addedCount)
        assertEquals(existing, result.favorites.take(2))
        val imported = result.favorites.single { it.id == 3L }
        assertEquals("New artist", imported.name)
        assertEquals("neteaseArtist", imported.source)
        assertEquals("https://image.test/3", imported.coverUrl)
        assertEquals(20, imported.trackCount)
        assertEquals("alias", imported.subtitle)
        assertEquals(200L, imported.sortOrder)
        assertTrue(imported.songs.isEmpty())
    }

    @Test
    fun `local cancellation during fetch takes priority over a remote follow`() {
        val beforeImport = favorite(1L).copy(isDeleted = true, modifiedAt = 99L)
        val duringImport = favorite(2L).copy(isDeleted = true, modifiedAt = 100L)
        val afterImport = favorite(3L).copy(isDeleted = true, modifiedAt = 101L)
        val result = mergeFollowedArtistFavorites(
            listOf(beforeImport, duringImport, afterImport),
            "neteaseArtist",
            listOf(FavoriteArtist(1L, "Restored"), FavoriteArtist(2L, "Cancelled"), FavoriteArtist(3L, "Cancelled")),
            importStartedAt = 100L,
            now = 200L
        )

        assertEquals(1, result.addedCount)
        assertFalse(result.favorites.single { it.id == 1L }.isDeleted)
        assertEquals(duringImport, result.favorites.single { it.id == 2L })
        assertEquals(afterImport, result.favorites.single { it.id == 3L })
    }

    @Test
    fun `channel browse identity survives a local follow import`() {
        val result = mergeFollowedArtistFavorites(
            emptyList(), "youtubeMusicArtist",
            listOf(FavoriteArtist(-10L, "Channel", browseId = "UCchannel")),
            importStartedAt = 100L, now = 200L
        )

        assertEquals(-10L, result.favorites.single().id)
        assertEquals("UCchannel", result.favorites.single().browseId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `playlist source cannot import creators`() {
        mergeFollowedArtistFavorites(emptyList(), "netease", emptyList(), 100L, 200L)
    }

    private fun favorite(id: Long, source: String = "neteaseArtist") = FavoritePlaylist(
        id = id,
        name = "Local $id",
        coverUrl = "https://image.test/local",
        trackCount = 1,
        source = source,
        songs = emptyList(),
        addedTime = 10L,
        sortOrder = 20L,
        modifiedAt = 30L
    )
}
