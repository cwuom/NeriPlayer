package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.data.model.netease.collection.NeteaseCollectionHeader
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCollectionDetailParsingTest {

    private val errors = NeteaseCollectionResponseErrors(
        apiCode = { code -> "api code $code" },
        missingNode = { node -> "missing $node" }
    )

    @Test
    fun `playlist detail parses header tracks and track ids`() {
        val parsed = parseNeteasePlaylistDetailResponse(
            raw = """
                {"code":200,"playlist":{
                  "id":42,"name":"Mix","coverImgUrl":"http://p.music/cover.jpg","playCount":9,"trackCount":5,
                  "tracks":[
                    {"id":1,"name":"One","ar":[{"id":7,"name":"A"},{"id":8,"name":"B"}],
                     "al":{"id":70,"name":"Album","picUrl":"http://p.music/one.jpg"},"dt":1000},
                    {"id":0,"name":"Invalid"},
                    {"id":2,"name":" "},
                    "skip",
                    {"id":3,"name":"Three","album":{"id":71,"name":"Legacy"}}
                  ],
                  "trackIds":[{"id":1},{"id":0},{},{"id":3},"skip"]
                }}
            """.trimIndent(),
            errors = errors
        )

        assertEquals(42L, parsed.header.id)
        assertFalse(parsed.header.isAlbum)
        assertEquals("Mix", parsed.header.name)
        assertEquals("https://p.music/cover.jpg", parsed.header.coverUrl)
        assertEquals(9L, parsed.header.playCount)
        assertEquals(5, parsed.header.trackCount)
        assertEquals(listOf(1L, 3L), parsed.tracks.map { it.id })
        assertEquals(listOf(1L, 3L), parsed.trackIds)

        val first = parsed.tracks[0]
        assertEquals("A / B", first.artist)
        assertEquals(listOf(7L, 8L), first.neteaseArtists?.map { it.id })
        assertEquals("NeteaseAlbum", first.album)
        assertEquals(70L, first.albumId)
        assertEquals(1000L, first.durationMs)
        assertEquals("https://p.music/one.jpg", first.coverUrl)
        assertEquals("https://p.music/one.jpg", first.originalCoverUrl)
        assertEquals("netease", first.channelId)
        assertEquals("1", first.audioId)

        val legacy = parsed.tracks[1]
        assertEquals("NeteaseLegacy", legacy.album)
        assertEquals(71L, legacy.albumId)
        assertNull(legacy.coverUrl)
    }

    @Test
    fun `playlist detail without tracks keeps an empty list`() {
        val parsed = parseNeteasePlaylistDetailResponse(
            raw = """{"code":200,"playlist":{"id":1,"name":"Empty"}}""",
            errors = errors
        )

        assertTrue(parsed.tracks.isEmpty())
        assertTrue(parsed.trackIds.isEmpty())
        assertEquals("", parsed.header.coverUrl)
    }

    @Test
    fun `album detail uses the album cover for tracks without artwork`() {
        val parsed = parseNeteaseAlbumDetailResponse(
            raw = """
                {"code":200,"album":{"id":5,"name":"Record","picUrl":"","size":2},
                 "songs":[{"id":11,"name":"Side A","al":{"id":5,"name":"Record"}},
                          {"id":12,"name":"Side B","al":{"picUrl":"https://own.jpg"}}]}
            """.trimIndent(),
            coverFallback = "http://entry/cover.jpg",
            errors = errors
        )

        assertTrue(parsed.header.isAlbum)
        assertEquals(5L, parsed.header.id)
        assertEquals("Record", parsed.header.name)
        assertEquals("https://entry/cover.jpg", parsed.header.coverUrl)
        assertEquals(0L, parsed.header.playCount)
        assertEquals(2, parsed.header.trackCount)
        assertEquals("https://entry/cover.jpg", parsed.tracks[0].coverUrl)
        assertEquals("https://own.jpg", parsed.tracks[1].coverUrl)
        assertEquals("Netease", parsed.tracks[1].album)
    }

    @Test
    fun `song detail returns parsed songs or nothing when the list is absent`() {
        val songs = parseNeteaseSongDetailResponse(
            raw = """{"code":200,"songs":[{"id":9,"name":"Nine"}]}""",
            errors = errors
        )

        assertEquals(listOf("Nine"), songs.map { it.name })
        assertTrue(parseNeteaseSongDetailResponse("""{"code":200}""", errors).isEmpty())
    }

    @Test
    fun `responses report api codes and missing nodes with the provided messages`() {
        assertEquals(
            "api code 301",
            runCatching { parseNeteasePlaylistDetailResponse("""{"code":301}""", errors) }
                .exceptionOrNull()?.message
        )
        assertEquals(
            "api code -1",
            runCatching { parseNeteaseSongDetailResponse("""{}""", errors) }.exceptionOrNull()?.message
        )
        assertEquals(
            "missing playlist",
            runCatching { parseNeteasePlaylistDetailResponse("""{"code":200}""", errors) }
                .exceptionOrNull()?.message
        )
        assertEquals(
            "missing album",
            runCatching { parseNeteaseAlbumDetailResponse("""{"code":200}""", null, errors) }
                .exceptionOrNull()?.message
        )
    }

    @Test
    fun `expected track count prefers header then track ids then loaded tracks`() {
        val withHeader = detail(trackCount = 7, trackIds = listOf(1L, 2L))
        val withIds = detail(trackCount = 0, trackIds = listOf(1L, 2L, 3L))
        val withTracksOnly = parseNeteasePlaylistDetailResponse(
            raw = """{"code":200,"playlist":{"id":1,"tracks":[{"id":1,"name":"A"},{"id":2,"name":"B"}]}}""",
            errors = errors
        )

        assertEquals(7, withHeader.expectedTrackCount())
        assertEquals(3, withIds.expectedTrackCount())
        assertEquals(2, withTracksOnly.expectedTrackCount())
    }

    @Test
    fun `fresh header keeps previous values for blank or zero fields`() {
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 1L,
            header = CachedNeteasePlaylistHeader(
                id = 1L,
                name = "Cached",
                coverUrl = "https://cached.jpg",
                playCount = 10L,
                trackCount = 4
            ),
            recentTrackSignature = "sig",
            tracks = emptyList()
        )

        val unchanged = refreshNeteasePlaylistCachedHeader(
            cached,
            NeteaseCollectionHeader(id = 1L, isAlbum = false, name = " ", coverUrl = "", playCount = 0L, trackCount = 0)
        )
        val replaced = refreshNeteasePlaylistCachedHeader(
            cached,
            NeteaseCollectionHeader(id = 2L, isAlbum = false, name = "New", coverUrl = "https://new.jpg", playCount = 3L, trackCount = 9)
        )

        assertEquals(cached.header, unchanged.header)
        assertEquals(
            CachedNeteasePlaylistHeader(id = 1L, name = "New", coverUrl = "https://new.jpg", playCount = 3L, trackCount = 9),
            replaced.header
        )
    }

    @Test
    fun `cached header fills blank fields from the entry summary`() {
        val entry = PlaylistSummary(id = 3L, name = "Entry", picUrl = "http://entry.jpg", playCount = 5L, trackCount = 6)

        val blank = CachedNeteasePlaylistHeader(id = 3L, name = "", coverUrl = "", playCount = 0L, trackCount = 0)
            .toNeteaseCollectionHeader(entry)
        val noEntryCover = CachedNeteasePlaylistHeader(id = 3L, name = "", coverUrl = "", playCount = 0L, trackCount = 0)
            .toNeteaseCollectionHeader(entry.copy(picUrl = " "))
        val filled = CachedNeteasePlaylistHeader(id = 3L, name = "Cached", coverUrl = "https://c.jpg", playCount = 1L, trackCount = 2)
            .toNeteaseCollectionHeader(entry)

        assertEquals("Entry", blank.name)
        assertEquals("https://entry.jpg", blank.coverUrl)
        assertEquals(5L, blank.playCount)
        assertEquals(6, blank.trackCount)
        assertFalse(blank.isAlbum)
        assertEquals("", noEntryCover.coverUrl)
        assertEquals("Cached", filled.name)
        assertEquals("https://c.jpg", filled.coverUrl)
        assertEquals(1L, filled.playCount)
        assertEquals(2, filled.trackCount)
    }

    private fun detail(trackCount: Int, trackIds: List<Long>) = ParsedNeteaseCollectionDetail(
        header = NeteaseCollectionHeader(
            id = 1L,
            isAlbum = false,
            name = "Detail",
            coverUrl = "",
            playCount = 0L,
            trackCount = trackCount
        ),
        tracks = emptyList(),
        trackIds = trackIds
    )
}
