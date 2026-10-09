package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.bilibili.collection.FavFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelMappingTest {

    @Test
    fun `netease playlists keep only identified named entries`() {
        val raw = """
            {"code":200,"playlist":[
              {"id":1,"name":"Liked","coverImgUrl":"http://p1.music.126.net/a.jpg","playCount":12,"trackCount":3},
              {"id":0,"name":"No id"},
              {"id":2,"name":" "},
              "not an object",
              {"id":3,"name":"Plain"}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                PlaylistSummary(1L, "Liked", "https://p1.music.126.net/a.jpg", 12L, 3),
                PlaylistSummary(3L, "Plain", "", 0L, 0)
            ),
            parseNeteaseLibraryPlaylists(raw)
        )
    }

    @Test
    fun `netease library responses without success or list are empty`() {
        assertTrue(parseNeteaseLibraryPlaylists("""{"code":301,"playlist":[{"id":1,"name":"A"}]}""").isEmpty())
        assertTrue(parseNeteaseLibraryPlaylists("""{"code":200}""").isEmpty())
        assertTrue(parseNeteaseLibraryAlbums("""{"code":500}""").isEmpty())
        assertTrue(parseNeteaseLibraryAlbums("""{"code":200}""").isEmpty())
    }

    @Test
    fun `netease albums read nested album data and its picture`() {
        val raw = """
            {"code":200,"playlist":[
              {"dataInfo":{"picUrl":"http://p2.music.126.net/b.jpg","data":{"id":10,"name":"Album","size":8}}},
              {"dataInfo":{"picUrl":"x"}},
              {"other":{}},
              {"dataInfo":{"data":{"id":0,"name":"No id"}}},
              {"dataInfo":{"data":{"id":11,"name":""}}},
              7,
              {"dataInfo":{"data":{"id":12,"name":"Bare"}}}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                AlbumSummary(10L, "Album", "https://p2.music.126.net/b.jpg", 8),
                AlbumSummary(12L, "Bare", "", 0)
            ),
            parseNeteaseLibraryAlbums(raw)
        )
    }

    @Test
    fun `bili favorite folders use loaded detail and owner labels`() = runTest {
        val requested = mutableListOf<Long>()
        val listed = folder(title = "Listed", mid = 9L, upperName = "")
        val detail = listed.copy(title = "Detail", coverUrl = "http://i0.hdslb.com/d.jpg", count = 20)

        val mapped = mapLibraryBiliFolder(listed, BiliPlaylistKind.CREATED_FAVORITE, currentMid = 7L) { mediaId ->
            requested += mediaId
            detail
        }

        assertEquals(listOf(5L), requested)
        assertEquals(
            BiliPlaylist(
                mediaId = 5L,
                fid = 6L,
                mid = 9L,
                title = "Detail",
                count = 20,
                coverUrl = "https://i0.hdslb.com/d.jpg",
                kind = BiliPlaylistKind.CREATED_FAVORITE,
                subtitle = "9"
            ),
            mapped
        )
    }

    @Test
    fun `bili folder owner label hides the current user and missing owners`() = runTest {
        suspend fun subtitle(folder: FavFolder) =
            mapLibraryBiliFolder(folder, BiliPlaylistKind.COLLECTED_FAVORITE, currentMid = 7L) { null }?.subtitle

        assertEquals("Owner", subtitle(folder(upperName = "Owner", mid = 9L)))
        assertEquals("", subtitle(folder(upperName = "", mid = 7L)))
        assertEquals("", subtitle(folder(upperName = "", mid = 0L)))
    }

    @Test
    fun `bili collections skip detail loading and blank titles are dropped`() = runTest {
        var detailRequests = 0
        val collection = folder(title = "Season").copy(itemType = 21)

        val mapped = mapLibraryBiliFolder(collection, BiliPlaylistKind.COLLECTED_FAVORITE, currentMid = 7L) {
            detailRequests++
            null
        }
        val explicitCollection = mapLibraryBiliFolder(folder(title = "Series"), BiliPlaylistKind.COLLECTION, 7L) {
            detailRequests++
            null
        }
        val untitled = mapLibraryBiliFolder(folder(title = " "), BiliPlaylistKind.CREATED_FAVORITE, 7L) { null }

        assertEquals(0, detailRequests)
        assertEquals(BiliPlaylistKind.COLLECTION, mapped?.kind)
        assertEquals("Season", mapped?.title)
        assertEquals(BiliPlaylistKind.COLLECTION, explicitCollection?.kind)
        assertNull(untitled)
    }

    private fun folder(
        title: String = "Folder",
        mid: Long = 9L,
        upperName: String = "Owner"
    ) = FavFolder(
        mediaId = 5L,
        fid = 6L,
        mid = mid,
        title = title,
        coverUrl = "https://cover.jpg",
        intro = "",
        count = 4,
        likeCount = null,
        playCount = null,
        collectCount = null,
        upperName = upperName
    )
}
