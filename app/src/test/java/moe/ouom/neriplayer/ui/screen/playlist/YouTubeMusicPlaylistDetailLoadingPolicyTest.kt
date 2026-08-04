package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.ui.viewmodel.playlist.shouldMarkYouTubeMusicPlaylistTracksUnavailable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicPlaylistDetailLoadingPolicyTest {

    @Test
    fun showsLoading_whenRequestedPlaylistHasNoPublishedState() {
        assertTrue(
            shouldShowYouTubeMusicPlaylistDetailLoading(
                requestedBrowseId = "VLrequested",
                loadedBrowseId = null,
                loading = false
            )
        )
    }

    @Test
    fun showsLoading_whenPublishedStateBelongsToAnotherPlaylist() {
        assertTrue(
            shouldShowYouTubeMusicPlaylistDetailLoading(
                requestedBrowseId = "VLrequested",
                loadedBrowseId = "VLprevious",
                loading = false
            )
        )
    }

    @Test
    fun hidesLoading_whenRequestedPlaylistIsFullyPublished() {
        assertFalse(
            shouldShowYouTubeMusicPlaylistDetailLoading(
                requestedBrowseId = "VLrequested",
                loadedBrowseId = "VLrequested",
                loading = false
            )
        )
    }

    @Test
    fun marksTracksUnavailable_onlyWhenRemotePlaylistClaimsTracksButParsesNone() {
        assertTrue(
            shouldMarkYouTubeMusicPlaylistTracksUnavailable(
                declaredTrackCount = 1,
                hasUsableTracks = false
            )
        )
        assertFalse(
            shouldMarkYouTubeMusicPlaylistTracksUnavailable(
                declaredTrackCount = 0,
                hasUsableTracks = false
            )
        )
        assertFalse(
            shouldMarkYouTubeMusicPlaylistTracksUnavailable(
                declaredTrackCount = 1,
                hasUsableTracks = true
            )
        )
    }
}
