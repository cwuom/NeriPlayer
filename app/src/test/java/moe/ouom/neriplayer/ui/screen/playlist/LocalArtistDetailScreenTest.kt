package moe.ouom.neriplayer.ui.screen.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtistDetailScreenTest {

    @Test
    fun `missing artist is retained until local playlists are ready`() {
        assertFalse(
            shouldRemoveMissingLocalArtistUsage(
                localPlaylistsReady = false,
                artistFound = false
            )
        )
    }

    @Test
    fun `missing artist is removed after local playlists are ready`() {
        assertTrue(
            shouldRemoveMissingLocalArtistUsage(
                localPlaylistsReady = true,
                artistFound = false
            )
        )
        assertFalse(
            shouldRemoveMissingLocalArtistUsage(
                localPlaylistsReady = true,
                artistFound = true
            )
        )
    }

    @Test
    fun `search index stays deferred until local artist search is visible`() {
        assertFalse(
            shouldBuildPlaylistSearchIndex(
                searchVisible = false,
                query = ""
            )
        )
        assertTrue(
            shouldBuildPlaylistSearchIndex(
                searchVisible = true,
                query = ""
            )
        )
        assertTrue(
            shouldBuildPlaylistSearchIndex(
                searchVisible = false,
                query = "周杰伦"
            )
        )
    }
}
