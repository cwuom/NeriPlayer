package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSongLookupTest {
    @Test
    fun `remote and matched local favorites are recognized in both directions`() {
        val remote = remoteSong()
        val local = localSong()
        assertTrue(FavoriteSongLookup(listOf(remote)).contains(local))
        assertTrue(FavoriteSongLookup(listOf(local)).contains(remote))
        assertTrue(FavoriteSongLookup(listOf(remote, local)).contains(remote))
        assertTrue(FavoriteSongLookup(listOf(local)).contains(local.copy(
            id = 101L, audioId = "101", mediaUri = "content://media/external/audio/media/101"
        )))
        assertFalse(SongIdentityLookup(listOf(remote)).contains(local))
        assertFalse(SongIdentityLookup(listOf(local)).contains(remote))
    }

    @Test
    fun `download provenance associates favorites without requiring a lyric match`() {
        val remote = remoteSong()
        val download = localSong().copy(
            matchedLyricSource = null,
            matchedSongId = null,
            sourceStableKey = "42|netease|"
        )
        assertTrue(FavoriteSongLookup(listOf(remote)).contains(download))
        assertTrue(FavoriteSongLookup(listOf(download)).contains(remote))
    }

    @Test
    fun `candidate collisions cannot override platform or provenance rules`() {
        val lookup = FavoriteSongLookup(listOf(remoteSong()))
        val local = localSong()
        listOf(
            local.copy(matchedLyricSource = null),
            local.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC),
            local.copy(matchedSongId = "0"),
            local.copy(matchedSongId = "-42"),
            local.copy(matchedSongId = "invalid"),
            local.copy(matchedSongId = "43"),
            local.copy(sourceStableKey = "77|bilibili|"),
            remoteSong().copy(album = "bilibili", channelId = "bilibili")
        ).forEach { query -> assertFalse("不能误认候选: $query", lookup.contains(query)) }
    }

    @Test
    fun `normal identity and moved local sources keep their existing membership`() {
        val local = localSong().copy(matchedSongId = null, matchedLyricSource = null)
        val lookup = FavoriteSongLookup(listOf(remoteSong(), local))
        assertTrue(lookup.contains(remoteSong().copy(name = "Renamed remote")))
        assertTrue(lookup.contains(local.copy(
            id = 101L, mediaUri = "content://media/external/audio/media/101"
        )))
        assertFalse(lookup.contains(local.copy(
            id = 101L, audioId = "101", mediaUri = "content://media/external/audio/media/101"
        )))
        assertFalse(FavoriteSongLookup(emptyList()).contains(remoteSong()))
    }

    @Test
    fun `indexed lookups agree with repository rules for mixed favorite representations`() {
        val candidates = listOf(
            remoteSong(), remoteSong(43L), localSong(),
            localSong().copy(matchedSongId = "43"),
            localSong().copy(sourceStableKey = "77|bilibili|"),
            localSong().copy(sourceStableKey = "43|netease|"),
            remoteSong().copy(album = "bilibili", channelId = "bilibili"),
            remoteSong().copy(album = "youtubeMusic", channelId = "youtubeMusic", audioId = "video")
        )
        candidates.forEach { favorite ->
            val lookup = FavoriteSongLookup(listOf(favorite))
            candidates.forEach { query ->
                assertEquals("收藏=$favorite 查询=$query", FavoritesPlaylist.matchesSong(favorite, query), lookup.contains(query))
            }
        }
    }

    @Test
    fun `row queries use the prepared snapshot without reading the favorites list again`() {
        var reads = 0
        val source = object : AbstractList<SongItem>() {
            override val size = 100
            override fun get(index: Int): SongItem {
                reads++
                return remoteSong(index + 1L)
            }
        }
        val lookup = FavoriteSongLookup(source)
        val preparationReads = reads
        repeat(100) {
            assertTrue(lookup.contains(localSong()))
            assertFalse(lookup.contains(localSong().copy(matchedSongId = "101")))
        }
        assertEquals(preparationReads, reads)
    }

    private fun remoteSong(id: Long = 42L) = SongItem(
        id = id, name = "Remote $id", artist = "Artist", album = "netease", albumId = 0L,
        durationMs = 60_000L, coverUrl = null, channelId = "netease", audioId = id.toString()
    )

    private fun localSong() = remoteSong().copy(
        id = 99L, name = "Local", album = "__local_files__", channelId = "local", audioId = "99",
        mediaUri = "content://media/external/audio/media/99", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "42"
    )
}
