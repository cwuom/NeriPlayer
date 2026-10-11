package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.ui.util.toSaveMap
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostSelectedItemSaversTest {

    private val playlist = PlaylistSummary(id = 7L, name = "Daily", picUrl = "pic", playCount = 3L, trackCount = 12)
    private val album = AlbumSummary(id = 8L, name = "Album", picUrl = "album-pic", size = 9)
    private val bili = BiliPlaylist(
        mediaId = 1L,
        fid = 2L,
        mid = 3L,
        title = "Fav",
        count = 4,
        coverUrl = "cover",
        kind = BiliPlaylistKind.COLLECTION,
        subtitle = "sub"
    )
    private val youtube = YouTubeMusicPlaylist(
        browseId = "VLPL1",
        playlistId = "PL1",
        title = "Mix",
        subtitle = "Playlist",
        coverUrl = "yt-cover",
        trackCount = 5
    )
    private val artist = NeteaseArtistSummary(id = 42L, name = "Artist")

    @Test
    fun `library saver round trips every selected item type`() {
        val items = listOf(
            null,
            LibrarySelectedItem.Local(playlistId = 11L),
            LibrarySelectedItem.LocalArtist(artistName = "Local Artist"),
            LibrarySelectedItem.Hot(monthly = true),
            LibrarySelectedItem.Netease(playlist),
            LibrarySelectedItem.NeteaseAlbum(album),
            LibrarySelectedItem.NeteaseArtist(artist),
            LibrarySelectedItem.NeteaseArtistAlbum(artist, album),
            LibrarySelectedItem.Bili(bili),
            LibrarySelectedItem.YouTubeMusic(youtube),
            LibrarySelectedItem.BiliUploader(BiliUploaderSummary(mid = 99L, name = "Up", avatarUrl = "avatar")),
            LibrarySelectedItem.YouTubeCreator(
                browseId = "UC1",
                title = "Creator",
                subtitle = "Artist",
                coverUrl = "creator-cover"
            )
        )

        items.forEach { item -> assertEquals(item, roundTrip(librarySelectedItemSaver, item)) }
    }

    @Test
    fun `library saver keeps the persisted key layout`() {
        assertEquals(
            mapOf("type" to "neteaseArtist", "artistId" to 42L, "artistName" to "Artist"),
            LibrarySelectedItem.NeteaseArtist(artist).saveState()
        )
        assertEquals(
            mapOf("type" to "biliArtist", "mid" to 99L, "name" to "Up", "avatar" to ""),
            LibrarySelectedItem.BiliUploader(BiliUploaderSummary(mid = 99L, name = "Up")).saveState()
        )
        assertEquals(
            mapOf("type" to "hot", "monthly" to false),
            LibrarySelectedItem.Hot(monthly = false).saveState()
        )
    }

    @Test
    fun `library saver rejects incomplete saved state`() {
        val invalid = listOf(
            mapOf("playlistId" to 1L),
            mapOf("type" to "unknown"),
            mapOf("type" to "local", "playlistId" to "1"),
            mapOf("type" to "localArtist", "artistName" to " "),
            mapOf("type" to "netease"),
            mapOf("type" to "neteaseAlbum", "album" to "not a map"),
            mapOf("type" to "neteaseArtist", "artistId" to 0L, "artistName" to "Artist"),
            mapOf("type" to "neteaseArtist", "artistId" to 42L, "artistName" to ""),
            mapOf("type" to "neteaseArtistAlbum", "artistId" to 42L, "artistName" to "Artist"),
            mapOf("type" to "neteaseArtistAlbum", "album" to album.toSaveMap()),
            mapOf("type" to "bili"),
            mapOf("type" to "ytmusic"),
            mapOf("type" to "biliArtist", "mid" to 0L, "name" to "Up"),
            mapOf("type" to "biliArtist", "mid" to 99L, "name" to " "),
            mapOf("type" to "youtubeArtist", "browseId" to "", "title" to "Creator"),
            mapOf("type" to "youtubeArtist", "browseId" to "UC1")
        )

        invalid.forEach { saved -> assertNull(saved.toString(), restore(librarySelectedItemSaver, saved)) }
    }

    @Test
    fun `library saver fills optional fields with defaults`() {
        assertEquals(
            LibrarySelectedItem.Hot(monthly = false),
            restore(librarySelectedItemSaver, mapOf("type" to "hot", "monthly" to "yes"))
        )
        assertEquals(
            LibrarySelectedItem.Local(playlistId = 5L),
            restore(librarySelectedItemSaver, mapOf("type" to "local", "playlistId" to 5))
        )
        assertEquals(
            LibrarySelectedItem.BiliUploader(BiliUploaderSummary(mid = 99L, name = "Up", avatarUrl = "")),
            restore(librarySelectedItemSaver, mapOf("type" to "biliArtist", "mid" to 99, "name" to "Up"))
        )
        assertEquals(
            LibrarySelectedItem.YouTubeCreator(browseId = "UC1", title = "Creator", subtitle = "", coverUrl = ""),
            restore(librarySelectedItemSaver, mapOf("type" to "youtubeArtist", "browseId" to "UC1", "title" to "Creator"))
        )
    }

    @Test
    fun `home saver round trips every selected item type and rejects unknown state`() {
        val items = listOf(
            null,
            HomeSelectedItem.Local(playlistId = 3L),
            HomeSelectedItem.LocalArtist(artistName = "Local Artist"),
            HomeSelectedItem.Netease(playlist),
            HomeSelectedItem.NeteaseAlbumList(album),
            HomeSelectedItem.Bili(bili),
            HomeSelectedItem.YouTubeMusic(youtube)
        )

        items.forEach { item -> assertEquals(item, roundTrip(homeSelectedItemSaver, item)) }
        assertEquals(mapOf("type" to "neteaseAlbum", "album" to album.toSaveMap()), HomeSelectedItem.NeteaseAlbumList(album).saveState())
        assertNull(restore(homeSelectedItemSaver, mapOf("type" to "hot", "monthly" to true)))
        assertNull(restore(homeSelectedItemSaver, mapOf("type" to "localArtist", "artistName" to "")))
        assertNull(restore(homeSelectedItemSaver, mapOf("type" to "local")))
    }

    @Test
    fun `recent entries open the matching platform detail`() {
        val base = UsageEntry(
            id = 5L,
            name = "Recent",
            picUrl = null,
            trackCount = 6,
            source = "",
            lastOpened = 100L,
            openCount = 1
        )

        assertEquals(
            HomeSelectedItem.Netease(PlaylistSummary(id = 5L, name = "Recent", picUrl = "", playCount = 0L, trackCount = 6)),
            recentSelectedItem(base.copy(source = "netease", picUrl = null))
        )
        assertEquals(
            HomeSelectedItem.NeteaseAlbumList(AlbumSummary(id = 5L, name = "Recent", picUrl = "pic", size = 6)),
            recentSelectedItem(base.copy(source = "neteaseAlbum", picUrl = "pic"))
        )
        assertEquals(HomeSelectedItem.Local(5L), recentSelectedItem(base.copy(source = "LOCAL")))
        assertEquals(HomeSelectedItem.LocalArtist("Recent"), recentSelectedItem(base.copy(source = "localArtist")))
        assertNull(recentSelectedItem(base.copy(source = "spotify")))
    }

    @Test
    fun `recent bili entries default missing ids and unknown kinds`() {
        val entry = UsageEntry(
            id = 5L,
            name = "Fav",
            picUrl = "cover",
            trackCount = 6,
            source = "bili",
            lastOpened = 100L,
            openCount = 1,
            subtype = "NOT_A_KIND"
        )

        assertEquals(
            HomeSelectedItem.Bili(
                BiliPlaylist(
                    mediaId = 5L,
                    fid = 0L,
                    mid = 0L,
                    title = "Fav",
                    count = 6,
                    coverUrl = "cover",
                    kind = BiliPlaylistKind.CREATED_FAVORITE,
                    subtitle = ""
                )
            ),
            recentSelectedItem(entry)
        )
        assertEquals(
            HomeSelectedItem.Bili(
                BiliPlaylist(
                    mediaId = 5L,
                    fid = 7L,
                    mid = 8L,
                    title = "Fav",
                    count = 6,
                    coverUrl = "cover",
                    kind = BiliPlaylistKind.COLLECTION,
                    subtitle = "season"
                )
            ),
            recentSelectedItem(entry.copy(fid = 7L, mid = 8L, subtype = "COLLECTION", subtitle = "season"))
        )
    }

    @Test
    fun `recent youtube music entries resolve browse and playlist ids`() {
        val entry = UsageEntry(
            id = 0L,
            name = "Mix",
            picUrl = null,
            trackCount = 4,
            source = "youtubeMusic",
            lastOpened = 100L,
            openCount = 1
        )

        fun expected(browseId: String, playlistId: String) = HomeSelectedItem.YouTubeMusic(
            YouTubeMusicPlaylist(
                browseId = browseId,
                playlistId = playlistId,
                title = "Mix",
                subtitle = "",
                coverUrl = "",
                trackCount = 4
            )
        )

        assertEquals(expected("VLPL1", "PL1"), recentSelectedItem(entry.copy(playlistId = "PL1")))
        assertEquals(expected("VLPL1", "VLPL1"), recentSelectedItem(entry.copy(playlistId = "VLPL1")))
        assertEquals(expected("VLPL2", "PL2"), recentSelectedItem(entry.copy(browseId = "VLPL2")))
        assertEquals(expected("MPRE1", "MPRE1"), recentSelectedItem(entry.copy(browseId = "MPRE1", playlistId = " ")))
        assertEquals(expected("VLPL3", "PL9"), recentSelectedItem(entry.copy(browseId = "VLPL3", playlistId = "PL9")))
        assertNull(recentSelectedItem(entry.copy(browseId = " ", playlistId = "")))
        assertNull(recentSelectedItem(entry))
    }

    private fun <T> roundTrip(saver: Saver<T?, Any>, value: T?): T? {
        val scope = SaverScope { true }
        val saved = with(saver) { scope.save(value) }
        return saved?.let(saver::restore)
    }

    private fun <T> restore(saver: Saver<T?, Any>, saved: Map<String, Any?>): T? =
        saver.restore(saved.flatMap { (key, value) -> listOf(key, value) })
}
