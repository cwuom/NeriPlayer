@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.di.player

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.download.playback.LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution
import moe.ouom.neriplayer.data.model.playback.storage.PlayerDownloadedMetadataSyncOutcome
import moe.ouom.neriplayer.data.model.playback.storage.PlayerMetadataClearRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.common.PlaybackException

class AppPlayerDownloadsTest {
    @Test
    fun `metadata sync bridge preserves success absence and failure independently`() {
        assertEquals(PlayerDownloadedMetadataSyncOutcome.SUCCESS,
            GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.SUCCESS.toPlayerOutcome())
        assertEquals(PlayerDownloadedMetadataSyncOutcome.NOT_DOWNLOADED,
            GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED.toPlayerOutcome())
        assertEquals(PlayerDownloadedMetadataSyncOutcome.FAILED,
            GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.FAILED.toPlayerOutcome())
    }

    @Test
    fun `wrapped SAF missing document stays recoverable across player bridge`() {
        val missingDocument = IllegalArgumentException(
            "Failed to determine if primary:Downloads/song.flac is child of " +
                "primary:Downloads: java.io.FileNotFoundException: Missing file for " +
                "primary:Downloads/song.flac"
        )
        val error = PlaybackException(
            "test", RuntimeException("loader failed", missingDocument),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        assertTrue(AppPlayerDownloads.isMissingReferenceFailure(error))
        assertFalse(AppPlayerDownloads.isMissingReferenceFailure(
            PlaybackException("test", java.io.IOException("connection reset"),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        ))
    }

    @Test
    fun `local playback bridge preserves availability and the exact playable reference`() {
        val reference = "content://provider/audio/123"
        assertEquals(PlayerLocalPlaybackResolution.Playable(reference),
            LocalPlaybackReferenceResolution.Playable(reference).toPlayerResolution())
        assertEquals(PlayerLocalPlaybackResolution.NotIndexed, LocalPlaybackReferenceResolution.NotIndexed.toPlayerResolution())
        assertEquals(PlayerLocalPlaybackResolution.Missing, LocalPlaybackReferenceResolution.Missing.toPlayerResolution())
        val evidence = ManagedDownloadReferenceLookup.Result.ProviderFailure(IllegalStateException("provider busy"))
        assertEquals(PlayerLocalPlaybackResolution.TemporarilyUnavailable(evidence.toString()),
            LocalPlaybackReferenceResolution.TemporarilyUnavailable(evidence).toPlayerResolution())
    }

    @Test
    fun `metadata clear bridge keeps every independent field across all combinations`() {
        repeat(32) { bits ->
            val request = PlayerMetadataClearRequest(
                title = bits and 1 != 0,
                artist = bits and 2 != 0,
                cover = bits and 4 != 0,
                lyrics = bits and 8 != 0,
                userLyricOffset = bits and 16 != 0,
            )
            val policy = request.toDownloadPolicy()
            assertEquals(request.title, policy.title)
            assertEquals(request.artist, policy.artist)
            assertEquals(request.cover, policy.cover)
            assertEquals(request.lyrics, policy.lyrics)
            assertEquals(request.userLyricOffset, policy.userLyricOffset)
        }
    }
}
