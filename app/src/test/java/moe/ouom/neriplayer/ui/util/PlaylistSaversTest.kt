package moe.ouom.neriplayer.ui.util

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistSaversTest {
    @Test
    fun biliPlaylistRoundTrip_keepsKindAndSubtitle() {
        val original = BiliPlaylist(
            mediaId = 9988L,
            fid = 7766L,
            mid = 5544L,
            title = "收藏的合集",
            count = 42,
            coverUrl = "https://example.test/cover.jpg",
            kind = BiliPlaylistKind.COLLECTION,
            subtitle = "哔哩哔哩拜年纪"
        )

        val restored = restoreBiliPlaylist(original.toSaveMap())
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun biliPlaylistRoundTrip_keepsSeriesKind() {
        val original = BiliPlaylist(
            mediaId = 1234L,
            fid = 1234L,
            mid = 5678L,
            title = "Android series",
            count = 8,
            coverUrl = "https://example.test/series.jpg",
            kind = BiliPlaylistKind.SERIES,
            subtitle = "Uploader"
        )

        assertEquals(original, restoreBiliPlaylist(original.toSaveMap()))
    }

    @Test
    fun youTubeMusicPlaylistRoundTrip_keepsCreatorName() {
        val original = YouTubeMusicPlaylist(
            browseId = "MPREdemoAlbum",
            playlistId = "MPREdemoAlbum",
            title = "Demo Album",
            subtitle = "Album",
            coverUrl = "https://example.test/album.jpg",
            trackCount = 12,
            creatorName = "Demo Creator"
        )

        assertEquals(original, restoreYouTubeMusicPlaylist(original.toSaveMap()))
    }

    @Test
    fun albumAndPlaylistSummaries_roundTripAndDefaultOptionalFields() {
        val album = AlbumSummary(id = 7L, name = "Album", picUrl = "https://example.test/a.jpg", size = 11)
        assertEquals(album, restoreAlbumSummary(album.toSaveMap()))
        assertEquals(
            AlbumSummary(id = 7L, name = "Album", picUrl = "", size = 0),
            restoreAlbumSummary(mapOf("id" to 7, "name" to "Album", "picUrl" to 3, "trackCount" to "11"))
        )

        val playlist = PlaylistSummary(id = 9L, name = "Mix", picUrl = "pic", playCount = 1234L, trackCount = 30)
        assertEquals(playlist, restorePlaylistSummary(playlist.toSaveMap()))
        assertEquals(
            PlaylistSummary(id = 9L, name = "Mix", picUrl = "", playCount = 0L, trackCount = 0),
            restorePlaylistSummary(mapOf("id" to 9.0, "name" to "Mix"))
        )
    }

    @Test
    fun restore_rejectsMissingOrMistypedRequiredFields() {
        assertNull(restoreAlbumSummary(null))
        assertNull(restoreAlbumSummary(emptyMap<String, Any>()))
        assertNull(restoreAlbumSummary(mapOf("id" to "7", "name" to "Album")))
        assertNull(restoreAlbumSummary(mapOf("id" to 7L, "name" to 1)))
        assertNull(restorePlaylistSummary(null))
        assertNull(restorePlaylistSummary(emptyMap<String, Any>()))
        assertNull(restorePlaylistSummary(mapOf("name" to "Mix")))
        assertNull(restorePlaylistSummary(mapOf("id" to 9L)))
        assertNull(restoreBiliPlaylist(null))
        assertNull(restoreBiliPlaylist(emptyMap<String, Any>()))
        assertNull(restoreBiliPlaylist(mapOf("title" to "Fav")))
        assertNull(restoreBiliPlaylist(mapOf("mediaId" to 1L)))
        assertNull(restoreYouTubeMusicPlaylist(null))
        assertNull(restoreYouTubeMusicPlaylist(emptyMap<String, Any>()))
        assertNull(restoreYouTubeMusicPlaylist(mapOf("playlistId" to "PL", "title" to "T")))
        assertNull(restoreYouTubeMusicPlaylist(mapOf("browseId" to "VL", "title" to "T")))
        assertNull(restoreYouTubeMusicPlaylist(mapOf("browseId" to "VL", "playlistId" to "PL")))
    }

    @Test
    fun biliPlaylist_defaultsOptionalFieldsAndUnknownKind() {
        val expected = BiliPlaylist(
            mediaId = 5L,
            fid = 0L,
            mid = 0L,
            title = "Fav",
            count = 0,
            coverUrl = "",
            kind = BiliPlaylistKind.CREATED_FAVORITE,
            subtitle = ""
        )
        assertEquals(expected, restoreBiliPlaylist(mapOf("mediaId" to 5, "title" to "Fav")))
        assertEquals(expected, restoreBiliPlaylist(mapOf("mediaId" to 5, "title" to "Fav", "kind" to "UNKNOWN")))
        assertEquals(
            expected.copy(kind = BiliPlaylistKind.COLLECTED_FAVORITE),
            restoreBiliPlaylist(mapOf("mediaId" to 5, "title" to "Fav", "kind" to "COLLECTED_FAVORITE"))
        )
    }

    @Test
    fun youTubeMusicPlaylist_fallsBackToLegacyCountAndEmptyText() {
        val base = mapOf("browseId" to "VL1", "playlistId" to "PL1", "title" to "Mix")
        val expected = YouTubeMusicPlaylist(
            browseId = "VL1",
            playlistId = "PL1",
            title = "Mix",
            subtitle = "",
            coverUrl = "",
            trackCount = 0,
            creatorName = ""
        )
        assertEquals(expected, restoreYouTubeMusicPlaylist(base))
        assertEquals(expected.copy(trackCount = 4), restoreYouTubeMusicPlaylist(base + ("count" to 4)))
        assertEquals(
            expected.copy(trackCount = 6),
            restoreYouTubeMusicPlaylist(base + ("count" to 4) + ("trackCount" to 6))
        )
    }

    @Test
    fun savers_restoreNullForEmptyStateAndRoundTripValues() {
        val playlist = PlaylistSummary(id = 9L, name = "Mix", picUrl = "pic", playCount = 1L, trackCount = 2)
        val bili = BiliPlaylist(mediaId = 1L, fid = 2L, mid = 3L, title = "Fav", count = 4, coverUrl = "c")

        assertNull(roundTrip(playlistSummarySaver, null))
        assertEquals(playlist, roundTrip(playlistSummarySaver, playlist))
        assertNull(roundTrip(biliPlaylistSaver, null))
        assertEquals(bili, roundTrip(biliPlaylistSaver, bili))
    }

    private fun <T> roundTrip(saver: Saver<T?, Any>, value: T?): T? {
        val scope = SaverScope { true }
        val saved = with(saver) { scope.save(value) }
        return saved?.let(saver::restore)
    }
}
