package moe.ouom.neriplayer.ui.screen.host

import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HomeHostRuntimeStateTest {

    @Test
    fun `legacy local system continue cards open canonical playlists without changing other ids`() {
        val localFiles = UsageEntry(-12, "Local Files", null, 1, "local", 100, 2)
        val favorites = localFiles.copy(id = -11, name = "我喜欢的音乐")

        assertEquals(HomeSelectedItem.Local(LocalFilesPlaylist.SYSTEM_ID), recentSelectedItem(localFiles))
        assertEquals(HomeSelectedItem.Local(FavoritesPlaylist.SYSTEM_ID), recentSelectedItem(favorites))
        assertEquals(HomeSelectedItem.Local(12), recentSelectedItem(localFiles.copy(id = 12)))
        assertEquals(HomeSelectedItem.Local(-13), recentSelectedItem(localFiles.copy(id = -13, name = "custom playlist")))
        assertEquals(-12L, (recentSelectedItem(localFiles.copy(source = "netease")) as HomeSelectedItem.Netease).playlist.id)
    }

    @Test
    fun newRuntimeStateStartsHomeAtTop() {
        val state = HomeHostRuntimeState()

        assertEquals(0, state.gridState.firstVisibleItemIndex)
        assertEquals(0, state.gridState.firstVisibleItemScrollOffset)
        assertEquals(0, state.radarPlaylistListState.firstVisibleItemIndex)
        assertEquals(0, state.radarPlaylistListState.firstVisibleItemScrollOffset)
        assertEquals(0f, state.topAppBarState.heightOffset, 0f)
        assertEquals(0f, state.topAppBarState.contentOffset, 0f)
        assertNull(state.pendingGridRestoreIndex)
        assertEquals(0, state.pendingGridRestoreOffset)
        assertNull(state.pendingGridRestoreKey)
        assertFalse(state.pendingGridRestoreArmed)
        assertEquals(emptyMap<String, Int>(), state.homeScrollAnchorIndexes)
    }

    @Test
    fun separateRuntimeStateDoesNotCarryPreviousRestoreRequest() {
        val previousState = HomeHostRuntimeState()
        previousState.pendingGridRestoreIndex = 8
        previousState.pendingGridRestoreOffset = 24
        previousState.pendingGridRestoreArmed = true

        val nextState = HomeHostRuntimeState()

        assertNull(nextState.pendingGridRestoreIndex)
        assertEquals(0, nextState.pendingGridRestoreOffset)
        assertFalse(nextState.pendingGridRestoreArmed)
        assertEquals(0, nextState.radarPlaylistListState.firstVisibleItemIndex)
        assertEquals(0, nextState.radarPlaylistListState.firstVisibleItemScrollOffset)
    }
}
