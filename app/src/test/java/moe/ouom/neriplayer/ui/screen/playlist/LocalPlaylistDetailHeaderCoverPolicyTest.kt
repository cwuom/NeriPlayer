package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistDetailHeaderCoverPolicyTest {

    @Test
    fun `normalizeLocalPlaylistHeaderCoverModel uses blank model for null cover`() {
        assertEquals("about:blank", normalizeLocalPlaylistHeaderCoverModel(null))
    }

    @Test
    fun `normalizeLocalPlaylistHeaderCoverModel uses blank model for blank cover`() {
        assertEquals("about:blank", normalizeLocalPlaylistHeaderCoverModel("   "))
    }

    @Test
    fun `normalizeLocalPlaylistHeaderCoverModel keeps non blank cover`() {
        assertEquals(
            "content://covers/demo.jpg",
            normalizeLocalPlaylistHeaderCoverModel("content://covers/demo.jpg")
        )
    }

    @Test
    fun `resolveDisplayedLocalPlaylistDetailState hides stale playlist from previous route`() {
        val staleState = LocalPlaylistDetailUiState(
            playlist = LocalPlaylist(id = 1L, name = "old playlist"),
            isResolved = true,
            requestedPlaylistId = 1L
        )

        assertEquals(
            LocalPlaylistDetailUiState(requestedPlaylistId = 2L),
            resolveDisplayedLocalPlaylistDetailState(staleState, requestedPlaylistId = 2L)
        )
    }

    @Test
    fun `resolveDisplayedLocalPlaylistDetailState hides stale deletion from previous route`() {
        val staleDeletionState = LocalPlaylistDetailUiState(
            playlist = null,
            isResolved = true,
            requestedPlaylistId = 1L
        )

        assertEquals(
            LocalPlaylistDetailUiState(requestedPlaylistId = 2L),
            resolveDisplayedLocalPlaylistDetailState(staleDeletionState, requestedPlaylistId = 2L)
        )
    }

    @Test
    fun `resolveDisplayedLocalPlaylistDetailState keeps deletion state for current route`() {
        val resolvedDeletionState = LocalPlaylistDetailUiState(
            playlist = null,
            isResolved = true,
            requestedPlaylistId = 2L
        )

        assertEquals(
            resolvedDeletionState,
            resolveDisplayedLocalPlaylistDetailState(
                resolvedDeletionState,
                requestedPlaylistId = 2L
            )
        )
    }
}
