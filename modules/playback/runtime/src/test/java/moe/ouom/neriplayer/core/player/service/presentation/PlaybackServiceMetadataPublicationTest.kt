package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import android.media.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackServiceMetadataPublicationTest {
    private val song = SongItem(1L, "Song", "Artist", "Album", 1L, 60_000L, null)
    private val mediaBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    private val iconBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)

    @Test
    fun `accepted metadata carries the large artwork once plus the display icon`() = runTest {
        val port = RecordingPort(rejectBitmaps = false)
        val owner = owner(port, FakeSource(), artworkFor("content://covers/a.jpg"))

        owner.updateMetadata()

        val metadata = port.published.single()
        assertEquals(mediaBitmap, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertEquals(iconBitmap, metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        assertNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ART))
    }

    @Test
    fun `rejected artwork metadata is republished without bitmaps instead of crashing`() = runTest {
        val port = RecordingPort(rejectBitmaps = true)
        val source = FakeSource()
        val artwork = artworkFor("content://covers/a.jpg")
        val owner = owner(port, source, artwork)

        owner.updateMetadata()

        assertEquals(2, port.attempts)
        val fallback = port.published.single()
        assertEquals("Song", fallback.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNull(fallback.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertNull(fallback.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))

        source.payload = ExternalBluetoothLyricPayload("next line")
        owner.updateMetadata()

        assertEquals("same rejected cover skips the bitmap attempt", 3, port.attempts)
        assertEquals("next line", port.published.last().getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNull(port.published.last().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
    }

    @Test
    fun `a new cover retries bitmap publication after an earlier rejection`() = runTest {
        val port = RecordingPort(rejectBitmaps = true)
        val artwork = artworkFor("content://covers/a.jpg")
        val owner = owner(port, FakeSource(), artwork)
        owner.updateMetadata()
        port.rejectBitmaps = false
        `when`(artwork.observe(song)).thenReturn(readyArtwork("content://covers/b.jpg"))

        owner.updateMetadata()

        assertEquals(3, port.attempts)
        assertNotNull(port.published.last().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
    }

    @Test
    fun `a session that rejects every publish is retried on the next update`() = runTest {
        val port = RecordingPort(rejectBitmaps = true, rejectAll = true)
        val owner = owner(port, FakeSource(), artworkFor("content://covers/a.jpg"))

        owner.updateMetadata()
        owner.updateMetadata()

        assertEquals(3, port.attempts)
        assertEquals(emptyList<MediaMetadata>(), port.published)
    }

    private fun kotlinx.coroutines.test.TestScope.owner(
        port: RecordingPort,
        source: FakeSource,
        artwork: PlaybackArtworkOwner,
    ) = PlaybackServicePresentationOwner(source, port.port, artwork, backgroundScope)

    private fun artworkFor(coverSource: String): PlaybackArtworkOwner {
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(artwork.observe(song)).thenReturn(readyArtwork(coverSource))
        return artwork
    }

    private fun readyArtwork(coverSource: String) =
        PlaybackArtworkSnapshot(coverSource, mediaBitmap, iconBitmap, true, true, false)

    private class RecordingPort(
        var rejectBitmaps: Boolean,
        val rejectAll: Boolean = false,
    ) : PlaybackServicePresentationPort by mock(PlaybackServicePresentationPort::class.java) {
        val published = mutableListOf<MediaMetadata>()
        var attempts = 0
        val port: PlaybackServicePresentationPort get() = this

        override fun setMetadata(metadata: MediaMetadata) {
            attempts++
            val hasBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
            if (rejectAll || (rejectBitmaps && hasBitmap)) {
                throw RuntimeException("android.os.TransactionTooLargeException: data parcel size 2101452 bytes")
            }
            published += metadata
        }
    }

    private inner class FakeSource : PlaybackServicePresentationSource {
        var payload = ExternalBluetoothLyricPayload()

        override fun playback() = PlaybackServicePlaybackSnapshot(
            song = song,
            playerSongPresent = true,
            playerPositionMs = 0L,
            roomPositionMs = 0L,
            buffering = false,
            transportActive = true,
            enginePlaying = true,
            roomPlaying = false,
            playbackControlPlaying = true,
            audioRouteMuted = false,
            playbackSpeed = 1f,
        )
        override fun metadata() = PlaybackServiceMetadataInputs(payload, null, forceSendLyrics = true)
        override fun timer() = PlaybackServiceTimerInputs(SleepTimerState(), "")
        override fun favoriteSongKeys() = emptySet<String>()
        override fun localPlaylistsReady() = true
        override fun isLocalSong(song: SongItem) = false
        override fun shareUrl(song: SongItem): String? = null
        override suspend fun setFloatingLyricsEnabled(enabled: Boolean) = Unit
    }
}
