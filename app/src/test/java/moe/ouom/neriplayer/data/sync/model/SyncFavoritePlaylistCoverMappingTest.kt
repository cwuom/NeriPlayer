package moe.ouom.neriplayer.data.sync.model

import android.content.Context
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylist
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class SyncFavoritePlaylistCoverMappingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        CoverUrlMapper.installForTest(null)
    }

    @Test
    fun `favorite playlist sync maps local playlist cover to network url`() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        `when`(context.applicationContext).thenReturn(context)
        val localCover = File(tempFolder.root, "cover.jpg").toURI().toString()
        val networkCover = "https://example.com/covers/favorite.jpg"
        val mapper = CoverUrlMapper.createForTest()
        CoverUrlMapper.installForTest(mapper)
        mapper.saveCoverMapping(localCover, networkCover)
        val playlist = FavoritePlaylist(
            id = 7L,
            name = "favorite",
            coverUrl = localCover,
            trackCount = 1,
            source = "netease",
            songs = listOf(remoteSong())
        )

        val active = SyncFavoritePlaylist.fromFavoritePlaylist(playlist, context)
        val deleted = SyncFavoritePlaylist.fromFavoritePlaylist(
            playlist.copy(isDeleted = true),
            context
        )

        assertEquals(networkCover, active.coverUrl)
        assertEquals(networkCover, deleted.coverUrl)
    }

    private fun remoteSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "song",
            artist = "artist",
            album = "netease",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/covers/song.jpg",
            channelId = "netease",
            audioId = "42"
        )
    }
}
