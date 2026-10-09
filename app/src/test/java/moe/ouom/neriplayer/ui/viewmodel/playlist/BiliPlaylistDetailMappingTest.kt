package moe.ouom.neriplayer.ui.viewmodel.playlist

import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.bilibili.collection.CollectionArchiveItem
import moe.ouom.neriplayer.data.model.bilibili.collection.FavFolder
import moe.ouom.neriplayer.data.model.bilibili.collection.FavResourceItem
import moe.ouom.neriplayer.data.model.bilibili.collection.FavResourcePage
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliPagedItems
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliPlaylistDetailMappingTest {

    @Test
    fun `favorite folders and paged archives are told apart by kind`() {
        val favoriteKinds = BiliPlaylistKind.entries.filter { playlist(kind = it).isFavoriteFolder() }
        val pagedKinds = BiliPlaylistKind.entries.filter { it.hasPagedArchives() }

        assertEquals(listOf(BiliPlaylistKind.CREATED_FAVORITE, BiliPlaylistKind.COLLECTED_FAVORITE), favoriteKinds)
        assertEquals(listOf(BiliPlaylistKind.COLLECTION, BiliPlaylistKind.SERIES), pagedKinds)
    }

    @Test
    fun `playlist identity needs the same media id and kind`() {
        val original = playlist(mediaId = 1L, kind = BiliPlaylistKind.COLLECTION)

        assertTrue(original.sameIdentityAs(original.copy(title = "Renamed", count = 99)))
        assertFalse(original.sameIdentityAs(original.copy(mediaId = 2L)))
        assertFalse(original.sameIdentityAs(original.copy(kind = BiliPlaylistKind.SERIES)))
    }

    @Test
    fun `favorite video items require a bvid and upgrade covers`() {
        val video = favoriteItem(bvid = "BV1", coverUrl = "http://i0.hdslb.com/a.jpg").toVideoItem()

        assertEquals(
            BiliVideoItem(
                id = 10L,
                bvid = "BV1",
                title = "Video",
                uploader = "Uploader",
                uploaderMid = 7L,
                coverUrl = "https://i0.hdslb.com/a.jpg",
                durationSec = 60
            ),
            video
        )
        assertNull(favoriteItem(bvid = null).toVideoItem())
        assertNull(favoriteItem(bvid = " ").toVideoItem())
    }

    @Test
    fun `latest page signature changes with any visible item field`() {
        val page = FavResourcePage(
            info = favFolder(count = 2),
            items = listOf(favoriteItem(bvid = "BV1"), favoriteItem(bvid = null).copy(favTime = null)),
            hasMore = false
        )

        assertEquals("2#2:10:BV1:5:60:Video|2:10::0:60:Video|", page.latestPageSignature())
        assertNotEquals(
            page.latestPageSignature(),
            page.copy(items = page.items.map { it.copy(title = "Renamed") }).latestPageSignature()
        )
    }

    @Test
    fun `favorite items expand collections and skip unplayable entries`() = runTest {
        val requested = mutableListOf<Pair<Long, Long>>()
        val result = mapBiliFavoriteItemsToVideos(
            items = listOf(
                favoriteItem(id = 1L, bvid = "BV1"),
                favoriteItem(id = 2L, bvid = null),
                favoriteItem(id = 3L, bvid = "BV3").copy(type = 12),
                favoriteItem(id = 40L, bvid = null).copy(type = 21, upperName = "", title = "Season"),
                favoriteItem(id = 1L, bvid = "BV1")
            )
        ) { upperMid, seasonId ->
            requested += upperMid to seasonId
            BiliPagedItems(
                listOf(archive(aid = 5L, bvid = "BV5"), archive(aid = 6L, bvid = "")),
                missingPages = 2
            )
        }

        assertEquals(listOf(7L to 40L), requested)
        assertEquals(listOf("BV1", "BV5", ""), result.items.map { it.bvid })
        assertEquals(listOf(1L, 5L, 6L), result.items.map { it.id })
        assertEquals("Season", result.items[1].uploader)
        assertEquals(7L, result.items[1].uploaderMid)
        assertEquals("https://archive.jpg", result.items[1].coverUrl)
        assertEquals(2, result.missingPages)
    }

    @Test
    fun `failed collection loads are counted as missing pages`() = runTest {
        val result = mapBiliFavoriteItemsToVideos(
            items = listOf(favoriteItem(id = 40L, bvid = null).copy(type = 21))
        ) { _, _ -> throw IOException("offline") }

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.missingPages)
    }

    private fun playlist(
        mediaId: Long = 1L,
        kind: BiliPlaylistKind = BiliPlaylistKind.CREATED_FAVORITE
    ) = BiliPlaylist(
        mediaId = mediaId,
        fid = 0L,
        mid = 7L,
        title = "Playlist",
        count = 3,
        coverUrl = "",
        kind = kind
    )

    private fun favoriteItem(
        id: Long = 10L,
        bvid: String?,
        coverUrl: String = "https://cover.jpg"
    ) = FavResourceItem(
        type = 2,
        id = id,
        bvid = bvid,
        title = "Video",
        coverUrl = coverUrl,
        intro = "",
        durationSec = 60,
        upperMid = 7L,
        upperName = "Uploader",
        play = null,
        danmaku = null,
        favTime = 5L
    )

    private fun archive(aid: Long, bvid: String) = CollectionArchiveItem(
        aid = aid,
        bvid = bvid,
        title = "Archive $aid",
        coverUrl = "http://archive.jpg",
        durationSec = 30,
        pubdate = null,
        play = null
    )

    private fun favFolder(count: Int) = FavFolder(
        mediaId = 1L,
        fid = 1L,
        mid = 7L,
        title = "Folder",
        coverUrl = "",
        intro = "",
        count = count,
        likeCount = null,
        playCount = null,
        collectCount = null
    )
}
