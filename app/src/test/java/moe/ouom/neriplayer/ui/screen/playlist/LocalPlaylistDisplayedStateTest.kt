package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalPlaylistDisplayedStateTest {

    @Test
    fun `state requested for another playlist is replaced by a fresh pending state`() {
        val stale = LocalPlaylistDetailUiState(
            playlist = LocalPlaylist(id = 1L, name = "Old"),
            isResolved = true,
            requestedPlaylistId = 1L
        )

        assertEquals(
            LocalPlaylistDetailUiState(requestedPlaylistId = 2L),
            resolveDisplayedLocalPlaylistDetailState(stale, requestedPlaylistId = 2L)
        )
    }

    @Test
    fun `unresolved state for the requested playlist is shown as is`() {
        val pending = LocalPlaylistDetailUiState(requestedPlaylistId = 2L)

        assertSame(pending, resolveDisplayedLocalPlaylistDetailState(pending, requestedPlaylistId = 2L))
    }

    @Test
    fun `state without a request id or playlist is shown as is`() {
        val initial = LocalPlaylistDetailUiState(isResolved = true)

        assertSame(initial, resolveDisplayedLocalPlaylistDetailState(initial, requestedPlaylistId = 5L))
    }

    @Test
    fun `loaded playlist matching the request is shown`() {
        val loaded = LocalPlaylistDetailUiState(
            playlist = LocalPlaylist(id = 2L, name = "Current"),
            isResolved = true,
            requestedPlaylistId = 2L
        )

        assertSame(loaded, resolveDisplayedLocalPlaylistDetailState(loaded, requestedPlaylistId = 2L))
    }

    @Test
    fun `loaded playlist from a previous screen is hidden behind a pending state`() {
        val leftover = LocalPlaylistDetailUiState(
            playlist = LocalPlaylist(id = 1L, name = "Previous"),
            isResolved = true
        )

        assertEquals(
            LocalPlaylistDetailUiState(requestedPlaylistId = 2L),
            resolveDisplayedLocalPlaylistDetailState(leftover, requestedPlaylistId = 2L)
        )
    }
}
